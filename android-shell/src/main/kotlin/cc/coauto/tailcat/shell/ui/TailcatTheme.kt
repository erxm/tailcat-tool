package cc.coauto.tailcat.shell.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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
 *    壁纸一换整套配色就变，同一个界面在不同手机上完全是两个样子，颜色之间也没有明确的
 *    层级关系（哪块是卡片、哪块是浮层只有碰运气）。这就是「看着乱」的主要来源。
 * 2. **`smoothRounding` 默认开**：MIUI 风格的四角是超椭圆（squircle）过渡，和下面这套
 *    方正克制的配色放在一起会显得软塌。
 *
 * 所以这里显式给出一套固定配色，并关掉平滑圆角。
 *
 * ## 这套值是哪来的
 *
 * 不是凭感觉挑的，是从参照应用（`dev.vodik7.atvtools`）在真机上**采样**出来的：
 * `adb exec-out screencap` 抓到界面，用 `uiautomator dump` 拿到每个控件的 bounds，
 * 再按 bounds 统计区域内的众数色 —— 于是背景 / 卡片 / 文字 / 导航栏 / 强调容器各有实测值。
 * 深色是把系统切到夜间模式再采一次得到的，不是把浅色简单反过来。
 *
 * | 角色 | 浅色 | 深色 |
 * |---|---|---|
 * | 背景 | `#F7F9FE` | `#111418` |
 * | 卡片 / 磁贴 | `#E0E2E7` | `#333539` |
 * | 浮层 / 次级表面 | `#ECEEF3` | `#1D2024` |
 * | 主文字 | `#191C20` | `#E0E2E7` |
 * | 次文字 | `#43474D` | `#C3C7CE` |
 * | 强调容器 | `#D8E3F5` | `#3D4856` |
 * | 弱强调 | `#98ACC3` | `#5B6F87` |
 *
 * 特征很明确：**整体是一条低饱和的蓝灰色阶**，卡片比背景略深一层（不是常见的浅色卡片），
 * 顶栏与背景同色、不靠阴影分层，唯一的彩色留给错误态。所以这套主题里 secondary/tertiary
 * 都不引入新色相，只在同一色阶内挪动明度 —— 界面因此安静下来。
 *
 * 少数几个值（如深色的 `surfaceContainerHigh`、两侧的 `outline`）参照物里没有对应元素，
 * 是按同色阶插值补的，见各自注释。
 */
private val LightColors = lightColorScheme(
    // ——— 背景与表面 ———
    background = Color(0xFFF7F9FE),
    onBackground = Color(0xFF191C20),
    onBackgroundVariant = Color(0xFF43474D),
    surface = Color(0xFFE0E2E7), // 卡片：比背景略深，是这套配色的标志性关系
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFECEEF3),
    onSurfaceSecondary = Color(0xFF43474D),
    onSurfaceVariantSummary = Color(0xFF54606E), // 说明性文字，比主文字再淡一档
    onSurfaceVariantActions = Color(0xFF3D4856),
    disabledOnSurface = Color(0xFF98ACC3),
    surfaceContainer = Color(0xFFECEEF3),
    onSurfaceContainer = Color(0xFF191C20),
    onSurfaceContainerVariant = Color(0xFF43474D),
    surfaceContainerHigh = Color(0xFFECEEF3),
    onSurfaceContainerHigh = Color(0xFF191C20),
    surfaceContainerHighest = Color(0xFFD8E3F5),
    onSurfaceContainerHighest = Color(0xFF191C20),

    // ——— 强调色：同一蓝灰色阶，只有明度差 ———
    primary = Color(0xFF3D4856),
    onPrimary = Color(0xFFF7F9FE),
    primaryVariant = Color(0xFF5B6F87),
    onPrimaryVariant = Color(0xFFF7F9FE),
    primaryContainer = Color(0xFFD8E3F5),
    onPrimaryContainer = Color(0xFF3D4856),
    secondary = Color(0xFF5B6F87),
    onSecondary = Color(0xFFF7F9FE),
    secondaryVariant = Color(0xFF98ACC3),
    onSecondaryVariant = Color(0xFF191C20),
    secondaryContainer = Color(0xFFE0E2E7),
    onSecondaryContainer = Color(0xFF191C20),
    secondaryContainerVariant = Color(0xFFECEEF3),
    onSecondaryContainerVariant = Color(0xFF191C20),
    tertiaryContainer = Color(0xFFECEEF3),
    onTertiaryContainer = Color(0xFF191C20),
    tertiaryContainerVariant = Color(0xFFD8E3F5),

    // ——— 错误：整套配色里唯一的彩色，留给人真的需要立刻看见的东西 ———
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),

    // ——— 禁用态 / 分隔线 / 滑块 ———
    disabledPrimary = Color(0xFFC3C7CE),
    disabledOnPrimary = Color(0xFFF7F9FE),
    disabledPrimaryButton = Color(0xFFC3C7CE),
    disabledOnPrimaryButton = Color(0xFFF7F9FE),
    disabledPrimarySlider = Color(0xFFC3C7CE),
    disabledSecondary = Color(0xFFC3C7CE),
    disabledOnSecondary = Color(0xFFF7F9FE),
    disabledSecondaryVariant = Color(0xFFC3C7CE),
    disabledOnSecondaryVariant = Color(0xFFF7F9FE),
    outline = Color(0xFFB7BDC6), // 参照物里没有明确描边元素，按 E0E2E7→98ACC3 之间插值
    dividerLine = Color(0xFFD5DAE2),
    windowDimming = Color(0x66000000),
    sliderKeyPoint = Color(0xFF3D4856),
    sliderKeyPointForeground = Color(0xFFF7F9FE),
    sliderBackground = Color(0xFFC3C7CE),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF111418),
    onBackground = Color(0xFFE0E2E7),
    onBackgroundVariant = Color(0xFFC3C7CE),
    surface = Color(0xFF333539),
    onSurface = Color(0xFFE0E2E7),
    surfaceVariant = Color(0xFF1D2024),
    onSurfaceSecondary = Color(0xFFC3C7CE),
    onSurfaceVariantSummary = Color(0xFFAEB3BA), // 浅色 54606E 的深色对应，压暗一档
    onSurfaceVariantActions = Color(0xFFD8E3F5),
    disabledOnSurface = Color(0xFF5B6F87),
    surfaceContainer = Color(0xFF1D2024),
    onSurfaceContainer = Color(0xFFE0E2E7),
    onSurfaceContainerVariant = Color(0xFFC3C7CE),
    surfaceContainerHigh = Color(0xFF282B30), // 1D2024 与 333539 之间插值
    onSurfaceContainerHigh = Color(0xFFE0E2E7),
    surfaceContainerHighest = Color(0xFF3D4856),
    onSurfaceContainerHighest = Color(0xFFD8E3F5),

    primary = Color(0xFF98ACC3),
    onPrimary = Color(0xFF111418),
    primaryVariant = Color(0xFFC3C7CE),
    onPrimaryVariant = Color(0xFF111418),
    primaryContainer = Color(0xFF3D4856),
    onPrimaryContainer = Color(0xFFD8E3F5),
    secondary = Color(0xFF5B6F87),
    onSecondary = Color(0xFFE0E2E7),
    secondaryVariant = Color(0xFF98ACC3),
    onSecondaryVariant = Color(0xFF111418),
    secondaryContainer = Color(0xFF333539),
    onSecondaryContainer = Color(0xFFE0E2E7),
    secondaryContainerVariant = Color(0xFF282B30),
    onSecondaryContainerVariant = Color(0xFFE0E2E7),
    tertiaryContainer = Color(0xFF282B30),
    onTertiaryContainer = Color(0xFFE0E2E7),
    tertiaryContainerVariant = Color(0xFF3D4856),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    disabledPrimary = Color(0xFF4A4F57),
    disabledOnPrimary = Color(0xFF8A9098),
    disabledPrimaryButton = Color(0xFF3D4147),
    disabledOnPrimaryButton = Color(0xFF8A9098),
    disabledPrimarySlider = Color(0xFF4A4F57),
    disabledSecondary = Color(0xFF4A4F57),
    disabledOnSecondary = Color(0xFF8A9098),
    disabledSecondaryVariant = Color(0xFF4A4F57),
    disabledOnSecondaryVariant = Color(0xFF8A9098),
    outline = Color(0xFF5B6F87),
    dividerLine = Color(0xFF2A2D32),
    windowDimming = Color(0x99000000),
    sliderKeyPoint = Color(0xFF98ACC3),
    sliderKeyPointForeground = Color(0xFF111418),
    sliderBackground = Color(0xFF4A4F57),
)

/**
 * 用固定配色包住整个界面。
 *
 * 深浅沿用系统设置（和之前一致，没有加应用内的主题开关 —— 那需要另一处持久化，
 * 与这次「只换主题层」的范围分开）。状态栏图标颜色在 [cc.coauto.tailcat.shell.MainActivity]
 * 里按同一个判据设置，两边必须一致。
 */
@Composable
public fun TailcatTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MiuixTheme(
        colors = if (dark) DarkColors else LightColors,
        // 关掉 MIUI 的超椭圆圆角：这套配色是方正克制的，squircle 会显得软塌。
        smoothRounding = false,
        content = content,
    )
}
