package cc.coauto.tailcat.shell.ui

import androidx.compose.ui.unit.dp

/**
 * 几何令牌。
 *
 * 全部数值来自参照应用（KernelSU 管理器）在真机上的**像素测量**：
 * 1216×2688 屏、density 460，所以 1dp = 2.875px，下面的 dp 值是把量到的像素除以它得来的。
 *
 * **界面里不要再写裸数值。** 之前的问题就是这里要 10dp、那里要 16dp、卡片内又是 10dp，
 * 同一个界面里出现四五套间距，圆角和边距自然看着不对。间距与圆角只能从 [Dimens] 取。
 *
 * | 令牌 | 值 | 实测来源 |
 * |---|---|---|
 * | 页面左右外边距 | 12dp | 卡片左缘 x=35px |
 * | 卡片之间 | 12dp | 相邻卡片间距 35px |
 * | 卡片圆角 | 12dp | 圆角扫描到 38px（含抗锯齿 13.2dp） |
 * | 卡片内边距 | 16dp | 行内容左缩 28dp = 12 + 16 |
 * | 标准行高 | 73dp | 设置页每行 210px |
 * | 行首图标 | 24dp | 69px |
 * | 图标与文字间距 | 14dp | 图标右缘 150 → 文字左缘 190 |
 * | 行尾控件 | 48dp | 开关/箭头点击区 141px |
 * | 大标题字号 | 42dp | 标题文本高 122px |
 * | 底部导航 | 64dp | 导航条 184px |
 */
public object Dimens {
    /** 页面左右外边距。 */
    public val PagePadding = 12.dp

    /** 卡片与卡片之间、分组与分组之间。 */
    public val CardGap = 12.dp

    /** 卡片圆角。 */
    public val CardRadius = 12.dp

    /** 卡片内容的内边距（行内容额外再内缩这么多）。 */
    public val CardInner = 16.dp

    /** 标准行高；副标题折行时允许更高。 */
    public val RowMinHeight = 73.dp

    /** 行首图标尺寸。 */
    public val RowIcon = 24.dp

    /** 行首图标与标题之间的间距。 */
    public val RowIconGap = 14.dp

    /** 行尾控件（开关 / 箭头 / 值）的点击区。 */
    public val Trailing = 48.dp

    /**
     * 行的垂直内边距。
     *
     * 12dp 是让**带 48dp 行尾控件**的行也正好停在 [RowMinHeight] 上的值：
     * 48 + 12×2 = 72 ≈ 73dp。写 16dp 的话这类行会被撑到 80dp，比参照应用高一截
     * （实测就是这样发现的：文字行 73dp、带按钮的行 80.3dp）。
     * 只有文字的行走 `heightIn(min = 73dp)` 兜底，不受这个值影响。
     */
    public val RowVertical = 12.dp

    /**
     * 分组标题左缩。**相对页边距**加 16dp，实际落在屏幕 28dp 处 —— 与行首图标对齐。
     * （页面的 12dp 由外层 Column 加，这里不要再重复算进去，否则会跑到 40dp 去。）
     */
    public val SectionInset = 16.dp

    /**
     * 大标题左缩。同理是**相对页边距**：12 + 14 = 26dp，与参照应用的实测值一致。
     */
    public val TitleInset = 14.dp

    /** 大标题字号。 */
    public val TitleSize = 30.dp

    /** 底部导航高度。 */
    public val NavBarHeight = 64.dp

    /** 大标题与第一张卡之间。 */
    public val TitleToContent = 10.dp

    /** 分组标题与上一张卡之间。 */
    public val SectionGap = 24.dp

    /** 分组标题与它下面那张卡之间。 */
    public val SectionToCard = 8.dp
}
