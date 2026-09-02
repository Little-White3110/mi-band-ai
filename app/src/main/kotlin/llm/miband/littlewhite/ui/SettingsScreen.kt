@file:OptIn(ExperimentalScrollBarApi::class)

package llm.miband.littlewhite.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.hook.LlmClient
import llm.miband.littlewhite.log.LogCollector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
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
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "环上LLM")
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 12.dp,
            bottom = 12.dp,
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

        Box {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
            ) {
                // ---------- 分组 1：基本设置 ----------
                item(key = "basic") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                            value = config.getBaseUrl(),
                            label = "Base URL",
                            placeholder = "https://api.deepseek.com",
                            onValueChange = { config.setBaseUrl(it) },
                        )
                        ApiKeyField(
                            value = config.getApiKey(),
                            onValueChange = { config.setApiKey(it) },
                        )
                        TextInputField(
                            value = config.getModel(),
                            label = "模型",
                            placeholder = "deepseek-chat",
                            onValueChange = { config.setModel(it) },
                        )
                    }
                }

                // ---------- 分组 2：生成参数 ----------
                item(key = "generationTitle") {
                    SmallTitle("生成参数")
                }
                item(key = "generation") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                            summary = "OpenAI: deepseek-reasoner / Anthropic: thinking",
                            checked = config.isThinkingMode(),
                            onCheckedChange = { config.setThinkingMode(it) },
                        )
                        TextInputField(
                            value = config.getSystemPrompt(),
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
                }

                // ---------- 分组 3：会话设置 ----------
                item(key = "sessionTitle") {
                    SmallTitle("会话设置")
                }
                item(key = "session") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
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
                }

                // ---------- 分组 4：日志 ----------
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

                // ---------- 分组 5：关于 ----------
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
}

/** 单行文本输入：Base URL / 模型 等字符串配置 */
@Composable
private fun TextInputField(
    value: String,
    label: String,
    placeholder: String = "",
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val effectiveLabel = if (placeholder.isEmpty()) label else "$label（$placeholder）"
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = effectiveLabel,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** API Key 密码输入：默认不显示明文 */
@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = "API Key",
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
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