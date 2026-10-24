package cc.coauto.tailcat.shell.ui

import androidx.annotation.DrawableRes
import cc.coauto.tailcat.shell.R

/**
 * 手绘图标清单（`res/drawable/ic_*.xml`）。
 *
 * 取用时转成 `ImageVector`：`ImageVector.vectorResource(TailcatIcons.Devices)`。
 * 图标一律画成黑色，颜色交给 `Icon(tint = …)` —— 这样同一份资源在深浅色与选中/未选中态下都能用。
 *
 * 名字不叫 `Icons`：会和 `androidx.compose.material.icons.Icons` 在同一个文件的 import 里撞车。
 */
public object TailcatIcons {
    @DrawableRes public val Home = R.drawable.ic_home
    @DrawableRes public val Devices = R.drawable.ic_devices
    @DrawableRes public val Server = R.drawable.ic_server
    @DrawableRes public val Settings = R.drawable.ic_settings
    @DrawableRes public val Terminal = R.drawable.ic_terminal
    @DrawableRes public val Install = R.drawable.ic_install
    @DrawableRes public val Upload = R.drawable.ic_upload
    @DrawableRes public val Download = R.drawable.ic_download
    @DrawableRes public val Screen = R.drawable.ic_screen
    @DrawableRes public val Link = R.drawable.ic_link
    @DrawableRes public val Refresh = R.drawable.ic_refresh
    @DrawableRes public val Qr = R.drawable.ic_qr
    @DrawableRes public val Delete = R.drawable.ic_delete
    @DrawableRes public val Chevron = R.drawable.ic_chevron
}
