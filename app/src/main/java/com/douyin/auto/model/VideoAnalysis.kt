package com.douyin.auto.model

/**
 * 视频内容分析结果（由多模态大模型返回）
 *
 * @property subject  视频主体（一句话概括，如「宠物猫在玩毛线球」）
 * @property tags    视频标签列表
 * @property shouldLike   是否应点赞
 * @property shouldCollect 是否应收藏
 * @property reason   判定理由（简短）
 * @property raw      模型原始返回文本（便于排查）
 */
data class VideoAnalysisResult(
    val subject: String = "",
    val tags: List<String> = emptyList(),
    val shouldLike: Boolean = false,
    val shouldCollect: Boolean = false,
    val reason: String = "",
    val raw: String = ""
)
