package com.douyin.auto.model

/**
 * 操作类型枚举
 */
enum class OperationType {
    /** 扫描评论 */
    SCAN,
    /** 分类评论 */
    CLASSIFY,
    /** 关注用户 */
    FOLLOW,
    /** 服务状态变更 */
    STATUS,
    /** 评论记录（评论内容 + 评论者昵称） */
    COMMENT,
    /** 视频内容分析 */
    ANALYZE,
    /** 点赞视频 */
    LIKE,
    /** 收藏视频 */
    COLLECT,
    /** 发送评论 */
    SEND_COMMENT
}

/**
 * 操作日志数据模型
 *
 * @property id 日志唯一 ID
 * @property timestamp 操作时间戳
 * @property action 操作类型
 * @property target 操作目标（用户名或描述）
 * @property result 操作结果（成功/失败/跳过）
 * @property detail 操作详情
 */
data class OperationLog(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val action: OperationType = OperationType.SCAN,
    val target: String = "",
    val result: String = "",
    val detail: String = ""
) {
    companion object {
        /**
         * 创建一条扫描日志
         */
        fun scanLog(commentCount: Int, pageInfo: String = ""): OperationLog {
            return OperationLog(
                action = OperationType.SCAN,
                target = "评论区",
                result = "成功",
                detail = "扫描到 $commentCount 条评论${if (pageInfo.isNotEmpty()) " ($pageInfo)" else ""}"
            )
        }

        /**
         * 创建一条分类日志
         */
        fun classifyLog(username: String, category: CommentCategory, keywords: List<String>): OperationLog {
            val categoryText = when (category) {
                CommentCategory.NORMAL -> "正常"
                CommentCategory.AD -> "广告"
                CommentCategory.INTENT -> "意向客户"
            }
            val detailText = if (keywords.isNotEmpty()) {
                "分类: $categoryText, 匹配关键词: ${keywords.joinToString(", ")}"
            } else {
                "分类: $categoryText"
            }
            return OperationLog(
                action = OperationType.CLASSIFY,
                target = username,
                result = categoryText,
                detail = detailText
            )
        }

        /**
         * 创建一条评论记录日志（评论内容 + 评论者昵称）
         *
         * @param username 评论者昵称
         * @param content 评论内容
         * @param category 分类结果（用作结果标签）
         * @param keywords 命中的关键词（可选，附在详情末尾）
         */
        fun commentLog(
            username: String,
            content: String,
            category: CommentCategory,
            keywords: List<String> = emptyList()
        ): OperationLog {
            val categoryText = when (category) {
                CommentCategory.NORMAL -> "正常"
                CommentCategory.AD -> "广告"
                CommentCategory.INTENT -> "意向客户"
            }
            val detailText = if (keywords.isNotEmpty()) {
                "$content（关键词: ${keywords.joinToString(", ")}）"
            } else {
                content
            }
            return OperationLog(
                action = OperationType.COMMENT,
                target = username,
                result = categoryText,
                detail = detailText
            )
        }

        /**
         * 创建一条关注日志
         */
        fun followLog(username: String, success: Boolean, reason: String = ""): OperationLog {
            return OperationLog(
                action = OperationType.FOLLOW,
                target = username,
                result = if (success) "成功" else "失败",
                detail = if (reason.isNotEmpty()) reason else if (success) "已点击关注按钮" else "关注操作未成功"
            )
        }

        /**
         * 创建一条服务状态日志
         */
        fun statusLog(status: String, detail: String = ""): OperationLog {
            return OperationLog(
                action = OperationType.STATUS,
                target = "服务",
                result = status,
                detail = detail
            )
        }

        /**
         * 创建一条视频内容分析日志
         */
        fun analyzeLog(
            subject: String,
            shouldLike: Boolean,
            shouldCollect: Boolean,
            reason: String
        ): OperationLog {
            val decision = buildString {
                if (shouldLike) append("点赞")
                if (shouldCollect) {
                    if (isNotEmpty()) append("/")
                    append("收藏")
                }
                if (isEmpty()) append("不操作")
            }
            return OperationLog(
                action = OperationType.ANALYZE,
                target = if (subject.isNotEmpty()) subject else "未知",
                result = decision,
                detail = reason
            )
        }

        /**
         * 创建一条点赞日志
         */
        fun likeLog(success: Boolean, reason: String = ""): OperationLog {
            return OperationLog(
                action = OperationType.LIKE,
                target = "当前视频",
                result = if (success) "成功" else "失败",
                detail = if (reason.isNotEmpty()) reason else if (success) "已点击点赞按钮" else "点赞操作未成功"
            )
        }

        /**
         * 创建一条发送评论日志
         */
        fun sendCommentLog(success: Boolean, detail: String = ""): OperationLog {
            return OperationLog(
                action = OperationType.SEND_COMMENT,
                target = "当前视频",
                result = if (success) "成功" else "失败",
                detail = if (detail.isNotEmpty()) detail else if (success) "已发送评论" else "发送评论未成功"
            )
        }

        fun collectLog(success: Boolean, reason: String = ""): OperationLog {
            return OperationLog(
                action = OperationType.COLLECT,
                target = "当前视频",
                result = if (success) "成功" else "失败",
                detail = if (reason.isNotEmpty()) reason else if (success) "已点击收藏按钮" else "收藏操作未成功"
            )
        }
    }
}
