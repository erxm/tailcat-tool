package cc.coauto.tailcat.shell.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.ConnectionState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/**
 * 界面组件层。
 *
 * 只有三个原语：分组卡（[GroupCard]）、设置行（[SettingRow]）、页标题（[BigTitleBar]），
 * 外加分组标题与状态点。几何全部走 [Dimens]，**不要在调用处再写裸 padding**。
 *
 * 为什么不直接用 MIUIX 的 `BasicComponent`：它的圆角、左右内边距、行高都按 MIUI 的规范走
 * （行高更高、内边距更大），拿不到参照应用的 73dp 行高与 28dp 文本内缩。这里用 Foundation
 * 自己拼一行，尺寸完全可控，只借用 MIUIX 的文字样式与开关控件。
 */

/** 行的尾部控件。 */
public sealed interface RowTrailing {
    /** 什么都没有（纯展示行）。 */
    public data object None : RowTrailing

    /** 一个箭头，表示「点进去还有一层」。 */
    public data object Chevron : RowTrailing

    /** 右侧显示一个值（只读）。 */
    public data class Value(val text: String) : RowTrailing

    /** 右侧一个开关。 */
    public data class Toggle(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit) : RowTrailing

    /** 右侧一个文字按钮（例如「断开」「复制」）。 */
    public data class Action(val text: String, val onClick: () -> Unit) : RowTrailing
}

/**
 * 分组卡：一张圆角卡里放若干行。
 *
 * **行与行之间没有分隔线**（参照应用的设置页实测如此，靠行高和文字层级区分），
 * 卡与卡之间靠 [Dimens.CardGap] 分开。`insideMargin` 传 0 是为了让行的点击区铺满整卡宽度，
 * 文字的 28dp 内缩由 [SettingRow] 自己加 —— 这样点卡片边缘也算点到那一行。
 */
@Composable
public fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = Dimens.CardRadius,
        insideMargin = PaddingValues(0.dp),
        content = content,
    )
}

/**
 * 设置行：可选行首图标 + 标题 + 副标题 + 尾部控件。
 *
 * 行高固定不低于 [Dimens.RowMinHeight]（73dp，参照应用实测），副标题折行时可以更高。
 */
@Composable
public fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    trailing: RowTrailing = RowTrailing.None,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    // 行首不是图标时（例如设备行的状态圆点）用这个；
    // 行尾需要放不止一个控件时（「连接/断开」+「关闭」）用 trailingContent —— 它优先于 trailing。
    leading: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    /** 覆盖标题颜色。危险操作（删除）用 `colors.error`，其余保持默认。 */
    titleColor: Color? = null,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val contentColor = titleColor ?: if (enabled) colors.onSurface else colors.disabledOnSurface

    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Dimens.RowMinHeight)
            .padding(horizontal = Dimens.CardInner, vertical = Dimens.RowVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(Dimens.RowIcon), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(Dimens.RowIconGap))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(Dimens.RowIcon),
            )
            Spacer(Modifier.width(Dimens.RowIconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = styles.body1,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = styles.footnote2,
                    color = if (enabled) colors.onSurfaceVariantSummary else colors.disabledOnSurface,
                )
            }
        }
        if (trailingContent != null) {
            trailingContent()
            return@Row
        }
        when (trailing) {
            RowTrailing.None -> Unit
            RowTrailing.Chevron -> Icon(
                imageVector = ImageVector.vectorResource(TailcatIcons.Chevron),
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
            is RowTrailing.Value -> Text(
                text = trailing.text,
                style = styles.footnote2,
                color = colors.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            is RowTrailing.Toggle -> Switch(
                checked = trailing.checked,
                onCheckedChange = trailing.onCheckedChange,
                enabled = enabled,
            )
            is RowTrailing.Action -> TextButton(
                text = trailing.text,
                onClick = trailing.onClick,
                enabled = enabled,
            )
        }
    }
}

/** 分组标题：小号灰字，左边距与行内文字对齐。 */

@Composable
public fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(
            start = Dimens.SectionInset,
            end = Dimens.PagePadding,
            top = Dimens.SectionGap,
            bottom = Dimens.SectionToCard,
        ),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/** 页标题：大号粗体，右侧可挂一个操作按钮。 */
@Composable
public fun BigTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.TitleInset,
                end = Dimens.PagePadding,
                top = Dimens.PagePadding,
                bottom = Dimens.TitleToContent,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.title1,
            color = MiuixTheme.colorScheme.onBackground,
        )
        trailing()
    }
}

/**
 * 状态圆点。「连接中 / 断开中」时做呼吸动画。
 *
 * 颜色来自主题的 [statusColors]，不再各处硬编码绿橙 —— 之前 `0xFF34C759` 这种 iOS 绿
 * 与主题色阶不搭，深色模式下也偏亮。
 */
@Composable
public fun StateDot(state: ConnectionState) {
    val colors = MiuixTheme.colorScheme
    val status = statusColors
    val target = when (state) {
        ConnectionState.Disconnected -> colors.outline
        ConnectionState.Connecting, ConnectionState.Disconnecting -> colors.primary
        ConnectionState.Online -> status.running
        ConnectionState.Offline, ConnectionState.Unauthorized -> status.warning
        ConnectionState.Failed -> status.error
    }
    val settled = if (state.isBusy) {
        val transition = rememberInfiniteTransition(label = "deviceDotPulse")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "deviceDotAlpha",
        )
        pulse
    } else {
        1f
    }
    Box(Modifier.size(11.dp).alpha(settled).background(target, CircleShape))
}

/**
 * 展开块：出现时把自己滚进可视区。
 *
 * 分组里的行越排越长（设备详情有四五组），点「编辑设备」或「查看日志」时，
 * 弹出的内容经常正好落在屏幕下缘以外 —— 看得见开头，剩下要自己往下拖。
 * 这里在内容进入组合后请求一次 bring-into-view，让页面自己上滑到能看见为止。
 *
 * 两个细节：
 *  - **等一帧再请求**：进入组合的那一帧布局还是展开前的高度，立刻请求会按错误的尺寸计算。
 *  - 请求失败直接吞掉：小屏 + 高内容时本来就滚不全，不该因此崩掉界面。
 */
@Composable
public fun ExpandingBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.bringIntoView() }
    }
    Box(modifier.bringIntoViewRequester(requester)) { content() }
}
