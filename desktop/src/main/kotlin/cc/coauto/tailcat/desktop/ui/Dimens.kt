package cc.coauto.tailcat.desktop.ui

import androidx.compose.ui.unit.dp

/**
 * 几何令牌 —— 与 Android 端 `android-shell/.../ui/Dimens.kt` 同一套命名。
 *
 * 只有 [PagePadding] 是按平台调过的（桌面 20dp，手机 12dp）；其余刻意一致，
 * 这样两端的卡片圆角、卡间距、内边距、分组标题缩进看起来是同一个规范。
 *
 * **不要在调用处再写裸 dp**：之前桌面端 16/20/24 混着用，同一列卡片的左边缘对不齐。
 */
public object DesktopDimens {
    /** 窗口内容左右边距。 */
    public val PagePadding = 20.dp

    /** 卡片与卡片之间。 */
    public val CardGap = 12.dp

    /** 卡片圆角（与手机一致）。 */
    public val CardRadius = 12.dp

    /** 卡片内边距。 */
    public val CardInner = 16.dp

    /** 设置行的最小高度。桌面上比手机松一点会显得散，52dp 刚好。 */
    public val RowMinHeight = 52.dp

    /** 行首图标/圆点的尺寸。 */
    public val RowIcon = 20.dp

    /** 图标与文字之间。 */
    public val RowIconGap = 12.dp

    /** 分组标题距上一组。 */
    public val SectionGap = 18.dp

    /** 分组标题与它下面那张卡之间。 */
    public val SectionToCard = 8.dp

    /** 分组标题的左缩进：与卡内文字对齐（PagePadding + CardInner）。 */
    public val SectionInset = 14.dp
}
