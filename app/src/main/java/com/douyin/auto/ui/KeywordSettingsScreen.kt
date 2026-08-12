package com.douyin.auto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.douyin.auto.config.AppPreferences
import com.douyin.auto.model.IntentKeywords
import com.douyin.auto.ui.theme.*
import kotlinx.coroutines.launch

/**
 * 关键词设置界面
 *
 * 管理意向关键词和广告过滤关键词，支持添加/删除/恢复默认
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeywordSettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    // 状态
    var intentKeywords by remember { mutableStateOf<Set<String>>(emptySet()) }
    var adKeywords by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogType by remember { mutableStateOf<KeywordType>(KeywordType.INTENT) }
    var newKeyword by remember { mutableStateOf("") }

    // 加载关键词
    LaunchedEffect(Unit) {
        prefs.intentKeywordsFlow.collect { intentKeywords = it }
    }
    LaunchedEffect(Unit) {
        prefs.adKeywordsFlow.collect { adKeywords = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关键词设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---- 意向关键词区域 ----
            item {
                KeywordSectionHeader(
                    title = "意向客户关键词",
                    subtitle = "匹配这些关键词的评论将识别为意向客户",
                    color = IntentOrange,
                    onAdd = {
                        addDialogType = KeywordType.INTENT
                        newKeyword = ""
                        showAddDialog = true
                    }
                )
            }

            item {
                KeywordChipsList(
                    keywords = intentKeywords.toList(),
                    color = IntentOrange,
                    onDelete = { keyword ->
                        scope.launch {
                            prefs.setIntentKeywords(intentKeywords - keyword)
                        }
                    }
                )
            }

            // ---- 广告过滤关键词区域 ----
            item {
                Spacer(modifier = Modifier.height(8.dp))
                KeywordSectionHeader(
                    title = "广告过滤关键词",
                    subtitle = "匹配这些关键词的评论将标记为广告并过滤",
                    color = AdPurple,
                    onAdd = {
                        addDialogType = KeywordType.AD
                        newKeyword = ""
                        showAddDialog = true
                    }
                )
            }

            item {
                KeywordChipsList(
                    keywords = adKeywords.toList(),
                    color = AdPurple,
                    onDelete = { keyword ->
                        scope.launch {
                            prefs.setAdKeywords(adKeywords - keyword)
                        }
                    }
                )
            }

            // ---- 恢复默认按钮 ----
            item {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch { prefs.resetKeywordsToDefault() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("恢复默认关键词")
                }
            }
        }
    }

    // 添加关键词对话框
    if (showAddDialog) {
        AddKeywordDialog(
            keywordType = addDialogType,
            currentValue = newKeyword,
            onValueChange = { newKeyword = it },
            onConfirm = {
                val trimmed = newKeyword.trim()
                if (trimmed.isNotEmpty()) {
                    scope.launch {
                        when (addDialogType) {
                            KeywordType.INTENT -> {
                                prefs.setIntentKeywords(intentKeywords + trimmed)
                            }
                            KeywordType.AD -> {
                                prefs.setAdKeywords(adKeywords + trimmed)
                            }
                        }
                    }
                }
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

/**
 * 关键词区域标题
 */
@Composable
private fun KeywordSectionHeader(
    title: String,
    subtitle: String,
    color: androidx.compose.ui.graphics.Color,
    onAdd: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Label,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onAdd) {
            Icon(
                Icons.Default.AddCircle,
                contentDescription = "添加",
                tint = color
            )
        }
    }
}

/**
 * 关键词标签流式布局
 */
@Composable
private fun KeywordChipsList(
    keywords: List<String>,
    color: androidx.compose.ui.graphics.Color,
    onDelete: (String) -> Unit
) {
    if (keywords.isEmpty()) {
        Text(
            text = "暂无关键词，点击 + 添加",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        return
    }

    // 使用 FlowRow 效果的手动布局
    Column {
        var currentRow = mutableListOf<String>()
        var currentRowWidth = 0
        val maxRowWidth = 380 // 近似 dp

        keywords.forEach { keyword ->
            val estimatedWidth = keyword.length * 16 + 48
            if (currentRowWidth + estimatedWidth > maxRowWidth && currentRow.isNotEmpty()) {
                // 开始新行
                KeywordRow(keywords = currentRow.toList(), color = color, onDelete = onDelete)
                currentRow = mutableListOf()
                currentRowWidth = 0
            }
            currentRow.add(keyword)
            currentRowWidth += estimatedWidth
        }
        if (currentRow.isNotEmpty()) {
            KeywordRow(keywords = currentRow.toList(), color = color, onDelete = onDelete)
        }
    }
}

@Composable
private fun KeywordRow(
    keywords: List<String>,
    color: androidx.compose.ui.graphics.Color,
    onDelete: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        keywords.forEach { keyword ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = keyword,
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = { onDelete(keyword) },
                        modifier = Modifier.size(18.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除",
                            modifier = Modifier.size(14.dp),
                            tint = color
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                border = AssistChipDefaults.assistChipBorder(
                    borderColor = color.copy(alpha = 0.3f)
                ),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = color.copy(alpha = 0.08f)
                )
            )
        }
    }
}

/**
 * 添加关键词对话框
 */
@Composable
private fun AddKeywordDialog(
    keywordType: KeywordType,
    currentValue: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when (keywordType) {
        KeywordType.INTENT -> "添加意向关键词"
        KeywordType.AD -> "添加广告过滤关键词"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(text = title, fontWeight = FontWeight.Bold)
        },
        text = {
            OutlinedTextField(
                value = currentValue,
                onValueChange = onValueChange,
                placeholder = { Text("输入关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = currentValue.trim().isNotEmpty()
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 关键词类型枚举
 */
private enum class KeywordType {
    INTENT, AD
}
