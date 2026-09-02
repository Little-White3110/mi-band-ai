package llm.miband.littlewhite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import llm.miband.littlewhite.config.ConfigStore
import llm.miband.littlewhite.log.LogCollector
import llm.miband.littlewhite.ui.AppTheme
import llm.miband.littlewhite.ui.LocalEnableBlur
import llm.miband.littlewhite.ui.LocalEnableFloatingBar
import llm.miband.littlewhite.ui.LocalEnableFloatingBarBlur
import llm.miband.littlewhite.ui.LocalEnableNavigationBadge
import llm.miband.littlewhite.ui.LocalPageScale
import llm.miband.littlewhite.ui.SettingsScreen
import llm.miband.littlewhite.ui.VisualPrefs
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * LSPosed Service 绑定信息（设置页读取框架状态与配置的统一入口）。
 *
 * @param service 框架 Service 实例，状态页用它读取框架名/版本/scope/运行目标
 * @param config 由 [service] 构建的可写 [ConfigStore]（配置页/关于页使用）
 */
data class LsposedBinding(val service: XposedService, val config: ConfigStore)

/**
 * 环上LLM 设置页入口。
 *
 * 通过 [XposedServiceHelper.registerListener] 监听框架 Service 的绑定状态，
 * 绑定成功后用返回的 [XposedService] 创建可写 [ConfigStore] 并传给设置页。
 * 若框架未安装 / 模块未启用导致永不绑定，界面显示"未检测到 LSPosed/Service"。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogCollector.init(applicationContext)

        setContent {
            val binding = rememberLsposedBinding()

            // 响应式主题状态：从配置读取，用户切换时驱动 AppTheme 重建
            var themeMode by remember { mutableStateOf(ColorSchemeMode.System) }
            var keyColor by remember { mutableStateOf(0L) }
            var paletteStyle by remember { mutableStateOf(ThemePaletteStyle.TonalSpot) }
            var colorSpec by remember { mutableStateOf(ThemeColorSpec.Spec2021) }
            var miuixMonet by remember { mutableStateOf(false) }

            // 响应式视觉效果状态（由主题设置页切换，驱动 SettingsScreen 重组）
            var visualPrefs by remember { mutableStateOf(VisualPrefs()) }

            LaunchedEffect(binding) {
                if (binding != null) {
                    val cfg = binding.config
                    themeMode = resolveThemeMode(cfg.getThemeMode(), cfg.isMiuixMonet())
                    keyColor = cfg.getKeyColor()
                    paletteStyle = resolvePaletteStyle(cfg.getPaletteStyle())
                    colorSpec = resolveColorSpec(cfg.getColorSpec())
                    miuixMonet = cfg.isMiuixMonet()
                    visualPrefs = VisualPrefs(
                        enableBlur = cfg.isEnableBlur(),
                        enableFloatingBar = cfg.isFloatingBottomBar(),
                        enableFloatingBarBlur = cfg.isFloatingBottomBarBlur(),
                        enableNavigationBadge = cfg.isEnableNavigationBadge(),
                        pageScale = cfg.getPageScale(),
                    )
                }
            }

            AppTheme(
                mode = themeMode,
                keyColor = keyColor,
                paletteStyle = paletteStyle,
                colorSpec = colorSpec,
            ) {
                CompositionLocalProvider(
                    LocalEnableBlur provides visualPrefs.enableBlur,
                    LocalEnableFloatingBar provides visualPrefs.enableFloatingBar,
                    LocalEnableFloatingBarBlur provides visualPrefs.enableFloatingBarBlur,
                    LocalEnableNavigationBadge provides visualPrefs.enableNavigationBadge,
                    LocalPageScale provides visualPrefs.pageScale,
                ) {
                    SettingsScreen(
                        binding = binding,
                        onThemeModeChange = { newMode ->
                            binding?.config?.setThemeMode(newMode)
                            themeMode = resolveThemeMode(newMode, miuixMonet)
                        },
                        onKeyColorChange = { newColor ->
                            binding?.config?.setKeyColor(newColor)
                            keyColor = newColor
                        },
                        onPaletteStyleChange = { newStyle ->
                            binding?.config?.setPaletteStyle(newStyle.name)
                            paletteStyle = newStyle
                        },
                        onColorSpecChange = { newSpec ->
                            binding?.config?.setColorSpec(newSpec.name)
                            colorSpec = newSpec
                        },
                        onMiuixMonetChange = { enabled ->
                            binding?.config?.setMiuixMonet(enabled)
                            miuixMonet = enabled
                            themeMode = resolveThemeMode(binding?.config?.getThemeMode(), enabled)
                        },
                        onVisualPrefsChange = { prefs ->
                            // 持久化到配置
                            binding?.config?.let { cfg ->
                                cfg.setEnableBlur(prefs.enableBlur)
                                cfg.setFloatingBottomBar(prefs.enableFloatingBar)
                                cfg.setFloatingBottomBarBlur(prefs.enableFloatingBarBlur)
                                cfg.setEnableNavigationBadge(prefs.enableNavigationBadge)
                                cfg.setPageScale(prefs.pageScale)
                            }
                            // 更新响应式状态，驱动 SettingsScreen 重组
                            visualPrefs = prefs
                        },
                    )
                }
            }
        }
    }

    companion object {
        /**
         * 把配置里存储的主题模式字符串映射为 Miuix ColorSchemeMode。
         * Monet 开关打开时，把非 Monet 的 system/light/dark 提升为对应 Monet 系列；
         * 关闭时把 Monet 系列降级为非 Monet 系列。
         */
        private fun resolveThemeMode(raw: String?, monet: Boolean): ColorSchemeMode {
            val base = raw?.lowercase()
            return when {
                monet -> when (base) {
                    "light", "monetlight" -> ColorSchemeMode.MonetLight
                    "dark", "monetdark" -> ColorSchemeMode.MonetDark
                    else -> ColorSchemeMode.MonetSystem
                }
                else -> when (base) {
                    "light" -> ColorSchemeMode.Light
                    "dark" -> ColorSchemeMode.Dark
                    else -> ColorSchemeMode.System
                }
            }
        }

        /** 调色板风格字符串 -> 枚举；未知值回退 TonalSpot */
        private fun resolvePaletteStyle(raw: String?): ThemePaletteStyle = try {
            ThemePaletteStyle.valueOf(raw ?: "")
        } catch (_: Exception) {
            ThemePaletteStyle.TonalSpot
        }

        /** 动态取色规范字符串 -> 枚举；未知值回退 Spec2021 */
        private fun resolveColorSpec(raw: String?): ThemeColorSpec = try {
            ThemeColorSpec.valueOf(raw ?: "")
        } catch (_: Exception) {
            ThemeColorSpec.Spec2021
        }
    }
}

/**
 * 注册 Xposed Service 监听，把绑定得到的 [LsposedBinding] 存进 Compose 状态。
 * 注意：实际回调名为 [XposedServiceHelper.OnServiceListener.onServiceDied]
 * （并非 onServiceDisconnected），表示框架 Service 断开。
 */
@Composable
private fun rememberLsposedBinding(): LsposedBinding? {
    // 保存当前可用的 Service + 配置；未绑定到 Service 时为 null
    var binding by remember { mutableStateOf<LsposedBinding?>(null) }

    DisposableEffect(Unit) {
        val listener = object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                // 框架 Service 绑定成功，拿到可写 ConfigStore 与框架状态
                binding = LsposedBinding(service, ConfigStore.fromService(service))
                LogCollector.i("Settings", "XposedService 已绑定，配置可写")
            }

            override fun onServiceDied(service: XposedService) {
                // 框架 Service 断开，清空绑定（界面回到"未检测到 Service"状态）
                binding = null
                LogCollector.i("Settings", "XposedService 断开")
            }
        }
        XposedServiceHelper.registerListener(listener)
        onDispose { }
    }

    return binding
}