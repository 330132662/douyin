package com.douyin.auto.service

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.douyin.auto.model.CommentInfo
import com.douyin.auto.model.IntentKeywords

/**
 * 评论扫描器
 *
 * 从 AccessibilityNodeInfo 树中提取评论列表。
 * 兼容不同抖音版本的节点结构，使用多种 fallback 匹配策略。
 */
class CommentScanner {

    companion object {
        private const val TAG = "CommentScanner"

        /** 最大扫描评论数（防止过多节点影响性能） */
        private const val MAX_COMMENTS = 50

        /** 评论内容最小长度（过滤空节点） */
        private const val MIN_CONTENT_LENGTH = 2
    }

    /**
     * 从根节点扫描评论列表
     *
     * @param rootNode 当前窗口的根 AccessibilityNodeInfo
     * @return 扫描到的评论列表
     */
    fun scanComments(rootNode: AccessibilityNodeInfo): List<CommentInfo> {
        val comments = mutableListOf<CommentInfo>()

        try {
            // 策略1: 通过 resource-id 查找评论列表容器
            var commentListFound = false
            for (listId in IntentKeywords.DOUYIN_COMMENT_LIST_IDS) {
                val listNodes = rootNode.findAccessibilityNodeInfosByViewId(listId)
                if (listNodes.isNotEmpty()) {
                    Log.d(TAG, "通过 resource-id 找到评论列表: $listId, 数量: ${listNodes.size}")
                    for (listNode in listNodes) {
                        extractCommentsFromListNode(listNode, comments)
                    }
                    recycleAllNodes(listNodes)
                    commentListFound = true
                    break
                }
                recycleAllNodes(listNodes)
            }

            // 策略2: 通过评论项 resource-id 直接查找
            if (!commentListFound) {
                Log.d(TAG, "策略1未找到评论列表，尝试策略2: 直接查找评论项")
                for (itemId in IntentKeywords.DOUYIN_COMMENT_ITEM_IDS) {
                    val itemNodes = rootNode.findAccessibilityNodeInfosByViewId(itemId)
                    if (itemNodes.isNotEmpty()) {
                        Log.d(TAG, "通过 resource-id 找到评论项: $itemId, 数量: ${itemNodes.size}")
                        for (itemNode in itemNodes) {
                            extractCommentFromItem(itemNode)?.let { comments.add(it) }
                        }
                        recycleAllNodes(itemNodes)
                        commentListFound = true
                        break
                    }
                    recycleAllNodes(itemNodes)
                }
            }

            // 策略3: 通过文本特征查找（查找"评论"相关文本附近的 RecyclerView）
            if (!commentListFound) {
                Log.d(TAG, "策略2未找到，尝试策略3: 通过文本和类名匹配")
                extractCommentsByTextSearch(rootNode, comments)
            }

            Log.d(TAG, "扫描完成，共提取 ${comments.size} 条评论")

        } catch (e: Exception) {
            Log.e(TAG, "扫描评论时出错: ${e.message}", e)
        }

        return comments.distinctBy { it.uniqueKey() }
    }

    /**
     * 从评论列表节点中提取子评论
     */
    private fun extractCommentsFromListNode(
        listNode: AccessibilityNodeInfo,
        comments: MutableList<CommentInfo>
    ) {
        if (comments.size >= MAX_COMMENTS) return

        for (i in 0 until listNode.childCount) {
            val child = listNode.getChild(i) ?: continue
            try {
                val comment = extractCommentFromItem(child)
                if (comment != null) {
                    comments.add(comment)
                }
                // 注意：extractCommentFromItem 内部（extractContent/extractUsername 调用的
                // findLongestText / findFirstNonEmptyText）已经遍历并回收了 child 的子孙节点。
                // 此处绝不能继续向下递归遍历 child 子树，否则 getChild 会取到对象池中已回收
                // 复用的同一实例并再次 recycle，触发 "Already in the pool!" 异常使扫描中断。
            } finally {
                child.recycle() // 每个评论项仅回收一次
            }
            if (comments.size >= MAX_COMMENTS) break
        }
    }

    /**
     * 从单个评论项节点中提取评论信息
     */
    private fun extractCommentFromItem(itemNode: AccessibilityNodeInfo): CommentInfo? {
        var username = ""
        var content = ""
        var isFollowed = false

        try {
            // 提取用户名
            username = extractUsername(itemNode)

            // 提取评论内容
            content = extractContent(itemNode)

            // 检查关注状态
            isFollowed = checkFollowStatus(itemNode)

            // 验证：用户名和内容至少有一个非空
            if (username.isEmpty() && content.length < MIN_CONTENT_LENGTH) {
                return null
            }

            return CommentInfo(
                username = username.ifEmpty { "未知用户" },
                content = content,
                isFollowed = isFollowed,
                nodeHash = itemNode.hashCode()
            )
        } catch (e: Exception) {
            Log.w(TAG, "提取单个评论时出错: ${e.message}")
            return null
        }
    }

    /**
     * 提取用户名
     */
    private fun extractUsername(node: AccessibilityNodeInfo): String {
        // 策略1: resource-id 匹配
        for (id in IntentKeywords.DOUYIN_USERNAME_IDS) {
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()?.trim() ?: ""
                recycleAllNodes(nodes)
                if (text.isNotEmpty()) return text
            }
            recycleAllNodes(nodes)
        }

        // 策略2: 查找 TextView，取第一个有内容的
        val textNodes = node.findAccessibilityNodeInfosByViewId(
            "com.ss.android.ugc.aweme:id/nickname"
        )
        if (textNodes.isNotEmpty()) {
            val text = textNodes[0].text?.toString()?.trim() ?: ""
            recycleAllNodes(textNodes)
            if (text.isNotEmpty()) return text
        }
        recycleAllNodes(textNodes)

        // 策略3: 递归查找所有 TextView，返回第一个非空文本
        return findFirstNonEmptyText(node, excludeTexts = IntentKeywords.FOLLOWED_TEXTS +
                IntentKeywords.UNFOLLOW_TEXTS + listOf("回复", "展开"))
    }

    /**
     * 提取评论内容
     */
    private fun extractContent(node: AccessibilityNodeInfo): String {
        // 策略1: resource-id 匹配
        for (id in IntentKeywords.DOUYIN_CONTENT_IDS) {
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()?.trim() ?: ""
                recycleAllNodes(nodes)
                if (text.isNotEmpty()) return text
            }
            recycleAllNodes(nodes)
        }

        // 策略2: 递归查找最长文本的 TextView
        return findLongestText(node, excludeTexts = IntentKeywords.FOLLOWED_TEXTS +
                IntentKeywords.UNFOLLOW_TEXTS + listOf("回复"))
    }

    /**
     * 检查用户是否已关注
     */
    private fun checkFollowStatus(node: AccessibilityNodeInfo): Boolean {
        // 查找关注按钮节点
        for (id in IntentKeywords.DOUYIN_FOLLOW_IDS) {
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                val text = nodes[0].text?.toString()?.trim() ?: ""
                val contentDesc = nodes[0].contentDescription?.toString()?.trim() ?: ""
                recycleAllNodes(nodes)
                val combined = "$text $contentDesc"
                return IntentKeywords.FOLLOWED_TEXTS.any { it in combined }
            }
            recycleAllNodes(nodes)
        }

        // 策略2: 通过文本查找
        for (followText in IntentKeywords.FOLLOWED_TEXTS) {
            val nodes = node.findAccessibilityNodeInfosByText(followText)
            if (nodes.isNotEmpty()) {
                recycleAllNodes(nodes)
                return true
            }
            recycleAllNodes(nodes)
        }

        return false
    }

    /**
     * 通过文本和类名特征搜索评论（策略3 fallback）
     */
    private fun extractCommentsByTextSearch(
        rootNode: AccessibilityNodeInfo,
        comments: MutableList<CommentInfo>
    ) {
        // 查找包含"评论"文本的父节点附近的 RecyclerView
        val commentLabelNodes = mutableListOf<AccessibilityNodeInfo>()

        for (label in IntentKeywords.COMMENT_PAGE_TEXTS) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(label)
            nodes.forEach { commentLabelNodes.add(it) }
        }

        for (labelNode in commentLabelNodes) {
            try {
                // 向上查找父节点，再向下查找 RecyclerView
                var parent = labelNode.parent
                var depth = 0
                while (parent != null && depth < 5) {
                    val recyclerChildren = findRecyclerViewChildren(parent)
                    if (recyclerChildren.isNotEmpty()) {
                        for (rv in recyclerChildren) {
                            try {
                                extractCommentsFromListNode(rv, comments)
                            } finally {
                                rv.recycle()
                            }
                        }
                        break
                    }
                    val nextParent = parent.parent
                    parent.recycle()
                    parent = nextParent
                    depth++
                }
                parent?.recycle()
            } finally {
                labelNode.recycle()
            }
        }
    }

    /**
     * 在节点的子节点中查找 RecyclerView
     */
    private fun findRecyclerViewChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val recyclerClasses = listOf(
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
        )
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.className?.toString() in recyclerClasses) {
                result.add(child)
            } else {
                child.recycle()
            }
        }
        return result
    }

    /**
     * 递归查找第一个非空文本节点
     */
    private fun findFirstNonEmptyText(
        node: AccessibilityNodeInfo,
        excludeTexts: List<String> = emptyList()
    ): String {
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty() && excludeTexts.none { text.contains(it) }) {
            return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val result = findFirstNonEmptyText(child, excludeTexts)
                if (result.isNotEmpty()) return result
            } finally {
                child.recycle()
            }
        }
        return ""
    }

    /**
     * 递归查找最长文本节点
     */
    private fun findLongestText(
        node: AccessibilityNodeInfo,
        excludeTexts: List<String> = emptyList()
    ): String {
        var longest = ""
        val text = node.text?.toString()?.trim() ?: ""
        if (text.isNotEmpty() && excludeTexts.none { text.contains(it) } && text.length > longest.length) {
            longest = text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val childText = findLongestText(child, excludeTexts)
                if (childText.length > longest.length) {
                    longest = childText
                }
            } finally {
                child.recycle()
            }
        }
        return longest
    }

    /**
     * 安全回收节点列表
     */
    private fun recycleAllNodes(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                node.recycle()
            } catch (_: Exception) {
                // 忽略回收异常
            }
        }
    }

    /**
     * 在当前窗口中查找指定用户“可点击进入主页”的节点（头像/昵称所在的可点击区域）。
     *
     * 实现：先用用户名文本定位其文本节点，再向上回溯找到第一个可点击的祖先（通常是
     * 整条评论行或头像容器），点击该区域即可打开用户主页。
     *
     * @param root 当前窗口根节点（调用方负责回收）
     * @param username 目标用户名（精确匹配）
     * @return 可点击节点；找不到返回 null。**调用方负责回收返回的节点**。
     */
    fun findUserClickableNode(root: AccessibilityNodeInfo, username: String): AccessibilityNodeInfo? {
        if (username.isEmpty()) return null
        val nodes = root.findAccessibilityNodeInfosByText(username)
        var match: AccessibilityNodeInfo? = null
        for (n in nodes) {
            val t = n.text?.toString()?.trim()
            // 精确匹配，或文本以用户名开头（兼容昵称带 emoji/后缀导致精确匹配失败）
            if (t == username || (t != null && username.isNotEmpty() && t.startsWith(username))) {
                match = n
                break
            }
        }
        // 回收所有非匹配节点
        for (n in nodes) {
            if (n != match) n.recycle()
        }
        if (match == null) return null

        // 向上回溯找到第一个可点击祖先（兼容 match 自身即可点击的情况）
        var cur: AccessibilityNodeInfo? = match
        var clickable: AccessibilityNodeInfo? = null
        while (cur != null) {
            if (cur.isClickable) {
                clickable = cur
                break
            }
            cur = cur.parent
        }

        // clickable 与 match 为不同节点时才回收 match，避免 clickable==match 时返回已回收节点
        return if (clickable != null && clickable != match) {
            match.recycle()
            clickable
        } else {
            match
        }
    }
}
