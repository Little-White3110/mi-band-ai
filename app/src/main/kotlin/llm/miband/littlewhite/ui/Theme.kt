package llm.miband.littlewhite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
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

    val controller = remember(mode, resolvedKeyColor, paletteStyle, effectiveSpec) {
        ThemeController(
            mode,
            keyColor = resolvedKeyColor,
            paletteStyle = paletteStyle,
            colorSpec = effectiveSpec,
        )
    }
    MiuixTheme(controller = controller) {
        content()
    }
}

/** 支持 Spec2025 的调色板风格（与 KernelSU 一致） */
private val SPEC_2025_STYLES = setOf(
    ThemePaletteStyle.TonalSpot,
    ThemePaletteStyle.Neutral,
    ThemePaletteStyle.Vibrant,
    ThemePaletteStyle.Expressive,
)