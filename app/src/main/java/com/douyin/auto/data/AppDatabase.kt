package com.douyin.auto.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用本地数据库（Room）
 *
 * 持久化操作日志，替代原纯内存 `logHistory` 方案。
 * 通过 [App.database] 获取单例，避免多实例。
 */
@Database(
    entities = [OperationLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun operationLogDao(): OperationLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * 获取数据库单例。
         * @param context 任意 Context，内部用 applicationContext 避免泄漏
         */
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "douyin_auto.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
