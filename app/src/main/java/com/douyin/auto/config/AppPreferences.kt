package com.douyin.auto.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

        // ---- 默认值 ----
        const val DEFAULT_DAILY_FOLLOW_LIMIT = 200
        const val DEFAULT_MAX_OPS_PER_SECOND = 2
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
