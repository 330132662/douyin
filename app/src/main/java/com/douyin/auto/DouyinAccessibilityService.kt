package com.douyin.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import com.douyin.auto.config.AppPreferences
import com.douyin.auto.model.CommentCategory
import com.douyin.auto.model.CommentInfo
import com.douyin.auto.model.IntentKeywords
import com.douyin.auto.model.OperationLog
import com.douyin.auto.service.AutoFollowEngine
import com.douyin.auto.service.CommentClassifier
import com.douyin.auto.service.CommentScanner
import com.douyin.auto.ui.FloatingAction
import com.douyin.auto.ui.FloatingDotManager
import kotlinx.coroutines.*

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

        /** 日志监听器 */
        var logListener: ((OperationLog) -> Unit)? = null
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

    /** 历史日志缓冲（跨页面/跨时间保留，供日志页读取，避免只在打开页面时记录） */
    private val logHistory = Collections.synchronizedList(mutableListOf<OperationLog>())

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
        followEngine = AutoFollowEngine(prefs, serviceScope)

        // 配置服务信息
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }
        setServiceInfo(info)

        // 加载用户自定义关键词（使用 combine 避免嵌套 collect 死锁）
        serviceScope.launch {
            var currentIntentKeywords: Set<String> = emptySet()
            var currentAdKeywords: Set<String> = emptySet()

            val intentJob = launch { prefs.intentKeywordsFlow.collect { currentIntentKeywords = it } }
            val adJob = launch { prefs.adKeywordsFlow.collect { currentAdKeywords = it } }

            // 每 2 秒检查一次并更新分类器
            while (isActive) {
                delay(2000L)
                commentClassifier.updateKeywords(currentIntentKeywords, currentAdKeywords)
                Log.d(TAG, "关键词配置已同步: 意向${currentIntentKeywords.size}个, 广告${currentAdKeywords.size}个")
            }

            intentJob.cancel()
            adJob.cancel()
        }

        statusListener?.invoke(true)
        addLog(OperationLog.statusLog("已启动", "无障碍服务连接成功"))

        // 显示悬浮操作按钮（小白点）
        floatingDot = FloatingDotManager(
            context = this,
            actions = listOf(
                FloatingAction("开始翻页") { startPageFlip() },
                FloatingAction("暂停翻页") { pausePageFlip() },
                FloatingAction("继续翻页") { resumePageFlip() },
                FloatingAction("结束翻页") { stopPageFlip() },
                FloatingAction("滚动到最新评论 (≤100)") { scrollCommentToEnd(100) }
            )
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
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
        logHistory.add(log)
        if (logHistory.size > 2000) logHistory.removeAt(0)
        logListener?.invoke(log)
        Log.d(TAG, "[${log.action}] ${log.target}: ${log.result} - ${log.detail}")
    }

    /**
     * 获取历史日志快照（供 UI 读取，弥补实时监听在页面未打开时遗漏的问题）
     */
    fun getLogHistory(): List<OperationLog> = synchronized(logHistory) {
        ArrayList(logHistory)
    }

    private fun notifyStats() {
        statsListener?.invoke(
            Stats(
                scannedCount = scannedCount,
                intentCount = intentCount,
                followedCount = followedCount,
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
                    addLog(OperationLog.statusLog("滚动失败", "未检测到可滚动的评论区（请先打开抖音评论区）"))
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
                            val newOnes = recordNewComments(commentClassifier.classifyBatch(comments))
                            if (newOnes.isNotEmpty()) {
                                Log.d(TAG, "翻页本轮新增 ${newOnes.size} 条评论")
                                notifyStats()
                            }
                            // 遇到意向客户：暂停翻页 → 进主页关注 → 返回 → 继续
                            val intentTargets = newOnes
                                .filter { it.category == CommentCategory.INTENT }
                                .take(3)
                            for (target in intentTargets) {
                                pageFlipState = PageFlipState.PAUSED
                                addLog(OperationLog.statusLog("暂停翻页", "遇到意向客户 ${target.username}，进入主页关注"))
                                try {
                                    followUserViaProfile(target.username)
                                } catch (e: Exception) {
                                    Log.e(TAG, "关注意向客户 ${target.username} 出错: ${e.message}", e)
                                    addLog(OperationLog.followLog(target.username, false, "关注流程异常: ${e.message}"))
                                }
                                pageFlipState = PageFlipState.RUNNING
                                delay(300)
                            }
                        } finally {
                            root.recycle()
                        }
                    }
                    if (!scrolled) {
                        addLog(OperationLog.statusLog("翻页到底", "已滚动到评论区底部，自动结束翻页"))
                        break
                    }
                    delay(800)
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
