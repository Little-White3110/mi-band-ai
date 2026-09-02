package llm.miband.littlewhite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 全局视觉效果偏好（移植 KernelSU LocalEnableBlur / LocalEnableFloatingBottomBar /
 * LocalEnableNavigationBadge / LocalPageScale）。
 *
 * 由 SettingsActivity 从 ConfigStore 读取并提供，UI 各处通过对应 Local 读取当前值。
 */
data class VisualPrefs(
    val enableBlur: Boolean = false,
    val enableFloatingBar: Boolean = false,
    val enableFloatingBarBlur: Boolean = false,
    val enableNavigationBadge: Boolean = true,
    val pageScale: Float = 1.0f,
)

/** 是否启用顶部栏/底部栏背景模糊 */
val LocalEnableBlur = staticCompositionLocalOf { false }

/** 是否启用悬浮底部导航栏 */
val LocalEnableFloatingBar = staticCompositionLocalOf { false }

/** 悬浮底部栏是否启用玻璃模糊效果 */
val LocalEnableFloatingBarBlur = staticCompositionLocalOf { false }

/** 是否在导航栏 Tab 上显示角标 */
val LocalEnableNavigationBadge = staticCompositionLocalOf { true }

/** 页面缩放系数（0.8 ~ 1.1） */
val LocalPageScale = staticCompositionLocalOf { 1.0f }

/** 便捷读取：当前是否启用模糊 */
@Composable
@ReadOnlyComposable
fun isBlurEnabled(): Boolean = LocalEnableBlur.current

/** 便捷读取：当前页面缩放系数 */
@Composable
@ReadOnlyComposable
fun currentPageScale(): Float = LocalPageScale.current