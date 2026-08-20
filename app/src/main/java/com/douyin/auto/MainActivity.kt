package com.douyin.auto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.douyin.auto.ui.CopyrightScreen
import com.douyin.auto.ui.KeywordSettingsScreen
import com.douyin.auto.ui.LogScreen
import com.douyin.auto.ui.MainScreen
import com.douyin.auto.ui.ModelSettingsScreen
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
    val activity = LocalContext.current as? ComponentActivity
    // 页面导航状态
    var currentPage by remember { mutableStateOf<Page>(Page.HOME) }
    // 退出应用确认弹框
    var showExitDialog by remember { mutableStateOf(false) }

    // 系统返回键/手势拦截：
    // - 当前不在首页 → 回到首页（不退出）
    // - 当前已在首页（再返回即会退出应用）→ 弹框二次确认，只有点「确认退出」才真正退出
    BackHandler(enabled = true) {
        if (currentPage == Page.HOME) {
            showExitDialog = true
        } else {
            currentPage = Page.HOME
        }
    }

    // 根据当前页面显示对应内容
    when (currentPage) {
        Page.HOME -> {
            MainScreen(
                currentPage = Page.HOME,
                onNavigateToHome = { currentPage = Page.HOME },
                onNavigateToKeywords = { currentPage = Page.KEYWORDS },
                onNavigateToLogs = { currentPage = Page.LOGS },
                onNavigateToModel = { currentPage = Page.MODEL },
                onNavigateToCopyright = { currentPage = Page.COPYRIGHT }
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
        Page.MODEL -> {
            ModelSettingsScreen(
                onNavigateBack = { currentPage = Page.HOME }
            )
        }
        Page.COPYRIGHT -> {
            CopyrightScreen(
                onNavigateBack = { currentPage = Page.HOME }
            )
        }
    }

    // 退出确认弹框
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(text = "确认退出") },
            text = { Text(text = "确定要退出应用吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitDialog = false
                        activity?.finish()
                    }
                ) {
                    Text(text = "确认退出")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text(text = "取消")
                }
            }
        )
    }
}

/**
 * 页面枚举
 */
enum class Page {
    HOME, KEYWORDS, LOGS, MODEL, COPYRIGHT
}
