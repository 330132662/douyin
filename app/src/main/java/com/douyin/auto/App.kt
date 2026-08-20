package com.douyin.auto

import android.app.Application
import com.douyin.auto.data.AppDatabase

/**
 * 应用入口
 *
 * 提供 Room [AppDatabase] 单例，供服务与 UI 共享。
 * 需在 AndroidManifest.xml 的 <application android:name=".App"> 注册。
 */
class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        lateinit var instance: App
            private set
    }
}
