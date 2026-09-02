package llm.miband.littlewhite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 环上LLM —— App 主题封装。
 *
 * 根主题：跟随系统深浅色模式（System）。
 * 整个应用只需在此处创建一次 ThemeController，各页面复用同一 MiuixTheme。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    // 创建 Miuix 主题控制器，跟随系统深浅色模式
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller) {
        content()
    }
}