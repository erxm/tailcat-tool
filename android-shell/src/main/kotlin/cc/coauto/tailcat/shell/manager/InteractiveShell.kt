package cc.coauto.tailcat.shell.manager

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 交互式 shell 的连接状态。 */
internal sealed interface ShellState {
    /** 还没有启动，或已经被主动停掉。 */
    data object Idle : ShellState

    /** 进程在跑，可以写。 */
    data object Running : ShellState

    /** 对端自己退出了（`exit`、设备掉线、adbd 重启……）。 */
    data class Exited(val code: Int) : ShellState

    /** 起不来（找不到 adb、进程被系统拒绝等）。 */
    data class Failed(val reason: String) : ShellState
}

/**
 * 一条**长驻**的 `adb shell` 会话。
 *
 * 和之前「一条命令起一个进程」的区别：`adb shell` **不带命令**时，adbd 起的是一个
 * 常驻的 `sh`，stdin/stdout 就是这条 socket —— 所以 `cd` / `export` / 别名这类
 * **跨命令的状态会保留**，行为跟真终端一致：
 *
 * ```
 * adb -s 127.0.0.1:5556 shell          ← 起一次，一直活着
 *   ↑ stdin（写命令）   ↓ stdout（输出）
 * ```
 *
 * **pty 是这条会话的关键**（`adb shell -tt`，见 `adbConsoleBuilder`）：有了伪终端，
 * 远端 shell 才是交互模式 —— 打印提示符、由终端驱动回显输入、把 `^C` 解释成中断。
 * 不带 `-tt` 时实测拿到的是非交互 shell（`tty` 答 `not a tty`、`$-` 是 `lsh`），
 * 没有提示符、不回显、Ctrl-C 只是个普通字节。
 *
 * | 能力 | 谁负责 |
 * |---|---|
 * | 回显 / 行编辑 / Ctrl-C / Ctrl-D / Tab 补全 | 设备端 pty + shell |
 * | `cd` / `export` 跨命令保留 | 远端同一个 shell 进程 |
 *
 * 客户端这边只做三件事：把整行写进 stdin、把 pty 输出翻译成屏幕、收尾时结束进程。
 *
 * 收尾：destroy 掉本地 adb 客户端 → socket 关 → 远端 shell 读到 EOF 自己退出，
 * 不会在设备上留进程（`adb shell` 只有一个会话进程，不是守护进程）。
 */
internal class InteractiveShell(
    val buffer: TerminalBuffer,
    /**
     * 起进程的动作。**必须由平台层提供**（`adbConsoleBuilder`）：
     * 裸 `ProcessBuilder` 缺 `HOME` / `LD_LIBRARY_PATH`，adb 会以退出码 1 静默退出。
     */
    private val launch: () -> Process,
) {

    var state by mutableStateOf<ShellState>(ShellState.Idle)
        private set

    val isRunning: Boolean get() = state == ShellState.Running

    private var process: Process? = null
    private var stdin: OutputStream? = null

    /** 启动一条会话；重复调用会先停掉旧的。 */
    suspend fun start() = withContext(Dispatchers.IO) {
        stop()
        val proc = try {
            // 构建器已经带上环境变量与工作目录，并把 stderr 并进 stdout
            // （`error: device offline` 这类 adb 报错也要显示在终端里）
            launch()
        } catch (t: Throwable) {
            state = ShellState.Failed(t.message ?: t.toString())
            buffer.notice("无法启动 adb：${t.message ?: t}")
            return@withContext
        }
        // 先挂上引用再返回：即使调用方随后被取消，它也能通过 stop() 收掉这个进程
        process = proc
        stdin = proc.outputStream
        state = ShellState.Running
        thread(name = "tailcat-terminal", isDaemon = true) { pump(proc) }
    }

    /** 往会话里写一段（命令请自带换行）。 */
    suspend fun send(text: String) = withContext(Dispatchers.IO) {
        val sink = stdin ?: return@withContext
        runCatching {
            sink.write(text.toByteArray(Charsets.UTF_8))
            sink.flush()
        }.onFailure {
            state = ShellState.Failed(it.message ?: "写入失败")
        }
    }

    /** 结束会话（幂等）。 */
    fun stop() {
        val proc = process ?: return
        // 先摘掉引用：读线程收尾时会用 `process === proc` 判断这是不是「被动退出」
        process = null
        runCatching { stdin?.close() }
        stdin = null
        state = ShellState.Idle
        runCatching { proc.destroy() }
        thread(isDaemon = true) {
            runCatching { if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly() }
        }
    }

    /** 读线程：把 pty 的输出持续喂给 [buffer]。 */
    private fun pump(proc: Process) {
        val reader = InputStreamReader(proc.inputStream, Charsets.UTF_8)
        val chars = CharArray(8192)
        try {
            while (true) {
                val n = reader.read(chars)
                if (n < 0) break
                buffer.feed(String(chars, 0, n))
            }
        } catch (_: Throwable) {
            // 进程被收掉/主动 stop 时读上必然抛 IOException，不是错误
        } finally {
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (process === proc) state = ShellState.Exited(code)
        }
    }
}
