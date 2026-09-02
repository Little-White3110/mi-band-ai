@file:OptIn(ExperimentalScrollBarApi::class)

package llm.miband.littlewhite.ui

import android.content.Context
import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.config.PresetManager
import llm.miband.littlewhite.config.StatsStore
import llm.miband.littlewhite.hook.LlmClient
import llm.miband.littlewhite.log.LogCollector
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 环上LLM —— 完整设置页（HyperOS 设计语言）。
 *
 * @param config 可写配置存储；由 Activity 在 Xposed Service 绑定成功时注入。
 *               为 null 表示未检测到 LSPosed / Xposed Service，页面显示提示。
 */
@Composable
fun SettingsScreen(config: ConfigStore?) {
    val context = LocalContext.current
    // 初始化预设数据层与统计持久化层（幂等，重复调用仅重新赋值 SharedPreferences 引用）
    PresetManager.init(context)
    StatsStore.init(context)
    val scope = rememberCoroutineScope()

    // 底部 Tab 切换状态：0=配置 1=统计 2=关于
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "环上LLM")
        },
        bottomBar = {
            // HyperOS 风格底部导航栏
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = MiuixIcons.Settings,
                    label = "配置",
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = MiuixIcons.Info,
                    label = "统计",
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = MiuixIcons.Edit,
                    label = "关于",
                )
            }
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 12.dp,
        )

        // 未绑定到 Xposed Service 时显示提示，不展示任何配置控件
        if (config == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "未检测到 LSPosed / Xposed Service\n请在 LSPosed 管理器中启用本模块后重试",
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        // 按底部 Tab 切换内容
        when (selectedTab) {
            0 -> ConfigTabContent(config = config, context = context, scope = scope, contentPadding = contentPadding)
            1 -> StatsTabContent(context = context, scope = scope, contentPadding = contentPadding)
            else -> AboutTabContent(config = config, context = context, scope = scope, contentPadding = contentPadding)
        }
    }
}

/**
 * Tab 0：配置 —— 基本设置（API）+ 生成参数 + 会话设置，各分组含预设管理。
 */
@Composable
private fun ConfigTabContent(
    config: ConfigStore,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
        ) {
            // ---------- 分组 1：基本设置 ----------
            item(key = "basic") {
                // refreshTick 用于 key() 包裹配置输入控件；应用预设后自增，
                // 使输入框内部的 remember 状态重置并重新从 ConfigStore 读取
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        SwitchPreference(
                            title = "启用模块",
                            summary = "关闭后 Hook 不再替换手环小爱回答",
                            checked = config.isEnabled(),
                            onCheckedChange = { config.setEnabled(it) },
                        )
                        OverlayDropdownPreference(
                            title = "API 类型",
                            summary = "openai 兼容 / anthropic",
                            items = listOf("openai", "anthropic"),
                            selectedIndex = if (config.getApiType().trim().lowercase() == "anthropic") 1 else 0,
                            onSelectedIndexChange = { index ->
                                config.setApiType(if (index == 1) "anthropic" else "openai")
                            },
                        )
                        TextInputField(
                            initialValue = config.getBaseUrl(),
                            label = "Base URL",
                            placeholder = "https://api.deepseek.com",
                            onValueChange = { config.setBaseUrl(it) },
                        )
                        SwitchPreference(
                            title = "自动拼接 API 路径",
                            summary = "开启：自动补全 /v1/chat/completions 或 /v1/messages；关闭：Base URL 作为完整地址直接使用",
                            checked = config.isAppendApiPath(),
                            onCheckedChange = { config.setAppendApiPath(it) },
                        )
                        ApiKeyField(
                            initialValue = config.getApiKey(),
                            onValueChange = { config.setApiKey(it) },
                        )
                        TextInputField(
                            initialValue = config.getModel(),
                            label = "模型",
                            placeholder = "deepseek-v4-flash",
                            onValueChange = { config.setModel(it) },
                        )
                    }
                    PresetSection(
                        category = PresetManager.CATEGORY_API,
                        title = "API 配置",
                        config = config,
                        context = context,
                        onPresetApplied = { refreshTick++ },
                    )
                }
            }

            // ---------- 分组 2：生成参数 ----------
            item(key = "generationTitle") {
                SmallTitle("生成参数")
            }
            item(key = "generation") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        // 温度 / Top P / Top K 均为「可空输入」：留空表示不传参，使用 API 默认值
                        DecimalInputField(
                            label = "温度（留空使用 API 默认）",
                            initialValue = config.getTemperature(),
                            onValueChange = { config.setTemperature(it) },
                        )
                        DecimalInputField(
                            label = "Top P（留空使用 API 默认）",
                            initialValue = config.getTopP(),
                            onValueChange = { config.setTopP(it) },
                        )
                        NullableIntInputField(
                            label = "Top K（留空使用 API 默认）",
                            initialValue = config.getTopK(),
                            onValueChange = { config.setTopK(it) },
                        )
                        SwitchPreference(
                            title = "思考模式",
                            summary = "DeepSeek V4 通过请求体 thinking 控制（旧 reasoner 模型名已弃用）",
                            checked = config.isThinkingMode(),
                            onCheckedChange = { config.setThinkingMode(it) },
                        )
                        // 思考强度：仅思考模式下生效（DeepSeek 普通请求默认 high）
                        OverlayDropdownPreference(
                            title = "思考强度",
                            summary = "high / max（仅思考模式下生效）",
                            items = listOf("high", "max"),
                            selectedIndex = if (config.getReasoningEffort().trim().lowercase() == "max") 1 else 0,
                            onSelectedIndexChange = { index ->
                                config.setReasoningEffort(if (index == 1) "max" else "high")
                            },
                        )
                        TextInputField(
                            initialValue = config.getSystemPrompt(),
                            label = "系统提示词",
                            singleLine = false,
                            onValueChange = { config.setSystemPrompt(it) },
                        )
                        NumberInputField(
                            label = "超时时间（毫秒）",
                            initialValue = config.getTimeoutMs().toInt(),
                            onValueChange = { config.setTimeoutMs(it) },
                        )
                        NumberInputField(
                            label = "最大 Token",
                            initialValue = config.getMaxTokens(),
                            onValueChange = { config.setMaxTokens(it) },
                        )
                    }
                    PresetSection(
                        category = PresetManager.CATEGORY_GENERATION,
                        title = "生成参数",
                        config = config,
                        context = context,
                        onPresetApplied = { refreshTick++ },
                    )
                }
            }

            // ---------- 分组 3：会话设置 ----------
            item(key = "sessionTitle") {
                SmallTitle("会话设置")
            }
            item(key = "session") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    key(refreshTick) {
                        OverlayDropdownPreference(
                            title = "会话模式",
                            summary = "single 连续上下文 / independent 独立会话",
                            items = listOf("single", "independent"),
                            selectedIndex = if (config.getContextMode().trim().lowercase() == "independent") 1 else 0,
                            onSelectedIndexChange = { index ->
                                config.setContextMode(if (index == 1) "independent" else "single")
                            },
                        )
                        NumberInputField(
                            label = "会话窗口时长（毫秒）",
                            initialValue = config.getContextWindowMs().toInt(),
                            onValueChange = { config.setContextWindowMs(it) },
                        )
                        NumberInputField(
                            label = "上下文长度（消息条数）",
                            initialValue = config.getContextLength(),
                            onValueChange = { config.setContextLength(it) },
                        )
                    }
                    PresetSection(
                        category = PresetManager.CATEGORY_SESSION,
                        title = "会话设置",
                        config = config,
                        context = context,
                        onPresetApplied = { refreshTick++ },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // 右侧纵向滚动条（HyperOS 风格），跟随 LazyColumn 滚动
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

/**
 * Tab 1：统计 —— API 调用记录与 token 用量（持久化存储）。
 */
@Composable
private fun StatsTabContent(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
        ) {
            item(key = "statsTitle") {
                SmallTitle("API 统计")
            }
            item(key = "stats") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    var statsRefresh by remember { mutableStateOf(0) }
                    key(statsRefresh) {
                        // 数据源为持久化统计（模块 App 进程 SharedPreferences，重启后仍保留）
                        val stats = StatsStore.readCallStats()
                        Text(
                            text = "总调用 ${stats.totalCalls} 次 · 失败 ${stats.totalFailures} 次",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                        )
                        Text(
                            text = "输入 ${stats.totalPromptTokens} tokens · 输出 ${stats.totalCompletionTokens} tokens · 合计 ${stats.totalTokens} tokens",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        // Token 用量可视化柱状图（最近最多 10 次调用）
                        if (stats.recentCalls.isNotEmpty()) {
                            TokenBarChart(calls = stats.recentCalls)
                        }
                        if (stats.recentCalls.isEmpty()) {
                            Text(
                                text = "暂无调用记录。手环真实调用（Hook 进程）的统计通过日志记录，可导出日志查看",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        } else {
                            // 最近 5 条调用记录
                            stats.recentCalls.take(5).forEach { call ->
                                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                    .format(java.util.Date(call.timestamp))
                                val status = if (call.success) "✓" else "✗"
                                Text(
                                    text = "$time $status ${call.model} · ${call.promptTokens}+${call.completionTokens}tk · ${call.querySummary}",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    ArrowPreference(
                        title = "刷新统计",
                        summary = "重新读取持久化统计（设置页进程内保存，重启后保留）",
                        onClick = { statsRefresh++ },
                    )
                    ArrowPreference(
                        title = "清除统计",
                        summary = "清除持久化统计与当前进程的内存统计",
                        onClick = {
                            StatsStore.clear()
                            LlmClient.clearStats()
                            statsRefresh++
                            Toast.makeText(context, "统计已清除", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // 右侧纵向滚动条
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

/**
 * Tab 2：关于 —— 日志导出 + 测试连接 + 版本信息。
 */
@Composable
private fun AboutTabContent(
    config: ConfigStore,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
        ) {
            // ---------- 分组 1：日志 ----------
            item(key = "logTitle") {
                SmallTitle("日志")
            }
            item(key = "log") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "导出日志",
                        summary = "导出当前日志文件到应用缓存目录",
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val file = LogCollector.exportLogFile()
                                withContext(Dispatchers.Main) {
                                    if (file != null) {
                                        Toast.makeText(context, "日志已导出：${file.absolutePath}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "日志导出失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                    )
                }
            }

            // ---------- 分组 2：关于 ----------
            item(key = "aboutTitle") {
                SmallTitle("关于")
            }
            item(key = "about") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    var testing by remember { mutableStateOf(false) }
                    ArrowPreference(
                        title = "测试连接",
                        summary = "使用当前配置请求一次，验证 API Key 是否有效",
                        enabled = !testing,
                        onClick = {
                            testing = true
                            scope.launch(Dispatchers.IO) {
                                // 确保 LlmClient 已注入当前配置再发起请求
                                LlmClient.init(config)
                                val result = LlmClient.ask("settings-connection-test", "连接测试：请回答“连接成功”")
                                withContext(Dispatchers.Main) {
                                    testing = false
                                    val msg = if (result.isNullOrBlank()) {
                                        "连接失败：请检查 Base URL / API Key / 超时设置"
                                    } else {
                                        "连接成功：${result.trim().take(40)}"
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    )
                    Text(
                        text = "环上LLM · 版本 0.1.0",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // 右侧纵向滚动条
        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

/**
 * 预设管理区块（每个可配置分组 Card 末尾复用）。
 *
 * 提供三行操作：
 * 1. 下拉选择已有预设并应用到当前分组；
 * 2. 将当前分组配置命名保存为预设；
 * 3. 删除所选预设。
 * 应用预设后通过 [onPresetApplied] 通知调用方刷新配置输入控件（递增 refreshTick）。
 */
@Composable
private fun PresetSection(
    category: String,
    title: String,
    config: ConfigStore,
    context: Context,
    onPresetApplied: () -> Unit,
) {
    var presets by remember(category) { mutableStateOf(PresetManager.listPresets(category)) }
    var selected by remember(category) { mutableStateOf<String?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    val hasPresets = presets.isNotEmpty()

    OverlayDropdownPreference(
        title = title,
        summary = selected ?: "选择预设并应用到当前分组",
        items = presets.ifEmpty { listOf("(无预设)") },
        selectedIndex = maxOf(presets.indexOf(selected), 0),
        onSelectedIndexChange = { index ->
            // 仅当点击的是真实预设时才加载应用（占位 "(无预设)" 不处理）
            if (hasPresets && index in presets.indices) {
                val name = presets[index]
                selected = name
                PresetManager.loadPreset(category, name)?.let { values ->
                    PresetManager.applyPreset(config, values)
                    onPresetApplied()
                    Toast.makeText(context, "已应用预设「$name」", Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
    ArrowPreference(
        title = "保存当前为预设",
        summary = "命名保存当前分组配置",
        onClick = { showSaveDialog = true },
    )
    ArrowPreference(
        title = "删除所选预设",
        summary = selected ?: "请先在上方选择一个预设",
        enabled = selected != null,
        onClick = {
            selected?.let { name ->
                PresetManager.deletePreset(category, name)
                presets = PresetManager.listPresets(category)
                selected = null
                Toast.makeText(context, "已删除预设「$name」", Toast.LENGTH_SHORT).show()
            }
        },
    )
    OverlayDialog(
        show = showSaveDialog,
        title = "保存预设",
        summary = "为当前${title}配置命名",
        onDismissRequest = { showSaveDialog = false },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = saveName,
                onValueChange = { saveName = it },
                label = "预设名称",
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showSaveDialog = false },
                )
                TextButton(
                    text = "保存",
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val name = saveName.trim()
                        if (name.isNotEmpty()) {
                            PresetManager.savePreset(
                                category,
                                name,
                                PresetManager.exportValues(config, category),
                            )
                            presets = PresetManager.listPresets(category)
                            saveName = ""
                            showSaveDialog = false
                            Toast.makeText(context, "已保存预设「$name」", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

/** 单行文本输入：Base URL / 模型 等字符串配置。
 *  内部用 remember 持有本地输入状态（受控组件直接绑定 config 不会触发重组，
 *  会导致输入即被旧值覆盖、无法输入），输入时同步写回配置。
 */
@Composable
private fun TextInputField(
    initialValue: String,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    val effectiveLabel = if (placeholder.isEmpty()) label else "$label（$placeholder）"
    TextField(
        value = text,
        onValueChange = { input ->
            text = input // 先更新本地状态，保证输入可见
            onValueChange(input)
        },
        label = effectiveLabel,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** API Key 输入：普通文本输入，内部持有本地状态 */
@Composable
private fun ApiKeyField(
    initialValue: String,
    onValueChange: (String) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue) }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            onValueChange(input)
        },
        label = "API Key",
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 数字输入：超时 / Token / 会话等必填整型配置，输入可解析时即时写入 */
@Composable
private fun NumberInputField(
    label: String,
    initialValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue.toString()) }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            input.toIntOrNull()?.let { onValueChange(it) }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * 可空整数输入：留空表示未设置（null，使用 API 默认值）。
 * 仅当输入可解析为整数时才写回；清空则写入 null。
 */
@Composable
private fun NullableIntInputField(
    label: String,
    initialValue: Int?,
    onValueChange: (Int?) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue?.toString() ?: "") }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (input.isBlank()) {
                onValueChange(null) // 留空 -> 使用 API 默认值
            } else {
                input.toIntOrNull()?.let { onValueChange(it) }
            }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * 可空小数输入（温度 / Top P）：留空表示未设置（null，使用 API 默认值）。
 * 仅当输入可解析为浮点数时才写回；清空则写入 null。
 */
@Composable
private fun DecimalInputField(
    label: String,
    initialValue: Float?,
    onValueChange: (Float?) -> Unit,
) {
    var text by remember(initialValue) { mutableStateOf(initialValue?.toString() ?: "") }
    TextField(
        value = text,
        onValueChange = { input ->
            text = input
            if (input.isBlank()) {
                onValueChange(null) // 留空 -> 使用 API 默认值
            } else {
                input.toFloatOrNull()?.let { onValueChange(it) }
            }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * Token 用量柱状图 —— 展示最近若干次调用的输入（prompt）/ 输出（completion）token 占比。
 *
 * 使用 Compose Canvas 手绘堆叠柱状图：
 * - X 轴：最近 [maxBars] 次调用（不足则全量），柱下标时间 HH:mm
 * - Y 轴：token 数量（自动按最大值取整刻度）
 * - 每根柱下半为输入 token（主题主色），上半为输出 token（辅助色），失败调用整体置灰
 */
@Composable
private fun TokenBarChart(
    calls: List<LlmClient.ApiCallRecord>,
    maxBars: Int = 10,
) {
    if (calls.isEmpty()) return

    // 取最近 maxBars 条，时间升序排列（最旧在左）
    val data = calls.takeLast(maxBars)
    val maxToken = (data.maxOfOrNull { it.promptTokens + it.completionTokens } ?: 1).coerceAtLeast(1)

    // Y 轴刻度：向上取整到 10 的倍数，至少 10，最多显示 4 档
    val yStep = ((maxToken / 4).coerceAtLeast(1) + 9) / 10 * 10
    val yMax = yStep * 4

    // 主题色（跟随 Miuix 深浅色）
    val promptColor = MiuixTheme.colorScheme.primary
    val completionColor = MiuixTheme.colorScheme.primaryContainer
    val failureColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
    val textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val gridColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 图例
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendDot(color = promptColor, label = "输入 token")
            LegendDot(color = completionColor, label = "输出 token")
            LegendDot(color = failureColor, label = "失败")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 图表主体：高度 180dp
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            val leftPad = 44.dp.toPx()   // 左侧留出 Y 轴刻度文字
            val bottomPad = 22.dp.toPx() // 底部留出 X 轴时间文字
            val topPad = 8.dp.toPx()
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad - topPad

            // Y 轴刻度文字（原生 Paint 绘制）
            val textPaint = Paint().apply {
                color = textColor.toArgb()
                textSize = 10.sp.toPx()
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
            for (i in 0..4) {
                val v = yStep * i
                val y = topPad + chartH - (v.toFloat() / yMax) * chartH
                drawLine(
                    color = gridColor,
                    start = Offset(leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    v.toString(),
                    leftPad - 4.dp.toPx(),
                    y + textPaint.textSize / 3,
                    textPaint,
                )
            }

            // 柱状图
            val barCount = data.size
            val slotW = chartW / barCount
            val barW = (slotW * 0.6f).coerceAtMost(36.dp.toPx())

            // X 轴时间标签（柱下方，居中）
            val timePaint = Paint().apply {
                color = textColor.toArgb()
                textSize = 9.sp.toPx()
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            data.forEachIndexed { i, call ->
                val total = call.promptTokens + call.completionTokens
                val barH = if (total > 0) (total.toFloat() / yMax) * chartH else 0f
                val x = leftPad + i * slotW + (slotW - barW) / 2
                val bottom = topPad + chartH

                if (!call.success) {
                    // 失败调用：整柱置灰
                    drawRect(
                        color = failureColor,
                        topLeft = Offset(x, bottom - barH),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                    )
                } else {
                    // 输入 token（下半）
                    val promptH = if (call.promptTokens > 0) (call.promptTokens.toFloat() / yMax) * chartH else 0f
                    val completionH = if (call.completionTokens > 0) (call.completionTokens.toFloat() / yMax) * chartH else 0f
                    drawRect(
                        color = completionColor,
                        topLeft = Offset(x, bottom - completionH),
                        size = androidx.compose.ui.geometry.Size(barW, completionH),
                    )
                    drawRect(
                        color = promptColor,
                        topLeft = Offset(x, bottom - completionH - promptH),
                        size = androidx.compose.ui.geometry.Size(barW, promptH),
                    )
                }

                // 时间标签
                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date(call.timestamp))
                drawContext.canvas.nativeCanvas.drawText(
                    time,
                    leftPad + i * slotW + slotW / 2,
                    size.height - 6.dp.toPx(),
                    timePaint,
                )
            }

            // 边框线
            drawRect(
                color = gridColor,
                topLeft = Offset(leftPad, topPad),
                size = androidx.compose.ui.geometry.Size(chartW, chartH),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}

/** 图例小圆点 + 文字 */
@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.height(10.dp).padding(horizontal = 0.dp)) {
            drawCircle(color = color, radius = 4.dp.toPx())
        }
        Spacer(modifier = Modifier.height(0.dp))
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}