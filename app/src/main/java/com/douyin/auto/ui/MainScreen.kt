package com.douyin.auto.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.douyin.auto.DouyinAccessibilityService
import com.douyin.auto.Page
import com.douyin.auto.media.ScreenCaptureService
import com.douyin.auto.ui.theme.*

/**
 * 主界面 - 状态概览
 *
 * 显示服务运行状态、今日统计数据、快捷操作按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentPage: Page = Page.HOME,
    onNavigateToHome: () -> Unit = {},
    onNavigateToKeywords: () -> Unit = {},
    onNavigateToLogs: () -> Unit = {},
    onNavigateToModel: () -> Unit = {},
    onNavigateToCopyright: () -> Unit = {}
) {
    val context = LocalContext.current

    // 状态
    var isServiceRunning by remember { mutableStateOf(DouyinAccessibilityService.instance != null) }
    var stats by remember { mutableStateOf(DouyinAccessibilityService.Stats()) }

    // 定时刷新状态
    LaunchedEffect(Unit) {
        DouyinAccessibilityService.statusListener = { running ->
            isServiceRunning = running
        }
        DouyinAccessibilityService.statsListener = { newStats ->
            stats = newStats
        }
        // 初始状态
        isServiceRunning = DouyinAccessibilityService.instance != null
        DouyinAccessibilityService.instance?.getCurrentStats()?.let { stats = it }
    }

    // 服务是否已开启（系统层面）
    val isAccessibilityEnabled = remember {
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabledServices?.contains(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "抖音获客助手",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = {
                        // 刷新状态
                        isServiceRunning = DouyinAccessibilityService.instance != null
                        DouyinAccessibilityService.instance?.getCurrentStats()?.let { stats = it }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentPage == Page.HOME,
                    onClick = onNavigateToHome,
                    icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                    label = { Text("首页") }
                )
                NavigationBarItem(
                    selected = currentPage == Page.KEYWORDS,
                    onClick = onNavigateToKeywords,
                    icon = { Icon(Icons.Default.Settings, contentDescription = "关键词") },
                    label = { Text("关键词") }
                )
                NavigationBarItem(
                    selected = currentPage == Page.MODEL,
                    onClick = onNavigateToModel,
                    icon = { Icon(Icons.Default.AutoAwesome, contentDescription = "模型") },
                    label = { Text("模型") }
                )
                NavigationBarItem(
                    selected = currentPage == Page.LOGS,
                    onClick = onNavigateToLogs,
                    icon = { Icon(Icons.Default.ListAlt, contentDescription = "日志") },
                    label = { Text("日志") }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- 版权声明入口（首页显眼处）----
            CopyrightNoticeBanner(onClick = onNavigateToCopyright)

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 服务状态卡片 ----
            ServiceStatusCard(
                isRunning = isServiceRunning,
                isEnabled = isAccessibilityEnabled
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 今日统计卡片 ----
            StatsCard(stats = stats)

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 录屏授权（视频分析截帧必需，从「模型」页迁入）----
            ScreenCaptureAuthCard()

            Spacer(modifier = Modifier.height(16.dp))

            // ---- 快捷操作按钮 ----
            ActionButtons(
                isServiceRunning = isServiceRunning,
                isAccessibilityEnabled = isAccessibilityEnabled,
                onStartService = {
                    // 需要用户手动在设置中开启
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                onStopService = {
                    DouyinAccessibilityService.instance?.disableSelf()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ---- 使用提示 ----
            UsageTipsCard()
        }
    }
}

/**
 * 服务状态卡片
 */
@Composable
private fun ServiceStatusCard(isRunning: Boolean, isEnabled: Boolean) {
    val statusColor by animateColorAsState(
        targetValue = if (isRunning) StatusGreen else StatusRed,
        label = "statusColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态圆点
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isRunning) "服务运行中" else "服务已停止",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isEnabled) "无障碍权限已授权" else "无障碍权限未授权",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 统计卡片
 */
@Composable
private fun StatsCard(stats: DouyinAccessibilityService.Stats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "今日统计",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 统计数据行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.Search,
                    label = "扫描评论",
                    value = stats.scannedCount.toString(),
                    color = NormalBlue
                )
                StatItem(
                    icon = Icons.Default.Star,
                    label = "意向客户",
                    value = stats.intentCount.toString(),
                    color = IntentOrange
                )
                StatItem(
                    icon = Icons.Default.PersonAdd,
                    label = "已关注",
                    value = stats.followedCount.toString(),
                    color = StatusGreen
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 视频分析统计（新增）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.AutoAwesome,
                    label = "已分析",
                    value = stats.analyzedCount.toString(),
                    color = NormalBlue
                )
                StatItem(
                    icon = Icons.Default.Favorite,
                    label = "已点赞",
                    value = stats.likedCount.toString(),
                    color = StatusRed
                )
                StatItem(
                    icon = Icons.Default.Bookmark,
                    label = "已收藏",
                    value = stats.collectedCount.toString(),
                    color = IntentOrange
                )
            }
        }
    }
}

/**
 * 单个统计项
 */
@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 快捷操作按钮
 */
@Composable
private fun ActionButtons(
    isServiceRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current

    if (!isAccessibilityEnabled) {
        // 未授权无障碍权限
        Button(
            onClick = onStartService,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Accessibility, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "开启无障碍权限",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    } else if (!isServiceRunning) {
        // 已授权但服务未运行
        Button(
            onClick = {
                // 跳转到无障碍设置确认
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = StatusGreen
            )
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "启动服务",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        // 服务运行中
        OutlinedButton(
            onClick = onStopService,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = StatusRed
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "停止服务",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 使用提示卡片
 */
@Composable
private fun UsageTipsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "1. 在系统设置中开启本服务的无障碍权限\n" +
                        "2. 打开抖音 App，进入任意视频的评论区，自动扫描评论并关注意向客户\n" +
                        "3. 在「模型」页配置国产大模型并授权录屏，可开启视频内容分析\n" +
                        "4. 刷视频时点小白点「开始视频分析」，自动截帧分析主体并（可选）点赞/收藏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "⚠️ 免责声明：本工具仅供辅助使用，请遵守抖音平台规则和相关法律法规。视频点赞/收藏仅在「模型」页开启「自动点赞/收藏」后才会执行，请合理使用避免影响账号安全。",
                style = MaterialTheme.typography.bodySmall,
                color = StatusRed.copy(alpha = 0.8f),
                lineHeight = 18.sp,
                fontSize = 11.sp
            )
        }
    }
}

/**
 * 首页顶部版权声明入口（醒目横幅）
 */
@Composable
private fun CopyrightNoticeBanner(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Copyright,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "版权声明 · 免费说明",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "本应用当前完全免费，遇收费即骗子",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * 录屏授权卡片（视频内容分析截帧必需）
 *
 * 发起系统 MediaProjection 录屏授权并启动 [ScreenCaptureService]，
 * 实时反映录屏服务存活状态。从「模型」页迁至首页，便于用户在首屏完成授权。
 */
@Composable
private fun ScreenCaptureAuthCard() {
    val context = LocalContext.current
    var captureAvailable by remember { mutableStateOf(ScreenCaptureService.isAvailable()) }

    // 实时反映录屏服务真实存活状态：startForegroundService 是异步的，
    // 若服务在 onStartCommand 中崩溃，UI 不应再显示「已授权」假象。
    LaunchedEffect(Unit) {
        while (true) {
            captureAvailable = ScreenCaptureService.isAvailable()
            kotlinx.coroutines.delay(1500)
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == ComponentActivity.RESULT_OK && res.data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", res.resultCode)
                putExtra("data", res.data)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "录屏授权（视频分析截帧必需）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(mgr.createScreenCaptureIntent())
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Videocam, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (captureAvailable) "重新授权录屏" else "开启录屏授权")
            }

            Text(
                text = if (captureAvailable) "✔ 录屏服务运行中，可截取抖音画面"
                       else "⚠ 录屏服务未运行：点上方按钮授权；若已授权仍显示此状态，说明服务启动失败，请用「adb logcat -s ScreenCaptureService」查看失败原因",
                style = MaterialTheme.typography.bodySmall,
                color = if (captureAvailable) StatusGreen else StatusRed,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
