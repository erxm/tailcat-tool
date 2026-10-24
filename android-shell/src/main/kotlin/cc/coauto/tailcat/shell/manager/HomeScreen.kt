package cc.coauto.tailcat.shell.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.DeviceSession
import cc.coauto.tailcat.core.model.DeviceTask
import cc.coauto.tailcat.shell.ServerState
import cc.coauto.tailcat.shell.TailcatServer
import cc.coauto.tailcat.shell.ui.BigTitleBar
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.GroupCard
import cc.coauto.tailcat.shell.ui.RowTrailing
import cc.coauto.tailcat.shell.ui.SectionHeader
import cc.coauto.tailcat.shell.ui.SettingRow
import cc.coauto.tailcat.shell.ui.TailcatIcons
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页：一眼看到「服务端在不在跑」「连了几台设备」以及最近发生了什么。
 *
 * 这不是原来任何一个界面的搬家 —— 之前的界面里，服务端状态在「服务端」页、连接数只能数设备列表，
 * 首页把它们并排放在最上面。下面两块是最近任务与快捷操作，都是参照应用那种「分组卡 + 设置行」的形态。
 */
@Composable
internal fun HomeScreen(
    model: ManagerModel,
    onOpenDevices: () -> Unit,
    onOpenServer: () -> Unit,
) {
    val manager = model.manager
    val scope = rememberCoroutineScope()
    val serverState by TailcatServer.state.collectAsState()
    val profiles by manager.profiles.collectAsState()
    val temporary by manager.temporaryProfiles.collectAsState()
    val tasks by manager.tasks.collectAsState()

    val allProfiles = profiles + temporary
    // 每台设备各有一条状态流；这里合并成一张表，任何一台连接/断开都会让计数跟着变。
    // 用设备 id 列表做 key：增删设备时重建合并，否则会一直在等已经消失的那条流。
    val ids = allProfiles.map { it.id }
    val sessions by remember(ids) {
        if (ids.isEmpty()) {
            flowOf(emptyList<DeviceSession>())
        } else {
            combine(ids.map { manager.sessionFlow(it) }) { array -> array.toList() }
        }
    }.collectAsState(emptyList())

    val online = sessions.count { it.serial != null }
    val running = serverState is ServerState.Running || serverState is ServerState.Starting
    val serverDetail = when (val state = serverState) {
        is ServerState.Running -> "端口 " + state.ports.joinToString(", ")
        ServerState.Starting -> "正在启动"
        ServerState.Stopped -> "未启动"
        is ServerState.Failed -> "启动失败"
        else -> "未启动"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Dimens.PagePadding, end = Dimens.PagePadding, bottom = Dimens.CardGap * 2),
    ) {
        BigTitleBar("Tailcat")

        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap)) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "服务端",
                value = if (running) "运行中" else "已停止",
                detail = serverDetail,
                accent = running,
                onClick = onOpenServer,
            )
            StatCard(
                modifier = Modifier.weight(1f),
                label = "设备",
                value = "$online 已连接",
                detail = "共 ${allProfiles.size} 台",
                accent = false,
                onClick = onOpenDevices,
            )
        }

        SectionHeader("最近任务")
        GroupCard {
            val recent = tasks.takeLast(4).reversed()
            if (recent.isEmpty()) {
                SettingRow(title = "暂无任务", summary = "连接设备后，安装 / 上传 / Shell 的记录会出现在这里")
            } else {
                recent.forEach { task -> TaskRow(task) }
            }
        }

        SectionHeader("快捷操作")
        GroupCard {
            SettingRow(
                title = "管理设备",
                icon = ImageVector.vectorResource(TailcatIcons.Devices),
                trailing = RowTrailing.Chevron,
                onClick = onOpenDevices,
            )
            SettingRow(
                title = "测试全部设备",
                summary = "并行 ping 每台设备的 Tailcat 可达性",
                icon = ImageVector.vectorResource(TailcatIcons.Link),
                trailing = RowTrailing.Chevron,
                onClick = {
                    manager.testAllDevices()
                    onOpenDevices()
                },
            )
            SettingRow(
                title = "全部断开",
                summary = "关闭所有已连接的转发通道",
                icon = ImageVector.vectorResource(TailcatIcons.Refresh),
                trailing = RowTrailing.Chevron,
                enabled = online > 0,
                onClick = { scope.launch { manager.disconnectAll() } },
            )
        }
    }
}

@Composable
private fun TaskRow(task: DeviceTask) {
    SettingRow(
        title = task.title,
        summary = task.deviceName + " · " + task.state.label,
        trailing = RowTrailing.Value(task.serial),
    )
}

/**
 * 首页的状态卡。
 *
 * 用 `Box` + `clip` + `background` 自己画，而不是 MIUIX 的 `Card`：只有这样才能在「运行中」时
 * 换成强调容器色（参照应用的首页正是用强调色卡片突出主状态），而 `Card` 的容器色要通过
 * `CardColors` 传，为一个卡片去猜它的构造参数不值得。
 */
@Composable
private fun StatCard(
    label: String,
    value: String,
    detail: String,
    accent: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val background = if (accent) colors.surfaceContainerHighest else colors.surface
    val strong = if (accent) colors.onSurfaceContainerHighest else colors.onSurface
    val weak = if (accent) colors.onSurfaceContainerHighest.copy(alpha = 0.72f) else colors.onSurfaceVariantSummary

    Column(
        modifier
            .heightIn(min = 86.dp)
            .clip(RoundedCornerShape(Dimens.CardRadius))
            .background(background)
            .clickable(onClick = onClick)
            .padding(Dimens.CardInner),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = styles.footnote1, color = weak)
        Text(value, style = styles.title3, color = strong, fontWeight = FontWeight.SemiBold)
        Text(detail, style = styles.footnote2, color = weak)
    }
}
