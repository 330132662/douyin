package com.douyin.auto.data

import android.content.Context
import com.douyin.auto.model.OperationLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 操作日志仓库
 *
 * 封装 [OperationLogDao]：把内存版 [OperationLog] 与 [OperationLogEntity] 互转，
 * 并在每次插入后裁剪历史，防止数据库无限增长。
 *
 * 通过 [App.database] 构造，或用 [get] 从任意 Context 获取。
 */
class LogRepository private constructor(
    private val dao: OperationLogDao
) {
    /** 单次插入后保留的日志条数上限 */
    private val maxKeep = 2000

    /** 全部日志（倒序），UI 订阅 */
    fun allFlow(): Flow<List<OperationLogEntity>> = dao.getAll()

    /** 按操作类型筛选（倒序） */
    fun byActionFlow(action: String): Flow<List<OperationLogEntity>> = dao.getByAction(action)

    /**
     * 插入一条日志（自动落盘 + 裁剪）。
     * @param log 内存版日志
     * @param videoUrl 视频分享链接（仅视频级操作传入，可空）
     * @param awemeId 从链接解析出的 aweme_id（可空，跳转用）
     */
    suspend fun insert(log: OperationLog, videoUrl: String? = null, awemeId: String? = null) {
        val entity = OperationLogEntity(
            timestamp = log.timestamp,
            action = log.action.name,
            target = log.target,
            result = log.result,
            detail = log.detail,
            videoUrl = videoUrl,
            awemeId = awemeId
        )
        dao.insert(entity)
        dao.trim(maxKeep)
    }

    suspend fun clear() = dao.clear()

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    companion object {
        @Volatile
        private var INSTANCE: LogRepository? = null

        fun get(context: Context): LogRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogRepository(AppDatabase.get(context).operationLogDao()).also { INSTANCE = it }
            }
        }
    }
}

/**
 * 从抖音视频链接解析 aweme_id。
 *
 * 支持形态：
 * - 长链：https://www.douyin.com/video/{awemeId}
 * - 长链：https://www.iesdouyin.com/share/video/{awemeId}
 * - 短链（v.douyin.com/xxx/）无法直接正则提取，返回 null，跳转时改用短链本身。
 */
fun parseAwemeId(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val regex = Regex("""/video/(\d+)""")
    return regex.find(url)?.groupValues?.getOrNull(1)
}
