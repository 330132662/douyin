package com.douyin.auto.network

import android.util.Base64
import android.util.Log
import com.douyin.auto.model.VideoAnalysisResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 国产多模态大模型客户端（OpenAI 兼容 /chat/completions）。
 *
 * 支持通义千问 Qwen-VL、智谱 GLM-4V、DeepSeek-VL、阶跃 Step 等任意兼容
 * OpenAI messages 多模态接口的服务。通过 [analyzeFrames] 把若干帧 JPEG 字节
 * 以 base64 图片形式发送给模型，并解析其返回的结构化 JSON。
 */
class LlmClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val modelName: String
) {
    companion object {
        private const val TAG = "LlmClient"
    }

    /**
     * 将若干帧 JPEG 发送给模型，返回结构化分析结果。
     *
     * @param frames        截取的视频帧（JPEG 字节数组）
     * @param likeCriteria  点赞条件（自然语言）
     * @param collectCriteria 收藏条件（自然语言）
     */
    suspend fun analyzeFrames(
        frames: List<ByteArray>,
        likeCriteria: String,
        collectCriteria: String
    ): VideoAnalysisResult = withContext(Dispatchers.IO) {
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt())
        })

        val userContent = JSONArray()
        userContent.put(JSONObject().apply {
            put("type", "text")
            put("text", buildUserPrompt(likeCriteria, collectCriteria))
        })
        for (frame in frames) {
            val b64 = Base64.encodeToString(frame, Base64.NO_WRAP)
            userContent.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64,$b64")
                })
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userContent)
        })

        val body = JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", 0.3)
            put("max_tokens", 800)
        }

        val responseText = postJson(endpoint, body)
        return@withContext parseResponse(responseText)
    }

    private fun postJson(endpoint: String, body: JSONObject): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 60_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Accept", "application/json")

        conn.outputStream.use { os ->
            os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
        }

        val code = conn.responseCode
        return if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            throw RuntimeException("大模型接口返回错误码 $code：${err ?: "(无错误体)"}")
        }
    }

    private fun parseResponse(json: String): VideoAnalysisResult {
        val root = JSONObject(json)
        val choices = root.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) throw RuntimeException("大模型响应缺少 choices 字段：${json.take(200)}")
        val message = choices.getJSONObject(0).optJSONObject("message") ?: JSONObject()
        val content = message.optString("content", "")
        return extractJson(content)
    }

    private fun extractJson(content: String): VideoAnalysisResult {
        // 去掉可能的 ```json ... ``` 包裹
        val cleaned = content
            .replace("```json", "", ignoreCase = true)
            .replace("```", "", ignoreCase = true)
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val jsonStr = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned

        val obj = runCatching { JSONObject(jsonStr) }.getOrNull()
        if (obj == null) {
            Log.w(TAG, "解析模型 JSON 失败，原始内容: $content")
            return VideoAnalysisResult(raw = content, reason = "模型未返回可解析的 JSON")
        }

        val tagsArr = obj.optJSONArray("tags")
        val tags = if (tagsArr != null) {
            (0 until tagsArr.length()).mapNotNull { tagsArr.optString(it).takeIf { s -> s.isNotBlank() } }
        } else emptyList()

        return VideoAnalysisResult(
            subject = obj.optString("subject", ""),
            tags = tags,
            shouldLike = obj.optBoolean("should_like", false),
            shouldCollect = obj.optBoolean("should_collect", false),
            reason = obj.optString("reason", ""),
            raw = content
        )
    }

    private fun buildSystemPrompt(): String = """
        你是一个抖音视频内容分析助手。用户会给你一段视频在前若干秒内的随机截帧（一张或多张图片）。
        请观察画面，判断视频的主体内容（主题/场景/人物/物体），并依据用户给出的「点赞条件」和「收藏条件」决定是否应该点赞、收藏。
        只能依据画面可见内容判断，不要臆测。
        请严格只返回一个 JSON 对象，不要包含任何额外说明文字，格式如下：
        {
          "subject": "视频主体（一句话概括，如：宠物猫在玩毛线球）",
          "tags": ["标签1", "标签2"],
          "should_like": true,
          "should_collect": false,
          "reason": "判定理由（简短）"
        }
    """.trimIndent()

    private fun buildUserPrompt(likeCriteria: String, collectCriteria: String): String = """
        以下是视频前若干秒的随机截帧，请分析视频主体内容。
        点赞条件：${if (likeCriteria.isBlank()) "（未设置，默认依据内容质量与吸引力判断）" else likeCriteria}
        收藏条件：${if (collectCriteria.isBlank()) "（未设置，默认依据内容可复用/回看价值判断）" else collectCriteria}
        请返回上述 JSON。
    """.trimIndent()
}
