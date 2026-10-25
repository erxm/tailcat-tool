package cc.coauto.tailcat.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows 端的主题层，规格与 Android 端**同一套**（`android-shell/.../ui/TailcatTheme.kt`）。
 *
 * 只有两处按平台调整，其余全部一致：
 *
 * | 量 | Android | Windows | 为什么 |
 * |---|---|---|---|
 * | 页边距 | 12dp | 20dp | 1180×780 的窗口比手机屏宽得多，12dp 贴边太紧 |
 * | 正文 | 16sp（MIUIX body1） | 14sp | Windows 的屏距与 DPI 缩放不同，14sp 才是"正常字号" |
 *
 * 其余（配色、状态色、圆角 12dp、字号层级 3 档）刻意保持一模一样的值，
 * 这样两端截图标出来是一家人：低饱和蓝灰、卡片只比背景深/浅一点点、
 * 唯一的彩色留给「主色 + 错误态」。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF426183),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E3F5),
    onPrimaryContainer = Color(0xFF2B425B),
    secondary = Color(0xFF607B99),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEEF4),
    onSecondaryContainer = Color(0xFF2E3339),
    tertiaryContainer = Color(0xFFF2F4F9),
    onTertiaryContainer = Color(0xFF2E3339),

    background = Color(0xFFF8F9FE),
    onBackground = Color(0xFF2E3339),
    surface = Color(0xFFEBEEF4),
    onSurface = Color(0xFF2E3339),
    surfaceVariant = Color(0xFFF2F4F9),
    onSurfaceVariant = Color(0xFF5B6066),
    // M3 的 surfaceContainer* 一档比一档"高出背景"，这里按 Android 的卡片/强调卡取值
    surfaceContainerLowest = Color(0xFFF8F9FE),
    surfaceContainerLow = Color(0xFFEBEEF4),
    surfaceContainer = Color(0xFFEBEEF4),
    surfaceContainerHigh = Color(0xFFEBEEF4),
    surfaceContainerHighest = Color(0xFFD8E3F5),

    outline = Color(0xFFC8CDD4),
    outlineVariant = Color(0xFFDDE1E8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = Color(0xFF2E3339),
    inverseOnSurface = Color(0xFFF8F9FE),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB1C9E7),
    onPrimary = Color(0xFF0C0E12),
    primaryContainer = Color(0xFF2B425B),
    onPrimaryContainer = Color(0xFFB1C9E7),
    secondary = Color(0xFF8FA8C6),
    onSecondary = Color(0xFF0C0E12),
    secondaryContainer = Color(0xFF171A1F),
    onSecondaryContainer = Color(0xFFE1E6ED),
    tertiaryContainer = Color(0xFF1D2229),
    onTertiaryContainer = Color(0xFFE1E6ED),

    background = Color(0xFF0C0E12),
    onBackground = Color(0xFFE1E6ED),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE1E6ED),
    surfaceVariant = Color(0xFF1F232A),
    onSurfaceVariant = Color(0xFFA7ABB3),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF171A1F),
    surfaceContainer = Color(0xFF171A1F),
    surfaceContainerHigh = Color(0xFF1D2229),
    surfaceContainerHighest = Color(0xFF2B425B),

    outline = Color(0xFF44484E),
    outlineVariant = Color(0xFF22262C),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    inverseSurface = Color(0xFFE1E6ED),
    inverseOnSurface = Color(0xFF171A1F),
)

/**
 * 字号层级：整页只有三档（和 Android 一致）。
 *
 * `headlineSmall` 页面标题 / `titleMedium` 分组标题 / `bodyMedium` 正文 / `bodySmall` 说明。
 * 之前桌面端混用 Material 默认的六档字号，同一个界面里 20sp、16sp、14sp、12sp 到处跳，
 * 是"没设计过"的观感来源。
 */
private val TailcatTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 12.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp),
    labelSmall = TextStyle(fontSize = 11.sp),
)

/** 圆角与 Android 一致：卡片 12dp，对话框略大一点。 */
private val TailcatShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

/**
 * 状态色（连接、任务、日志里的 OK/ERROR）。Android 端同一份定义，
 * 绿取自参照应用的运行指示色；桌面端以前是 0xFF4CAF50 这种 Material 绿，和蓝灰色阶不搭。
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

public val statusColors: StatusColors
    @Composable get() = LocalStatusColors.current

/** 等宽字体样式：终端 / 命令输出 / 地址这类"要能逐字读"的内容统一用它。 */
public val monoStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )

@Composable
public fun TailcatTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = TailcatTypography,
            shapes = TailcatShapes,
        ) {
            // MaterialTheme 自己**不画背景**：Android 上有 Activity 主题兜底，桌面上露出来的
            // 直接是 Windows 的 #F0F0F0，跟这套配色完全不搭。这里用 Surface 铺底色。
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}
