package cc.coauto.tailcat.shell.manager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.model.DeviceTask
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.reach.ReachabilityResult
import cc.coauto.tailcat.core.reach.ReachabilityState
import cc.coauto.tailcat.core.session.SessionManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 与 ShellScreen 统一的过渡时长。 */
private const val TRANSITION_MS = 320

/**
 * 设备列表：管理端的入口页。
 *
 * 「添加设备」用**内联展开的卡片**而不是对话框 —— 与壳那部分的设置卡一致，
 * 也省掉一个弹窗组件。
 */
@Composable
fun DeviceListScreen(
    manager: SessionManager,
    onRelocateTools: suspend () -> Unit,
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

    var adding by remember { mutableStateOf(false) }
    var temporaryMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 表单展开时，返回键先收起表单。软键盘弹着的话系统会先吃掉第一次返回，所以不会误触。
    BackHandler(enabled = adding) { adding = false }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设备", style = styles.title2, color = colors.onBackground)
        Text(
            "连到别的设备的 Tailcat 服务端。填对方手机上显示的 tc 地址即可接通，" +
                "然后就能跑 shell、装 APK。",
            style = styles.footnote1,
            color = colors.onBackgroundVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { adding = !adding; temporaryMode = false; error = null }) {
                Text(if (adding) "取消" else "添加设备")
            }
            TextButton(
                text = "临时连接",
                onClick = { adding = true; temporaryMode = true; error = null },
            )
            // 不做「有没有活动连接」的可见性判断：ownedActiveCount 不是 State，
            // 在组合里读它只会在别的状态顺带变化时才刷新，会留下一个骗人的按钮。
            // disconnectAll() 对空集合是空操作，一直摆着更简单也更诚实。
            TextButton(
                text = "全部断开",
                onClick = { scope.launch { runCatching { manager.disconnectAll() } } },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val testing = reachability.any {
                it.state == ReachabilityState.Testing || it.state == ReachabilityState.Queued
            }
            TextButton(
                text = if (testing) "测试中…" else "测试全部设备",
                enabled = !testing && allDevices.isNotEmpty(),
                // 只 ping、不建转发、不 adb connect —— 用来分辨「地址失效」和「对方没在跑服务端」
                onClick = { manager.testAllDevices() },
            )
            // 「导入地址簿」的按钮**有意不提供**。地址簿目录固定在应用私有目录
            //（filesDir/tailcat-adb），手机上没有任何流程会往里放文件，那个按钮按下去
            // 只有一种结局：「未找到 tailcat-adb 地址簿」。桌面端的对应目录是
            // tailcat-adb 命令行工具写的，手机上没有这条链路。
            //
            // 首启时 ManagerModel.startup() 仍会静默试一次导入（手动把地址簿塞进去的人能用上），
            // 但不在界面上摆一个按下去注定失败的按钮。
        }

        AnimatedVisibility(
            visible = adding,
            enter = fadeIn(tween(TRANSITION_MS)) + expandVertically(tween(TRANSITION_MS)),
            exit = fadeOut(tween(TRANSITION_MS)) + shrinkVertically(tween(TRANSITION_MS)),
        ) {
            DeviceEditorCard(
                manager = manager,
                profile = null,
                temporary = temporaryMode,
                onDone = { adding = false },
            )
        }

        error?.let { Text(it, style = styles.footnote1, color = colors.error) }
        notice?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, style = styles.footnote1, color = colors.primary, modifier = Modifier.weight(1f))
                TextButton(text = "知道了", onClick = { manager.setNotice(null) })
            }
        }

        if (profiles.isEmpty() && temporary.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("还没有设备", style = styles.body1, color = colors.onSurface)
                    Text(
                        "先在对方设备上跑 tailcat serve，把拿到的 tc 地址填进来。",
                        style = styles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        if (profiles.isNotEmpty()) {
            SmallTitle("我的设备")
            profiles.forEach { DeviceRow(manager, it, temporary = false, onOpen = onOpen) }
        }
        if (temporary.isNotEmpty()) {
            SmallTitle("临时设备")
            temporary.forEach { DeviceRow(manager, it, temporary = true, onOpen = onOpen) }
        }

        if (reachability.isNotEmpty()) {
            HorizontalDivider()
            ReachabilitySection(manager, reachability)
        }

        if (tasks.isNotEmpty()) {
            HorizontalDivider()
            TaskSection(manager, tasks)
        }

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallTitle("工具")
            Spacer(Modifier.weight(1f))
            TextButton(
                text = "重新检测",
                onClick = {
                    scope.launch {
                        // 重装应用会把二进制解压到一个新的哈希目录，这时候当场重找一遍最省事。
                        // 单独一条「工具设置」在这端没意义：二进制都随包，没有可安装的目标。
                        runCatching { onRelocateTools() }.onFailure { error = it.message }
                    }
                },
            )
        }
        ToolRow(manager, "tailcat", manager.findTool("tailcat"))
        ToolRow(manager, "adb", manager.findTool("adb"))
        Text(
            "两个二进制都随应用打包，直接跑，遇到报错不用管 PATH。",
            style = styles.footnote2,
            color = colors.onSurfaceVariantSummary,
        )
    }
}

/** 批量可达性结果。只 ping，不建转发，用来分辨「地址失效」和「对方没在跑服务端」。 */
@Composable
private fun ReachabilitySection(manager: SessionManager, results: List<ReachabilityResult>) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val reachable = results.count { it.state == ReachabilityState.Reachable }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SmallTitle("可达性")
        Spacer(Modifier.weight(1f))
        Text("$reachable / ${results.size} 可达", style = styles.footnote2, color = colors.onSurfaceVariantSummary)
    }
    results.forEach { result ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(11.dp).background(
                        when (result.state) {
                            ReachabilityState.Reachable -> Color(0xFF34C759)
                            ReachabilityState.Testing, ReachabilityState.Queued -> colors.primary
                            ReachabilityState.Cancelled -> colors.outline
                            else -> colors.error
                        },
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(result.name, style = styles.body1, color = colors.onSurface)
                    Text(
                        buildString {
                            append(result.state.label)
                            if (result.route != "—") append("  ·  ").append(result.route)
                        },
                        style = styles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                Text(result.latency, style = styles.footnote2, color = colors.onSurfaceVariantSummary)
            }
        }
    }
    TextButton(text = "停止测试", onClick = { manager.reachability.cancel() })
}
@Composable
private fun DeviceRow(
    manager: SessionManager,
    profile: DeviceProfile,
    temporary: Boolean,
    onOpen: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles

    // 每台设备订阅自己的流：某台写一行日志不会让其它行重组
    val session by manager.sessionFlow(profile.id).collectAsState()
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(profile.id) }) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateDot(session.state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (temporary) "⚡ " else "") + profile.name,
                    style = styles.body1,
                    color = colors.onSurface,
                )
                Text(
                    session.state.label + (session.serial?.let { "  ·  $it" } ?: ""),
                    style = styles.footnote2,
                    color = if (session.state == ConnectionState.Online) colors.primary
                    else colors.onSurfaceVariantSummary,
                )
            }
            TextButton(
                text = if (session.serial == null) "连接" else "断开",
                enabled = !session.state.isBusy,
                onClick = {
                    if (session.serial == null) manager.connect(profile.id)
                    else manager.disconnect(profile.id)
                },
            )
            if (temporary) {
                TextButton(
                    text = "关闭",
                    onClick = { scope.launch { runCatching { manager.removeDevice(profile.id) } } },
                )
            }
        }
    }
}

/** 状态圆点。「连接中/断开中」时做呼吸动画。 */
@Composable
fun StateDot(state: ConnectionState) {
    val colors = MiuixTheme.colorScheme
    val target = when (state) {
        ConnectionState.Disconnected -> colors.outline
        ConnectionState.Connecting, ConnectionState.Disconnecting -> colors.primary
        ConnectionState.Online -> Color(0xFF34C759)
        ConnectionState.Offline -> Color(0xFFFF9F0A)
        ConnectionState.Unauthorized -> Color(0xFFFF9F0A)
        ConnectionState.Failed -> colors.error
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

@Composable
private fun TaskSection(manager: SessionManager, tasks: List<DeviceTask>) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    var expanded by remember { mutableStateOf<String?>(null) }

    SmallTitle("任务")
    tasks.takeLast(20).reversed().forEach { task ->
        Card(modifier = Modifier.fillMaxWidth().clickable {
            expanded = if (expanded == task.id) null else task.id
        }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = styles.body1, color = colors.onSurface)
                        Text(
                            "${task.deviceName}  ·  ${task.serial}",
                            style = styles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        task.state.label,
                        style = styles.footnote2,
                        color = if (task.state == TaskState.Failed) colors.error
                        else colors.onSurfaceVariantSummary,
                    )
                    if (task.state == TaskState.Running) {
                        TextButton(text = "取消", onClick = { manager.cancelTask(task.id) })
                    }
                }
                if (expanded == task.id) {
                    Text(
                        task.output.ifEmpty { "等待命令输出…" },
                        style = styles.footnote2,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolRow(manager: SessionManager, label: String, path: String?) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (path != null) "✓" else "✗",
            style = styles.body1,
            color = if (path != null) Color(0xFF34C759) else colors.error,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = styles.body1, color = colors.onSurface)
            Text(
                path ?: "未找到",
                style = styles.footnote2,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}
