package com.douyin.auto.model

/**
 * 评论分类枚举
 */
enum class CommentCategory {
    /** 正常评论 - 普通互动内容 */
    NORMAL,
    /** 广告/垃圾评论 - 含推广链接、刷粉等垃圾信息 */
    AD,
    /** 意向客户 - 潜在购买意向 */
    INTENT
}

/**
 * 评论数据模型
 *
 * @property username 评论用户名
 * @property content 评论内容
 * @property timestamp 评论时间戳（提取时的系统时间）
 * @property isFollowed 是否已关注该用户
 * @property category 评论分类结果
 * @property matchedKeywords 匹配到的关键词列表
 * @property nodeHash 用于去重的节点哈希值
 */
data class CommentInfo(
    val username: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isFollowed: Boolean = false,
    val category: CommentCategory = CommentCategory.NORMAL,
    val matchedKeywords: List<String> = emptyList(),
    val nodeHash: Int = 0
) {
    /**
     * 生成用于去重的唯一标识
     */
    fun uniqueKey(): String = "${username}_${content}".hashCode().toString()

    companion object {
        /** 空评论单例，用于错误处理 */
        val EMPTY = CommentInfo()
    }
}
