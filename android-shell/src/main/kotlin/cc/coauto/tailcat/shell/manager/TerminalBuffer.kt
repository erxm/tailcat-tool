package cc.coauto.tailcat.shell.manager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot

/**
 * 终端屏幕缓冲区：把字节流里那套控制字符翻译成「一行一行的带颜色文本」。
 *
 * 为什么需要它：输出里会夹 ANSI 转义（`ls --color`、`grep --color` 等），直接当文本贴出来
 * 会看到 `[01;32m` 之类的碎片，所以这里做一次最小解释。
 *
 * **回显是本地做的**，不是远端。实测（mix5 / Android 17）：
 *
 * ```
 * $ printf 'tty; echo "flags=$-"\n' | adb shell
 * not a tty
 * flags=lsh            ← 没有 i，说明 shell 不是交互模式
 * ```
 *
 * 也就是说 `adb shell` 的 stdin 即使是管道也**不分配 pty**，而回显、行编辑、Ctrl-C 都是
 * 终端驱动（tty）的行为 —— 没有 tty 就没有。设备上也没有 `script` 这类能自己开 pty 的工具
 * （`/system/bin` 里只有 setsid / stty / sh）。所以这里用 [echo] 在本地把输入打出来。
 *
 * 会话仍然是**一条长驻的 shell 进程**，所以 `cd` / `export` / 别名照样跨命令保留。
 *
 * 支持范围（够用即可，不追求 VT100 全兼容）：
 * - `\n` 换行、`\r` 回车（回到行首覆盖重写，进度条就靠它）、`\b` 退格、`\t` 制表
 * - `ESC [ ... m` 选显色（SGR）：0/1/22/39、30–37、90–97、以及 38/48 扩展色的**跳过**
 * - `ESC [ K` 擦除本行、`ESC [ 2 J` 清屏
 * - `ESC ] ... BEL` / `ESC \`（OSC，例如设置窗口标题）整体丢弃
 * - 其它 CSI（光标定位、上/下移等）**忽略** —— 于是 `vi`、`top -n 1` 这类全屏程序不会正确排版。
 *   这是有意取舍：手机上用终端主要是查属性、列包、`dumpsys`、`am`/`input`，全屏程序的收益远小于代价。
 *
 * 线程模型：解析在**读线程**里进行，状态写入 Compose 快照（[Snapshot.withMutableSnapshot] 批量提交），
 * 界面只管读 [lines] / [current]。
 */
internal class TerminalBuffer(private val maxLines: Int = 2_000) {

    /** 一段同色文字。[fg] 是 ANSI 调色板下标 0–15，null 表示用默认前景色。 */
    internal data class Span(val text: String, val fg: Int?, val bold: Boolean)

    /** 已经换行结束的一行。 */
    internal data class Line(val spans: List<Span>) {
        val text: String = spans.joinToString("") { it.text }
    }

    /** 已提交的行（不含正在输入的那一行）。 */
    internal val lines = mutableStateListOf<Line>()

    /** 光标所在行：提示符、正在回显的输入都在这里，还没换行。 */
    internal var current by mutableStateOf(Line(emptyList()))
        private set

    // ——— 解析器状态（跨 chunk 保留，转义序列可能被读边界切断） ———
    private val cur = ArrayList<Span>()
    private val pending = StringBuilder()
    private var fg: Int? = null
    private var bold = false
    private var crPending = false

    private var esc = false
    private var csi = false
    private val params = StringBuilder()
    private var osc = false
    private var oscEsc = false

    /** 喂一段文本（可以任意切断，控制序列跨段也没关系）。 */
    @Synchronized
    internal fun feed(chunk: String) {
        if (chunk.isEmpty()) return
        Snapshot.withMutableSnapshot {
            for (c in chunk) handle(c)
            flushPending()
            current = Line(cur.toList())
        }
    }

    /** 追加一行「本应用」的提示（任务状态之类），不和 shell 的输出混在同一行。 */
    internal fun notice(text: String) = appendOwnLine(text, color = null)


    /** 由本端独立成行写一行字，颜色只影响这一行。 */
    @Synchronized
    private fun appendOwnLine(text: String, color: Int?) {
        Snapshot.withMutableSnapshot {
            flushPending()
            if (cur.isNotEmpty()) commit()
            val previousFg = fg
            val previousBold = bold
            fg = color
            bold = false
            pending.append(text)
            flushPending()
            commit()
            fg = previousFg
            bold = previousBold
            current = Line(cur.toList())
        }
    }

    /** 清屏（界面的「清空」按钮，只清本地，不给 shell 发 clear）。 */
    @Synchronized
    internal fun clear() {
        Snapshot.withMutableSnapshot {
            lines.clear()
            cur.clear()
            pending.clear()
            crPending = false
            current = Line(emptyList())
        }
    }

    private fun handle(c: Char) {
        if (osc) {
            if (oscEsc) {
                oscEsc = false
                if (c == '\\') osc = false
            } else if (c == '\u0007') {
                osc = false
            } else if (c == '\u001B') {
                oscEsc = true
            }
            return
        }
        if (csi) {
            if (c in '@'..'~') {
                csi = false
                applyCsi(c, params.toString())
                params.clear()
            } else {
                params.append(c)
            }
            return
        }
        if (esc) {
            esc = false
            when (c) {
                '[' -> {
                    csi = true
                    params.clear()
                }
                ']' -> {
                    osc = true
                    oscEsc = false
                }
                // `ESC (` / `ESC )` 之类还会再吃掉一个字符；不常见，忽略即可
                else -> Unit
            }
            return
        }
        when (c) {
            '\u001B' -> esc = true
            '\n' -> {
                flushPending()
                commit()
                crPending = false
            }
            '\r' -> {
                flushPending()
                crPending = true
            }
            '\b' -> {
                flushPending()
                crPending = false
                backspace()
            }
            '\t' -> {
                flushPending()
                appendText("    ")
            }
            '\u0007' -> Unit // 响铃
            else -> if (c >= ' ') pending.append(c) // 其余控制字符直接丢
        }
    }

    private fun applyCsi(final: Char, raw: String) {
        // `?25l`、`>0c` 这类私有模式：去掉前缀再取数字
        val body = raw.trimStart('?', '>', '!', '<', '=')
        val codes = if (body.isEmpty()) emptyList() else body.split(';').mapNotNull { it.toIntOrNull() }
        when (final) {
            'm' -> applySgr(if (codes.isEmpty()) listOf(0) else codes)
            'K' -> {
                flushPending()
                cur.clear()
            }
            'J' -> if (codes.firstOrNull() == 2) {
                flushPending()
                cur.clear()
                lines.clear()
            }
            // 'H'/'f' 光标定位、'A'/'B' 上下移、'h'/'l' 模式开关 —— 一律忽略
            else -> Unit
        }
    }

    private fun applySgr(codes: List<Int>) {
        flushPending() // 先落地：之前的字用旧样式
        var i = 0
        while (i < codes.size) {
            when (val n = codes[i]) {
                0 -> {
                    fg = null
                    bold = false
                }
                1 -> bold = true
                22 -> bold = false
                39 -> fg = null
                in 30..37 -> fg = n - 30
                in 90..97 -> fg = n - 90 + 8
                // 38/48 = 扩展前景/背景：`;5;n` 或 `;2;r;g;b`，参数直接跳过
                38, 48 -> i += when (codes.getOrNull(i + 1)) {
                    5 -> 2
                    2 -> 4
                    else -> 0
                }
                else -> Unit
            }
            i++
        }
    }

    private fun backspace() {
        if (cur.isEmpty()) return
        val last = cur[cur.size - 1]
        if (last.text.length <= 1) {
            cur.removeAt(cur.size - 1)
        } else {
            cur[cur.size - 1] = last.copy(text = last.text.dropLast(1))
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        // 回车之后再出字 = 从行首覆盖重写
        if (crPending) {
            crPending = false
            cur.clear()
        }
        val last = cur.lastOrNull()
        if (last != null && last.fg == fg && last.bold == bold) {
            cur[cur.size - 1] = last.copy(text = last.text + text)
        } else {
            cur.add(Span(text, fg, bold))
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        appendText(pending.toString())
        pending.setLength(0)
    }

    private fun commit() {
        flushPending()
        lines.add(Line(if (cur.isEmpty()) emptyList() else cur.toList()))
        cur.clear()
        while (lines.size > maxLines) lines.removeAt(0)
    }
}
