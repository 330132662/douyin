package com.douyin.auto.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.douyin.auto.model.OperationType

/**
 * 操作日志实体（Room 持久化）
 *
 * 在内存版 [com.douyin.auto.model.OperationLog] 基础上新增 [videoUrl] / [awemeId]，
 * 用于「点击日志跳转到该条视频」。仅视频级操作（分析/点赞/收藏）会填充这两个字段。
 *
 * @property action 存 [OperationType] 的 name()，读取时反解，避免引入 TypeConverter
 */
@Entity(
    tableName = "operation_log",
    indices = [Index("timestamp"), Index("action")]
)
data class OperationLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val action: String,          // OperationType.name()
    val target: String = "",
    val result: String = "",
    val detail: String = "",
    /** 视频分享链接（短链或长链），非视频级操作为 null */
    val videoUrl: String? = null,
    /** 从链接解析出的 aweme_id，用于 snssdk1128://aweme/detail/{id} 跳转；无则 null */
    val awemeId: String? = null
) {
    /** 反解为 [OperationType]；未知值兜底为 STATUS */
    fun toOperationType(): OperationType =
        runCatching { OperationType.valueOf(action) }.getOrDefault(OperationType.STATUS)
}
