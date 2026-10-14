package cc.coauto.tailcat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.TaskState

/**
 * 对话框内容面板。
 *
 * Compose Desktop 的 [androidx.compose.ui.window.Dialog] 是一个**独立窗口**，本身不带背景。
 * 内容如果不自己兜底，就会直接叠在父窗口上——文字和底下的界面糊在一起，几乎看不清。
 * 这里统一给一层不透明的 [Surface]，顺便统一圆角、内边距和元素间距。
 */
@Composable
public fun DialogPanel(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 16.dp,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
public fun StatusDot(state: ConnectionState, size: Int = 9) {
    val color = when (state) {
        ConnectionState.Online -> Color(0xFF4CAF50)
        ConnectionState.Unauthorized, ConnectionState.Offline -> Color(0xFFFF9800)
        ConnectionState.Failed -> Color(0xFFE53935)
        ConnectionState.Connecting, ConnectionState.Disconnecting -> Color(0xFF2196F3)
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
public fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
public fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
public fun InfoBlock(title: String, body: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

public fun taskColor(state: TaskState, fallback: Color): Color = when (state) {
    TaskState.Failed -> Color(0xFFE53935)
    TaskState.Succeeded -> Color(0xFF4CAF50)
    else -> fallback
}
