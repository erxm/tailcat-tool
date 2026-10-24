package cc.coauto.tailcat.shell.manager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.model.DeviceTask
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.reach.ReachabilityResult
import cc.coauto.tailcat.core.reach.ReachabilityState
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.shell.ui.BigTitleBar
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.GroupCard
import cc.coauto.tailcat.shell.ui.RowTrailing
import cc.coauto.tailcat.shell.ui.SectionHeader
import cc.coauto.tailcat.shell.ui.SettingRow
import cc.coauto.tailcat.shell.ui.StateDot
import cc.coauto.tailcat.shell.ui.TailcatIcons
import cc.coauto.tailcat.shell.ui.statusColors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 与 AppRoot 统一的过渡时长。 */
private const val TRANSITION_MS = 320

/** 添加表单展开在哪一行下面。 */
private enum class AddMode { Persistent, Temporary }

/**
 * 设备列表：管理端的入口页。
 *
 * 形态与参照应用一致 —— **一卡多行**：一台设备一行，行内放状态点、名称、状态摘要与
 * 「连接/断开」，点整行进详情。之前是「一台设备一张卡 + 上下两排按钮」，按钮占了视线，
 * 设备本身反而被压小；现在操作收进「操作」分组，列表只剩数据。
 *
 * 「工具」那一组已经搬到「设置」页；这里不再出现路径之类的运维细节。
 */
@Composable
fun DeviceListScreen(
    manager: SessionManager,
    onOpen: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles

    val profiles by manager.profiles.collectAsState()
    val temporary by manager.temporaryProfiles.collectAsState()
    val tasks by manager.tasks.collectAsState()
    val notice by manager.notice.collectAsState()
    val reachability by manager.reachability.results.collectAsState()
    val scope = rememberCoroutineScope()

    val allDevices = profiles + temporary
    // 表单挂在**哪一行**下面：字符串是模式名，点同一行再点一次即收起。
    var adding by remember { mutableStateOf<AddMode?>(null) }

    // 表单展开时，返回键先收起表单。软键盘弹着的话系统会先吃掉第一次返回，所以不会误触。
    BackHandler(enabled = adding != null) { adding = null }

    val testing = reachability.any {
        it.state == ReachabilityState.Testing || it.state == ReachabilityState.Queued
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Dimens.PagePadding,
                end = Dimens.PagePadding,
                bottom = Dimens.SectionGap,
            ),
    ) {
        BigTitleBar("设备")
        Text(
            "连到别的设备的 Tailcat 服务端。填对方手机上显示的 tc 地址即可接通，然后就能跑 shell、装 APK。",
            modifier = Modifier.padding(
                start = Dimens.CardInner,
                end = Dimens.CardInner,
                bottom = Dimens.TitleToContent,
            ),
            style = styles.footnote1,
            color = colors.onBackgroundVariant,
        )

        GroupCard {
            SettingRow(
                title = "添加设备",
                summary = "填写 tc 地址，保存后自动连接",
                icon = ImageVector.vectorResource(TailcatIcons.Install),
                trailing = RowTrailing.Chevron,
                onClick = { adding = if (adding == AddMode.Persistent) null else AddMode.Persistent },
            )
            // 表单紧贴触发它的那一行展开，而不是掉到整组的末尾 ——
            // 否则点了「临时连接」却要往上看一大截才知道展开了什么。
            if (adding == AddMode.Persistent) {
                DeviceEditorCard(manager, profile = null, temporary = false, onDone = { adding = null }, embedded = true)
            }
            SettingRow(
                title = "临时连接",
                summary = "不写入配置，关掉应用即消失",
                icon = ImageVector.vectorResource(TailcatIcons.Link),
                trailing = RowTrailing.Chevron,
                onClick = { adding = if (adding == AddMode.Temporary) null else AddMode.Temporary },
            )
            if (adding == AddMode.Temporary) {
                DeviceEditorCard(manager, profile = null, temporary = true, onDone = { adding = null }, embedded = true)
            }
            SettingRow(
                title = "测试全部设备",
                summary = "只 ping，不建转发、不 connect",
                icon = ImageVector.vectorResource(TailcatIcons.Refresh),
                enabled = !testing && allDevices.isNotEmpty(),
                trailing = if (testing) RowTrailing.Value("测试中…") else RowTrailing.Chevron,
                onClick = { manager.testAllDevices() },
            )
            SettingRow(
                title = "全部断开",
                summary = "关闭所有已连接的转发通道",
                icon = ImageVector.vectorResource(TailcatIcons.Server),
                trailing = RowTrailing.Chevron,
                onClick = { scope.launch { runCatching { manager.disconnectAll() } } },
            )
        }

        notice?.let { message ->
            SectionHeader("提示")
            GroupCard {
                SettingRow(
                    title = message,
                    trailing = RowTrailing.Action("知道了") { manager.setNotice(null) },
                )
            }
        }

        SectionHeader("我的设备")
        GroupCard {
            if (profiles.isEmpty()) {
                SettingRow(
                    title = "还没有设备",
                    summary = "先在对方设备上跑 tailcat serve，把拿到的 tc 地址填进来。",
                )
            } else {
                profiles.forEach { profile -> DeviceRow(manager, profile, temporary = false, onOpen = onOpen) }
            }
        }

        if (temporary.isNotEmpty()) {
            SectionHeader("临时设备")
            GroupCard {
                temporary.forEach { profile -> DeviceRow(manager, profile, temporary = true, onOpen = onOpen) }
            }
        }

        if (reachability.isNotEmpty()) {
            val reachable = reachability.count { it.state == ReachabilityState.Reachable }
            SectionHeader("可达性 · $reachable/${reachability.size} 可达")
            GroupCard {
                reachability.forEach { result -> ReachabilityRow(result) }
                if (testing) {
                    SettingRow(
                        title = "停止测试",
                        trailing = RowTrailing.Action("停止") { manager.reachability.cancel() },
                    )
                }
            }
        }

        if (tasks.isNotEmpty()) {
            SectionHeader("任务")
            GroupCard {
                TaskRows(manager, tasks)
            }
        }
    }
}

/** 一台设备一行：状态点 + 名称 + 状态摘要，尾部「连接/断开」（临时设备再加「关闭」）。 */
@Composable
private fun DeviceRow(
    manager: SessionManager,
    profile: DeviceProfile,
    temporary: Boolean,
    onOpen: (String) -> Unit,
) {
    // 每台设备订阅自己的流：某台写一行日志不会让其它行重组
    val session by manager.sessionFlow(profile.id).collectAsState()
    val scope = rememberCoroutineScope()

    SettingRow(
        title = (if (temporary) "临时 · " else "") + profile.name,
        summary = session.state.label + (session.serial?.let { "  ·  $it" } ?: ""),
        leading = { StateDot(session.state) },
        onClick = { onOpen(profile.id) },
        trailingContent = {
            TextButton(
                text = if (session.serial == null) "连接" else "断开",
                enabled = !session.state.isBusy,
                onClick = {
                    if (session.serial == null) manager.connect(profile.id) else manager.disconnect(profile.id)
                },
            )
            if (temporary) {
                TextButton(
                    text = "关闭",
                    onClick = { scope.launch { runCatching { manager.removeDevice(profile.id) } } },
                )
            }
        },
    )
}

@Composable
private fun ReachabilityRow(result: ReachabilityResult) {
    val colors = MiuixTheme.colorScheme
    val status = statusColors
    val dot = when (result.state) {
        ReachabilityState.Reachable -> status.running
        ReachabilityState.Testing, ReachabilityState.Queued -> colors.primary
        ReachabilityState.Cancelled -> colors.outline
        else -> status.error
    }
    SettingRow(
        title = result.name,
        summary = buildString {
            append(result.state.label)
            if (result.route != "—") append("  ·  ").append(result.route)
        },
        leading = { Box(Modifier.size(11.dp).background(dot, CircleShape)) },
        trailing = RowTrailing.Value(result.latency),
    )
}

/** 任务：点一行展开输出；运行中的任务右侧可直接取消。 */
@Composable
private fun TaskRows(manager: SessionManager, tasks: List<DeviceTask>) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    var expanded by remember { mutableStateOf<String?>(null) }

    tasks.takeLast(20).reversed().forEach { task ->
        val isExpanded = expanded == task.id
        SettingRow(
            title = task.title,
            summary = task.deviceName + "  ·  " + task.state.label,
            onClick = { expanded = if (isExpanded) null else task.id },
            trailing = if (task.state == TaskState.Running) {
                RowTrailing.Action("取消") { manager.cancelTask(task.id) }
            } else {
                RowTrailing.Chevron
            },
        )
        if (isExpanded && task.output.isNotEmpty()) {
            Text(
                text = task.output.takeLast(4000),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Dimens.CardInner, end = Dimens.CardInner, bottom = Dimens.RowVertical),
                style = styles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}
