package cc.coauto.tailcat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 桌面端的组件层，对应 Android 端的 `ui/Components.kt`。
 *
 * 只有四个原语：分组卡（[GroupCard]）、设置行（[SettingRow]）、分组标题（[SectionHeader]）、
 * 药丸（[Chip]）。界面里**不要再自己拼 Card + Row + 裸 padding** —— 之前桌面端每处都各写一遍，
 * 结果行高、圆角、间距各不相同，这就是"没统一"的来源。
 */

/** 分组卡：一张圆角卡里放若干行，行间没有分隔线（与手机一致，靠行高与文字层级区分）。 */
@Composable
public fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesktopDimens.CardRadius),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** 分组标题：小号灰字，左缩进与卡内文字对齐。 */
@Composable
public fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(
            start = DesktopDimens.SectionInset,
            bottom = DesktopDimens.SectionToCard,
        ),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 设置行：可选标题 + 说明 + 右侧值/动作。
 *
 * 整行可点、最小高度 [DesktopDimens.RowMinHeight]，点卡片边缘也算点到这一行。
 */
@Composable
public fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = DesktopDimens.RowMinHeight)
            .padding(horizontal = DesktopDimens.CardInner, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.width(DesktopDimens.RowIcon), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(DesktopDimens.RowIconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 药丸：快捷操作、筛选项、档位选择共用（对应手机端的 `Chip`）。 */
@Composable
public fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (selected) colors.surfaceContainerHighest else colors.surfaceContainer
    val contentColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> colors.onPrimaryContainer
        else -> colors.onSurface
    }
    Box(
        modifier
            .background(background, RoundedCornerShape(8.dp))
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = contentColor, maxLines = 1)
    }
}

/** 页标题：大号字 + 一行说明 + 右侧动作。 */
@Composable
public fun PageTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}
