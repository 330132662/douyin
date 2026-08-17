package com.douyin.auto.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * 透明、1x1、不拦截触摸的保活 Activity。
 *
 * Android 10+ 限制：使用 MediaProjection 录屏时，若录制方 App 退到后台，系统会立即停止录屏。
 * 视频分析场景里用户会切到抖音（本 App 退后台），因此需要一个透明前台 Activity 让本 App
 * 始终处于「前台可见」状态，从而 MediaProjection 不被系统停止。
 *
 * 该 Activity 不获取焦点、不拦截触摸（FLAG_NOT_TOUCHABLE），用户可正常在下方刷抖音。
 * 视频分析停止时通过 [ACTION_STOP_KEEPALIVE] 广播关闭。
 */
class KeepAliveActivity : Activity() {

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP_KEEPALIVE) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // 1x1 像素，几乎不可见
        window.setLayout(1, 1)

        val filter = IntentFilter(ACTION_STOP_KEEPALIVE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(this, stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stopReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_KEEPALIVE = "com.douyin.auto.action.STOP_KEEPALIVE"
    }
}
