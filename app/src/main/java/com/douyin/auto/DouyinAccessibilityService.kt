package com.douyin.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import com.douyin.auto.config.AppPreferences
import com.douyin.auto.data.LogRepository
import com.douyin.auto.data.parseAwemeId
import com.douyin.auto.media.ScreenCaptureService
import com.douyin.auto.model.CommentCategory
import com.douyin.auto.model.CommentInfo
import com.douyin.auto.model.IntentKeywords
import com.douyin.auto.model.OperationLog
import com.douyin.auto.model.OperationType
import com.douyin.auto.service.AutoFollowEngine
import com.douyin.auto.service.CommentClassifier
import com.douyin.auto.service.CommentScanner
import com.douyin.auto.service.VideoContentAnalyzer
import com.douyin.auto.ui.FloatingAction
import com.douyin.auto.ui.FloatingDotManager
import com.douyin.auto.ui.KeepAliveActivity
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * 抖音无障碍服务 - 核心服务
 *
 * 通过 Android AccessibilityService 监听抖音 App 的界面变化，
 * 自动扫描评论区、分类评论、关注意向客户。
 */
class DouyinAccessibilityService : AccessibilityService() {

    /**
     * 统计数据
     */
    data class Stats(
        val scannedCount: Int = 0,
        val intentCount: Int = 0,
        val followedCount: Int = 0,
        val analyzedCount: Int = 0,
        val likedCount: Int = 0,
        val collectedCount: Int = 0,
        val commentCount: Int = 0,
        val lastScanTime: Long = 0L
    )

    companion object {
        private const val TAG = "DouyinA11yService"

        /** 抖音包名 */
        private const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"

        /** 最小扫描间隔（毫秒）- 防止频繁扫描 */
        private const val MIN_SCAN_INTERVAL_MS = 3000L

        /** 服务实例（用于外部获取状态） */
        @Volatile
        var instance: DouyinAccessibilityService? = null
            private set

        /** 服务运行状态监听器 */
        var statusListener: ((Boolean) -> Unit)? = null

        /** 统计更新监听器 */
        var statsListener: ((Stats) -> Unit)? = null

        // ---- 双击点赞相关常量 ----
        /** 双击选点时禁区外扩边距（dp），降低擦边误触可点击元素的概率 */
        private const val SAFE_MARGIN_DP = 10

        /** 选点保持距视频区域边缘的内边距（dp） */
        private const val SAFE_POINT_PAD_DP = 24

        /** 安全选点最大重试次数 */
        private const val SAFE_POINT_TRIES = 50

        /** 双击单点按下的时长（ms） */
        private const val DOUBLE_TAP_DURATION_MS = 60L

        /** 双击两次点按之间的间隔（ms），合计需 < 系统双击阈值(约300ms) */
        private const val DOUBLE_TAP_GAP_MS = 90L
    }

    // ---- 核心组件 ----
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private val commentScanner = CommentScanner()
    private val commentClassifier = CommentClassifier()
    private lateinit var followEngine: AutoFollowEngine

    /** 悬浮操作按钮（小白点） */
    private var floatingDot: FloatingDotManager? = null

    // ---- 状态变量 ----
    @Volatile
    private var lastScanTime: Long = 0L

    @Volatile
    private var scannedCount: Int = 0

    @Volatile
    private var intentCount: Int = 0

    @Volatile
    private var followedCount: Int = 0

    // ---- 视频内容分析相关状态 ----
    @Volatile
    private var analyzedCount: Int = 0

    @Volatile
    private var likedCount: Int = 0

    @Volatile
    private var collectedCount: Int = 0

    @Volatile
    private var commentCount: Int = 0

    /** 视频内容分析编排器 */
    private lateinit var videoAnalyzer: VideoContentAnalyzer

    /** 视频分析（自动刷视频）运行状态 */
    @Volatile
    private var isVideoWatching: Boolean = false

    @Volatile
    private var videoWatchPaused: Boolean = false

    /** 视频分析循环任务 */
    private var videoWatchJob: Job? = null

    /** 最近一次已分析视频的身份标识（作者+文案），用于去重避免重复分析 */
    private var lastVideoIdentity: String? = null

    @Volatile
    private var isAnalyzingVideo: Boolean = false

    /** 录屏缺失日志节流时间戳 */
    private var lastCaptureMissingLog: Long = 0L

    /** 保活 Activity 是否已启动（视频分析进行时保持 App 前台，避免系统停止录屏） */
    @Volatile
    private var keepAliveStarted: Boolean = false

    /** 上次尝试自动切换视频的时间戳（切换失败时用于定时重试） */
    private var lastAdvanceTime: Long = 0L

    /** 临时提示气泡（覆盖层，用于「已点赞 / 已收藏 / 跳过」等决策结果提示） */
    private var toastView: android.view.View? = null
    private var toastToken: Int = 0
    private val overlayWm by lazy {
        getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    }

    @Volatile
    private var isProcessing: Boolean = false

    /** 当前是否在抖音评论区 */
    @Volatile
    private var isInCommentPage: Boolean = false

    // ---- 翻页（自动滚动评论区）状态机 ----
    /** 翻页状态：空闲 / 运行中 / 已暂停 */
    @Volatile
    private var pageFlipState: PageFlipState = PageFlipState.IDLE

    /** 翻页协程任务 */
    private var pageFlipJob: Job? = null

    /** 已记录评论去重集合（会话内同一评论只记录一次，避免滚动回看时重复） */
    private val seenCommentKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** 操作日志仓库（Room 持久化），在 onServiceConnected 初始化 */
    private lateinit var logRepository: LogRepository

    /**
     * 当前正在处理的视频的分享链接 (url, awemeId)。
     * 由 [analyzeAndAct] 在分析前取链写入，供视频级日志（分析/点赞/收藏）附链落盘；
     * 视频处理结束后置空。非视频级日志忽略此字段。
     */
    @Volatile
    private var currentVideoLink: Pair<String, String?>? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "无障碍服务 onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")

        // 初始化组件
        prefs = AppPreferences(this)
        logRepository = LogRepository.get(this)
        followEngine = AutoFollowEngine(prefs, serviceScope)
        videoAnalyzer = VideoContentAnalyzer(prefs)

        // 配置服务信息
        // 手势能力只能由 manifest 的 android:canPerformGestures="true" 提供——
        // AccessibilityServiceInfo 里对应的 FLAG_REQUEST_CAN_PERFORM_GESTURES 常量在公共 SDK 是 @hide，
        // Kotlin 无法引用；canPerformGestures 属性又是只读、无法赋值。
        // 因此这里用 getServiceInfo() 取「已按 manifest 填充」的实例（其 canPerformGestures 已为 true），
        // 仅修改 flags、不新建实例，避免把手势能力重置为默认 false。
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags =
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        setServiceInfo(info)

        // 加载用户自定义关键词（使用 combine 避免嵌套 collect 死锁）
        serviceScope.launch {
            var currentIntentKeywords: Set<String> = emptySet()
            var currentAdKeywords: Set<String> = emptySet()

            val intentJob =
                launch { prefs.intentKeywordsFlow.collect { currentIntentKeywords = it } }
            val adJob = launch { prefs.adKeywordsFlow.collect { currentAdKeywords = it } }

            // 每 2 秒检查一次并更新分类器
            while (isActive) {
                delay(20000L)
                commentClassifier.updateKeywords(currentIntentKeywords, currentAdKeywords)
                /*Log.d(
                    TAG,
                    "关键词配置已同步: 意向${currentIntentKeywords.size}个, 广告${currentAdKeywords.size}个"
                )*/
            }

            intentJob.cancel()
            adJob.cancel()
        }

        statusListener?.invoke(true)
        addLog(OperationLog.statusLog("已启动", "无障碍服务连接成功"))

        // 显示悬浮操作按钮（小白点）
        floatingDot = FloatingDotManager(
            context = this, actions = listOf(
                FloatingAction("开始翻页") { startPageFlip() },
                FloatingAction("暂停翻页") { pausePageFlip() },
                FloatingAction("继续翻页") { resumePageFlip() },
                FloatingAction("结束翻页") { stopPageFlip() },
                FloatingAction("滚动到最新评论 (≤100)") { scrollCommentToEnd(100) },
                FloatingAction("开始散步模式") { startVideoWatch() },
                FloatingAction("暂停散步") { pauseVideoWatch() },
                FloatingAction("继续散步") { resumeVideoWatch() },
                FloatingAction("结束散步") { stopVideoWatch() },
                FloatingAction("下一视频(手动)") { advanceToNextVideo() })
        ).also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        // 仅处理抖音 App 的事件
        if (packageName != DOUYIN_PACKAGE) return

        // 处理感兴趣的事件类型
        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowEvent(event)
            }

            else -> {
                // 其他事件类型暂不处理
            }
        }
    }

    /**
     * 处理窗口事件
     */
    private fun handleWindowEvent(event: AccessibilityEvent) {
        if (isProcessing) {
            Log.d(TAG, "正在处理中，跳过本次事件")
            return
        }

        val rootNode = rootInActiveWindow ?: return
        if (rootNode.packageName?.toString() != DOUYIN_PACKAGE) {
            rootNode.recycle()
            return
        }

        try {
            // 检测当前是否在评论区
            val inCommentPage = detectCommentPage(rootNode)

            if (inCommentPage && !isInCommentPage) {
                // 刚进入评论区
                Log.d(TAG, "检测到进入抖音评论区")
                isInCommentPage = true
                // 延迟一小段时间等 UI 稳定
                serviceScope.launch {
                    delay(800L)
                    processCommentPage()
                }
            } else if (!inCommentPage && isInCommentPage) {
                // 离开了评论区
                Log.d(TAG, "离开抖音评论区")
                isInCommentPage = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理窗口事件出错: ${e.message}", e)
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * 检测当前页面是否为评论区
     */
    private fun detectCommentPage(rootNode: AccessibilityNodeInfo): Boolean {
        var foundCount = 0

        for (text in IntentKeywords.COMMENT_PAGE_TEXTS) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                foundCount++
                nodes.forEach { it.recycle() }
            }
        }

        // 至少匹配到 2 个评论页面特征文本才认为是评论区
        return foundCount >= 2
    }

    /**
     * 处理评论页面：扫描 → 分类 → 关注
     */
    private suspend fun processCommentPage() {
        if (isProcessing) return
        isProcessing = true

        try {
            val rootNode = rootInActiveWindow ?: return

            try {
                // 1. 扫描评论
                addLog(OperationLog.scanLog(0, "开始扫描"))
                val comments = commentScanner.scanComments(rootNode)
                scannedCount += comments.size

                if (comments.isEmpty()) {
                    addLog(OperationLog.scanLog(0, "未发现评论内容"))
                    notifyStats()
                    return
                }

                addLog(OperationLog.scanLog(comments.size, "扫描完成"))

                // 2. 分类评论，并记录所有尚未记录过的新评论（去重）
                val classified = commentClassifier.classifyBatch(comments)
                recordNewComments(classified)

                val intentComments = classified.filter { it.category == CommentCategory.INTENT }

                Log.d(TAG, "分类结果: 总数=${classified.size}, 意向=${intentComments.size}")

                // 3. 关注意向用户
                if (intentComments.isNotEmpty()) {
                    val successCount = followEngine.followIntentUsers(rootNode, intentComments)
                    followedCount += successCount

                    for (comment in intentComments.take(successCount)) {
                        addLog(OperationLog.followLog(comment.username, true, "已关注意向客户"))
                    }
                }

                notifyStats()

            } finally {
                rootNode.recycle()
            }

        } catch (e: Exception) {
            Log.e(TAG, "处理评论页面出错: ${e.message}", e)
        } finally {
            lastScanTime = System.currentTimeMillis()
            isProcessing = false
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
        addLog(OperationLog.statusLog("被中断", "无障碍服务中断"))
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        floatingDot?.dismiss()
        floatingDot = null
        serviceScope.cancel()
        followEngine.destroy()
        statusListener?.invoke(false)
        addLog(OperationLog.statusLog("已停止", "无障碍服务销毁"))
        Log.d(TAG, "无障碍服务 onDestroy")
    }

    // ---- 日志和统计 ----

    private fun addLog(log: OperationLog) {
        Log.d(TAG, "[${log.action}] ${log.target}: ${log.result} - ${log.detail}")

        // 落盘到 Room。视频级操作（分析/点赞/收藏）附带当前视频链接，供点击跳转。
        val isVideoAction = log.action in VIDEO_ACTIONS_WITH_LINK
        val link = if (isVideoAction) currentVideoLink else null
        serviceScope.launch {
            logRepository.insert(log, videoUrl = link?.first, awemeId = link?.second)
        }
    }

    /** 可附带视频链接的操作类型（点击日志可跳转到该视频） */
    private val VIDEO_ACTIONS_WITH_LINK = setOf(
        OperationType.ANALYZE, OperationType.LIKE, OperationType.COLLECT, OperationType.SEND_COMMENT
    )

    private fun notifyStats() {
        statsListener?.invoke(
            Stats(
                scannedCount = scannedCount,
                intentCount = intentCount,
                followedCount = followedCount,
                analyzedCount = analyzedCount,
                likedCount = likedCount,
                collectedCount = collectedCount,
                commentCount = commentCount,
                lastScanTime = lastScanTime
            )
        )
    }

    /**
     * 重置今日统计
     */
    fun resetStats() {
        scannedCount = 0
        intentCount = 0
        followedCount = 0
        analyzedCount = 0
        likedCount = 0
        collectedCount = 0
        commentCount = 0
        notifyStats()
    }

    /**
     * 获取当前统计
     */
    fun getCurrentStats(): Stats {
        return Stats(
            scannedCount = scannedCount,
            intentCount = intentCount,
            followedCount = followedCount,
            analyzedCount = analyzedCount,
            likedCount = likedCount,
            collectedCount = collectedCount,
            commentCount = commentCount,
            lastScanTime = lastScanTime
        )
    }

    /**
     * 检查服务是否在评论区
     */
    fun isOnCommentPage(): Boolean = isInCommentPage

    // ---- 悬浮按钮动作 ----

    /**
     * 将评论区滚动到最后一个评论（向下滚动到底部）。
     *
     * 通过无障碍 API 对可滚动的评论列表执行 [AccessibilityNodeInfo.ACTION_SCROLL_FORWARD]，
     * 直到无法继续滚动或达到 [maxScrolls] 上限（按“最多 100 个评论”的语义限制滚动次数）。
     *
     * @param maxScrolls 最多滚动次数上限
     */
    fun scrollCommentToEnd(maxScrolls: Int = 100) {
        serviceScope.launch {
            var count = 0
            try {
                while (count < maxScrolls) {
                    val ok = tryScrollOnce()
                    if (!ok) break
                    count++
                    delay(500)
                }
                if (count == 0) {
                    addLog(
                        OperationLog.statusLog(
                            "滚动失败", "未检测到可滚动的评论区（请先打开抖音评论区）"
                        )
                    )
                } else {
                    addLog(OperationLog.statusLog("滚动完成", "已将评论区滚动到底部，共 $count 次"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "滚动评论区出错: ${e.message}", e)
            }
        }
    }

    /**
     * 单次向下滚动评论列表。
     * @return 是否成功触发了滚动
     */
    private fun tryScrollOnce(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val scrollables = ArrayList<AccessibilityNodeInfo>()
            fun collect(node: AccessibilityNodeInfo) {
                if (node != root && node.isScrollable) {
                    scrollables.add(node)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    collect(child)
                    if (!child.isScrollable) child.recycle()
                }
            }
            collect(root)
            if (scrollables.isEmpty()) return false

            // 优先选择 RecyclerView / ListView / ScrollView 类节点
            val target = scrollables.firstOrNull { node ->
                val cn = node.className?.toString() ?: ""
                cn.contains("RecyclerView") || cn.contains("ListView") || cn.contains("ScrollView")
            } ?: scrollables.last()

            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollables.forEach { it.recycle() }
            return ok
        } finally {
            root.recycle()
        }
    }

    // ---- 视频内容分析：点赞 / 收藏 / 自动刷视频 ----

    /**
     * 在根节点中查找匹配 [labels] 且未命中 [exclude] 的「可点击」节点
     * （兼容 text 与 contentDescription，用于定位点赞/收藏按钮）。
     *
     * 关键点：抖音的右侧操作按钮通常是「可点击容器 + 内部文字/图标子节点」结构，
     * 文字（如「收藏」）往往不在可点击节点自身，而在其子 TextView 上。
     * 因此这里先找到命中关键词的节点，再向上回溯到最近的可点击祖先来点击，
     * 避免「按钮文字落在非可点击子节点上」导致一直找不到按钮（表现为「一直收藏失败」）。
     */
    private fun findVideoActionNode(
        root: AccessibilityNodeInfo, labels: List<String>, exclude: List<String>
    ): AccessibilityNodeInfo? {
        // 收集整棵树节点引用并记录父子关系（本次搜索不回收，便于向上回溯祖先）
        val all = ArrayList<AccessibilityNodeInfo>()
        val parentOf = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        all.add(root)
        parentOf[root] = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            repeat(node.childCount) { i ->
                node.getChild(i)?.let { child ->
                    all.add(child)
                    parentOf[child] = node
                    queue.add(child)
                }
            }
        }

        // 1) 找到自身命中 label 且未命中 exclude 的节点
        var textNode: AccessibilityNodeInfo? = null
        for (n in all) {
            val label = buildString {
                append(n.text?.toString() ?: "")
                append(" ")
                append(n.contentDescription?.toString() ?: "")
                append(" ")
                append(n.viewIdResourceName ?: "")
            }.lowercase()
            val hit = labels.any { it.lowercase() in label }
            val excl = exclude.any { it.lowercase() in label }
            if (hit && !excl) {
                textNode = n
                break
            }
        }

        // 2) 从命中节点向上找最近的可点击祖先（含自身）
        var clickable: AccessibilityNodeInfo? = null
        var cur = textNode
        while (cur != null) {
            if (cur.isClickable) {
                clickable = cur
                break
            }
            cur = parentOf[cur]
        }

        // 3) 回收除 root 与返回节点外的所有节点（root 由调用方在 finally 中回收）
        for (n in all) {
            if (n != root && n != clickable) n.recycle()
        }
        return clickable
    }

    /**
     * 按「右栏位置」定位收藏（白色五角星）按钮。
     *
     * 抖音的收藏按钮是五角星图标，很多版本没有「收藏」文字/描述，纯文本搜索找不到。
     * 这里以右栏的「评论」与「分享」按钮为上下锚点，取二者之间、且非其它已知按钮
     * （赞/头像/音乐等）的可点击图标按钮，即判定为收藏按钮。
     * 整树收集后统一回收（root 与返回节点除外），避免重复回收或内存泄漏。
     */
    private fun findCollectButtonByPosition(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val railLeft = (w * 0.70f).toInt()   // 右栏起点（留余量防误判）
        val top = (h * 0.25f).toInt()
        val bottom = (h * 0.95f).toInt()

        // 收集整棵树节点引用（本次搜索不回收，便于计算与统一回收）
        val all = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        all.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            repeat(node.childCount) { i ->
                node.getChild(i)?.let { child ->
                    all.add(child)
                    queue.add(child)
                }
            }
        }

        data class Hit(val node: AccessibilityNodeInfo, val cx: Int, val cy: Int, val label: String)

        val hits = ArrayList<Hit>()
        for (n in all) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val cx = r.centerX()
            val cy = r.centerY()
            if (n.isClickable && cx in railLeft until w && cy in top until bottom) {
                val label = buildString {
                    append(n.text?.toString() ?: "")
                    append(" ")
                    append(n.contentDescription?.toString() ?: "")
                }.lowercase()
                hits.add(Hit(n, cx, cy, label))
            }
        }

        val commentY = hits.firstOrNull { "评论" in it.label }?.cy ?: -1
        val shareY = hits.firstOrNull { "分享" in it.label || "转发" in it.label }?.cy ?: -1
        val star = if (commentY >= 0 && shareY >= 0) {
            hits.filter {
                it.cy > commentY && it.cy < shareY &&
                        !listOf("评论", "分享", "转发", "赞", "头像", "音乐", "原声")
                            .any { k -> k in it.label }
            }.minByOrNull { Math.abs(it.cy - (commentY + shareY) / 2) }?.node
        } else {
            null
        }

        for (n in all) if (n != root && n != star) n.recycle()
        return star
    }

    /**
     * 定位视频页的评论按钮。
     *
     * 抖音右侧竖排操作栏（从上到下）：头像 → 赞 → 评论 → 收藏 → 分享。
     * 关键：评论按钮是「聊天气泡图标」，部分版本 contentDescription/text 里不含「评论」二字，
     * 纯文字匹配会失败。因此采用多级策略：
     *
     * 1. 文字/描述直接含「评论」→ 向上回溯可点击祖先 → 右栏校验（x ≥ 60% 屏宽）；
     * 2. 「收藏」按钮正上方锚点：收藏按钮有文字（已验证），评论紧贴收藏上方，
     *    取右栏中 y 坐标小于收藏、且最靠近收藏的可点击图标；
     * 3. 兜底：「赞」与「分享」可点击祖先之间最靠上的右栏按钮。
     *
     * 评论区面板打开时右栏被遮挡/不可见，此方法返回 null，
     * 从而避免在面板内误点「全部评论」等文本节点。
     */
    private fun findCommentButtonByPosition(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val railLeft = (w * 0.60f).toInt()   // 右栏起点（留余量防误判）

        // 单次遍历收集整棵树节点引用并记录父子关系（便于向上回溯祖先）
        val all = ArrayList<AccessibilityNodeInfo>()
        val parentOf = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        all.add(root)
        parentOf[root] = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            repeat(node.childCount) { i ->
                node.getChild(i)?.let { child ->
                    all.add(child)
                    parentOf[child] = node
                    queue.add(child)
                }
            }
        }

        fun labelOf(n: AccessibilityNodeInfo): String = buildString {
            append(n.text?.toString() ?: "")
            append(" ")
            append(n.contentDescription?.toString() ?: "")
        }.lowercase()

        fun clickableAncestorOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable) return cur
                cur = parentOf[cur]
            }
            return null
        }

        fun cxOf(n: AccessibilityNodeInfo): Int {
            val r = Rect()
            n.getBoundsInScreen(r)
            return r.centerX()
        }

        fun cyOf(n: AccessibilityNodeInfo): Int {
            val r = Rect()
            n.getBoundsInScreen(r)
            return r.centerY()
        }

        var chosen: AccessibilityNodeInfo? = null

        // 0) 纯几何定位（图标按钮无文字/描述时的主力方案）：
        //    收集右栏所有可点击节点，按 y 聚类合并同按钮的重复节点，
        //    从底部往上第 3 个即「评论」（底部三个稳定为 评论→收藏→分享）。
        run {
            val railClickables = all
                .filter { it.isClickable && cxOf(it) >= railLeft }
                .sortedBy { cyOf(it) }
            val groups = ArrayList<AccessibilityNodeInfo>()
            var lastCy = -100000
            for (c in railClickables) {
                val cy = cyOf(c)
                if (groups.isEmpty() || cy - lastCy > 60) {
                    groups.add(c)
                    lastCy = cy
                }
            }
            if (groups.size >= 3) {
                chosen = groups[groups.size - 3]
                Log.d(TAG, "评论按钮几何定位命中：右栏可点击组数=${groups.size}, 选定组cy=${cyOf(chosen!!)}")
            } else {
                Log.d(TAG, "评论按钮几何定位失败：右栏可点击组数=${groups.size}")
            }
        }

        // 1) 文字/描述含「评论」→ 可点击祖先 → 右栏校验
        if (chosen == null) {
            for (n in all) {
                if ("评论" !in labelOf(n)) continue
                val anc = clickableAncestorOf(n) ?: continue
                if (cxOf(anc) >= railLeft) {
                    chosen = anc
                    break
                }
            }
        }

        // 2) 「收藏」按钮正上方锚点（评论图标紧贴收藏上方，不依赖评论文字）
        if (chosen == null) {
            val collectAnc = all.firstOrNull { "收藏" in labelOf(it) }
                ?.let { clickableAncestorOf(it) }
            if (collectAnc != null) {
                val collectY = cyOf(collectAnc)
                chosen = all
                    .filter { it.isClickable && cxOf(it) >= railLeft && cyOf(it) in 0 until collectY }
                    .maxByOrNull { cyOf(it) }
            }
        }

        // 3) 兜底：「赞」与「分享」可点击祖先之间最靠上的右栏按钮
        if (chosen == null) {
            val like = all.firstOrNull { "赞" in labelOf(it) }?.let { clickableAncestorOf(it) }
            val share = all.firstOrNull { "分享" in labelOf(it) || "转发" in labelOf(it) }
                ?.let { clickableAncestorOf(it) }
            if (like != null && share != null) {
                val likeY = cyOf(like)
                val shareY = cyOf(share)
                if (shareY > likeY) {
                    chosen = all
                        .filter { it.isClickable && cxOf(it) >= railLeft && cyOf(it) in (likeY + 1 until shareY) }
                        .minByOrNull { cyOf(it) }
                }
            }
        }

        for (n in all) if (n != root && n != chosen) n.recycle()
        return chosen
    }

    /**
     * 诊断用：收集右栏区域（x ≥ 60% 屏宽）内的节点详情，
     * 用于评论按钮定位失败时输出到日志，便于对照真实节点结构调整定位策略。
     * 输出格式：`[cy=坐标,id=资源id简称,cls=类名简称,desc=描述,text=文本,click]`
     * 图标按钮无文字时也应输出（这是纯图标场景的关键诊断依据）。
     */
    private fun describeRightRail(root: AccessibilityNodeInfo): String {
        val dm = resources.displayMetrics
        val railLeft = (dm.widthPixels * 0.60f).toInt()
        val sb = StringBuilder()
        val all = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        all.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            repeat(node.childCount) { i ->
                node.getChild(i)?.let { child ->
                    all.add(child)
                    queue.add(child)
                }
            }
        }
        var count = 0
        for (node in all) {
            if (count >= 40) break
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.centerX() < railLeft) continue
            val id = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val desc = (node.contentDescription?.toString() ?: "").take(12)
            val text = (node.text?.toString() ?: "").take(12)
            // 仅保留可点击节点，或带文字/描述/资源id的节点
            val interesting = node.isClickable || desc.isNotEmpty() || text.isNotEmpty() || id.isNotEmpty()
            if (!interesting) continue
            sb.append("[cy=").append(r.centerY())
                .append(",id=").append(id)
                .append(",cls=").append(cls)
                .append(",desc=").append(desc)
                .append(",text=").append(text)
                .append(if (node.isClickable) ",CLICK" else "")
                .append("]")
            count++
        }
        for (n in all) if (n != root) n.recycle()
        return sb.toString()
    }

    /**
     * 对当前视频执行点赞（在视频区域随机选点双击；若已点赞则跳过以免取消赞）。
     *
     * 流程：
     * 1. 单次遍历无障碍节点树，定位视频区域（优先视频渲染表面）、判断是否已赞、收集所有可点击禁区；
     * 2. 在视频区域内随机取点，避开禁区（已外扩安全边距）；
     * 3. 在该点下发「双击」手势 —— 抖音双击视频即点赞，且不会误触右侧点赞/评论/分享/头像等按钮。
     */
    private suspend fun performLike(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val ctx = analyzeVideoForLike(root)

            // 已点赞则跳过：二次双击会触发「取消赞」，必须避免
            if (ctx.alreadyLiked) {
                addLog(OperationLog.likeLog(false, "已点赞，跳过（双击会取消赞）"))
                return false
            }

            val point = pickSafeDoubleTapPoint(ctx.videoArea, ctx.clickableRects)
            val ok = dispatchDoubleTap(point.x, point.y)
            if (ok) {
                likedCount++
                addLog(OperationLog.likeLog(true, "视频区域双击点赞 @(${point.x},${point.y})"))
            } else {
                addLog(OperationLog.likeLog(false, "双击失败（请检查无障碍「执行手势」权限）"))
            }
            return ok
        } finally {
            root.recycle()
        }
    }

    /**
     * 单次遍历节点树，收集双击点赞所需的上下文：
     * - [VideoLikeContext.videoArea] 视频区域（屏幕坐标）：优先取视频渲染表面
     *   TextureView/SurfaceView/VideoView 的范围；取不到则回退为整屏去掉安全边距。
     * - [VideoLikeContext.alreadyLiked] 是否已点赞（命中 [IntentKeywords.LIKED_TEXTS]）。
     * - [VideoLikeContext.clickableRects] 视频区域内所有可点击节点的屏幕矩形（已外扩 [SAFE_MARGIN_DP]），
     *   双击选点时需避开，防止误触其它功能。
     */
    private fun analyzeVideoForLike(root: AccessibilityNodeInfo): VideoLikeContext {
        val dm = resources.displayMetrics
        var surface: Rect? = null
        var surfaceArea = 0
        var alreadyLiked = false
        val clickableRects = ArrayList<Rect>()
        val margin = (SAFE_MARGIN_DP * dm.density).toInt().coerceAtLeast(8)

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var isRoot = true
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // 视频渲染表面
            val cn = node.className?.toString() ?: ""
            if (cn.contains("TextureView") || cn.contains("SurfaceView") || cn.contains("VideoView")) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > surfaceArea) {
                    surfaceArea = area
                    surface = r
                }
            }
            // 已点赞判定
            if (!alreadyLiked) {
                val label = buildString {
                    append(node.text?.toString() ?: "")
                    append(" ")
                    append(node.contentDescription?.toString() ?: "")
                }.lowercase()
                if (IntentKeywords.LIKED_TEXTS.any { it.lowercase() in label }) alreadyLiked = true
            }
            // 可点击节点 → 禁区（外扩安全边距，降低擦边误触概率）
            if (node.isClickable) {
                val r = Rect()
                node.getBoundsInScreen(r)
                r.inset(-margin, -margin)
                clickableRects.add(r)
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let { queue.add(it) } }
            if (!isRoot) node.recycle()
            isRoot = false
        }

        val videoArea: Rect =
            if (surface != null && surface!!.width() > 0 && surface!!.height() > 0) {
                surface!!
            } else {
                val topInset = (dm.heightPixels * 0.05f).toInt()
                val bottomInset = (dm.heightPixels * 0.12f).toInt()
                val rightInset = (dm.widthPixels * 0.14f).toInt()
                val leftInset = (dm.widthPixels * 0.03f).toInt()
                Rect(
                    leftInset,
                    topInset,
                    dm.widthPixels - rightInset,
                    dm.heightPixels - bottomInset
                )
            }
        // 仅保留与视频区域相交的禁区
        val forbidden = clickableRects.filter { Rect.intersects(videoArea, it) }
        return VideoLikeContext(videoArea, alreadyLiked, forbidden)
    }

    /**
     * 在视频区域内随机取点，避开 [forbidden] 禁区。
     * 多次重试仍失败则回退到区域几何中心（主 Feed 中心通常为纯视频，无点击元素）。
     */
    private fun pickSafeDoubleTapPoint(area: Rect, forbidden: List<Rect>): Point {
        val pad = (SAFE_POINT_PAD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(16)
        val minX = area.left + pad
        val maxX = area.right - pad
        val minY = area.top + pad
        val maxY = area.bottom - pad
        if (maxX > minX && maxY > minY) {
            repeat(SAFE_POINT_TRIES) {
                val x = Random.nextInt(minX, maxX)
                val y = Random.nextInt(minY, maxY)
                if (forbidden.none { it.contains(x, y) }) {
                    return Point(x, y)
                }
            }
        }
        return Point(area.centerX(), area.centerY())
    }

    /**
     * 在指定屏幕坐标下发「双击」手势：两次短促点按，间隔 [DOUBLE_TAP_GAP_MS]，
     * 以被抖音识别为双击点赞，而非两次独立单击。
     */
    private fun dispatchDoubleTap(x: Int, y: Int): Boolean {
        val fx = x.toFloat()
        val fy = y.toFloat()
        val tapDuration = DOUBLE_TAP_DURATION_MS
        val gap = DOUBLE_TAP_GAP_MS
        val stroke1 = GestureDescription.StrokeDescription(
            Path().apply { moveTo(fx, fy) }, 0, tapDuration
        )
        val stroke2 = GestureDescription.StrokeDescription(
            Path().apply { moveTo(fx, fy) }, tapDuration + gap, tapDuration
        )
        val gesture = GestureDescription.Builder().addStroke(stroke1).addStroke(stroke2).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /** 双击点赞所需的视频上下文 */
    private data class VideoLikeContext(
        val videoArea: Rect,
        val alreadyLiked: Boolean,
        val clickableRects: List<Rect>
    )

    /**
     * 对当前视频执行收藏（若已收藏则跳过）。
     * 先尝试无障碍 ACTION_CLICK；若返回 false（部分自定义 View 不响应无障碍点击），
     * 回退为在该按钮中心下发「单击」手势，提高成功率。
     */
    private suspend fun performCollect(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            // 1) 优先按文本/描述/资源id 找收藏按钮
            var node = findVideoActionNode(
                root, IntentKeywords.COLLECT_TEXTS, IntentKeywords.COLLECTED_TEXTS
            )
            // 2) 回退：纯图标五角星按钮无「收藏」文字，按右栏位置定位（需重新取快照，
            //    上一次遍历已回收子树，root 子树已失效）
            if (node == null) {
                node = rootInActiveWindow?.let { r2 ->
                    val found = findCollectButtonByPosition(r2)
                    r2.recycle()
                    found
                }
            }
            if (node == null) {
                addLog(OperationLog.collectLog(false, "未找到收藏按钮（含五角星位置定位）"))
                return false
            }
            // 先取按钮中心坐标（回退手势用），再据此释放节点
            val r = Rect()
            node.getBoundsInScreen(r)
            val cx = r.centerX()
            val cy = r.centerY()

            val okClick = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (okClick) {
                collectedCount++
                addLog(OperationLog.collectLog(true))
                node.recycle()
                return true
            }
            // ACTION_CLICK 无效 → 改用手势单击按钮中心
            node.recycle()
            val okGesture = dispatchTap(cx, cy)
            if (okGesture) {
                collectedCount++
                addLog(OperationLog.collectLog(true, "无障碍点击无效，改用单击手势"))
            } else {
                addLog(OperationLog.collectLog(false, "点击失败（请检查无障碍「执行手势」权限）"))
            }
            return okGesture
        } finally {
            root.recycle()
        }
    }

    /**
     * 在指定屏幕坐标下发一次「单击」手势（按下后极短释放），
     * 用于无障碍 ACTION_CLICK 不生效时兜底触发按钮。
     */
    private fun dispatchTap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    // ---- 自动评论 ----

    /**
     * 对当前视频执行评论：打开评论区 → 粘贴随机正能量句子 → 延迟1秒 → 点击提交 →
     * 延迟1秒 → 关闭评论区（切换下一视频由主循环负责）。
     *
     * 流程：
     * 0. 若评论区已打开（输入框已可见，如用户手动点开），跳过点按钮直接进入输入；
     * 1. 按「右栏位置」定位评论按钮并点击（避免误匹配面板内「全部评论」等文本）；
     * 2. 等待评论区面板出现，定位评论输入框（优先 hint 匹配，否则取屏幕最底部的输入框）；
     * 3. 聚焦 → ACTION_SET_TEXT 写入；失败则剪贴板 + ACTION_PASTE 粘贴兜底；
     * 4. 延迟 1 秒，找到提交按钮并点击；
     * 5. 延迟 1 秒，按返回键关闭评论区面板。
     *
     * 任何环节失败均不阻塞主流程，记录日志后跳过。
     */
    private suspend fun performComment(): Boolean {
        val commentText = IntentKeywords.POSITIVE_COMMENTS.random()

        // 0) 检测评论区是否已打开（输入框已可见）
        val root0 = rootInActiveWindow
        var panelOpen = false
        if (root0 != null) {
            try {
                panelOpen = findInputFieldInRoot(root0) != null
            } finally {
                root0.recycle()
            }
        }

        if (!panelOpen) {
            // 1) 按「右栏位置」定位评论按钮并点击
            val root1 = rootInActiveWindow ?: return false
            var commentBtn: AccessibilityNodeInfo? = null
            var btnCx = 0
            var btnCy = 0
            try {
                commentBtn = findCommentButtonByPosition(root1)
                if (commentBtn == null) {
                    // 无障碍拿不到评论按钮节点（纯图标无描述）→ 坐标硬点兜底
                    val diag = describeRightRail(root1)
                    Log.w(TAG, "评论按钮定位失败，右栏节点摘要: $diag")
                    val dm = resources.displayMetrics
                    btnCx = (dm.widthPixels * 0.875f).toInt()
                    btnCy = (dm.heightPixels * 0.75f).toInt()
                    Log.d(TAG, "使用坐标硬点点击评论按钮 ($btnCx, $btnCy)")
                    dispatchTap(btnCx, btnCy)
                } else {
                    // 提前保存按钮中心坐标（节点回收后无法再获取）
                    val r = Rect()
                    commentBtn.getBoundsInScreen(r)
                    btnCx = r.centerX()
                    btnCy = r.centerY()

                    val clicked = commentBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        // 无障碍点击无响应，回退到手势点击
                        Log.d(TAG, "评论按钮无障碍点击无响应，回退手势点击 ($btnCx, $btnCy)")
                        dispatchTap(btnCx, btnCy)
                    }
                }
            } finally {
                commentBtn?.recycle()
                root1.recycle()
            }
            delay(1000)
        }

        // 2) 定位评论输入框（最多 3s）
        val inputNode = waitForInputField(timeoutMs = 3000)
        if (inputNode == null) {
            addLog(OperationLog.sendCommentLog(false, "未找到评论输入框"))
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
            return false
        }

        // 3) 聚焦 → 写入文字；失败则剪贴板粘贴兜底
        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle()
        bundle.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, commentText
        )
        var textOk = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!textOk) {
            // SET_TEXT 失败：写入剪贴板后用 ACTION_PASTE 真正「粘贴」
            runCatching {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("comment", commentText))
                inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                textOk = true
            }
        }
        inputNode.recycle()
        if (!textOk) {
            addLog(OperationLog.sendCommentLog(false, "写入评论内容失败"))
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
            return false
        }

        // 4) 延迟 1 秒后点击提交评论按钮
        delay(1000)
        val sendNode = waitForNode(
            labels = IntentKeywords.COMMENT_SEND_TEXTS, timeoutMs = 3000
        )
        if (sendNode == null) {
            addLog(OperationLog.sendCommentLog(false, "未找到提交按钮"))
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
            return false
        }
        val sendClicked = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        sendNode.recycle()
        if (!sendClicked) {
            addLog(OperationLog.sendCommentLog(false, "提交按钮点击无响应"))
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(500)
            return false
        }

        // 5) 延迟 1 秒后关闭评论区（主循环随后切换下一视频）
        delay(1000)
        performGlobalAction(GLOBAL_ACTION_BACK)

        commentCount++
        addLog(OperationLog.sendCommentLog(true, "评论内容：$commentText"))
        notifyStats()
        return true
    }

    /**
     * 在当前节点树中查找评论输入框（EditText）。
     *
     * 评论区面板内可能存在多个输入框（如顶部的「搜索评论」框、底部的评论输入条），
     * 直接取第一个会命中搜索框导致文字打错位置。选择策略：
     * 1. 优先取 hint 命中 [IntentKeywords.COMMENT_INPUT_HINTS]（如「说点什么」）的输入框；
     * 2. 否则取屏幕位置最靠底部的输入框（评论输入条固定在面板底部）。
     *
     * 调用方负责 recycle 返回的节点。
     */
    private fun findInputFieldInRoot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        all.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            repeat(node.childCount) { i ->
                node.getChild(i)?.let { child ->
                    all.add(child)
                    queue.add(child)
                }
            }
        }

        var result: AccessibilityNodeInfo? = null
        // 1) hint 命中评论输入提示词
        for (n in all) {
            val cn = n.className?.toString() ?: ""
            if (!cn.contains("EditText") || !n.isVisibleToUser) continue
            val hintText = buildString {
                append(n.hintText?.toString() ?: "")
                append(" ")
                append(n.text?.toString() ?: "")
                append(" ")
                append(n.contentDescription?.toString() ?: "")
            }.lowercase()
            if (IntentKeywords.COMMENT_INPUT_HINTS.any { it.lowercase() in hintText }) {
                result = n
                break
            }
        }
        // 2) 兜底：取屏幕最底部的可见 EditText（评论输入条在面板底部）
        if (result == null) {
            var bestCy = -1
            for (n in all) {
                val cn = n.className?.toString() ?: ""
                if (!cn.contains("EditText") || !n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.centerY() > bestCy) {
                    bestCy = r.centerY()
                    result = n
                }
            }
        }

        for (n in all) {
            if (n != root && n != result) n.recycle()
        }
        return result
    }

    /**
     * 轮询当前节点树，等待出现用户可见的 EditText 输入框。
     * 用于评论区打开后定位输入框。
     */
    private suspend fun waitForInputField(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val input = findInputFieldInRoot(root)
                    if (input != null) return input
                } finally {
                    root.recycle()
                }
            }
            delay(200)
        }
        return null
    }

    /**
     * 通过「分享 → 复制链接」获取当前视频的分享链接，并解析 aweme_id。
     *
     * 流程：点分享按钮 → 轮询等待分享面板出现 → 点「复制链接」→ 读剪贴板 → 按返回键关闭面板。
     * 全程带超时与兜底，任何环节失败均返回 null，不抛异常、不阻塞主流程。
     *
     * 注意：会弹出分享面板约 1~2 秒，并消耗一次剪贴板内容。仅在散步模式下、
     * 对需要落盘的视频级操作调用。
     *
     * @return (分享链接, awemeId) 二元组；awemeId 可能为 null（短链无法本地解析）
     */
    private suspend fun captureCurrentVideoShareLink(): Pair<String, String?>? {
        // 清空剪贴板，便于后续判断「复制链接」是否成功写入了新内容
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }

        // 1) 找并点击「分享/转发」按钮
        val root1 = rootInActiveWindow ?: return null
        try {
            val shareNode = findVideoActionNode(
                root1,
                labels = listOf("分享", "转发"),
                exclude = listOf("分享给朋友", "分享到")
            )
            if (shareNode == null) {
                Log.w(TAG, "取链失败：未找到分享按钮")
                return null
            }
            val clicked = shareNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            shareNode.recycle()
            if (!clicked) {
                Log.w(TAG, "取链失败：分享按钮点击无响应")
                return null
            }
        } finally {
            root1.recycle()
        }

        // 2) 轮询等待分享面板出现，找到「复制链接」按钮（最多等 3s）
        val copyLabels = listOf("复制链接", "复制", "copy link", "复制分享链接")
        val copyNode = waitForNode(copyLabels, timeoutMs = 3000)
            ?: run {
                Log.w(TAG, "取链失败：分享面板未出现「复制链接」")
                // 兜底按返回关闭可能弹出的面板
                performGlobalAction(GLOBAL_ACTION_BACK)
                return null
            }

        // 3) 点击「复制链接」
        val copyClicked = copyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        copyNode.recycle()
        if (!copyClicked) {
            Log.w(TAG, "取链失败：「复制链接」点击无响应")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return null
        }

        // 4) 等待剪贴板写入（最多 1.5s），读取链接
        var link: String? = null
        val deadline = System.currentTimeMillis() + 1500
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank() && (text.contains("http") || text.contains("douyin"))) {
                link = text.trim()
                break
            }
        }

        // 5) 关闭分享面板
        performGlobalAction(GLOBAL_ACTION_BACK)

        if (link == null) {
            Log.w(TAG, "取链失败：剪贴板未捕获到链接")
            return null
        }
        // 从文案里提取 URL（抖音复制链接格式通常为「X.XXXX 抖音文案 https://v.douyin.com/xxx/」）
        val url = Regex("""https?://[^\s，,。]+""").find(link)?.value ?: link
        val awemeId = parseAwemeId(url)
        Log.d(TAG, "取链成功：url=$url, awemeId=$awemeId")
        return url to awemeId
    }

    /**
     * 轮询当前节点树，等待出现文本命中 [labels] 任一的可点击节点。
     * @return 命中节点（已从可点击祖先回溯，调用方负责 recycle）；超时返回 null
     */
    private suspend fun waitForNode(
        labels: List<String>, timeoutMs: Long
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val node = findVideoActionNode(root, labels, exclude = emptyList())
                    if (node != null) return node
                } finally {
                    root.recycle()
                }
            }
            delay(200)
        }
        return null
    }

    /**
     * 识别「当前正在播放的是哪一个视频」：拼接作者名与文案作为身份标识。
     * 身份变化即代表刷到了新视频，触发一次分析。
     */
    private fun detectVideoIdentity(root: AccessibilityNodeInfo): String? {
        val parts = mutableListOf<String>()
        for (id in IntentKeywords.DOUYIN_VIDEO_AUTHOR_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) parts.add("author:$t")
                n.recycle()
            }
        }
        for (id in IntentKeywords.DOUYIN_VIDEO_CAPTION_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) parts.add("cap:${t.take(40)}")
                n.recycle()
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("|")
    }

    /** 直播间检测（预览卡片或已进入直播间均适用），不依赖大模型。 */
    private data class LiveDetectResult(val isLive: Boolean, val reason: String)

    /**
     * 判断节点是否真正落在屏幕可视区域内（不仅是 VISIBLE 标志为真）。
     * 部分国产 ROM（MIUI/EMUI/ColorOS 等）下，被滑出屏幕但仍挂载的节点
     * [AccessibilityNodeInfo.isVisibleToUser] 仍可能返回 true，因此再用
     * boundsInScreen 与屏幕矩形做相交校验，过滤掉「逻辑可见但肉眼不可见」的残留节点。
     */
    private fun isOnScreen(node: AccessibilityNodeInfo): Boolean {
        val dm = resources.displayMetrics
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val b = Rect()
        node.getBoundsInScreen(b)   // 老版本无 boundsInScreen 无参属性（API 33+），用 getBoundsInScreen(Rect) 兼容
        return b.width() > 0 && b.height() > 0 && Rect.intersects(screen, b)
    }

    /** 直播跳过标记：避免同一张直播预览卡片在循环里被反复判定为“新视频”。 */
    private val LIVE_SKIP_MARKER = "__live_room_skipped__"

    /**
     * 检测当前屏幕是否为直播间/直播预览。纯无障碍节点文本扫描，**不调用大模型**。
     * 强信号：仅当用户可见、且确实落在屏幕可视区域内的「点击进入直播间」CTA 出现时，
     * 才判定为直播预览卡片（见 [IntentKeywords.LIVE_CTA_TEXTS]）。
     * 以「是否对用户可见 + 边界相交」双重约束替代裸 "直播" 子串匹配，避免「直播广场」等误命中。
     */
    private fun detectLiveStream(root: AccessibilityNodeInfo): LiveDetectResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var hitText: String? = null
        var weak = false
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val isRoot = node == root
            val text = node.text?.toString() ?: ""
            val visible = node.isVisibleToUser
            val desc = node.contentDescription?.toString() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString() ?: ""
            } else ""
            val id = node.viewIdResourceName ?: ""
            val label = (text + " " + desc + " " + hint + " " + id).lowercase()
            // 强信号：仅当「点击进入直播间」CTA 对用户可见、且确实落在屏幕可视区域内，才判定为直播预览卡片
            if (hitText == null && visible && isOnScreen(node) &&
                IntentKeywords.LIVE_CTA_TEXTS.any { it.lowercase() in label }
            ) {
                hitText = text.ifBlank { desc.ifBlank { hint.ifBlank { id } } }
            }
            if (!weak && listOf("进入直播间")
                    .any { it in label }
            ) {
                weak = true
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let { queue.add(it) } }
            if (!isRoot) node.recycle()
        }
        return if (hitText != null) {
            LiveDetectResult(true, "命中直播文本：$hitText")
        } else {
            LiveDetectResult(false, "未命中直播特征（弱信号=$weak）")
        }
    }

    /**
     * 检测是否已「**进入直播间**」（区别于直播预览卡片，见 [detectLiveStream]）。
     *
     * 判别逻辑：
     * - 直播预览卡片带有「点击进入直播间」按钮（CTA），而真正进入直播间后该 CTA 消失；
     * - 真正进入直播间后，界面上会出现**公屏聊天输入框**（EditText，hint 含 主播/公屏/弹幕/聊 等），
     *   推荐流主播放页不存在这种输入框，命中即判定为「确实进了直播间」。
     *
     * @return true 表示当前已在直播间内部（非预览），应执行「按返回键回到推荐列表」逻辑。
     */
    private fun detectEnteredLiveRoom(root: AccessibilityNodeInfo): Boolean {
        var hasLiveChat = false
        var hasPreviewCta = false
        val chatHints = listOf("主播", "公屏", "弹幕", "聊")
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val isRoot = node == root
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString() ?: ""
            } else ""
            val id = node.viewIdResourceName ?: ""
            val label = (text + " " + desc + " " + hint + " " + id).lowercase()
            if (!hasPreviewCta &&
                listOf("点击进入直播间", "进入直播间").any { it in label }
            ) {
                hasPreviewCta = true
            }
            if (!hasLiveChat && (node.isEditable ||
                        node.className?.toString()?.contains("EditText") == true)
            ) {
                if (chatHints.any { it in (hint + " " + text) }) {
                    hasLiveChat = true
                }
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let { queue.add(it) } }
            if (!isRoot) node.recycle()
        }
        // 进了直播间：存在公屏聊天框，且已没有预览 CTA
        return hasLiveChat && !hasPreviewCta
    }

    /**
     * 分析当前视频并按配置决定是否点赞/收藏，最后提示用户决策结果。
     */
    private suspend fun analyzeAndAct(identity: String, analysisEnabled: Boolean) {
        // 每条视频开始：重置链接，避免残留上一条视频的分享链接
        currentVideoLink = null
        // 直播场景：直接跳过，不调用大模型、不点赞/收藏
        rootInActiveWindow?.let { root ->
            try {
                val live = detectLiveStream(root)
                if (live.isLive) {
                    addLog(
                        OperationLog.statusLog(
                            "散步模式", "检测到直播（${live.reason}），跳过分析（不调用大模型）"
                        )
                    )
                    notifyUser("检测到直播，已跳过 1")
                    return
                }
            } finally {
                root.recycle()
            }
        }
        if (analysisEnabled) {
            addLog(OperationLog.statusLog("散步模式", "正在分析视频：$identity"))
            // 取当前视频分享链接（弹分享面板→复制链接→读剪贴板→关闭面板），供视频级日志点击跳转。
            // 失败不阻塞主流程，视频级日志将无链落盘（仍可查看，只是不可跳转）。
            currentVideoLink = runCatching { captureCurrentVideoShareLink() }.getOrNull()
            if (currentVideoLink == null) {
                addLog(OperationLog.statusLog("取链", "未能获取本视频分享链接，日志将无法跳转"))
            }
        }
        val result = if (analysisEnabled) {
            try {
                videoAnalyzer.analyze()
            } catch (e: Exception) {
                Log.e(TAG, "分析异常: ${e.message}", e)
                addLog(OperationLog.analyzeLog("", false, false, "分析出错：${e.message}"))
                notifyUser("分析失败：${e.message}")
                return
            }
        } else {
            // 分析未启用：跳过 VLM 调用与取链，空结果（不点赞/不收藏，评论仍可独立执行）
            com.douyin.auto.model.VideoAnalysisResult(reason = "分析未启用")
        }

        if (analysisEnabled) {
            analyzedCount++
            addLog(
                OperationLog.analyzeLog(
                    result.subject, result.shouldLike, result.shouldCollect, result.reason
                )
            )
            notifyStats()
        }

        val auto = prefs.autoExecuteFlow.first()
        val autoComment = prefs.autoCommentFlow.first()
        val limitReached = prefs.isDailyActionLimitReached()
        var didLike = false
        var didCollect = false
        var didComment = false
        if (auto && !limitReached) {
            if (result.shouldLike) {
                delay(400)
                didLike = performLike()
                if (didLike) {
                    prefs.recordAction()
                    notifyStats()
                }
            }
            if (result.shouldCollect) {
                delay(400)
                didCollect = performCollect()
                if (didCollect) {
                    prefs.recordAction()
                    notifyStats()
                }
            }
        }
        // 评论独立于点赞/收藏开关，不受 VLM 分析结果约束，只要开关打开就评论
        if (autoComment && !limitReached) {
            delay(500)
            didComment = performComment()
            if (didComment) {
                prefs.recordAction()
                notifyStats()
            }
        }
        // 提示用户本视频的决策结果
        notifyUser(buildDecisionMessage(result, auto, didLike, didCollect, limitReached, didComment, autoComment))
        // 若已达到每日操作上限，停止自动操作并结束散步模式（避免空转与风控）
        if ((auto || autoComment) && limitReached) {
            addLog(
                OperationLog.statusLog(
                    "散步模式",
                    "今日点赞/收藏已达上限（${prefs.getTodayActionCount()}），自动结束散步模式"
                )
            )
            notifyUser("今日点赞/收藏已达上限（${prefs.getTodayActionCount()}），已自动停止散步模式")
            stopVideoWatch()
        }
    }

    /** 根据分析结果拼装给用户的提示文案 */
    private fun buildDecisionMessage(
        result: com.douyin.auto.model.VideoAnalysisResult,
        auto: Boolean,
        didLike: Boolean,
        didCollect: Boolean,
        limitReached: Boolean = false,
        didComment: Boolean = false,
        autoComment: Boolean = false
    ): String {
        val subj = result.subject.ifEmpty { "（无主体）" }
        val action = buildString {
            if (result.shouldLike) {
                append(
                    when {
                        !auto -> "建议点赞"
                        didLike -> "已点赞"
                        else -> "点赞失败"
                    }
                )
            }
            if (result.shouldCollect) {
                if (isNotEmpty()) append(" · ")
                append(
                    when {
                        !auto -> "建议收藏"
                        didCollect -> "已收藏"
                        else -> "收藏失败"
                    }
                )
            }
            // 评论 — 独立于分析结果，开关打开即显示状态
            if (autoComment) {
                if (isNotEmpty()) append(" · ")
                append(if (didComment) "已评论" else "评论失败")
            }
            if (isEmpty()) append("已跳过（不符合条件）")
            if (limitReached) {
                if (isNotEmpty()) append(" · ")
                append("（已达今日上限）")
            }
        }
        return "视频：$subj\n$action"
    }

    /**
     * 启动「散步模式」：在抖音推荐流自动截帧分析。
     * 每刷到一个新视频：抓取前 ~10 秒随机几帧 → 调用大模型识别 → 按本地点赞/收藏条件
     * 自动点赞/收藏 → 顶部气泡提示决策结果 → 自动切换下一个视频继续分析。
     */
    fun startVideoWatch() {
        if (videoWatchJob?.isActive == true) {
            addLog(OperationLog.statusLog("散步模式", "散步模式已在运行"))
            return
        }
        isVideoWatching = true
        videoWatchPaused = false
        isAnalyzingVideo = false
        lastVideoIdentity = null
        videoWatchJob = serviceScope.launch {
            addLog(OperationLog.statusLog("散步模式", "开始散步模式：自动截帧分析并切换下一个视频"))
            try {
                while (isActive && isVideoWatching) {
                    if (videoWatchPaused) {
                        delay(500)
                        continue
                    }
                    // 分析开关与评论开关互相独立，任一组合都持续刷视频，绝不空转：
                    // 分析开→截帧分析；评论开→自动评论；都关→纯散步只刷视频
                    val analysisEnabled = prefs.analysisEnabledFlow.first()
                    if (!analysisEnabled && keepAliveStarted) stopKeepAlive()
                    if (analysisEnabled && !ScreenCaptureService.isAvailable()) {
                        if (keepAliveStarted) stopKeepAlive()
                        val now = System.currentTimeMillis()
                        if (now - lastCaptureMissingLog > 10_000) {
                            val msg = "录屏未授权，无法分析（请先在「模型」页开启录屏）"

                            notifyUser(msg)
//                            Toast.makeText(applicationContext, "$msg", Toast.LENGTH_LONG).show()
                            addLog(
                                OperationLog.statusLog(
                                    "散步模式", msg
                                )
                            )
                            lastCaptureMissingLog = now
                        }
                        delay(5000)
                        continue
                    }
                    // 已授权：确保保活 Activity 在运行（切到抖音后 App 退后台会被系统停止录屏）
                    // 仅在需要截帧分析时保活；纯评论模式无需录屏
                    if (analysisEnabled && !keepAliveStarted) startKeepAlive()
                    if (isAnalyzingVideo) {
                        delay(500)
                        continue
                    }
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            // 直播前置检测（独立快照）
                            val liveRoot = rootInActiveWindow
                            if (liveRoot != null) {
                                // 1) 已进入直播间（非预览卡片）：按一下返回键，回到推荐列表界面
                                if (detectEnteredLiveRoom(liveRoot)) {
                                    addLog(
                                        OperationLog.statusLog(
                                            "散步模式", "检测到已进入直播间，按返回键回到推荐列表"
                                        )
                                    )
                                    notifyUser("已进入直播间，按返回键中…")
                                    performGlobalAction(GLOBAL_ACTION_BACK)
                                    liveRoot.recycle()
                                    delay(1500)
                                    continue
                                }
                                // 2) 直播预览卡片（如「点击进入直播间」）：跳过并切下一个视频，绝不调用大模型
                                val live = detectLiveStream(liveRoot)
                                liveRoot.recycle()
                                if (live.isLive) {
                                    addLog(
                                        OperationLog.statusLog(
                                            "散步模式", "检测到直播预览（${live.reason}），跳过"
                                        )
                                    )
                                    notifyUser("检测到直播，已跳过 2")
                                    lastVideoIdentity = LIVE_SKIP_MARKER
                                    advanceToNextVideo()
                                    lastAdvanceTime = System.currentTimeMillis()
                                    delay(1200)
                                    continue
                                }
                            }
                            val identity = detectVideoIdentity(root)
                            if (identity != null && identity != lastVideoIdentity) {
                                // 刷到新视频：抓取前 10 秒随机几帧 → 分析 → 自动点赞/收藏 → 提示 → 切下一个
                                lastVideoIdentity = identity
                                isAnalyzingVideo = true
                                analyzeAndAct(identity, analysisEnabled)
                                isAnalyzingVideo = false
                                advanceToNextVideo()
                                lastAdvanceTime = System.currentTimeMillis()
                                // 随机间隔抖动：模拟真人看完视频后的自然停顿，打破规律节奏，降低风控命中
                                val jMin = prefs.jitterMinMsFlow.first()
                                val jMax = prefs.jitterMaxMsFlow.first()
                                val jitter = if (jMax > jMin) Random.nextLong(
                                    jMin.toLong(),
                                    jMax.toLong()
                                ) else jMin.toLong()
                                delay(2600 + jitter)
                            } else {
                                // 仍是同一个视频：若切换失败则定时重试，否则稍后再看
                                if (lastVideoIdentity != null && System.currentTimeMillis() - lastAdvanceTime > 6000) {
                                    advanceToNextVideo()
                                    lastAdvanceTime = System.currentTimeMillis()
                                }
                                delay(1200)
                            }
                        } finally {
                            root.recycle()
                        }
                    } else {
                        delay(1200)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "散步模式循环出错: ${e.message}", e)
            } finally {
                videoWatchJob = null
                isVideoWatching = false
            }
        }
    }

    /** 暂停自动视频分析（仅在运行中有效） */
    fun pauseVideoWatch() {
        if (isVideoWatching && !videoWatchPaused) {
            videoWatchPaused = true
            addLog(OperationLog.statusLog("散步模式", "已暂停散步模式"))
        }
    }

    /** 继续自动视频分析（仅在暂停中有效） */
    fun resumeVideoWatch() {
        if (isVideoWatching && videoWatchPaused) {
            videoWatchPaused = false
            addLog(OperationLog.statusLog("散步模式", "已恢复散步模式"))
        }
    }

    /** 结束自动视频分析 */
    fun stopVideoWatch() {
        if (videoWatchJob?.isActive == true || isVideoWatching) {
            videoWatchJob?.cancel()
            videoWatchJob = null
            isVideoWatching = false
            videoWatchPaused = false
            stopKeepAlive()
            addLog(OperationLog.statusLog("散步模式", "已结束散步模式"))
        }
    }

    /** 启动透明保活 Activity，使本 App 保持前台，避免切到抖音后系统停止 MediaProjection 录屏 */
    private fun startKeepAlive() {
        try {
            val intent = Intent(this, KeepAliveActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            keepAliveStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "启动保活 Activity 失败: ${e.message}")
        }
    }

    /** 关闭保活 Activity（视频分析停止时调用） */
    private fun stopKeepAlive() {
        if (keepAliveStarted) {
            applicationContext.sendBroadcast(Intent(KeepAliveActivity.ACTION_STOP_KEEPALIVE))
            keepAliveStarted = false
        }
    }

    /**
     * 切换到下一个视频：优先用无障碍手势向上滑动（抖音推荐流标准切下一个手势），
     * 失败（如未授予手势权限）再退化为对可滚动节点执行向前滚动。
     */
    fun advanceToNextVideo() {
        serviceScope.launch {
            val ok = goToNextVideoByGesture() || tryScrollOnce()
            addLog(
                OperationLog.statusLog(
                    "散步模式",
                    if (ok) "已切换到下一个视频" else "未能自动切换（请检查无障碍「执行手势」权限）"
                )
            )
        }
    }

    /**
     * 通过无障碍手势向上滑动，切换到抖音的下一个视频。
     * 需要 accessibility_service_config 中 android:canPerformGestures="true"（已开启）。
     */
    private fun goToNextVideoByGesture(): Boolean {
        val dm = resources.displayMetrics
        val midX = dm.widthPixels / 2f
        val startY = dm.heightPixels * 0.82f
        val endY = dm.heightPixels * 0.18f
        val path = Path().apply {
            moveTo(midX, startY)
            lineTo(midX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /** 在抖音界面顶部居中显示一条临时提示气泡（约 2.8 秒后自动消失），用于决策结果提示 */
    private fun notifyUser(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            showTransientToast(message)
        }
    }

    private fun showTransientToast(text: String) {
        val token = ++toastToken
        try {
            toastView?.let { overlayWm.removeView(it) }
        } catch (_: Exception) {
        }
        toastView = null

        val dm = resources.displayMetrics
        val density = dm.density
        val tv = TextView(this@DouyinAccessibilityService).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(
                (14 * density).toInt(),
                (10 * density).toInt(),
                (14 * density).toInt(),
                (10 * density).toInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.parseColor("#D9000000"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (90 * density).toInt()
        }
        toastView = tv
        runCatching { overlayWm.addView(tv, params) }

        serviceScope.launch(Dispatchers.Main) {
            delay(2800)
            if (toastToken == token) {
                try {
                    toastView?.let { overlayWm.removeView(it) }
                } catch (_: Exception) {
                }
                toastView = null
            }
        }
    }

    // ---- 翻页（自动滚动评论区并记录新评论） ----

    /**
     * 记录一批评论中尚未记录过的新评论（按 uniqueKey 去重，会话内同一评论只记一次）。
     * @return 本次新记录的评论列表（供调用方进一步处理，如意向客户关注）
     */
    private fun recordNewComments(classified: List<CommentInfo>): List<CommentInfo> {
        val newlyAdded = mutableListOf<CommentInfo>()
        for (comment in classified) {
            if (comment.content.isNotBlank() && seenCommentKeys.add(comment.uniqueKey())) {
                addLog(
                    OperationLog.commentLog(
                        username = comment.username,
                        content = comment.content,
                        category = comment.category,
                        keywords = comment.matchedKeywords
                    )
                )
                if (comment.category == CommentCategory.INTENT) intentCount++
                newlyAdded.add(comment)
            }
        }
        return newlyAdded
    }

    /**
     * 开始自动翻页：循环向下滚动评论区，每滚一页扫描一次，逐条记录新出现的评论。
     * 触底或离开评论区自动结束；若当前处于暂停状态则视为“继续”。
     */
    fun startPageFlip() {
        if (pageFlipState == PageFlipState.PAUSED) {
            pageFlipState = PageFlipState.RUNNING
            addLog(OperationLog.statusLog("继续翻页", "已从暂停恢复自动翻页"))
            return
        }
        if (pageFlipJob?.isActive == true) {
            addLog(OperationLog.statusLog("翻页进行中", "自动翻页已在运行，无需重复开始"))
            return
        }
        pageFlipState = PageFlipState.RUNNING
        pageFlipJob = serviceScope.launch {
            addLog(OperationLog.statusLog("开始翻页", "开始自动翻页，新评论将逐条记录"))
            try {
                while (isActive && pageFlipState != PageFlipState.IDLE) {
                    // 暂停时挂起等待，不退出循环
                    if (pageFlipState == PageFlipState.PAUSED) {
                        delay(300)
                        continue
                    }
                    // 离开评论区则自动停止
                    if (!isInCommentPage) {
                        addLog(OperationLog.statusLog("翻页停止", "已离开抖音评论区，自动停止翻页"))
                        break
                    }
                    // 滚动一页
                    val scrolled = tryScrollOnce()
                    // 扫描当前可见评论并记录新评论
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            val comments = commentScanner.scanComments(root)
                            val newOnes =
                                recordNewComments(commentClassifier.classifyBatch(comments))
                            if (newOnes.isNotEmpty()) {
                                Log.d(TAG, "翻页本轮新增 ${newOnes.size} 条评论")
                                notifyStats()
                            }
                            // 遇到意向客户：暂停翻页 → 进主页关注 → 返回 → 继续
                            val intentTargets =
                                newOnes.filter { it.category == CommentCategory.INTENT }.take(3)
                            for (target in intentTargets) {
                                pageFlipState = PageFlipState.PAUSED
                                addLog(
                                    OperationLog.statusLog(
                                        "暂停翻页", "遇到意向客户 ${target.username}，进入主页关注"
                                    )
                                )
                                try {
                                    followUserViaProfile(target.username)
                                } catch (e: Exception) {
                                    Log.e(
                                        TAG, "关注意向客户 ${target.username} 出错: ${e.message}", e
                                    )
                                    addLog(
                                        OperationLog.followLog(
                                            target.username, false, "关注流程异常: ${e.message}"
                                        )
                                    )
                                }
                                pageFlipState = PageFlipState.RUNNING
                                delay(300)
                            }
                        } finally {
                            root.recycle()
                        }
                    }
                    if (!scrolled) {
                        addLog(
                            OperationLog.statusLog(
                                "翻页到底", "已滚动到评论区底部，自动结束翻页"
                            )
                        )
                        break
                    }
                    // 每轮间隔随机 300~2000ms（含 300、不含 2001 即上限 2000ms），模拟真人节奏、降低被限流概率
                    delay(kotlin.random.Random.nextLong(300, 2001))
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动翻页出错: ${e.message}", e)
            } finally {
                pageFlipJob = null
                pageFlipState = PageFlipState.IDLE
            }
        }
    }

    /** 暂停自动翻页（仅在运行中有效） */
    fun pausePageFlip() {
        if (pageFlipState == PageFlipState.RUNNING) {
            pageFlipState = PageFlipState.PAUSED
            addLog(OperationLog.statusLog("暂停翻页", "已暂停自动翻页"))
        } else {
            addLog(OperationLog.statusLog("无翻页任务", "当前没有正在运行的翻页任务，无法暂停"))
        }
    }

    /** 继续自动翻页（仅在暂停中有效） */
    fun resumePageFlip() {
        if (pageFlipState == PageFlipState.PAUSED) {
            pageFlipState = PageFlipState.RUNNING
            addLog(OperationLog.statusLog("继续翻页", "已恢复自动翻页"))
        } else {
            addLog(OperationLog.statusLog("无暂停任务", "当前没有暂停的翻页任务，无法继续"))
        }
    }

    /** 结束自动翻页（取消任务并回到空闲态） */
    fun stopPageFlip() {
        if (pageFlipJob?.isActive == true || pageFlipState != PageFlipState.IDLE) {
            pageFlipJob?.cancel()
            pageFlipJob = null
            pageFlipState = PageFlipState.IDLE
            addLog(OperationLog.statusLog("结束翻页", "已手动结束自动翻页"))
        } else {
            addLog(OperationLog.statusLog("无翻页任务", "当前没有运行中的翻页任务"))
        }
    }

    // ---- 意向客户：进主页关注 ----

    /**
     * 遇到意向客户时：在当前评论区点击其头像/昵称进入主页，点击关注后返回评论区。
     * 整个过程在翻页协程内同步执行（相当于暂停翻页），完成后由调用方恢复 RUNNING。
     *
     * @param username 目标用户名
     * @return 是否成功在主页点击了关注
     */
    private suspend fun followUserViaProfile(username: String): Boolean {
        // 已关注过的用户跳过（避免重复进场）
        if (prefs.isUserFollowed(username)) {
            addLog(OperationLog.followLog(username, false, "已关注过，跳过主页关注"))
            return false
        }

        // 1. 在评论区找到该用户的可点击入口
        val root = rootInActiveWindow ?: return false
        var entry: AccessibilityNodeInfo? = null
        try {
            entry = commentScanner.findUserClickableNode(root, username)
        } finally {
            root.recycle()
        }
        if (entry == null) {
            addLog(OperationLog.followLog(username, false, "未在当前评论区找到用户入口"))
            return false
        }

        // 2. 点击进入主页
        val clicked = try {
            entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            entry.recycle()
        }
        if (!clicked) {
            addLog(OperationLog.followLog(username, false, "点击用户入口失败"))
            return false
        }
        addLog(OperationLog.statusLog("处理意向客户", "已进入 $username 的主页，准备关注"))
        delay(1500)

        // 3. 在主页查找并点击“关注”
        val followed = navigateAndFollowOnProfile(username)
        if (followed) {
            prefs.recordFollow(username)
            followedCount++
            addLog(OperationLog.followLog(username, true, "已进入主页并关注"))
        } else {
            addLog(OperationLog.followLog(username, false, "主页未找到“关注”按钮"))
        }

        // 4. 返回评论区
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(1200)
        waitForCommentPage()
        return followed
    }

    /**
     * 在主页窗口中轮询查找“关注”按钮并点击。
     */
    private suspend fun navigateAndFollowOnProfile(username: String): Boolean {
        repeat(8) {
            val root = rootInActiveWindow ?: return@repeat
            try {
                val btn = findFollowButtonOnProfile(root)
                if (btn != null) {
                    val ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    btn.recycle()
                    if (ok) {
                        delay(500)
                        return true
                    }
                }
            } finally {
                root.recycle()
            }
            delay(400)
        }
        Log.w(TAG, "主页关注 $username 超时未找到按钮")
        return false
    }

    /**
     * 在主页根节点中查找“关注”按钮（文本命中 [IntentKeywords.UNFOLLOW_TEXTS] 且非已关注态）。
     */
    private fun findFollowButtonOnProfile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 策略1: resource-id 匹配
        for (id in IntentKeywords.DOUYIN_FOLLOW_IDS) {
            val buttons = root.findAccessibilityNodeInfosByViewId(id)
            for (b in buttons) {
                val combined = buildString {
                    append(b.text?.toString()?.trim() ?: "")
                    append(" ")
                    append(b.contentDescription?.toString()?.trim() ?: "")
                }
                val isFollow = IntentKeywords.UNFOLLOW_TEXTS.any { it in combined }
                val isFollowed = IntentKeywords.FOLLOWED_TEXTS.any { it in combined }
                if (isFollow && !isFollowed) {
                    return b // 调用方回收
                }
                b.recycle()
            }
        }
        // 策略2: 文本查找可点击的关注按钮
        for (followText in IntentKeywords.UNFOLLOW_TEXTS) {
            val nodes = root.findAccessibilityNodeInfosByText(followText)
            var found: AccessibilityNodeInfo? = null
            for (n in nodes) {
                val combined = buildString {
                    append(n.text?.toString()?.trim() ?: "")
                    append(" ")
                    append(n.contentDescription?.toString()?.trim() ?: "")
                }
                val isFollowed = IntentKeywords.FOLLOWED_TEXTS.any { it in combined }
                if (found == null && n.isClickable && !isFollowed) {
                    found = n
                    continue
                }
                n.recycle()
            }
            if (found != null) return found
        }
        return null
    }

    /**
     * 等待回到评论区（轮询检测，超时即返回）。
     */
    private suspend fun waitForCommentPage(timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    if (detectCommentPage(root)) return
                } finally {
                    root.recycle()
                }
            }
            delay(400)
        }
    }

    /** 翻页状态枚举 */
    private enum class PageFlipState {
        /** 空闲 */
        IDLE,

        /** 运行中 */
        RUNNING,

        /** 已暂停 */
        PAUSED
    }
}
