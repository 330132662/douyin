package com.douyin.auto.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.douyin.auto.config.AppPreferences
import com.douyin.auto.media.ScreenCaptureService
import com.douyin.auto.model.VideoAnalysisResult
import com.douyin.auto.network.LlmClient
import com.douyin.auto.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型与视频分析设置界面
 *
 * 配置国产大模型接口（OpenAI 兼容）、视频分析开关、点赞/收藏条件、截帧参数，
 * 并负责发起系统录屏授权（MediaProjection）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    val scope = rememberCoroutineScope()

    var apiBaseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var analysisEnabled by remember { mutableStateOf(false) }
    var autoExecute by remember { mutableStateOf(true) }
    var likeCriteria by remember { mutableStateOf("") }
    var collectCriteria by remember { mutableStateOf("") }
    var frameCount by remember { mutableStateOf("3") }
    var captureSeconds by remember { mutableStateOf("10") }
    var testResult by remember { mutableStateOf<VideoAnalysisResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var dailyActionLimit by remember { mutableStateOf("200") }
    var jitterMin by remember { mutableStateOf("2000") }
    var jitterMax by remember { mutableStateOf("6000") }

    LaunchedEffect(Unit) { prefs.apiBaseUrlFlow.collect { apiBaseUrl = it } }
    LaunchedEffect(Unit) { prefs.apiKeyFlow.collect { apiKey = it } }
    LaunchedEffect(Unit) { prefs.modelNameFlow.collect { modelName = it } }
    LaunchedEffect(Unit) { prefs.analysisEnabledFlow.collect { analysisEnabled = it } }
    LaunchedEffect(Unit) { prefs.autoExecuteFlow.collect { autoExecute = it } }
    LaunchedEffect(Unit) { prefs.likeCriteriaFlow.collect { likeCriteria = it } }
    LaunchedEffect(Unit) { prefs.collectCriteriaFlow.collect { collectCriteria = it } }
    LaunchedEffect(Unit) { prefs.frameCountFlow.collect { frameCount = it.toString() } }
    LaunchedEffect(Unit) { prefs.captureWindowMsFlow.collect { captureSeconds = (it / 1000).toString() } }
    LaunchedEffect(Unit) { prefs.dailyActionLimitFlow.collect { dailyActionLimit = it.toString() } }
    LaunchedEffect(Unit) { prefs.jitterMinMsFlow.collect { jitterMin = it.toString() } }
    LaunchedEffect(Unit) { prefs.jitterMaxMsFlow.collect { jitterMax = it.toString() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型与视频分析", fontWeight = FontWeight.Bold) },
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
            item { SectionTitle("国产大模型接口（OpenAI 兼容）") }
            item {
                LabeledTextField("API 地址", apiBaseUrl, "https://your-endpoint/v1") {
                    apiBaseUrl = it
                    scope.launch { prefs.setApiBaseUrl(it) }
                }
            }
            item {
                LabeledTextField("API Key", apiKey, "sk-...", isPassword = true) {
                    apiKey = it
                    scope.launch { prefs.setApiKey(it) }
                }
            }
            item {
                LabeledTextField("模型名称", modelName, "如 qwen-vl-max / glm-4v-flash") {
                    modelName = it
                    scope.launch { prefs.setModelName(it) }
                }
            }

            item { Divider() }
            item { SectionTitle("视频分析设置") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用视频内容分析", fontWeight = FontWeight.Medium)
                        Text(
                            "刷到视频时自动截帧分析主体并决定是否点赞/收藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = analysisEnabled, onCheckedChange = {
                        analysisEnabled = it
                        scope.launch { prefs.setAnalysisEnabled(it) }
                    })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动点赞 / 收藏", fontWeight = FontWeight.Medium)
                        Text(
                            "判定命中后由无障碍服务自动点击（关闭则仅记录判定结果）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoExecute, onCheckedChange = {
                        autoExecute = it
                        scope.launch { prefs.setAutoExecute(it) }
                    })
                }
            }
            item {
                LabeledTextField("点赞条件", likeCriteria, "描述什么内容值得点赞", singleLine = false) {
                    likeCriteria = it
                    scope.launch { prefs.setLikeCriteria(it) }
                }
            }
            item {
                LabeledTextField("收藏条件", collectCriteria, "描述什么内容值得收藏", singleLine = false) {
                    collectCriteria = it
                    scope.launch { prefs.setCollectCriteria(it) }
                }
            }
            item {
                LabeledTextField("截帧数量 (1-8)", frameCount, "如 3", keyboardNumeric = true) {
                    frameCount = it
                    it.toIntOrNull()?.let { v -> scope.launch { prefs.setFrameCount(v) } }
                }
            }
            item {
                LabeledTextField("截帧窗口(秒, 3-30)", captureSeconds, "如 10", keyboardNumeric = true) {
                    captureSeconds = it
                    it.toIntOrNull()?.let { v -> scope.launch { prefs.setCaptureWindowMs(v * 1000) } }
                }
            }

            item { Divider() }
            item { SectionTitle("限速保护（模拟真人 · 降封号风险）") }
            item {
                LabeledTextField("每日点赞/收藏上限 (0=不限制)", dailyActionLimit, "如 200", keyboardNumeric = true) {
                    dailyActionLimit = it
                    it.toIntOrNull()?.let { v -> scope.launch { prefs.setDailyActionLimit(v) } }
                }
                Text(
                    "达到上限后自动停止散步模式。设为 0 表示不限（不推荐）。默认 200 已接近重度用户量级，仍有上限保护。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            item {
                LabeledTextField("间隔抖动最小值(毫秒)", jitterMin, "如 2000", keyboardNumeric = true) {
                    jitterMin = it
                    it.toIntOrNull()?.let { v -> scope.launch { prefs.setJitterMinMs(v) } }
                }
            }
            item {
                LabeledTextField("间隔抖动最大值(毫秒)", jitterMax, "如 6000", keyboardNumeric = true) {
                    jitterMax = it
                    it.toIntOrNull()?.let { v -> scope.launch { prefs.setJitterMaxMs(v) } }
                }
                Text(
                    "每次切到下一个视频前，在基础 2.6 秒之上随机追加 min~max 毫秒等待，打破固定节奏。建议 max 明显大于 min。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            item { Divider() }
            item { SectionTitle("视频分析测试") }
            item {
                Text(
                    "录屏授权已移至首页：请先在首页「录屏授权」卡片完成授权，并确保状态显示「录屏服务运行中」后再测试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Button(
                    onClick = {
                        if (ScreenCaptureService.instance == null) {
                            testResult = VideoAnalysisResult(raw = "", reason = "录屏服务未运行，无法截帧。请先在首页授权录屏并确保「录屏服务运行中」。")
                            return@Button
                        }
                        testing = true
                        scope.launch {
                            try {
                                val frame = withContext(Dispatchers.IO) { ScreenCaptureService.instance?.captureFrameJpeg() }
                                testResult = if (frame == null) {
                                    VideoAnalysisResult(raw = "", reason = "截帧失败：录屏服务在运行但拿不到帧（请确认抖音正在播放视频，且 App 未退到后台）")
                                } else {
                                    withContext(Dispatchers.IO) {
                                        LlmClient(apiBaseUrl, apiKey, modelName)
                                            .analyzeFrames(listOf(frame), likeCriteria, collectCriteria)
                                    }
                                }
                            } catch (e: Exception) {
                                testResult = VideoAnalysisResult(raw = "", reason = "测试失败：${e.message}")
                            } finally {
                                testing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !testing
                ) {
                    Icon(Icons.Default.Science, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (testing) "测试中..." else "测试一帧分析")
                }
            }
        }
    }

    if (testResult != null) {
        val r = testResult!!
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text("分析结果") },
            text = {
                Column {
                    Text("主体：${r.subject}")
                    Text("标签：${r.tags.joinToString(", ")}")
                    Text("点赞：${r.shouldLike}　收藏：${r.shouldCollect}")
                    Text("理由：${r.reason}")
                    if (r.raw.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("原始返回：\n${r.raw.take(500)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    placeholder: String,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    keyboardNumeric: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder) },
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (keyboardNumeric) KeyboardType.Number else KeyboardType.Text
            )
        )
    }
}
