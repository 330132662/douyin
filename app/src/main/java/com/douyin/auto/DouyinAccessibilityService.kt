package com.douyin.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.douyin.auto.config.AppPreferences
import com.douyin.auto.model.CommentCategory
import com.douyin.auto.model.CommentInfo
import com.douyin.auto.model.IntentKeywords
import com.douyin.auto.model.OperationLog
import com.douyin.auto.service.AutoFollowEngine
import com.douyin.auto.service.CommentClassifier
import com.douyin.auto.service.CommentScanner
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

                // 2. 分类评论
                val classified = commentClassifier.classifyBatch(comments)
                for (comment in classified) {
                    addLog(OperationLog.classifyLog(comment.username, comment.category, comment.matchedKeywords))
                }

                val intentComments = classified.filter { it.category == CommentCategory.INTENT }
                intentCount += intentComments.size

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
        serviceScope.cancel()
        followEngine.destroy()
        statusListener?.invoke(false)
        addLog(OperationLog.statusLog("已停止", "无障碍服务销毁"))
        Log.d(TAG, "无障碍服务 onDestroy")
    }

    // ---- 日志和统计 ----

    private fun addLog(log: OperationLog) {
        logListener?.invoke(log)
        Log.d(TAG, "[${log.action}] ${log.target}: ${log.result} - ${log.detail}")
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
}
