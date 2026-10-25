package cc.coauto.tailcat.shell.manager

import androidx.compose.foundation.horizontalScroll
import cc.coauto.tailcat.shell.ui.GroupCard
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.shell.platform.adbConsoleBuilder
import cc.coauto.tailcat.shell.platform.AppPreferences
import cc.coauto.tailcat.shell.ui.Chip
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.statusColors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Shell 窗口：一条**带 pty 的**长驻 `adb shell`。
 *
 * 关键在 `adb shell -tt`（见 `adbConsoleBuilder`）：强制分配伪终端之后，远端 shell 才会
 * 打印提示符、**由终端驱动回显输入**、把 `^C` 解释成中断 —— 也就是真终端该有的行为。
 * 不带 `-tt` 时它是非交互模式：没有提示符、不回显、`Ctrl-C` 只是个普通字节。
 *
 * 输入方式仍是「整行发送」（手机上软键盘编辑一行，比在终端里逐字编辑好用）：
 * 回车/发送把整行写进 pty → pty 回显 → shell 执行 → 输出流回同一个屏幕。
 *
 * ⚠️ 已知边界：`Tab` 补全需要**逐字发送**（补全时远端会重画当前行），当前是整行模式，
 * 所以这里不提供 Tab 按钮，免得做出一个按了没反应的键。
 */
@Composable
internal fun DeviceConsoleScreen(
    manager: SessionManager,
    deviceId: String,
    deviceName: String,
    onBack: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    // 深浅色与字号都跟设置页走（应用内主题开关要能在终端里看到效果）
    val dark = AppPreferences.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val density = LocalDensity.current
    // 终端正文：等宽 + 与控件同一档字号（footnote1）。整页只有三档字：
    // 标题 title3 / 正文与控件 12sp / 次要说明 11sp —— 之前是 11sp 正文配默认字号按钮，
    // 忽大忽小才是「观感割裂」的根源。
    val terminalStyle = styles.footnote1.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = AppPreferences.value.terminalFontSize.sp,
    )

    val session by manager.sessionFlow(deviceId).collectAsState()
    val tasks by manager.tasks.collectAsState()
    val online = session.state == ConnectionState.Online
    val adbPath = remember(manager) { manager.findTool("adb") }
    val serial = session.serial?.takeIf { online && it.isNotEmpty() }

    val buffer = remember(deviceId) { TerminalBuffer() }
    val shellHolder = remember(deviceId) { mutableStateOf<InteractiveShell?>(null) }
    var restartToken by remember(deviceId) { mutableIntStateOf(0) }
    var input by remember(deviceId) { mutableStateOf("") }
    val history = remember(deviceId) { mutableStateListOf<String>() }
    var historyCursor by remember(deviceId) { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 终端区的行列数：pty 默认是 0×0，top / less 这类会算错，所以按实际尺寸设一次
    var termRows by remember(deviceId) { mutableIntStateOf(0) }
    var termCols by remember(deviceId) { mutableIntStateOf(0) }

    // 快捷命令：新建 / 删除都直接落盘
    val shortcutStore = remember(context) { ShellShortcutStore(context) }
    var shortcuts by remember { mutableStateOf(shortcutStore.load()) }
    var editingShortcuts by remember { mutableStateOf(false) }
    var addingShortcut by remember { mutableStateOf(false) }
    var newLabel by remember { mutableStateOf("") }
    var newCommand by remember { mutableStateOf("") }

    val shell = shellHolder.value
    val running = shell?.isRunning == true

    // 在线就起会话；掉线、换设备、点「重连」都重来。缓冲区在作用域外，重连不会抹掉屏幕。
    //
    // 两个坑：
    //  - **尺寸不能进 key**。它随布局/键盘变化，进了 key 就会把会话反复重启。
    //    这里在协程里等第一次测量结果，拿不到就用兜底值。
    //  - **start() 必须放在 try 里面**。放在外面时，如果协程在 start() 里被取消，
    //    finally 不会执行 → 进程泄漏（实测出现过两个 pty 会话同时往一块屏幕写）。
    LaunchedEffect(online, serial, adbPath, restartToken) {
        val path = adbPath ?: return@LaunchedEffect
        val target = serial ?: return@LaunchedEffect
        withTimeoutOrNull(1_500) {
            snapshotFlow { termRows > 0 && termCols > 0 }.first { it }
        }
        val rows = termRows.takeIf { it > 0 } ?: 40
        val cols = termCols.takeIf { it > 0 } ?: 80
        val sessionShell = InteractiveShell(buffer) {
            adbConsoleBuilder(context, path, target, rows, cols).start()
        }
        shellHolder.value = sessionShell
        try {
            sessionShell.start()
            awaitCancellation()
        } finally {
            sessionShell.stop()
            if (shellHolder.value === sessionShell) shellHolder.value = null
        }
    }

    // 本机任务输出串进同一个屏幕。按任务记录已消费长度，只贴增量；
    // core 会把输出裁到末尾 32768 字符，长度变短说明被裁过，整段重放。
    val consumed = remember(deviceId) { mutableStateMapOf<String, Int>() }
    val finalized = remember(deviceId) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(tasks) {
        for (task in tasks.filter { it.deviceId == deviceId }) {
            val seen = consumed[task.id] ?: 0
            val text = task.output
            when {
                text.length > seen -> {
                    buffer.feed(text.substring(seen))
                    consumed[task.id] = text.length
                }
                text.length < seen -> {
                    buffer.feed("\n[本机] …（输出超过上限，前面的已被截断）\n$text")
                    consumed[task.id] = text.length
                }
            }
            if (task.state != TaskState.Running && finalized[task.id] != true) {
                buffer.feed("\n[本机] ${task.state.label} — ${task.title}\n")
                finalized[task.id] = true
            }
        }
    }

    // 新内容进来时贴着底走；用户往上翻看历史时就别把他拽回去
    val lineCount = buffer.lines.size
    val currentLength = buffer.current.text.length
    LaunchedEffect(lineCount, currentLength) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lineCount - lastVisible <= 2) listState.scrollToItem(lineCount)
    }

    /** 整行发给远端。pty 会回显这一行，所以本地不再重复打印。空行也发 —— 等于回车。 */
    fun send(line: String) {
        val target = shellHolder.value ?: return
        if (!target.isRunning) return
        val trimmed = line.trimEnd()
        if (trimmed.isNotBlank()) {
            history.remove(trimmed)
            history.add(trimmed)
            historyCursor = history.size
        }
        scope.launch { target.send(trimmed + "\n") }
        input = ""
    }

    /** 控制字符原样送过去（Ctrl-C / Ctrl-D / Ctrl-L / Esc），不补换行。 */
    fun sendRaw(bytes: String) {
        val target = shellHolder.value ?: return
        if (!target.isRunning) return
        scope.launch { target.send(bytes) }
    }

    fun persist(next: List<ShellShortcut>) {
        shortcuts = next
        shortcutStore.save(next)
    }

    Column(Modifier.fillMaxSize().imePadding()) {

        // ——— 标题栏 ———
        Row(
            Modifier.fillMaxWidth()
                .padding(start = Dimens.CardGap, end = Dimens.CardGap, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "‹ 设备", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Shell · $deviceName",
                    style = styles.title3,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    describeShell(shell, online, lineCount),
                    style = styles.footnote2,
                    color = if (online && running) statusColors.running else colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                text = "重连",
                enabled = online && adbPath != null,
                onClick = { restartToken++ },
            )
        }

        // ——— 终端 ———
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .padding(horizontal = Dimens.CardGap, vertical = 4.dp),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .onSizeChanged { size ->
                        // 行列数按同一套字号推：等宽字符宽约 0.6em，行高约 1.45em
                        val fontSizePx = with(density) { terminalStyle.fontSize.toPx() }
                        val cols = (size.width / (fontSizePx * 0.6f)).toInt().coerceIn(20, 400)
                        val rows = (size.height / (fontSizePx * 1.45f)).toInt().coerceIn(6, 300)
                        if (cols != termCols) termCols = cols
                        if (rows != termRows) termRows = rows
                    },
            ) {
                if (lineCount == 0 && currentLength == 0) {
                    Text(
                        if (online) "正在建立 pty 会话…" else "设备不在线，先回设备页连接。",
                        style = terminalStyle,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lineCount + 1) { index ->
                        val line = if (index < lineCount) buffer.lines[index] else buffer.current
                        Text(
                            line.annotated(dark),
                            style = terminalStyle,
                            color = colors.onSurface,
                        )
                    }
                }
            }
        }

        // ——— 控制区：快捷命令 / 功能键 / 输入行 —— 同一块面板、同一套左右边距与间距 ———
        Column(
            Modifier.fillMaxWidth()
                .padding(start = Dimens.CardGap, end = Dimens.CardGap, top = 2.dp, bottom = Dimens.CardGap),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 快捷命令（可自定义）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(
                    text = if (editingShortcuts) "完成" else "编辑",
                    selected = editingShortcuts,
                    onClick = {
                        editingShortcuts = !editingShortcuts
                        if (!editingShortcuts) addingShortcut = false
                    },
                )
                shortcuts.forEach { shortcut ->
                    Chip(
                        text = if (editingShortcuts) "${shortcut.label}  ✕" else shortcut.label,
                        enabled = if (editingShortcuts) true else running,
                        onClick = {
                            if (editingShortcuts) persist(shortcuts - shortcut) else send(shortcut.command)
                        },
                    )
                }
                if (editingShortcuts) {
                    Chip(
                        text = "＋ 新建",
                        selected = true,
                        onClick = { addingShortcut = !addingShortcut },
                    )
                }
            }

            if (addingShortcut) {
                GroupCard {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        TextField(
                            value = newLabel,
                            onValueChange = { newLabel = it },
                            label = "名称（显示在按钮上，可留空）",
                        )
                        Spacer(Modifier.height(6.dp))
                        TextField(
                            value = newCommand,
                            onValueChange = { newCommand = it },
                            label = "命令（原样发给设备端的 shell）",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Chip(
                                text = "保存",
                                selected = true,
                                enabled = newCommand.isNotBlank(),
                                onClick = {
                                    val command = newCommand.trim()
                                    val label = newLabel.trim().ifEmpty {
                                        command.substringBefore(' ').take(12)
                                    }
                                    persist(shortcuts + ShellShortcut(label, command))
                                    newLabel = ""
                                    newCommand = ""
                                    addingShortcut = false
                                },
                            )
                            Chip(
                                text = "取消",
                                onClick = {
                                    newLabel = ""
                                    newCommand = ""
                                    addingShortcut = false
                                },
                            )
                        }
                    }
                }
            }

            // 功能键：没有物理键盘，这些键只能靠按钮发原始字节（有 pty 之后才真的有语义）
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip("Ctrl-C", enabled = running) { sendRaw(CTRL_C) }
                Chip("Ctrl-D", enabled = running) { sendRaw(CTRL_D) }
                Chip("Ctrl-L", enabled = running) { sendRaw(CTRL_L) }
                Chip("Esc", enabled = running) { sendRaw(ESC) }
                Chip("↑", enabled = history.isNotEmpty()) {
                    if (history.isEmpty()) return@Chip
                    historyCursor = (historyCursor - 1).coerceAtLeast(0)
                    input = history[historyCursor]
                }
                Chip("↓", enabled = history.isNotEmpty()) {
                    if (history.isEmpty()) return@Chip
                    historyCursor = (historyCursor + 1).coerceAtMost(history.size)
                    input = if (historyCursor >= history.size) "" else history[historyCursor]
                }
                Chip("清屏", enabled = lineCount > 0) { buffer.clear() }
            }

            // 输入行
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        label = if (running) "输入命令，回车发送" else "会话未运行",
                        enabled = running,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send(input) }),
                    )
                }
                Button(enabled = running, onClick = { send(input) }) { Text("发送") }
            }
        }
    }
}

/** 会话状态 → 一行说明。 */
private fun describeShell(shell: InteractiveShell?, online: Boolean, lines: Int): String = when {
    !online -> "设备不在线"
    shell == null -> "正在建立会话…"
    shell.state is ShellState.Running -> "会话运行中 · 真实 pty · $lines 行"
    shell.state is ShellState.Exited -> "会话已结束（退出码 ${(shell.state as ShellState.Exited).code}），点「重连」"
    shell.state is ShellState.Failed -> "启动失败：${(shell.state as ShellState.Failed).reason}"
    else -> "未启动"
}

/** 一行终端文本 → 带颜色的 [AnnotatedString]。 */
private fun TerminalBuffer.Line.annotated(dark: Boolean): AnnotatedString = buildAnnotatedString {
    if (spans.isEmpty()) {
        append(" ")
        return@buildAnnotatedString
    }
    spans.forEach { span ->
        withStyle(SpanStyle(color = ansiColor(span.fg, span.bold, dark))) { append(span.text) }
    }
}

/**
 * ANSI 16 色。
 *
 * 只做 16 色：提示符、`ls --color`、`grep --color` 基本都在这个范围；
 * `38;5;n` / `38;2;r;g;b` 这类扩展色在解析阶段就被跳过（保持默认前景色）。
 */
private fun ansiColor(fg: Int?, bold: Boolean, dark: Boolean): Color {
    if (fg == null) return Color.Unspecified
    // 真终端里「粗体 + 基本色」= 对应的亮色
    val index = if (bold && fg < 8) fg + 8 else fg
    return Color(if (dark) ANSI_DARK[index] else ANSI_LIGHT[index])
}

/** 控制字符常量，写成转义而不是裸字符，免得编辑器/补丁链路把它们吃掉。 */
private const val CTRL_C = "\u0003"
private const val CTRL_D = "\u0004"
private const val CTRL_L = "\u000C"
private const val ESC = "\u001B"

private val ANSI_DARK = intArrayOf(
    0xFF7F7F7F.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFD19A66.toInt(),
    0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFDCDCDC.toInt(),
    0xFF9AA0A6.toInt(), 0xFFFF8A80.toInt(), 0xFFB5E890.toInt(), 0xFFFFD479.toInt(),
    0xFF8CC4FF.toInt(), 0xFFE2A6F2.toInt(), 0xFF8FE3EC.toInt(), 0xFFFFFFFF.toInt(),
)

private val ANSI_LIGHT = intArrayOf(
    0xFF2E3339.toInt(), 0xFFC0392B.toInt(), 0xFF1E8449.toInt(), 0xFF9A6700.toInt(),
    0xFF1F5FA9.toInt(), 0xFF8E44AD.toInt(), 0xFF0E7490.toInt(), 0xFF5B6066.toInt(),
    0xFF7D8189.toInt(), 0xFFE74C3C.toInt(), 0xFF27AE60.toInt(), 0xFFB8860B.toInt(),
    0xFF3B82F6.toInt(), 0xFFA855F7.toInt(), 0xFF06B6D4.toInt(), 0xFF2E3339.toInt(),
)
