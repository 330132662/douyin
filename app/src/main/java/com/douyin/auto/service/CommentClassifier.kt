package com.douyin.auto.service

import android.util.Log
import com.douyin.auto.model.CommentCategory
import com.douyin.auto.model.CommentInfo
import com.douyin.auto.model.IntentKeywords

/**
 * 评论分类器
 *
 * 对评论内容进行分类：正常评论、广告/垃圾评论、意向客户。
 * 支持预设关键词和用户自定义关键词。
 */
class CommentClassifier(
    /** 用户自定义意向关键词（可从 DataStore 加载） */
    private var customIntentKeywords: Set<String> = emptySet(),
    /** 用户自定义广告过滤关键词 */
    private var customAdKeywords: Set<String> = emptySet()
) {
    companion object {
        private const val TAG = "CommentClassifier"
    }

    /**
     * 更新自定义关键词
     */
    fun updateKeywords(intentKeywords: Set<String>, adKeywords: Set<String>) {
        this.customIntentKeywords = intentKeywords
        this.customAdKeywords = adKeywords
//        Log.d(TAG, "关键词已更新 - 意向: ${intentKeywords.size} 个, 广告: ${adKeywords.size} 个")
    }

    /**
     * 对单条评论进行分类
     *
     * @param comment 待分类的评论
     * @return 更新了分类结果的评论副本
     */
    fun classify(comment: CommentInfo): CommentInfo {
        val content = comment.content.trim()
        if (content.isEmpty()) {
            return comment.copy(category = CommentCategory.NORMAL, matchedKeywords = emptyList())
        }

        // 合并预设关键词和用户自定义关键词
        val allIntentKeywords = IntentKeywords.DEFAULT_INTENT_KEYWORDS.toSet() + customIntentKeywords
        val allAdKeywords = IntentKeywords.DEFAULT_AD_KEYWORDS.toSet() + customAdKeywords

        // 优先检查广告关键词（广告检测优先级高于意向）
        val matchedAd = allAdKeywords.filter { keyword ->
            content.contains(keyword, ignoreCase = true)
        }
        if (matchedAd.isNotEmpty()) {
            Log.d(TAG, "评论 [${comment.username}]: 识别为广告, 匹配词: $matchedAd")
            return comment.copy(
                category = CommentCategory.AD,
                matchedKeywords = matchedAd
            )
        }

        // 检查意向关键词
        val matchedIntent = allIntentKeywords.filter { keyword ->
            content.contains(keyword, ignoreCase = true)
        }
        if (matchedIntent.isNotEmpty()) {
            Log.d(TAG, "评论 [${comment.username}]: 识别为意向客户, 匹配词: $matchedIntent")
            return comment.copy(
                category = CommentCategory.INTENT,
                matchedKeywords = matchedIntent
            )
        }

        // 默认为正常评论
        return comment.copy(
            category = CommentCategory.NORMAL,
            matchedKeywords = emptyList()
        )
    }

    /**
     * 批量分类评论
     *
     * @param comments 待分类的评论列表
     * @return 分类后的评论列表，保持原顺序
     */
    fun classifyBatch(comments: List<CommentInfo>): List<CommentInfo> {
        if (comments.isEmpty()) return emptyList()

        var normalCount = 0
        var adCount = 0
        var intentCount = 0

        val results = comments.map { comment ->
            val classified = classify(comment)
            when (classified.category) {
                CommentCategory.NORMAL -> normalCount++
                CommentCategory.AD -> adCount++
                CommentCategory.INTENT -> intentCount++
            }
            classified
        }

        Log.d(TAG, "批量分类完成: 总数=${comments.size}, 正常=$normalCount, 广告=$adCount, 意向=$intentCount")
        return results
    }

    /**
     * 仅筛选出意向客户评论
     */
    fun filterIntentComments(comments: List<CommentInfo>): List<CommentInfo> {
        return classifyBatch(comments).filter { it.category == CommentCategory.INTENT }
    }

    /**
     * 获取当前生效的所有意向关键词
     */
    fun getEffectiveIntentKeywords(): Set<String> {
        return IntentKeywords.DEFAULT_INTENT_KEYWORDS.toSet() + customIntentKeywords
    }

    /**
     * 获取当前生效的所有广告过滤关键词
     */
    fun getEffectiveAdKeywords(): Set<String> {
        return IntentKeywords.DEFAULT_AD_KEYWORDS.toSet() + customAdKeywords
    }
}
