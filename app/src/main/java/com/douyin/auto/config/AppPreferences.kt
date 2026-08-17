package com.douyin.auto.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.douyin.auto.model.IntentKeywords
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore 扩展属性 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "douyin_auto_prefs")

/**
 * 应用配置存储管理器
 *
 * 使用 Jetpack DataStore 存储用户配置，支持响应式读取
 */
class AppPreferences(private val context: Context) {

    companion object {
        // ---- 配置键定义 ----

        /** 意向关键词集合 */
        val KEY_INTENT_KEYWORDS = stringSetPreferencesKey("intent_keywords")

        /** 广告过滤关键词集合 */
        val KEY_AD_KEYWORDS = stringSetPreferencesKey("ad_keywords")

        /** 每日关注上限（默认 200） */
        val KEY_DAILY_FOLLOW_LIMIT = intPreferencesKey("daily_follow_limit")

        /** 每秒最大操作次数（默认 2） */
        val KEY_MAX_OPS_PER_SECOND = intPreferencesKey("max_ops_per_second")

        /** 是否启用服务 */
        val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")

        /** 今日已关注数量 */
        val KEY_TODAY_FOLLOW_COUNT = intPreferencesKey("today_follow_count")

        /** 最后关注日期（用于重置每日计数） */
        val KEY_LAST_FOLLOW_DATE = intPreferencesKey("last_follow_date")

        /** 是否显示过免责声明 */
        val KEY_DISCLAIMER_SHOWN = booleanPreferencesKey("disclaimer_shown")

        /** 已关注的用户名集合（去重用） */
        val KEY_FOLLOWED_USERS = stringSetPreferencesKey("followed_users")

        // ---- 视频内容分析（国产大模型）配置 ----
        /** 大模型 API 地址（OpenAI 兼容，结尾不带 /） */
        val KEY_API_BASE_URL = stringPreferencesKey("api_base_url")

        /** 大模型 API Key（敏感值，明文存储于 DataStore） */
        val KEY_API_KEY = stringPreferencesKey("api_key")

        /** 模型名称（如 qwen-vl-max / glm-4v-flash / deepseek-vl 等） */
        val KEY_MODEL_NAME = stringPreferencesKey("model_name")

        /** 是否启用视频内容分析 */
        val KEY_ANALYSIS_ENABLED = booleanPreferencesKey("analysis_enabled")

        /** 判定命中后是否自动点赞/收藏 */
        val KEY_AUTO_EXECUTE = booleanPreferencesKey("auto_execute")

        /** 点赞条件（自然语言描述） */
        val KEY_LIKE_CRITERIA = stringPreferencesKey("like_criteria")

        /** 收藏条件（自然语言描述） */
        val KEY_COLLECT_CRITERIA = stringPreferencesKey("collect_criteria")

        /** 截帧数量（1-8） */
        val KEY_FRAME_COUNT = intPreferencesKey("frame_count")

        /** 截帧窗口时长（毫秒，默认 10000 = 视频前 10 秒） */
        val KEY_CAPTURE_WINDOW_MS = intPreferencesKey("capture_window_ms")

        // ---- 散步模式「限速保护」配置 ----
        /** 每日点赞/收藏操作上限（0=不限制）。用于模拟真人节奏、降低封号风险 */
        val KEY_DAILY_ACTION_LIMIT = intPreferencesKey("daily_action_limit")

        /** 切到下一个视频前的随机间隔抖动最小值（毫秒） */
        val KEY_JITTER_MIN_MS = intPreferencesKey("jitter_min_ms")

        /** 随机间隔抖动最大值（毫秒） */
        val KEY_JITTER_MAX_MS = intPreferencesKey("jitter_max_ms")

        /** 今日已执行的点赞/收藏操作数（按日期重置） */
        val KEY_TODAY_ACTION_COUNT = intPreferencesKey("today_action_count")

        /** 最后操作日期（用于重置每日计数） */
        val KEY_LAST_ACTION_DATE = intPreferencesKey("last_action_date")

        // ---- 默认值 ----
        const val DEFAULT_DAILY_FOLLOW_LIMIT = 200
        const val DEFAULT_MAX_OPS_PER_SECOND = 2
        const val DEFAULT_API_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL_NAME = "gpt-4o"
        const val DEFAULT_LIKE_CRITERIA =
            "视频内容属于我感兴趣的主题（如美食、旅行、科技数码、宠物、知识干货等），画面质量高、有吸引力"
        const val DEFAULT_COLLECT_CRITERIA =
            "视频具有收藏价值：教程/干货/知识类、可复用的方法、值得回看的内容"
        const val DEFAULT_FRAME_COUNT = 3
        const val DEFAULT_CAPTURE_WINDOW_MS = 10000

        // 限速保护默认值
        const val DEFAULT_DAILY_ACTION_LIMIT = 200
        const val DEFAULT_JITTER_MIN_MS = 2000
        const val DEFAULT_JITTER_MAX_MS = 6000
    }

    // ---- 响应式数据流 ----

    /** 意向关键词 Flow */
    val intentKeywordsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_INTENT_KEYWORDS] ?: IntentKeywords.DEFAULT_INTENT_KEYWORDS.toSet()
    }

    /** 广告过滤关键词 Flow */
    val adKeywordsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_AD_KEYWORDS] ?: IntentKeywords.DEFAULT_AD_KEYWORDS.toSet()
    }

    /** 每日关注上限 Flow */
    val dailyFollowLimitFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_DAILY_FOLLOW_LIMIT] ?: DEFAULT_DAILY_FOLLOW_LIMIT
    }

    /** 最大操作速率 Flow */
    val maxOpsPerSecondFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAX_OPS_PER_SECOND] ?: DEFAULT_MAX_OPS_PER_SECOND
    }

    /** 服务启用状态 Flow */
    val serviceEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVICE_ENABLED] ?: false
    }

    /** 今日已关注数 Flow */
    val todayFollowCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_TODAY_FOLLOW_COUNT] ?: 0
    }

    /** 已关注用户 Flow */
    val followedUsersFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_FOLLOWED_USERS] ?: emptySet()
    }

    // ---- 视频内容分析配置 Flow ----

    /** 大模型 API 地址 Flow */
    val apiBaseUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_BASE_URL] ?: DEFAULT_API_BASE_URL
    }

    /** 大模型 API Key Flow */
    val apiKeyFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: ""
    }

    /** 模型名称 Flow */
    val modelNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MODEL_NAME] ?: DEFAULT_MODEL_NAME
    }

    /** 是否启用视频分析 Flow */
    val analysisEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ANALYSIS_ENABLED] ?: false
    }

    /** 自动执行（点赞/收藏）Flow */
    val autoExecuteFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_EXECUTE] ?: true
    }

    /** 点赞条件 Flow */
    val likeCriteriaFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LIKE_CRITERIA] ?: DEFAULT_LIKE_CRITERIA
    }

    /** 收藏条件 Flow */
    val collectCriteriaFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_COLLECT_CRITERIA] ?: DEFAULT_COLLECT_CRITERIA
    }

    /** 截帧数量 Flow */
    val frameCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_FRAME_COUNT] ?: DEFAULT_FRAME_COUNT
    }

    /** 截帧窗口时长 Flow */
    val captureWindowMsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_CAPTURE_WINDOW_MS] ?: DEFAULT_CAPTURE_WINDOW_MS
    }

    // ---- 限速保护 Flow ----

    /** 每日点赞/收藏操作上限 Flow（0=不限制） */
    val dailyActionLimitFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_DAILY_ACTION_LIMIT] ?: DEFAULT_DAILY_ACTION_LIMIT
    }

    /** 间隔抖动最小值 Flow */
    val jitterMinMsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_JITTER_MIN_MS] ?: DEFAULT_JITTER_MIN_MS
    }

    /** 间隔抖动最大值 Flow */
    val jitterMaxMsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_JITTER_MAX_MS] ?: DEFAULT_JITTER_MAX_MS
    }

    /** 今日已执行操作数 Flow（按日期自动重置为 0） */
    val todayActionCountFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        val today = getCurrentDayKey()
        val last = prefs[KEY_LAST_ACTION_DATE] ?: 0
        if (last == today) prefs[KEY_TODAY_ACTION_COUNT] ?: 0 else 0
    }

    // ---- 写入方法 ----

    /**
     * 更新意向关键词列表
     */
    suspend fun setIntentKeywords(keywords: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_INTENT_KEYWORDS] = keywords
        }
    }

    /**
     * 更新广告过滤关键词列表
     */
    suspend fun setAdKeywords(keywords: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AD_KEYWORDS] = keywords
        }
    }

    /**
     * 重置关键词为默认值
     */
    suspend fun resetKeywordsToDefault() {
        context.dataStore.edit { prefs ->
            prefs[KEY_INTENT_KEYWORDS] = IntentKeywords.DEFAULT_INTENT_KEYWORDS.toSet()
            prefs[KEY_AD_KEYWORDS] = IntentKeywords.DEFAULT_AD_KEYWORDS.toSet()
        }
    }

    /**
     * 设置每日关注上限
     */
    suspend fun setDailyFollowLimit(limit: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DAILY_FOLLOW_LIMIT] = limit.coerceIn(10, 500)
        }
    }

    /**
     * 设置每秒最大操作次数
     */
    suspend fun setMaxOpsPerSecond(rate: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAX_OPS_PER_SECOND] = rate.coerceIn(1, 10)
        }
    }

    /**
     * 设置服务启用状态
     */
    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVICE_ENABLED] = enabled
        }
    }

    // ---- 视频内容分析配置写入 ----

    /** 设置大模型 API 地址（自动去除结尾的 /） */
    suspend fun setApiBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_BASE_URL] = url.trim().trimEnd('/')
        }
    }

    /** 设置大模型 API Key */
    suspend fun setApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = key.trim()
        }
    }

    /** 设置模型名称 */
    suspend fun setModelName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MODEL_NAME] = name.trim()
        }
    }

    /** 设置是否启用视频分析 */
    suspend fun setAnalysisEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ANALYSIS_ENABLED] = enabled
        }
    }

    /** 设置是否自动点赞/收藏 */
    suspend fun setAutoExecute(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_EXECUTE] = enabled
        }
    }

    /** 设置点赞条件 */
    suspend fun setLikeCriteria(criteria: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LIKE_CRITERIA] = criteria
        }
    }

    /** 设置收藏条件 */
    suspend fun setCollectCriteria(criteria: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COLLECT_CRITERIA] = criteria
        }
    }

    /** 设置截帧数量（1-8） */
    suspend fun setFrameCount(count: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FRAME_COUNT] = count.coerceIn(1, 8)
        }
    }

    /** 设置截帧窗口时长（毫秒，3000-30000） */
    suspend fun setCaptureWindowMs(ms: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CAPTURE_WINDOW_MS] = ms.coerceIn(3000, 30000)
        }
    }

    // ---- 限速保护写入 ----

    /** 设置每日操作上限（0-2000，0=不限制） */
    suspend fun setDailyActionLimit(limit: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DAILY_ACTION_LIMIT] = limit.coerceIn(0, 2000)
        }
    }

    /** 设置间隔抖动最小值（>=0 毫秒） */
    suspend fun setJitterMinMs(ms: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_JITTER_MIN_MS] = ms.coerceAtLeast(0)
        }
    }

    /** 设置间隔抖动最大值（>=0 毫秒） */
    suspend fun setJitterMaxMs(ms: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_JITTER_MAX_MS] = ms.coerceAtLeast(0)
        }
    }

    /**
     * 记录一次自动点赞/收藏操作（每日计数 + 按日期重置）
     */
    suspend fun recordAction() {
        context.dataStore.edit { prefs ->
            val today = getCurrentDayKey()
            val last = prefs[KEY_LAST_ACTION_DATE] ?: 0
            val current = if (last == today) prefs[KEY_TODAY_ACTION_COUNT] ?: 0 else 0
            prefs[KEY_TODAY_ACTION_COUNT] = current + 1
            prefs[KEY_LAST_ACTION_DATE] = today
        }
    }

    /** 读取今日已执行操作数（按日期重置） */
    suspend fun getTodayActionCount(): Int {
        val prefs = context.dataStore.data.first()
        val today = getCurrentDayKey()
        val last = prefs[KEY_LAST_ACTION_DATE] ?: 0
        return if (last == today) prefs[KEY_TODAY_ACTION_COUNT] ?: 0 else 0
    }

    /** 是否已达到每日操作上限（limit<=0 视为不限制） */
    suspend fun isDailyActionLimitReached(): Boolean {
        val prefs = context.dataStore.data.first()
        val limit = prefs[KEY_DAILY_ACTION_LIMIT] ?: DEFAULT_DAILY_ACTION_LIMIT
        if (limit <= 0) return false
        val today = getCurrentDayKey()
        val last = prefs[KEY_LAST_ACTION_DATE] ?: 0
        val count = if (last == today) prefs[KEY_TODAY_ACTION_COUNT] ?: 0 else 0
        return count >= limit
    }

    /**
     * 记录一次关注操作（每日计数 + 已关注用户集合）
     */
    suspend fun recordFollow(username: String) {
        context.dataStore.edit { prefs ->
            val today = getCurrentDayKey()
            val lastDate = prefs[KEY_LAST_FOLLOW_DATE] ?: 0

            // 如果日期变了，重置计数
            val currentCount = if (lastDate == today) {
                prefs[KEY_TODAY_FOLLOW_COUNT] ?: 0
            } else {
                0
            }

            prefs[KEY_TODAY_FOLLOW_COUNT] = currentCount + 1
            prefs[KEY_LAST_FOLLOW_DATE] = today

            // 添加到已关注集合
            val followed = (prefs[KEY_FOLLOWED_USERS] ?: emptySet()).toMutableSet()
            followed.add(username)
            prefs[KEY_FOLLOWED_USERS] = followed
        }
    }

    /**
     * 检查是否达到每日关注上限
     */
    suspend fun isDailyLimitReached(): Boolean {
        val prefs = context.dataStore.data.first()
        val today = getCurrentDayKey()
        val lastDate = prefs[KEY_LAST_FOLLOW_DATE] ?: 0
        val limit = prefs[KEY_DAILY_FOLLOW_LIMIT] ?: DEFAULT_DAILY_FOLLOW_LIMIT
        val count = if (lastDate == today) prefs[KEY_TODAY_FOLLOW_COUNT] ?: 0 else 0
        return count >= limit
    }

    /**
     * 检查用户是否已被关注
     */
    suspend fun isUserFollowed(username: String): Boolean {
        val prefs = context.dataStore.data.first()
        val users = prefs[KEY_FOLLOWED_USERS] ?: emptySet()
        return username in users
    }

    /**
     * 标记免责声明已显示
     */
    suspend fun setDisclaimerShown() {
        context.dataStore.edit { prefs ->
            prefs[KEY_DISCLAIMER_SHOWN] = true
        }
    }

    /**
     * 免责声明是否已显示
     */
    suspend fun isDisclaimerShown(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_DISCLAIMER_SHOWN] ?: false
    }

    /** 获取当前日期标识（用于每日重置） */
    private fun getCurrentDayKey(): Int {
        val cal = java.util.Calendar.getInstance()
        return cal.get(java.util.Calendar.YEAR) * 10000 +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
                cal.get(java.util.Calendar.DAY_OF_MONTH)
    }
}
