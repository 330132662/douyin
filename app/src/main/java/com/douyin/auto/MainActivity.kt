package com.douyin.auto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.douyin.auto.ui.KeywordSettingsScreen
import com.douyin.auto.ui.LogScreen
import com.douyin.auto.ui.MainScreen
import com.douyin.auto.ui.theme.DouyinAutoTheme

/**
 * 主 Activity
 *
 * 使用 Jetpack Compose 构建 UI，包含三个主要页面：
 * - 首页（MainScreen）：状态概览和统计数据
 * - 关键词设置（KeywordSettingsScreen）：关键词管理
 * - 日志（LogScreen）：操作日志查看
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DouyinAutoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp()
                }
            }
        }
    }
}

/**
 * 应用主入口 Composable
 *
 * 管理三个页面的导航状态
 */
@Composable
private fun MainApp() {
    // 页面导航状态
    var currentPage by remember { mutableStateOf<Page>(Page.HOME) }

    // 根据当前页面显示对应内容
    when (currentPage) {
        Page.HOME -> {
            MainScreen(
                onNavigateToKeywords = { currentPage = Page.KEYWORDS },
                onNavigateToLogs = { currentPage = Page.LOGS }
            )
        }
        Page.KEYWORDS -> {
            KeywordSettingsScreen(
                onNavigateBack = { currentPage = Page.HOME }
            )
        }
        Page.LOGS -> {
            LogScreen(
                onNavigateBack = { currentPage = Page.HOME }
            )
        }
    }
}

/**
 * 页面枚举
 */
private enum class Page {
    HOME, KEYWORDS, LOGS
}
