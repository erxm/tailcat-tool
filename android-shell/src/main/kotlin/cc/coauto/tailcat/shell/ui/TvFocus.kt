package cc.coauto.tailcat.shell.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 给可点击控件加一个**可见的焦点框**。
 *
 * 手机上不需要它：手指点哪儿就是哪儿。电视/盒子只有遥控器方向键，焦点看不见就等于
 * "点不到"——这正是应用装上电视后完全没法操作的直接原因。
 *
 * 用法：套在任何已经可点击（`Modifier.clickable` / MIUIX 的可点卡片）的修饰符上。
 * `Modifier.clickable` 本身就会让节点可聚焦、并响应方向键中键/回车，缺的只是视觉反馈，
 * 所以这里**只画框、不改点击行为**，不会造成重复触发。
 */
@Composable
public fun Modifier.focusRing(
    shape: Shape = RoundedCornerShape(Dimens.CardRadius),
    color: Color = MiuixTheme.colorScheme.primary,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}
