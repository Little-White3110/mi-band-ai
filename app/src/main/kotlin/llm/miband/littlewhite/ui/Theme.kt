package llm.miband.littlewhite.ui

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 环上LLM —— App 主题封装。
 *
 * 主题由外部注入（跟随设置页用户选择）：
 * - [mode]：深浅色 + Monet 动态取色（system/light/dark/monetSystem/monetLight/monetDark）
 * - [keyColor]：Monet 种子色（ARGB Int，0 = 跟随系统壁纸）
 * - [paletteStyle]：调色板风格（TonalSpot/Neutral/Vibrant/Expressive 等）
 * - [colorSpec]：动态取色规范（Spec2021 / Spec2025）
 *
 * mode/keyColor 变化时通过 remember 重建控制器，实现运行时主题切换。
 * 主题切换（深浅色/莫奈色/种子色等）通过 AnimatedContent 交叉淡入淡出，
 * 避免颜色突变。
 */
@Composable
fun AppTheme(
    mode: ColorSchemeMode,
    keyColor: Long = 0L,
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2021,
    content: @Composable () -> Unit,
) {
    // Spec2025 仅部分调色板风格支持，不支持时回退 Spec2021（参考 KernelSU effectiveFor）
    val effectiveSpec = if (colorSpec == ThemeColorSpec.Spec2025 &&
        paletteStyle !in SPEC_2025_STYLES
    ) ThemeColorSpec.Spec2021 else colorSpec

    // keyColor=0 时跟随系统（传 null 让 Miuix 自动处理）
    val resolvedKeyColor = keyColor.takeIf { it != 0L }?.let { Color(it.toInt()) }

    // 状态栏图标反色：浅色主题用深色图标，深色主题用浅色图标。
    // Miuix 自身不处理系统栏外观，需在此显式同步，避免主题切换后图标不可见。
    val isLight = when (mode) {
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> true
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> false
        else -> !isSystemInDarkTheme()
    }
    val view = LocalView.current
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = isLight
            }
        }
    }

    // 主题参数作为 AnimatedContent 目标状态：任一属性变化都触发交叉淡入淡出过渡
    val themeState = ThemeState(mode, resolvedKeyColor, paletteStyle, effectiveSpec)

    AnimatedContent(
        targetState = themeState,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "themeTransition",
    ) { target ->
        key(target) {
            val targetController = remember(target) {
                ThemeController(
                    target.mode,
                    keyColor = target.keyColor,
                    paletteStyle = target.paletteStyle,
                    colorSpec = target.colorSpec,
                )
            }
            MiuixTheme(controller = targetController) {
                content()
            }
        }
    }
}

/** AnimatedContent 目标状态：主题参数集合（任一字段变化即触发过渡） */
private data class ThemeState(
    val mode: ColorSchemeMode,
    val keyColor: Color?,
    val paletteStyle: ThemePaletteStyle,
    val colorSpec: ThemeColorSpec,
)

/** 支持 Spec2025 的调色板风格（与 KernelSU 一致） */
private val SPEC_2025_STYLES = setOf(
    ThemePaletteStyle.TonalSpot,
    ThemePaletteStyle.Neutral,
    ThemePaletteStyle.Vibrant,
    ThemePaletteStyle.Expressive,
)