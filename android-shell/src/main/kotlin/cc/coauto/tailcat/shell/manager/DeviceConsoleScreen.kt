package cc.coauto.tailcat.shell.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.session.SessionManager
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 控制台里一行字的种类，只用来决定颜色。 */
private enum class LineKind { Command, Output, Status, Error }

private data class ConsoleLine(val text: String, val kind: LineKind)

/**
 * Shell 窗口：把「敲 adb shell 命令」和「本机任务输出」整合成一份连续的终端式日志。
 *
 * **它不是持久 shell。** 每条命令都是一次独立的 `adb shell`，所以 `cd` / `export` 这类
 * 跨命令的状态**不会保留** —— core 的进程抽象（`RunningProcess`）只有 `pid`/`isRunning`/`stop()`，
 * 没有 stdin，做不到「指着一个长驻 shell 一直写」。要那样得给进程层加 stdin 并在两端实现，
 * 是另一个量级的改动，而且没有 pty 就没有提示符与行编辑、有 pty 又得配一个终端模拟器。
 * 对「查属性 / 列包 / dumpsys / am / input」这类设备调试来说，一次性命令够用。
 *
 * 任务的输出是**增量**贴进来的：记录每个任务已消费到的长度，只追加新增的部分。
 * core 会把任务输出裁到末尾 32768 字符，一旦发生裁剪，已记录的索引就失效了 ——
 * 所以那里单独判了 `<` 的情况并重放整段，而不是让下标越界或错位。
 */
@Composable
fun DeviceConsoleScreen(
    manager: SessionManager,
    deviceId: String,
    deviceName: String,
    onBack: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles

    val session by manager.sessionFlow(deviceId).collectAsState()
    val tasks by manager.tasks.collectAsState()
    val online = session.state == ConnectionState.Online

    val lines = remember(deviceId) { mutableStateListOf<ConsoleLine>() }
    /** 每个任务已经贴进控制台的字符数。 */
    val consumed = remember(deviceId) { mutableStateMapOf<String, Int>() }
    /** 已经报过「结束」的任务。不能拿 [consumed] 的哨兵值兼职 —— 那会被当成索引又重放一遍。 */
    val finalized = remember(deviceId) { mutableStateMapOf<String, Boolean>() }
    val history = remember(deviceId) { mutableStateListOf<String>() }
    var historyCursor by remember(deviceId) { mutableIntStateOf(-1) }
    var input by remember(deviceId) { mutableStateOf("") }
    val listState = rememberLazyListState()

    fun push(text: String, kind: LineKind = LineKind.Output) {
        // 逐行放进来，滚动区才能按行回收；顺带把 Windows 的 \r\n 归一化
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if (line.isNotEmpty()) lines.add(ConsoleLine(line, kind))
        }
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    // 任务的输出增量贴进来。观察的是整个 tasks 流，所以要按设备过滤。
    LaunchedEffect(tasks) {
        for (task in tasks.filter { it.deviceId == deviceId }) {
            val seen = consumed[task.id] ?: 0
            val text = task.output
            when {
                text.length > seen -> {
                    push(text.substring(seen))
                    consumed[task.id] = text.length
                }
                // 前端被裁掉了（超过 core 的 32768 上限）→ 旧索引失效，说一声再整段重放
                text.length < seen -> {
                    push("…（输出超过上限，前面的已被截断）", LineKind.Status)
                    push(text)
                    consumed[task.id] = text.length
                }
            }
            if (task.state != TaskState.Running && finalized[task.id] != true) {
                push("${task.state.label} — ${task.title}", LineKind.Status)
                finalized[task.id] = true
            }
        }
    }

    // 新内容进来时贴着底走 —— 但用户往上翻看历史时不要把他拽回去
    LaunchedEffect(lines.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        val visibleEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (last <= visibleEnd + 2) listState.scrollToItem(maxOf(0, lines.size - 1))
    }

    fun run(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || !online) return
        push("$ $trimmed", LineKind.Command)
        history.remove(trimmed)
        history.add(trimmed)
        historyCursor = history.size
        runCatching { manager.beginShell(deviceId, trimmed) }
            .onFailure { push(it.message ?: "执行失败", LineKind.Error) }
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "‹ 设备", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text("Shell · $deviceName", style = styles.title3, color = colors.onBackground)
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxSize().padding(10.dp)) {
                if (lines.isEmpty()) {
                    Text(
                        if (online) "在下面输入命令，输出会跟本机任务（装包 / 上传 / 下载）一起按顺序流进这里。"
                        else "设备不在线，先回设备页连接。",
                        style = styles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines.size) { index ->
                        val line = lines[index]
                        Text(
                            line.text,
                            style = styles.footnote2,
                            fontFamily = FontFamily.Monospace,
                            color = when (line.kind) {
                                LineKind.Command -> colors.primary
                                LineKind.Status -> colors.onSurfaceVariantSummary
                                LineKind.Error -> colors.error
                                LineKind.Output -> colors.onSurface
                            },
                        )
                    }
                }
            }
        }

        // 常用命令：手敲 dumpsys 这类长串太费劲
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            COMMON_COMMANDS.forEach { (label, command) ->
                TextButton(text = label, enabled = online, onClick = { run(command) })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    label = if (online) "adb shell 命令" else "设备不在线",
                    enabled = online,
                )
            }
            Button(enabled = online, onClick = { run(input) }) { Text("运行") }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 历史：手机上多半是软键盘，没有上下键，所以给按钮而不是只靠按键
            TextButton(
                text = "上一条",
                enabled = history.isNotEmpty(),
                onClick = {
                    if (history.isEmpty()) return@TextButton
                    historyCursor = (historyCursor - 1).coerceAtLeast(0)
                    input = history[historyCursor]
                },
            )
            TextButton(
                text = "清空",
                enabled = lines.isNotEmpty(),
                onClick = { lines.clear() },
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${lines.size} 行",
                style = styles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
        }
    }
}

/** 控制台的滚动区上限。core 那边任务输出本身也有上限，这里只防界面无限增长。 */
private const val MAX_LINES = 2_000

private val COMMON_COMMANDS = listOf(
    "版本" to "getprop ro.build.version.release",
    "型号" to "getprop ro.product.model",
    "包列表" to "pm list packages -3",
    "当前界面" to "dumpsys window | grep mCurrentFocus",
    "屏幕" to "wm size",
    "温度" to "cat /sys/class/thermal/thermal_zone0/temp",
)
