package com.douyin.auto.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.douyin.auto.data.LogRepository
import com.douyin.auto.data.OperationLogEntity
import com.douyin.auto.model.OperationType
import com.douyin.auto.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * 操作日志界面
 *
 * 数据源：Room [LogRepository]（持久化，跨服务重启保留）。
 * 视频级日志（分析/点赞/收藏）若带分享链接，点击可跳转到抖音该视频。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { LogRepository.get(context) }

    // 从 Room 订阅全部日志（倒序）
    val allLogs by repository.allFlow().collectAsState(initial = emptyList())
    var filterType by remember { mutableStateOf<OperationType?>(null) }
    val listState = rememberLazyListState()

    // 本地筛选
    val filteredLogs = remember(allLogs, filterType) {
        if (filterType == null) allLogs
        else allLogs.filter { it.toOperationType() == filterType }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("操作日志", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (allLogs.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { repository.clear() }
                        }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "清除日志",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ---- 筛选栏 ----
            FilterBar(
                currentFilter = filterType,
                onFilterChange = { filterType = it },
                totalCount = allLogs.size,
                filteredCount = filteredLogs.size
            )

            // ---- 日志列表 ----
            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Inbox,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无操作日志",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "打开抖音评论区后，操作记录将显示在这里",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredLogs,
                        key = { it.id }
                    ) { log ->
                        LogItem(log = log, onOpenVideo = { openVideo(context, log) })
                    }
                }
            }
        }
    }
}

/**
 * 跳转到抖音打开指定视频。
 * 优先用 aweme_id 走 deeplink（snssdk1128），其次用分享短链。
 */
private fun openVideo(context: android.content.Context, log: OperationLogEntity) {
    val uri = when {
        log.awemeId != null -> Uri.parse("snssdk1128://aweme/detail/${log.awemeId}")
        !log.videoUrl.isNullOrBlank() -> Uri.parse(log.videoUrl)
        else -> return
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * 筛选栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    currentFilter: OperationType?,
    onFilterChange: (OperationType?) -> Unit,
    totalCount: Int,
    filteredCount: Int
) {
    Surface(
        tonalElevation = 1.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentFilter == null,
                    onClick = { onFilterChange(null) },
                    label = { Text("全部") },
                    leadingIcon = {
                        if (currentFilter == null) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.SCAN,
                    onClick = { onFilterChange(OperationType.SCAN) },
                    label = { Text("扫描") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.COMMENT,
                    onClick = { onFilterChange(OperationType.COMMENT) },
                    label = { Text("评论") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Comment,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.FOLLOW,
                    onClick = { onFilterChange(OperationType.FOLLOW) },
                    label = { Text("关注") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.ANALYZE,
                    onClick = { onFilterChange(OperationType.ANALYZE) },
                    label = { Text("分析") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.LIKE,
                    onClick = { onFilterChange(OperationType.LIKE) },
                    label = { Text("点赞") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.COLLECT,
                    onClick = { onFilterChange(OperationType.COLLECT) },
                    label = { Text("收藏") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Bookmark,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            Text(
                text = "共 $totalCount 条日志${if (currentFilter != null) " · 筛选后 $filteredCount 条" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * 单条日志项
 *
 * 视频级日志（分析/点赞/收藏）若带链接，整张卡片可点击跳转到该视频，右侧显示跳转箭头。
 */
@Composable
private fun LogItem(log: OperationLogEntity, onOpenVideo: () -> Unit) {
    val action = log.toOperationType()
    val (icon, color) = when (action) {
        OperationType.SCAN -> Icons.Default.Search to NormalBlue
        OperationType.CLASSIFY -> Icons.Default.Category to IntentOrange
        OperationType.FOLLOW -> Icons.Default.PersonAdd to StatusGreen
        OperationType.STATUS -> Icons.Default.Info to AdPurple
        OperationType.COMMENT -> Icons.Default.Comment to NormalBlue
        OperationType.ANALYZE -> Icons.Default.AutoAwesome to Primary
        OperationType.LIKE -> Icons.Default.Favorite to StatusRed
        OperationType.COLLECT -> Icons.Default.Bookmark to IntentOrange
        OperationType.SEND_COMMENT -> TODO()
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // 可跳转：视频级操作且带链接
    val canJump = action in VIDEO_ACTIONS && (log.awemeId != null || !log.videoUrl.isNullOrBlank())

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canJump) Modifier.clickable { onOpenVideo() } else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 操作类型图标
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = action.name,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // 日志内容
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${actionLabel(action)} · ${log.target}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timeFormat.format(Date(log.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 结果标签
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ResultBadge(result = log.result)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 可跳转日志显示箭头，提示可点击
            if (canJump) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "跳转到视频",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 可跳转到视频的操作类型 */
private val VIDEO_ACTIONS = setOf(
    OperationType.ANALYZE, OperationType.LIKE, OperationType.COLLECT
)

/**
 * 结果标签
 */
@Composable
private fun ResultBadge(result: String) {
    val bgColor = when (result) {
        "成功" -> StatusGreen.copy(alpha = 0.15f)
        "失败" -> StatusRed.copy(alpha = 0.15f)
        "意向客户" -> IntentOrange.copy(alpha = 0.15f)
        "广告" -> AdPurple.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when (result) {
        "成功" -> StatusGreen
        "失败" -> StatusRed
        "意向客户" -> IntentOrange
        "广告" -> AdPurple
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor
    ) {
        Text(
            text = result,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 操作类型的中文标签
 */
private fun actionLabel(action: OperationType): String = when (action) {
    OperationType.SCAN -> "扫描"
    OperationType.CLASSIFY -> "分类"
    OperationType.FOLLOW -> "关注"
    OperationType.STATUS -> "状态"
    OperationType.COMMENT -> "评论"
    OperationType.ANALYZE -> "分析"
    OperationType.LIKE -> "点赞"
    OperationType.COLLECT -> "收藏"
    else -> {
        "-"
    }
}
