package com.douyin.auto.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.douyin.auto.config.AppPreferences
import com.douyin.auto.model.CommentCategory
import com.douyin.auto.model.CommentInfo
import com.douyin.auto.model.IntentKeywords
import kotlinx.coroutines.*

/**
 * 自动关注引擎
 *
 * 在评论区中找到"关注"按钮并对意向用户执行关注操作。
 * 包含限速、每日上限、去重等安全机制。
 */
class AutoFollowEngine(
    private val prefs: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "AutoFollowEngine"

        /** 关注操作之间的最小间隔（毫秒） */
        private const val MIN_FOLLOW_INTERVAL_MS = 600L

        /** 操作超时时间（毫秒） */
        private const val OPERATION_TIMEOUT_MS = 3000L
    }

    /** 上次操作时间戳 */
    private var lastOperationTime: Long = 0L

    /** 操作中标记（防止并发） */
    @Volatile
    private var isOperating: Boolean = false

    /**
     * 对列表中的意向评论执行关注操作
     *
     * @param rootNode 当前窗口根节点
     * @param intentComments 分类后的意向评论列表
     * @return 关注结果：成功关注数
     */
    suspend fun followIntentUsers(
        rootNode: AccessibilityNodeInfo,
        intentComments: List<CommentInfo>
    ): Int = withContext(Dispatchers.Default) {
        if (intentComments.isEmpty()) {
            Log.d(TAG, "没有意向用户需要关注")
            return@withContext 0
        }

        // 检查每日上限
        if (prefs.isDailyLimitReached()) {
            Log.d(TAG, "已达到每日关注上限，跳过")
            return@withContext 0
        }

        var successCount = 0
        val targetComments = intentComments
            .filter { it.category == CommentCategory.INTENT && !it.isFollowed }
            .take(5) // 每轮最多处理5个

        for (comment in targetComments) {
            // 再次检查每日上限
            if (prefs.isDailyLimitReached()) {
                Log.d(TAG, "已到达每日上限，停止关注")
                break
            }

            // 检查是否已关注
            if (prefs.isUserFollowed(comment.username)) {
                Log.d(TAG, "用户 ${comment.username} 已关注过，跳过")
                continue
            }

            // 执行关注
            val success = performFollow(rootNode, comment.username)
            if (success) {
                prefs.recordFollow(comment.username)
                successCount++
                Log.d(TAG, "成功关注用户: ${comment.username} (今日第 ${successCount} 个)")
            }

            // 限速等待
            delay(MIN_FOLLOW_INTERVAL_MS)
        }

        Log.d(TAG, "本轮关注完成: 成功 $successCount 个")
        successCount
    }

    /**
     * 执行单个关注操作
     *
     * @param rootNode 当前窗口根节点
     * @param username 目标用户名
     * @return 是否成功点击关注按钮
     */
    private suspend fun performFollow(
        rootNode: AccessibilityNodeInfo,
        username: String
    ): Boolean {
        if (isOperating) {
            Log.d(TAG, "引擎正忙，跳过 $username")
            return false
        }

        isOperating = true
        try {
            // 限速检查
            val now = System.currentTimeMillis()
            val elapsed = now - lastOperationTime
            if (elapsed < MIN_FOLLOW_INTERVAL_MS) {
                delay(MIN_FOLLOW_INTERVAL_MS - elapsed)
            }

            // 查找关注按钮
            val followButton = findFollowButtonNearUser(rootNode, username)
            if (followButton == null) {
                Log.w(TAG, "未找到用户 $username 的关注按钮")
                return false
            }

            try {
                // 点击关注按钮
                val clicked = followButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    Log.d(TAG, "已点击 $username 的关注按钮")
                    // 等待 UI 更新
                    delay(300L)
                    return true
                } else {
                    Log.w(TAG, "点击 $username 的关注按钮失败")
                    return false
                }
            } finally {
                followButton.recycle()
            }

        } catch (e: Exception) {
            Log.e(TAG, "关注 $username 时出错: ${e.message}", e)
            return false
        } finally {
            lastOperationTime = System.currentTimeMillis()
            isOperating = false
        }
    }

    /**
     * 在指定用户名附近查找关注按钮
     *
     * @param rootNode 根节点
     * @param username 目标用户名
     * @return 关注按钮节点，未找到返回 null
     */
    private fun findFollowButtonNearUser(
        rootNode: AccessibilityNodeInfo,
        username: String
    ): AccessibilityNodeInfo? {
        // 策略1: 通过用户名查找节点，然后在其兄弟/父节点中查找关注按钮
        val userNodes = rootNode.findAccessibilityNodeInfosByText(username)
        for (userNode in userNodes) {
            try {
                // 在用户节点的父节点中查找关注按钮
                var parent = userNode.parent
                var depth = 0
                while (parent != null && depth < 4) {
                    val btn = findFollowButtonInNode(parent)
                    if (btn != null) {
                        parent.recycle()
                        return btn
                    }
                    val nextParent = parent.parent
                    parent.recycle()  // 每次迭代都回收
                    parent = nextParent
                    depth++
                }
                parent?.recycle()
            } finally {
                userNode.recycle()
            }
        }

        // 策略2: 直接通过 resource-id 查找所有关注按钮
        for (id in IntentKeywords.DOUYIN_FOLLOW_IDS) {
            val buttons = rootNode.findAccessibilityNodeInfosByViewId(id)
            var found: AccessibilityNodeInfo? = null
            for (button in buttons) {
                if (found == null) {
                    val text = button.text?.toString()?.trim() ?: ""
                    val desc = button.contentDescription?.toString()?.trim() ?: ""
                    if (IntentKeywords.UNFOLLOW_TEXTS.any { it in text || it in desc }) {
                        found = button
                        continue
                    }
                }
                button.recycle()
            }
            if (found != null) return found
        }

        // 策略3: 通过文本查找所有"关注"按钮
        for (followText in IntentKeywords.UNFOLLOW_TEXTS) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(followText)
            var found: AccessibilityNodeInfo? = null
            for (node in nodes) {
                if (found == null && node.isClickable) {
                    found = node
                    continue
                }
                node.recycle()
            }
            if (found != null) return found
        }

        return null
    }

    /**
     * 在节点树中查找关注按钮
     */
    private fun findFollowButtonInNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 通过 resource-id 查找
        for (id in IntentKeywords.DOUYIN_FOLLOW_IDS) {
            val buttons = node.findAccessibilityNodeInfosByViewId(id)
            for (button in buttons) {
                val text = button.text?.toString()?.trim() ?: ""
                val desc = button.contentDescription?.toString()?.trim() ?: ""
                if (IntentKeywords.UNFOLLOW_TEXTS.any { it in text || it in desc }) {
                    return button
                }
                button.recycle()
            }
        }

        // 递归查找可点击的"关注"文本节点
        return findClickableFollowNode(node)
    }

    /**
     * 递归查找可点击的关注按钮
     */
    private fun findClickableFollowNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""

        if (node.isClickable && IntentKeywords.UNFOLLOW_TEXTS.any { it in text || it in desc }) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findClickableFollowNode(child)
                if (result != null) return result
            } finally {
                child.recycle()
            }
        }

        return null
    }

    /**
     * 重置引擎状态
     */
    fun reset() {
        lastOperationTime = 0L
        isOperating = false
        Log.d(TAG, "引擎状态已重置")
    }

    /**
     * 清理资源
     */
    fun destroy() {
        scope.cancel()
        Log.d(TAG, "引擎已销毁")
    }
}
