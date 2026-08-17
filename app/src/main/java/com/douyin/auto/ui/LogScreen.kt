package com.douyin.auto.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.douyin.auto.model.OperationLog
import com.douyin.auto.model.OperationType
import com.douyin.auto.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 操作日志界面
 *
 * 显示所有操作日志，支持按类型筛选和清除
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: () -> Unit = {}
) {
    // 日志列表
    val logs = remember { mutableStateListOf<OperationLog>() }
    var filterType by remember { mutableStateOf<OperationType?>(null) }
    val listState = rememberLazyListState()

    // 监听日志
    LaunchedEffect(Unit) {
        // 先预加载服务内缓冲的历史日志（按时间顺序，最新的排在前面）
        com.douyin.auto.DouyinAccessibilityService.instance
            ?.getLogHistory()
            ?.let { logs.addAll(it.asReversed()) }
        com.douyin.auto.DouyinAccessibilityService.logListener = { log ->
            logs.add(0, log) // 最新的在前
            if (logs.size > 500) {
                logs.removeRange(400, logs.size)
            }
        }
    }

    // 过滤后的日志
    val filteredLogs = remember(logs.toList(), filterType) {
        if (filterType == null) logs.toList()
        else logs.filter { it.action == filterType }
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
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = { logs.clear() }) {
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
                totalCount = logs.size,
                filteredCount = filteredLogs.size
            )

            // ---- 日志列表 ----
            if (filteredLogs.isEmpty()) {
                // 空状态
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
                        LogItem(log = log)
                    }
                }
            }
        }
    }
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
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.SCAN,
                    onClick = { onFilterChange(OperationType.SCAN) },
                    label = { Text("扫描") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.COMMENT,
                    onClick = { onFilterChange(OperationType.COMMENT) },
                    label = { Text("评论") },
                    leadingIcon = {
                        Icon(Icons.Default.Comment, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.FOLLOW,
                    onClick = { onFilterChange(OperationType.FOLLOW) },
                    label = { Text("关注") },
                    leadingIcon = {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.ANALYZE,
                    onClick = { onFilterChange(OperationType.ANALYZE) },
                    label = { Text("分析") },
                    leadingIcon = {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.LIKE,
                    onClick = { onFilterChange(OperationType.LIKE) },
                    label = { Text("点赞") },
                    leadingIcon = {
                        Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
                FilterChip(
                    selected = currentFilter == OperationType.COLLECT,
                    onClick = { onFilterChange(OperationType.COLLECT) },
                    label = { Text("收藏") },
                    leadingIcon = {
                        Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
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
 */
@Composable
private fun LogItem(log: OperationLog) {
    val (icon, color) = when (log.action) {
        OperationType.SCAN -> Icons.Default.Search to NormalBlue
        OperationType.CLASSIFY -> Icons.Default.Category to IntentOrange
        OperationType.FOLLOW -> Icons.Default.PersonAdd to StatusGreen
        OperationType.STATUS -> Icons.Default.Info to AdPurple
        OperationType.COMMENT -> Icons.Default.Comment to NormalBlue
        OperationType.ANALYZE -> Icons.Default.AutoAwesome to Primary
        OperationType.LIKE -> Icons.Default.Favorite to StatusRed
        OperationType.COLLECT -> Icons.Default.Bookmark to IntentOrange
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
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
                    contentDescription = log.action.name,
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
                        text = "${actionLabel(log.action)} · ${log.target}",
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
        }
    }
}

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
}
