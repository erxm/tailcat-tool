package cc.coauto.tailcat.shell.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前是不是电视/盒子（10 英尺界面）。
 *
 * 判据用 `UiModeManager` 的设备类型，而不是 `FEATURE_LEANBACK`：后者在部分国产电视上
 * 根本没声明，但系统仍然把界面切成 TELEVISION 模式。两个都不命中就当手机处理。
 *
 * 为什么需要它：
 * - 电视**只有遥控器**，没有触屏，底部导航那一排贴边的小 tab 用方向键很难瞄准；
 * - 电视的可用高度小（1080p 下约 960×540dp）而手机页面按竖屏堆叠，二维码这类元素会被挤到折叠线以下；
 * - 观看距离 3 米，12sp 的正文偏小。
 */
public val LocalTvMode = staticCompositionLocalOf { false }

public fun isTelevision(context: Context): Boolean {
    val mode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val pm = context.packageManager
    return pm.hasSystemFeature("android.software.leanback") &&
        !pm.hasSystemFeature("android.hardware.touchscreen")
}
