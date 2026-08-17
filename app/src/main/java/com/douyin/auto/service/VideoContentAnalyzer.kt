package com.douyin.auto.service

import com.douyin.auto.config.AppPreferences
import com.douyin.auto.media.ScreenCaptureService
import com.douyin.auto.model.VideoAnalysisResult
import com.douyin.auto.network.LlmClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * 视频内容分析编排器：
 * 1) 在视频前 N 秒内随机截取若干帧（借助系统录屏 [ScreenCaptureService]）
 * 2) 调用用户配置的国产多模态大模型，返回结构化判定
 *
 * 需要在视频正在播放时调用，截帧窗口覆盖视频前若干秒。
 */
class VideoContentAnalyzer(
    private val prefs: AppPreferences
) {

    /**
     * 在 [captureWindowMs] 内随机生成 [frameCount] 个去重时间点（升序），
     * 到点时各截取一帧，收集为 JPEG 字节数组列表。
     */
    private suspend fun captureRandomFrames(frameCount: Int, captureWindowMs: Long): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        if (!ScreenCaptureService.isAvailable()) return frames

        val offsets = generateRandomOffsets(frameCount, captureWindowMs)
        var elapsed = 0L
        for (offset in offsets) {
            val wait = (offset - elapsed).coerceAtLeast(0)
            delay(wait)
            elapsed = offset
            val jpeg = ScreenCaptureService.instance?.captureFrameJpeg()
            if (jpeg != null && jpeg.isNotEmpty()) frames.add(jpeg)
        }
        return frames
    }

    /** 生成 [count] 个落在 (300, windowMs] 内的随机去重时间点 */
    private fun generateRandomOffsets(count: Int, windowMs: Long): List<Long> {
        val set = sortedSetOf<Long>()
        var guard = 0
        while (set.size < count && guard < count * 10) {
            set.add(Random.nextLong(300, (windowMs + 1).coerceAtLeast(301)))
            guard++
        }
        return set.toList()
    }

    /**
     * 完整分析流程：截帧 → 调用大模型 → 返回结果。
     * 若未能截到任何帧，返回带说明的 [VideoAnalysisResult]（不抛异常）。
     */
    suspend fun analyze(): VideoAnalysisResult {
        val frameCount = prefs.frameCountFlow.first()
        val windowMs = prefs.captureWindowMsFlow.first().toLong().coerceAtLeast(3000)
        val baseUrl = prefs.apiBaseUrlFlow.first()
        val apiKey = prefs.apiKeyFlow.first()
        val model = prefs.modelNameFlow.first()
        val like = prefs.likeCriteriaFlow.first()
        val collect = prefs.collectCriteriaFlow.first()

        val frames = captureRandomFrames(frameCount, windowMs)
        if (frames.isEmpty()) {
            return VideoAnalysisResult(
                subject = "",
                reason = "未能截取任何视频帧（请确认已授权录屏且抖音正在播放视频）"
            )
        }

        val client = LlmClient(baseUrl, apiKey, model)
        return client.analyzeFrames(frames, like, collect)
    }
}
