package llm.miband.littlewhite

import android.content.pm.ApplicationInfo
import android.os.Build
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
                    // 预测性返回（API 33+）：同步到 Activity（反射设置隐藏 API，参考 KernelSU）
                    if (Build.VERSION.SDK_INT >= 33) {
                        setEnableOnBackInvokedCallbackCompat(cfg.isEnablePredictiveBack())
                    }
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
                        onEnablePredictiveBackChange = { enabled ->
                            // 持久化 + 同步到 Activity（API 33+，参考 KernelSU：设置后重建生效）
                            binding?.config?.setEnablePredictiveBack(enabled)
                            if (Build.VERSION.SDK_INT >= 33) {
                                setEnableOnBackInvokedCallbackCompat(enabled)
                                recreate()
                            }
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

    /**
     * 运行时切换预测性返回（API 33+）。
     * 反射调用 ApplicationInfo.setEnableOnBackInvokedCallback（隐藏 API，参考 KernelSU）。
     * 切换后需重建 Activity 才能完全生效。
     */
    private fun setEnableOnBackInvokedCallbackCompat(enable: Boolean) {
        runCatching {
            val method = ApplicationInfo::class.java.getDeclaredMethod(
                "setEnableOnBackInvokedCallback",
                Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(applicationInfo, enable)
        }
    }
}

/**
 * 全局单例：持有 XposedService 绑定状态，跨 Activity 配置变更存活。
 *
 * XposedServiceHelper 使用单 listener 字段（mListener），且 mCache 缓存
 * 只在首次注册时非空。旋转屏幕（Activity 重建）后新注册的 listener
 * 无法收到已绑定服务的回调，因此需要将 listener 注册一次并在全局保存状态。
 */
private object BindingHolder {
    var binding: LsposedBinding? by mutableStateOf(null)
        private set

    private var registered = false

    fun ensureRegistered() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                binding = LsposedBinding(service, ConfigStore.fromService(service))
                LogCollector.i("Settings", "XposedService 已绑定，配置可写")
            }

            override fun onServiceDied(service: XposedService) {
                binding = null
                LogCollector.i("Settings", "XposedService 断开")
            }
        })
    }
}

/**
 * 读取 LSPosed Service 绑定状态。
 *
 * 注册一次全局 listener 后，从 [BindingHolder] 读取 Compose 状态，
 * 确保旋转屏幕等配置变更后 binding 状态不丢失。
 */
@Composable
private fun rememberLsposedBinding(): LsposedBinding? {
    DisposableEffect(Unit) {
        BindingHolder.ensureRegistered()
        onDispose { }
    }
    return BindingHolder.binding
}