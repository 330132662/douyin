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
    STATUS
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
    }
}
