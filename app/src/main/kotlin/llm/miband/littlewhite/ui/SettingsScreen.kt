@file:OptIn(ExperimentalScrollBarApi::class)

package llm.miband.littlewhite.ui

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import llm.miband.littlewhite.LsposedBinding
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.config.PresetManager
import llm.miband.littlewhite.config.StatsStore
import llm.miband.littlewhite.hook.LlmClient
import llm.miband.littlewhite.log.LogCollector
import llm.miband.littlewhite.ui.VisualPrefs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import llm.miband.littlewhite.ui.BlurredBar
import llm.miband.littlewhite.ui.LocalEnableBlur
import llm.miband.littlewhite.ui.LocalEnableFloatingBar
import llm.miband.littlewhite.ui.LocalEnableFloatingBarBlur
import llm.miband.littlewhite.ui.LocalEnableNavigationBadge
import llm.miband.littlewhite.ui.LocalPageScale
import llm.miband.littlewhite.ui.rememberBlurBackdrop
import llm.miband.littlewhite.ui.component.FloatingBottomBar
import llm.miband.littlewhite.ui.component.FloatingBottomBarItem

/**
 * 环上LLM —— 完整设置页（参考 KernelSU Manager 设计风格）。
 *
 * 4 个 Tab 通过 HorizontalPager 左右滑动切换：
 * 0=状态 1=配置 2=统计 3=关于
 *
 * @param binding LSPosed Service 绑定信息；为 null 表示未检测到框架，显示提示
 * @param pagerState Pager 状态（由外层提升注入：主题切换动画会重建本页面组合，
 *                   状态提升到 AnimatedContent 之外以保持 Tab 位置存活）
 * @param showThemePage 是否显示主题设置二级页（同样提升注入，避免切换主题后被踢回主页）
 * @param onOpenThemePage 打开主题设置页
 * @param onCloseThemePage 关闭主题设置页
 * @param onThemeModeChange 主题模式变更回调（持久化 + 驱动 AppTheme 重建）
 * @param onKeyColorChange Monet 种子色变更回调
 * @param onPaletteStyleChange 调色板风格变更回调
 * @param onColorSpecChange 动态取色规范变更回调
 */
@Composable
fun SettingsScreen(
    binding: LsposedBinding?,
    pagerState: PagerState,
    showThemePage: Boolean,
    onOpenThemePage: () -> Unit,
    onCloseThemePage: () -> Unit,
    onThemeModeChange: (String) -> Unit = {},
    onKeyColorChange: (Long) -> Unit = {},
    onPaletteStyleChange: (ThemePaletteStyle) -> Unit = {},
    onColorSpecChange: (ThemeColorSpec) -> Unit = {},
    onMiuixMonetChange: (Boolean) -> Unit = {},
    onVisualPrefsChange: (VisualPrefs) -> Unit = {},
    onEnablePredictiveBackChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    PresetManager.init(context)
    StatsStore.init(context)
    val scope = rememberCoroutineScope()

    val config = binding?.config

    data class TabInfo(val label: String, val icon: ImageVector)
    val tabs = listOf(
        TabInfo("状态", MiuixIcons.Home),
        TabInfo("配置", MiuixIcons.Settings),
        TabInfo("统计", MiuixIcons.Info),
        TabInfo("关于", MiuixIcons.Edit),
    )

    // 主题设置页时拦截系统返回：回到主设置页而非直接退出
    BackHandler(enabled = showThemePage && config != null) {
        onCloseThemePage()
    }

    // 主题设置页需要 config 才能操作
    if (showThemePage && config != null) {
        ThemeSettingsScreen(
            config = config,
            onBack = onCloseThemePage,
            onThemeModeChange = onThemeModeChange,
            onKeyColorChange = onKeyColorChange,
            onPaletteStyleChange = onPaletteStyleChange,
            onColorSpecChange = onColorSpecChange,
            onMiuixMonetChange = onMiuixMonetChange,
            onVisualPrefsChange = onVisualPrefsChange,
            onEnablePredictiveBackChange = onEnablePredictiveBackChange,
        )
        return
    }

    // 视觉效果（从 CompositionLocal 读取，由 SettingsActivity 提供）
    val enableBlur = LocalEnableBlur.current
    val enableFloatingBar = LocalEnableFloatingBar.current
    val enableFloatingBarBlur = LocalEnableFloatingBarBlur.current
    val enableNavBadge = LocalEnableNavigationBadge.current
    val pageScale = LocalPageScale.current
    // 顶部栏模糊 backdrop（参考 KernelSU：blur 开启且支持时非空）
    val blurBackdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = blurBackdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    // 悬浮底栏玻璃效果 backdrop（参考 KernelSU：始终创建，pager 内容挂在上面）
    val floatingSurface = MiuixTheme.colorScheme.surface
    val floatingBackdrop = rememberLayerBackdrop {
        drawRect(floatingSurface)
        drawContent()
    }

    Scaffold(
        topBar = {
            if (blurActive) {
                BlurredBar(blurBackdrop) {
                    SmallTopAppBar(title = "环上LLM", color = barColor)
                }
            } else {
                SmallTopAppBar(title = "环上LLM")
            }
        },
        bottomBar = {
            if (enableFloatingBar) {
                // 悬浮胶囊底部导航栏（居中显示，参考 KernelSU FloatingBottomBar）
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingBottomBar(
                        modifier = Modifier.padding(bottom = 12.dp),
                        selectedIndex = { pagerState.currentPage },
                        onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                        backdrop = floatingBackdrop,
                        tabsCount = tabs.size,
                        isBlurEnabled = enableFloatingBarBlur,
                    ) {
                        tabs.forEachIndexed { i, tab ->
                            FloatingBottomBarItem(
                                onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                            ) {
                                Icon(imageVector = tab.icon, contentDescription = tab.label)
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            } else {
                 NavigationBar {
                     tabs.forEachIndexed { i, tab ->
                         NavigationBarItem(
                             selected = pagerState.currentPage == i,
                             onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                             icon = tab.icon,
                             label = tab.label,
                             badge = {
                                 if (enableNavBadge && i == 0) {
                                     // 状态 Tab 显示连接状态角标：已连接用绿色，未连接用 error
                                     Badge(
                                         containerColor = if (binding != null) {
                                             Color(0xFF4CAF50)
                                         } else {
                                             MiuixTheme.colorScheme.error
                                         },
                                         modifier = Modifier.size(8.dp),
                                     )
                                 }
                             },
                         )
                     }
                 }
             }
        },
    ) { innerPadding ->
        val contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + 12.dp,
            bottom = innerPadding.calculateBottomPadding() + 12.dp,
        )

        // 页面缩放：包裹 Pager 内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // 顶部栏模糊：把内容绘制到 blur backdrop 上，顶栏才能模糊到滚动内容
                    if (blurActive) Modifier.layerBackdrop(blurBackdrop!!) else Modifier
                )
                .graphicsLayer {
                    scaleX = pageScale
                    scaleY = pageScale
                },
        ) {
            // 悬浮底栏玻璃效果：把 Pager 内容绘制到浮动底栏 backdrop 上
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.then(
                    if (enableFloatingBar && enableFloatingBarBlur) {
                        Modifier.layerBackdrop(floatingBackdrop)
                    } else {
                        Modifier
                    }
                ),
            ) { page ->
                when (page) {
                    0 -> StatusTabContent(binding = binding, context = context, contentPadding = contentPadding)
                    1 -> ConfigTabContent(config = config, context = context, scope = scope, contentPadding = contentPadding)
                    2 -> StatsTabContent(context = context, scope = scope, contentPadding = contentPadding)
                    3 -> AboutTabContent(config = config, context = context, scope = scope, contentPadding = contentPadding, onOpenThemePage = onOpenThemePage)
                }
            }
        }
    }
}

// ====================================================================
// Tab 0：状态 —— LSPosed 连接状态总览（参考 KernelSU StatusCard 设计）
// ====================================================================

/**
 * 状态页 —— 展示 LSPosed 框架连接状态、模块作用域、目标进程 Hook 状态。
 * 参考 KernelSU HomePager 的 StatusCard + InfoCard 设计风格。
 */
@Composable
private fun StatusTabContent(
    binding: LsposedBinding?,
    context: Context,
    contentPadding: PaddingValues,
) {
    val service = binding?.service
    val listState = rememberLazyListState()
    // 协程作用域（与上方读取 scope 列表的局部变量区分命名）
    val uiScope = rememberCoroutineScope()
    // 是否正在执行 Root 重启
    var restarting by remember { mutableStateOf(false) }
    // 重启二次确认弹窗
    var showRestartDialog by remember { mutableStateOf(false) }

    // 异常容错读取框架信息（未绑定时为默认占位）
    val frameworkName = remember { runCatching { service?.frameworkName }.getOrNull() ?: "LSPosed" }
    val frameworkVersion = remember { runCatching { service?.frameworkVersion }.getOrNull() ?: "?" }
    val frameworkVersionCode = remember { runCatching { service?.frameworkVersionCode }.getOrNull() ?: 0L }
    val scope = remember { runCatching { service?.scope }.getOrNull() ?: emptyList() }
    val targets = remember { runCatching { service?.runningTargets }.getOrNull() ?: emptyList() }
    val targetInScope = scope.any { it.equals("com.mi.health", ignoreCase = true) }
    val miHealthTarget = targets.firstOrNull { it.processName.contains("com.mi.health") }

    // 是否已激活（Service 绑定成功）
    val activated = binding != null

    // 大卡片配色（恢复原经典配色，与动态取色无关）：未激活浅红，激活浅绿
    val cardBg = if (!activated) Color(0xFFF8D7DA) else Color(0xFFDFFAE4)
    val cardFg = if (!activated) Color(0xFF8B1A1A) else Color(0xFF1A3825)
    val tagColor = MiuixTheme.colorScheme.secondaryContainer
    val tagTextColor = MiuixTheme.colorScheme.onSecondaryContainer

    Box {
        LazyColumn(state = listState, contentPadding = contentPadding) {
            // ---------- 主状态卡片（参考 KernelSU StatusCard） ----------
            item(key = "statusCard") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = CardDefaults.defaultColors(color = cardBg),
                        onClick = {
                            // 点击卡片尝试打开 LSPosed 管理器
                            runCatching {
                                val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                                if (intent != null) context.startActivity(intent)
                            }
                        },
                        showIndication = true,
                        pressFeedbackType = PressFeedbackType.Tilt,
                    ) {
                        Box {
                            // 右下角大图标（参考 KernelSU：110dp 对勾/叉号，右下偏移）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset(27.dp, 31.dp),
                                contentAlignment = Alignment.BottomEnd,
                            ) {
                                Icon(
                                    modifier = Modifier.size(110.dp),
                                    imageVector = if (activated) MiuixIcons.Ok else MiuixIcons.Close,
                                    tint = if (activated) {
                                        Color(0xFF36D167)
                                    } else {
                                        MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
                                    },
                                    contentDescription = null,
                                )
                            }
                            // 左下角工作模式标签（参考 KernelSU workingMode）
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 10.dp),
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    text = if (activated) "LSPosed" else "未激活",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = cardFg,
                                )
                            }
                            // 左上角标题 + 版本
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp, 14.dp),
                                contentAlignment = Alignment.TopStart,
                            ) {
                                Column {
                                    Text(
                                        text = if (activated) "已连接 LSPosed" else "LSPosed 未激活",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cardFg,
                                    )
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Text(
                                        text = if (activated) "$frameworkName · $frameworkVersion"
                                        else "请在 LSPosed 管理器中启用本模块",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = cardFg,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 框架信息卡片 ----------
            item(key = "frameworkTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("框架信息")
            }
            item(key = "framework") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    InfoRow(
                        label = "框架名称",
                        value = frameworkName,
                        tag = "LSPosed",
                        tagBg = tagColor,
                        tagFg = tagTextColor,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    InfoRow(
                        label = "框架版本",
                        value = "$frameworkVersion (code $frameworkVersionCode)",
                        tag = frameworkVersion,
                        tagBg = tagColor,
                        tagFg = tagTextColor,
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    InfoRow(
                        label = "目标应用",
                        value = "com.mi.health",
                        tag = if (targetInScope) "已勾选" else "未勾选",
                        tagBg = if (targetInScope) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.errorContainer,
                        tagFg = if (targetInScope) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            // ---------- Hook 运行状态卡片 ----------
            item(key = "hookTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("Hook 运行状态")
            }
            item(key = "hook") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    val targetInfo = miHealthTarget
                    val statusText = when {
                        targetInfo == null -> "未运行"
                        targetInfo.state == HookedTarget.State.UP_TO_DATE -> "运行中 · 已加载"
                        targetInfo.state == HookedTarget.State.STALE -> "运行中 · 需重载"
                        targetInfo.state == HookedTarget.State.RELOADING -> "重载中"
                        targetInfo.state == HookedTarget.State.FAILED -> "加载失败"
                        else -> "未知"
                    }
                    val statusColor = when {
                        targetInfo == null -> MiuixTheme.colorScheme.errorContainer
                        targetInfo.state == HookedTarget.State.UP_TO_DATE -> MiuixTheme.colorScheme.primaryContainer
                        else -> MiuixTheme.colorScheme.tertiaryContainer
                    }
                    InfoRow(
                        label = "com.mi.health",
                        value = "[pid=${targetInfo?.pid ?: "?"}]",
                        tag = statusText,
                        tagBg = statusColor,
                        tagFg = MiuixTheme.colorScheme.onPrimaryContainer,
                    )
                    if (targetInfo != null) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        InfoRow(
                            label = "模块版本",
                            value = "v${targetInfo.loadedVersionCode}",
                            tag = "loaded",
                            tagBg = tagColor,
                            tagFg = tagTextColor,
                        )
                    }
                }
            }

            // ---------- 快速操作 ----------
            item(key = "quickTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("快速操作")
            }
            item(key = "quick") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "打开 LSPosed 管理器",
                        summary = "管理模块作用域与查看运行状态",
                        onClick = {
                            // 尝试打开 LSPosed 管理器（org.lsposed.manager）
                            runCatching {
                                val intent = context.packageManager.getLaunchIntentForPackage("org.lsposed.manager")
                                if (intent != null) context.startActivity(intent)
                            }
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "自启动设置",
                        summary = "为小米运动健康开启自启动权限，保证后台常驻",
                        onClick = {
                            openAutoStartSettings(context, TARGET_APP_PACKAGE)
                        },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ArrowPreference(
                        title = "省电策略设置",
                        summary = "设置小米运动健康的省电策略，避免后台被系统限制",
                        onClick = {
                            openBatteryOptimizationSettings(context, TARGET_APP_PACKAGE)
                        },
                    )
                }
            }

            // ---------- 重启目标应用（需 Root） ----------
            item(key = "restartTitle") {
                Spacer(modifier = Modifier.height(8.dp))
                SmallTitle("目标应用")
            }
            item(key = "restart") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "重启小米运动健康",
                        summary = if (restarting) {
                            "正在以 Root 权限重启…"
                        } else {
                            "以 Root 权限强制重启 com.mi.health，使模块 Hook 立即生效"
                        },
                        enabled = !restarting,
                        onClick = {
                            // 二次确认后再执行 Root 重启
                            showRestartDialog = true
                        },
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }

    // 重启二次确认对话框（StatusTabContent 位于 SettingsScreen 的 Scaffold 内，满足 Overlay 宿主要求）
    OverlayDialog(
        show = showRestartDialog,
        title = "重启小米运动健康",
        summary = "将以 Root 权限强制重启 com.mi.health，模块 Hook 会重新加载生效",
        onDismissRequest = { showRestartDialog = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = { showRestartDialog = false },
            )
            TextButton(
                text = "确认重启",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    showRestartDialog = false
                    restarting = true
                    uiScope.launch(Dispatchers.IO) {
                        val ok = restartAppWithRoot(TARGET_APP_PACKAGE)
                        withContext(Dispatchers.Main) {
                            restarting = false
                            Toast.makeText(
                                context,
                                if (ok) "已重启小米运动健康" else "重启失败：请检查 Root 授权",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            )
        }
    }
}

/** 目标应用包名（重启/状态展示统一使用） */
private const val TARGET_APP_PACKAGE = "com.mi.health"

/**
 * 以 Root 权限强制重启目标应用（com.mi.health）。
 *
 * 通过 su 执行 am force-stop 强制停止目标应用，等待短暂时间后
 * 使用 monkey 重新拉起其 Launcher Activity，使模块 Hook 重新注入生效。
 * Root 不可用或授权被拒时返回 false。
 */
private fun restartAppWithRoot(packageName: String): Boolean {
    return try {
        // su -c 直接执行合并命令；失败（无 Root/授权被拒）时 exit code 非 0
        val process = ProcessBuilder("su", "-c", "am force-stop $packageName && sleep 1 && monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            .redirectErrorStream(true)
            .start()
        // 读取输出，避免管道缓冲阻塞；最多等待 15s
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(15, TimeUnit.SECONDS)
        // force-stop 成功 + monkey 注入事件成功才算重启完成
        process.exitValue() == 0 && output.contains("Events injected: 1")
    } catch (_: Throwable) {
        false
    }
}

/**
 * 跳转系统「自启动设置」。
 *
 * 优先尝试 MIUI/HyperOS 安全中心的自启动管理页（方便为指定应用开启自启动），
 * 无法解析时回退到系统应用详情页（多数系统在此页提供自启动入口）。
 * 全部失败时给出 Toast 提示，不抛出异常。
 */
private fun openAutoStartSettings(context: Context, packageName: String) {
    // MIUI/HyperOS 自启动管理（com.miui.securitycenter 的 AutoStart 管理 Activity）
    val miuiIntent = Intent("miui.intent.action.OP_AUTO_START")
    // 通用回退：系统应用详情页
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
    if (launchFirstAvailable(context, miuiIntent, detailsIntent)) return
    Toast.makeText(context, "未找到自启动设置入口", Toast.LENGTH_SHORT).show()
}

/**
 * 跳转系统「省电策略设置」。
 *
 * 优先尝试系统电池优化设置页，无法解析时回退到系统应用详情页，
 * 多数系统（含 MIUI/HyperOS）的应用详情页内置「省电策略/电池」入口。
 */
private fun openBatteryOptimizationSettings(context: Context, packageName: String) {
    // 通用电池优化设置（Android 系统设置，可选择忽略优化）
    val batteryIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    // 通用回退：系统应用详情页（含省电策略入口）
    val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
    if (launchFirstAvailable(context, batteryIntent, detailsIntent)) return
    Toast.makeText(context, "未找到省电策略设置入口", Toast.LENGTH_SHORT).show()
}

/**
 * 依次尝试启动 Intent，返回是否有一个成功启动。
 * 需要以 Activity 上下文启动，故加上 NEW_TASK 标志以防缺少 Activity 栈。
 */
private fun launchFirstAvailable(context: Context, vararg intents: Intent): Boolean {
    for (intent in intents) {
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        } catch (_: Throwable) {
            // 尝试下一个候选
        }
    }
    return false
}

/** 信息行：标签 + 值 + 状态标签 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    tag: String,
    tagBg: Color,
    tagFg: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
            maxLines = 1,
        )
        StatusTag(
            label = tag,
            backgroundColor = tagBg,
            contentColor = tagFg,
        )
    }
}

/** 小圆角状态标签（参考 KernelSU StatusTagMiuix：圆角 6dp，9sp 字体） */
@Composable
private fun StatusTag(
    label: String,
    backgroundColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight(750),
            color = contentColor,
        )
    }
}

// ====================================================================
// Tab 1：配置 —— 基本设置（API）+ 生成参数 + 会话设置 + 主题设置
// ====================================================================

/**
 * Tab 1：配置 —— 基本设置（API）+ 生成参数 + 会话设置，各分组含预设管理。
 */
@Composable
private fun ConfigTabContent(
    config: ConfigStore?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
) {
    // 未激活（无 Service）时显示占位提示
    if (config == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "LSPosed 未激活\n请在 LSPosed 管理器中启用本模块后\n配置 API 参数",
                textAlign = TextAlign.Center,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
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
                        // 受控组件（Switch/Dropdown）用本地状态驱动 UI，回调时同时写回 config，
                        // 避免非响应式 RemotePreferences 读取导致界面不更新
                        var enabled by remember { mutableStateOf(config.isEnabled()) }
                        SwitchPreference(
                            title = "启用模块",
                            summary = "关闭后 Hook 不再替换手环小爱回答",
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                config.setEnabled(it)
                            },
                        )
                        var apiTypeIndex by remember {
                            mutableStateOf(if (config.getApiType().trim().lowercase() == "anthropic") 1 else 0)
                        }
                        OverlayDropdownPreference(
                            title = "API 类型",
                            summary = "openai 兼容 / anthropic",
                            items = listOf("openai", "anthropic"),
                            selectedIndex = apiTypeIndex,
                            onSelectedIndexChange = { index ->
                                apiTypeIndex = index
                                config.setApiType(if (index == 1) "anthropic" else "openai")
                            },
                        )
                        TextInputField(
                            initialValue = config.getBaseUrl(),
                            label = "Base URL",
                            placeholder = "https://api.deepseek.com",
                            onValueChange = { config.setBaseUrl(it) },
                        )
                        var appendApiPath by remember { mutableStateOf(config.isAppendApiPath()) }
                        SwitchPreference(
                            title = "自动拼接 API 路径",
                            summary = "开启：自动补全 /v1/chat/completions 或 /v1/messages；关闭：Base URL 作为完整地址直接使用",
                            checked = appendApiPath,
                            onCheckedChange = {
                                appendApiPath = it
                                config.setAppendApiPath(it)
                            },
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

            // ---------- 分组 2：回答模式（语音指令切换小爱 / LLM） ----------
            item(key = "modeTitle") {
                SmallTitle("回答模式")
            }
            item(key = "mode") {
                var refreshTick by remember { mutableStateOf(0) }
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    // 受控组件（OverlayDropdown/Switch）必须由本地 state 驱动显示：
                    // 直接用 config 读取作为 checked/selectedIndex，写 config 不触发重组，
                    // 会导致"点了没反应"。state 声明在 key(refreshTick) 内，应用预设刷新时
                    // 整个子树重建、回读 config 最新值，保证显示与配置一致。
                    key(refreshTick) {
                        var defaultMode by remember { mutableStateOf(config.getDefaultMode().trim().lowercase()) }
                        var interceptGeneral by remember { mutableStateOf(config.getInterceptGeneral()) }
                        OverlayDropdownPreference(
                            title = "默认回答模式",
                            summary = "无指令时默认由谁回答",
                            items = listOf("LLM 接管", "小爱接管"),
                            selectedIndex = if (defaultMode == "xiaoai") 1 else 0,
                            onSelectedIndexChange = { index ->
                                defaultMode = if (index == 1) "xiaoai" else "llm"
                                config.setDefaultMode(defaultMode)
                            },
                        )
                        NumberInputField(
                            label = "小爱模式持续时长（分钟，0=永久）",
                            initialValue = (config.getXiaoaiModeMs() / 60_000L).toInt(),
                            onValueChange = { minutes ->
                                config.setXiaoaiModeMs(minutes.toLong() * 60_000L)
                            },
                        )
                        NumberInputField(
                            label = "LLM 模式持续时长（分钟，0=永久）",
                            initialValue = (config.getLlmModeMs() / 60_000L).toInt(),
                            onValueChange = { minutes ->
                                config.setLlmModeMs(minutes.toLong() * 60_000L)
                            },
                        )
                        TextInputField(
                            initialValue = config.getCmdToLlm().joinToString("\n"),
                            label = "切换到 LLM 的指令词",
                            singleLine = false,
                            placeholder = "每行一个",
                            onValueChange = { text ->
                                config.setCmdToLlm(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
                            },
                        )
                        TextInputField(
                            initialValue = config.getCmdToXiaoai().joinToString("\n"),
                            label = "切换到小爱的指令词",
                            singleLine = false,
                            placeholder = "每行一个",
                            onValueChange = { text ->
                                config.setCmdToXiaoai(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
                            },
                        )
                        TextInputField(
                            initialValue = config.getCmdQueryMode().joinToString("\n"),
                            label = "查询当前模式的提示词",
                            singleLine = false,
                            placeholder = "每行一个，默认含：你是谁",
                            onValueChange = { text ->
                                config.setCmdQueryMode(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList())
                            },
                        )
                        SwitchPreference(
                            title = "拦截米家/设备类(General)（开发中）",
                            summary = "米家富卡片文本走独立通道，当前版本暂不支持替换，敬请期待",
                            checked = interceptGeneral,
                            enabled = false, // 开发中：置灰不可用
                            onCheckedChange = { v ->
                                interceptGeneral = v
                                config.setInterceptGeneral(v)
                            },
                        )
                    }
                    PresetSection(
                        category = PresetManager.CATEGORY_MODE,
                        title = "回答模式",
                        config = config,
                        context = context,
                        onPresetApplied = { refreshTick++ },
                    )
                }
            }

            // ---------- 分组 3：生成参数 ----------
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
                        // 受控组件用本地状态驱动 UI，避免 RemotePreferences 非响应式导致界面不更新
                        var thinkingMode by remember { mutableStateOf(config.isThinkingMode()) }
                        SwitchPreference(
                            title = "思考模式",
                            summary = "DeepSeek V4 通过请求体 thinking 控制（旧 reasoner 模型名已弃用）",
                            checked = thinkingMode,
                            onCheckedChange = {
                                thinkingMode = it
                                config.setThinkingMode(it)
                            },
                        )
                        // 思考强度：仅思考模式下生效（DeepSeek 普通请求默认 high）
                        var reasoningEffortIndex by remember {
                            mutableStateOf(if (config.getReasoningEffort().trim().lowercase() == "max") 1 else 0)
                        }
                        OverlayDropdownPreference(
                            title = "思考强度",
                            summary = "high / max（仅思考模式下生效）",
                            items = listOf("high", "max"),
                            selectedIndex = reasoningEffortIndex,
                            onSelectedIndexChange = { index ->
                                reasoningEffortIndex = index
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
                        // 受控组件用本地状态驱动 UI，避免 RemotePreferences 非响应式导致界面不更新
                        var contextModeIndex by remember {
                            mutableStateOf(if (config.getContextMode().trim().lowercase() == "independent") 1 else 0)
                        }
                        OverlayDropdownPreference(
                            title = "会话模式",
                            summary = "single 连续上下文 / independent 独立会话",
                            items = listOf("single", "independent"),
                            selectedIndex = contextModeIndex,
                            onSelectedIndexChange = { index ->
                                contextModeIndex = index
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

// ====================================================================
// Tab 2：统计 —— API 调用记录与 token 用量
// ====================================================================

/**
 * Tab 2：统计 —— API 调用记录与 token 用量（持久化存储）。
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
                        summary = "重新读取持久化统计",
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

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

// ====================================================================
// Tab 3：关于 —— 日志导出 + 测试连接 + 版本信息
// ====================================================================

/**
 * Tab 3：关于 —— 日志导出 + 测试连接 + 主题设置入口 + 版本信息。
 */
@Composable
private fun AboutTabContent(
    config: ConfigStore?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    contentPadding: PaddingValues,
    onOpenThemePage: () -> Unit,
) {
    // 未激活（无 Service）时显示占位提示
    if (config == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "LSPosed 未激活\n请在 LSPosed 管理器中启用本模块",
                textAlign = TextAlign.Center,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
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
                        summary = "通过系统分享发送日志文件",
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val file = LogCollector.exportLogFile()
                                withContext(Dispatchers.Main) {
                                    if (file != null) {
                                        // 通过 FileProvider 生成 content:// Uri，交给系统分享
                                        val uri = runCatching {
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                file,
                                            )
                                        }.getOrNull()
                                        if (uri != null) {
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(Intent.EXTRA_TEXT, "环上LLM 日志文件")
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            runCatching {
                                                context.startActivity(Intent.createChooser(share, "分享日志"))
                                            }.onFailure {
                                                Toast.makeText(context, "未找到可分享的应用", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "日志导出失败", Toast.LENGTH_SHORT).show()
                                        }
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
                                LlmClient.init(config)
                                val result = LlmClient.ask("settings-connection-test", "连接测试：请回答连接成功")
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
                    ArrowPreference(
                        title = "项目地址",
                        summary = "https://github.com/Little-White3110/mi-band-ai",
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Little-White3110/mi-band-ai"))
                                context.startActivity(intent)
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

            // ---------- 分组 3：主题设置入口（KSU 风格独立页面） ----------
            item(key = "themeTitle") {
                SmallTitle("主题设置")
            }
            item(key = "theme") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "设置主题",
                        summary = "主题模式 / 动态取色 / 种子色 / 调色板风格 / 视觉效果",
                        onClick = onOpenThemePage,
                    )
                }
            }

            item(key = "bottomSpacer") {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        VerticalScrollBar(
            adapter = rememberScrollBarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(),
            trackPadding = contentPadding,
        )
    }
}

// ====================================================================
// 预设管理 —— 每组配置 Card 底部复用的预设保存/应用/删除区块
// ====================================================================

/**
 * 预设管理区块（每个可配置分组 Card 末尾复用）。
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    modifier = Modifier.weight(1f),
                    onClick = { showSaveDialog = false },
                )
                TextButton(
                    text = "保存",
                    modifier = Modifier.weight(1f),
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

// ====================================================================
// 输入组件 —— 自定义文本/数字/可空输入框
// ====================================================================

/** 单行文本输入：Base URL / 模型 等字符串配置 */
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
            text = input
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

/** API Key 输入 */
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

/** 数字输入：超时 / Token / 会话等必填整型配置 */
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
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 可空整数输入：留空表示未设置（null，使用 API 默认值） */
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
                onValueChange(null)
            } else {
                input.toIntOrNull()?.let { onValueChange(it) }
            }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 可空小数输入（温度 / Top P）：留空表示未设置（null，使用 API 默认值） */
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
                onValueChange(null)
            } else {
                input.toFloatOrNull()?.let { onValueChange(it) }
            }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

// ====================================================================
// Token 柱状图 —— 统计页可视化组件
// ====================================================================

/**
 * Token 用量柱状图 —— 展示最近若干次调用的输入（prompt）/ 输出（completion）token 占比。
 */
@Composable
private fun TokenBarChart(
    calls: List<LlmClient.ApiCallRecord>,
    maxBars: Int = 10,
) {
    if (calls.isEmpty()) return

    val data = calls.takeLast(maxBars)
    val maxToken = (data.maxOfOrNull { it.promptTokens + it.completionTokens } ?: 1).coerceAtLeast(1)

    val yStep = ((maxToken / 4).coerceAtLeast(1) + 9) / 10 * 10
    val yMax = yStep * 4

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendDot(color = promptColor, label = "输入 token")
            LegendDot(color = completionColor, label = "输出 token")
            LegendDot(color = failureColor, label = "失败")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            val leftPad = 44.dp.toPx()
            val bottomPad = 22.dp.toPx()
            val topPad = 8.dp.toPx()
            val chartW = size.width - leftPad
            val chartH = size.height - bottomPad - topPad

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

            val barCount = data.size
            val slotW = chartW / barCount
            val barW = (slotW * 0.6f).coerceAtMost(36.dp.toPx())

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
                    drawRect(
                        color = failureColor,
                        topLeft = Offset(x, bottom - barH),
                        size = androidx.compose.ui.geometry.Size(barW, barH),
                    )
                } else {
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

                val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(java.util.Date(call.timestamp))
                drawContext.canvas.nativeCanvas.drawText(
                    time,
                    leftPad + i * slotW + slotW / 2,
                    size.height - 6.dp.toPx(),
                    timePaint,
                )
            }

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
        Canvas(modifier = Modifier
            .size(10.dp)
            .padding(0.dp)) {
            drawCircle(color = color, radius = 4.dp.toPx())
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}