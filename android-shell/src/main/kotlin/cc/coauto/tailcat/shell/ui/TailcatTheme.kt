package cc.coauto.tailcat.shell.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 应用主题层。
 *
 * ## 为什么不再用裸 `MiuixTheme { }`
 *
 * 它有两处默认行为不适合这里：
 *
 * 1. **默认配色带 monet**：MIUIX 0.8 的 `ColorSchemeMode` 有 `MonetSystem/MonetLight/MonetDark`
 *    几档，主题控制器还带 `keyColor` / `colorSpec` / `paletteStyle` —— 也就是会从系统壁纸取色。
 *    壁纸一换整套配色就变，同一个界面在不同手机上完全是两个样子，颜色之间也没有明确的层级关系。
 * 2. **`smoothRounding` 默认开**：MIUI 风格的四角是超椭圆（squircle）过渡，与下面这套方正克制的
 *    配色放在一起会显得软塌。
 *
 * 所以这里显式给出一套固定配色，并关掉平滑圆角。
 *
 * ## 这套值是哪来的
 *
 * 不是凭感觉挑的，是从参照应用（KernelSU 管理器）在真机上**采样**出来的：
 * `adb exec-out screencap` 抓到界面，`uiautomator dump` 拿到每个控件的 bounds，
 * 再按 bounds 统计区域内的众数色；深色是把系统切到夜间模式再采一次得到的。
 *
 * | 角色 | 浅色 | 深色 |
 * |---|---|---|
 * | 页面背景 | `#F8F9FE` | `#0C0E12` |
 * | 卡片 | `#EBEEF4` | `#171A1F` |
 * | 主文字 | `#2E3339` | `#E1E6ED` |
 * | 次文字 | `#5B6066` | `#A7ABB3` |
 * | 弱化 | `#7D8189` | `#626469` |
 * | 主色（钢蓝） | `#426183` | `#B1C9E7` |
 * | 强调容器 | `#D8E3F5` | `#2B425B` |
 *
 * 特征：一条低饱和的蓝灰色阶，卡片只比页面背景亮/深一点点（不像常见做法那样明显分层），
 * 界面里唯一的彩色留给「主色 + 错误态」。少数几个值（`surfaceVariant`、`outline` 等）参照应用里
 * 没有对应元素，是按同色阶插值补的，各自有注释。
 */
private val LightColors = lightColorScheme(
    // ——— 背景与表面 ———
    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF2E3339),
    onBackgroundVariant = Color(0xFF5B6066),
    surface = Color(0xFFEBEEF4), // 卡片：只比背景深一点点，这是这套配色的标志
    onSurface = Color(0xFF2E3339),
    surfaceVariant = Color(0xFFF2F4F9), // 输入框等更浅一层（插值）
    onSurfaceSecondary = Color(0xFF5B6066),
    onSurfaceVariantSummary = Color(0xFF6B7076), // 说明文字，比次文字再淡一档（插值）
    onSurfaceVariantActions = Color(0xFF426183),
    disabledOnSurface = Color(0xFF7D8189),
    surfaceContainer = Color(0xFFEBEEF4),
    onSurfaceContainer = Color(0xFF2E3339),
    onSurfaceContainerVariant = Color(0xFF5B6066),
    surfaceContainerHigh = Color(0xFFEBEEF4),
    onSurfaceContainerHigh = Color(0xFF2E3339),
    surfaceContainerHighest = Color(0xFFD8E3F5), // 强调卡（首页状态卡实测就是这个底色）
    onSurfaceContainerHighest = Color(0xFF2B425B),

    // ——— 主色与强调 ———
    primary = Color(0xFF426183),
    onPrimary = Color(0xFFFFFFFF),
    primaryVariant = Color(0xFF607B99),
    onPrimaryVariant = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E3F5),
    onPrimaryContainer = Color(0xFF2B425B),
    secondary = Color(0xFF607B99),
    onSecondary = Color(0xFFFFFFFF),
    secondaryVariant = Color(0xFFAEB2BA),
    onSecondaryVariant = Color(0xFF2E3339),
    secondaryContainer = Color(0xFFEBEEF4),
    onSecondaryContainer = Color(0xFF2E3339),
    secondaryContainerVariant = Color(0xFFF2F4F9),
    onSecondaryContainerVariant = Color(0xFF2E3339),
    tertiaryContainer = Color(0xFFF2F4F9),
    onTertiaryContainer = Color(0xFF2E3339),
    tertiaryContainerVariant = Color(0xFFD8E3F5),

    // ——— 错误：整套配色里唯一的暖色 ———
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    // ——— 禁用态 / 描边 / 滑块 ———
    disabledPrimary = Color(0xFFC4C8CE),
    disabledOnPrimary = Color(0xFFF8F9FE),
    disabledPrimaryButton = Color(0xFFC4C8CE),
    disabledOnPrimaryButton = Color(0xFFF8F9FE),
    disabledPrimarySlider = Color(0xFFC4C8CE),
    disabledSecondary = Color(0xFFC4C8CE),
    disabledOnSecondary = Color(0xFFF8F9FE),
    disabledSecondaryVariant = Color(0xFFC4C8CE),
    disabledOnSecondaryVariant = Color(0xFFF8F9FE),
    outline = Color(0xFFC8CDD4), // 参照应用没有明显描边，按背景→次文字之间插值
    dividerLine = Color(0xFFDDE1E8),
    windowDimming = Color(0x66000000),
    sliderKeyPoint = Color(0xFF426183),
    sliderKeyPointForeground = Color(0xFFFFFFFF),
    sliderBackground = Color(0xFFC4C8CE),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE1E6ED),
    onBackgroundVariant = Color(0xFFA7ABB3),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE1E6ED),
    surfaceVariant = Color(0xFF1F232A), // 比卡片再浅一层（插值）
    onSurfaceSecondary = Color(0xFFA7ABB3),
    onSurfaceVariantSummary = Color(0xFF8B9098), // 插值
    onSurfaceVariantActions = Color(0xFFB1C9E7),
    disabledOnSurface = Color(0xFF626469),
    surfaceContainer = Color(0xFF171A1F),
    onSurfaceContainer = Color(0xFFE1E6ED),
    onSurfaceContainerVariant = Color(0xFFA7ABB3),
    surfaceContainerHigh = Color(0xFF1D2229), // 插值
    onSurfaceContainerHigh = Color(0xFFE1E6ED),
    surfaceContainerHighest = Color(0xFF2B425B),
    onSurfaceContainerHighest = Color(0xFFB1C9E7),

    primary = Color(0xFFB1C9E7),
    onPrimary = Color(0xFF0C0E12),
    primaryVariant = Color(0xFFD5E3F5),
    onPrimaryVariant = Color(0xFF0C0E12),
    primaryContainer = Color(0xFF2B425B),
    onPrimaryContainer = Color(0xFFB1C9E7),
    secondary = Color(0xFF8FA8C6),
    onSecondary = Color(0xFF0C0E12),
    secondaryVariant = Color(0xFFB1C9E7),
    onSecondaryVariant = Color(0xFF0C0E12),
    secondaryContainer = Color(0xFF171A1F),
    onSecondaryContainer = Color(0xFFE1E6ED),
    secondaryContainerVariant = Color(0xFF1D2229),
    onSecondaryContainerVariant = Color(0xFFE1E6ED),
    tertiaryContainer = Color(0xFF1D2229),
    onTertiaryContainer = Color(0xFFE1E6ED),
    tertiaryContainerVariant = Color(0xFF2B425B),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    disabledPrimary = Color(0xFF44484E),
    disabledOnPrimary = Color(0xFF626469),
    disabledPrimaryButton = Color(0xFF3A3E44),
    disabledOnPrimaryButton = Color(0xFF626469),
    disabledPrimarySlider = Color(0xFF44484E),
    disabledSecondary = Color(0xFF44484E),
    disabledOnSecondary = Color(0xFF626469),
    disabledSecondaryVariant = Color(0xFF44484E),
    disabledOnSecondaryVariant = Color(0xFF626469),
    outline = Color(0xFF44484E),
    dividerLine = Color(0xFF22262C),
    windowDimming = Color(0x99000000),
    sliderKeyPoint = Color(0xFFB1C9E7),
    sliderKeyPointForeground = Color(0xFF0C0E12),
    sliderBackground = Color(0xFF44484E),
)

/**
 * 状态色。
 *
 * MIUIX 的 `Colors` 里没有「成功/运行中」这一档，而连接状态、服务端是否在跑、日志里的
 * OK/ERROR 都需要它。之前这些绿/橙是**散在各界面里硬编码**的（`0xFF34C759` 之类），
 * 与主题色阶不搭；这里统一收进来，随深浅色切换。
 *
 * 绿取自参照应用里的运行指示色（`#1DCD3A`），深色下提亮一档。
 */
@Immutable
public data class StatusColors(
    val running: Color,
    val idle: Color,
    val warning: Color,
    val error: Color,
)

private val LightStatus = StatusColors(
    running = Color(0xFF1DCD3A),
    idle = Color(0xFF7D8189),
    warning = Color(0xFFE08600),
    error = Color(0xFFB3261E),
)

private val DarkStatus = StatusColors(
    running = Color(0xFF3ADB54),
    idle = Color(0xFF626469),
    warning = Color(0xFFFFB020),
    error = Color(0xFFF2B8B5),
)

private val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** 在 `TailcatTheme` 内部取状态色；没有主题包裹时退回浅色一套（预览用）。 */
public val statusColors: StatusColors
    @Composable get() = LocalStatusColors.current

/**
 * 用固定配色包住整个界面。
 *
 * 深浅沿用系统设置（与之前一致，没有加应用内的主题开关 —— 那需要另一处持久化，
 * 与这次「只换主题层」的范围分开）。状态栏图标颜色在 [cc.coauto.tailcat.shell.MainActivity]
 * 里按同一个判据设置，两边必须一致。
 */
@Composable
public fun TailcatTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
    ) {
        MiuixTheme(
            colors = if (dark) DarkColors else LightColors,
            // 关掉 MIUI 的超椭圆圆角：这套配色是方正克制的，squircle 会显得软塌。
            smoothRounding = false,
            content = content,
        )
    }
}
