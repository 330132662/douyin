package com.douyin.auto.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 操作日志 DAO
 *
 * - [getAll] / [getByAction] 返回 Flow，UI 自动响应数据变化
 * - [insert] 供服务在产生日志时调用
 * - [clear] 供日志页「清除」按钮调用
 */
@Dao
interface OperationLogDao {

    /** 全部日志，按时间倒序（最新在前） */
    @Query("SELECT * FROM operation_log ORDER BY timestamp DESC")
    fun getAll(): Flow<List<OperationLogEntity>>

    /** 按操作类型筛选，按时间倒序 */
    @Query("SELECT * FROM operation_log WHERE action = :action ORDER BY timestamp DESC")
    fun getByAction(action: String): Flow<List<OperationLogEntity>>

    /** 插入一条日志 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: OperationLogEntity): Long

    /** 清空全部日志（日志页「清除」按钮） */
    @Query("DELETE FROM operation_log")
    suspend fun clear()

    /** 删除指定 id */
    @Query("DELETE FROM operation_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 保留最近 N 条，清理更旧的（防止无限增长） */
    @Query("DELETE FROM operation_log WHERE id NOT IN (SELECT id FROM operation_log ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
