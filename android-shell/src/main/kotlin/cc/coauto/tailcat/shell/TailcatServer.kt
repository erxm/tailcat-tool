package cc.coauto.tailcat.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

sealed interface ServerState {
    data object Stopped : ServerState
    data object Starting : ServerState
    data class Running(
        val address: String,
        val ports: List<Int>,
        val startedAtMillis: Long,
        /** 地址是否来自持久密钥（即重启后不会变）。 */
        val persistent: Boolean,
    ) : ServerState

    data class Failed(val message: String) : ServerState
}

/**
 * tailcat 服务端的进程与状态。
 *
 * 进程由前台服务负责续命（见 [TailcatService]），状态放在这个单例里，
 * 界面与服务同进程，直接观察即可，不需要 bindService。
 *
 * 二进制被当作 **native library** 打包，运行时从 `nativeLibraryDir` 直接执行 ——
 * Android 10 起 App 不能 exec 自己可写数据目录里的文件，但 nativeLibraryDir 可以。
 * 实测本机 Android 17（untrusted_app_34 域）上直接执行返回 v0.7.0，无需 root。
 *
 * **地址的两种生命周期**：
 *  - 默认用临时密钥：进程退出地址即作废，安全但每重启一次都要改电脑端；
 *  - 打开「固定地址」后先用 `genkey --key=default --fixed-region` 落盘一个持久密钥，
 *    `serve` 会自动复用它，地址跨重启不变。代价是它变成长期有效的凭据，别外传。
 */
object TailcatServer {

    private const val TAG = "TailcatShell"
    private const val BINARY_NAME = "libtailcat.so"
    private const val LOG_NAME = "tailcat-shell.log"
    private const val MAX_LIVE_LINES = 500
    private const val MAX_LOG_BYTES = 256 * 1024L
    private const val KEY_NAME = "default"

    /** tc 地址就是 `tc` 加上一串 base64url 字符；不依赖前缀文案，避免上游改措辞就失效。 */
    private val ADDRESS = Regex("""tc[A-Za-z0-9_-]{20,}""")

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private val lock = Any()
    private var process: Process? = null
    private var launching = false

    /** 每次启动递增；用于让「正在启动中」的旧流程失效（例如启动途中被停止）。 */
    private var generation = 0

    fun binary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    private fun keyFile(context: Context): File =
        File(context.filesDir, "xdg-config/tailcat/keys/$KEY_NAME.private.json")

    /** 是否已经落盘过持久密钥。 */
    fun hasPersistentKey(context: Context): Boolean = keyFile(context).isFile

    val isRunning: Boolean get() = synchronized(lock) { process?.isAlive == true }

    fun binaryDiagnostic(context: Context): String {
        val file = binary(context)
        return buildString {
            append(file.absolutePath)
            append("\n存在=").append(file.exists())
            append("  可执行=").append(file.canExecute())
            append("  大小=").append(file.length())
        }
    }

    fun start(context: Context, ports: List<Int>, persistentKey: Boolean) {
        val token: Int
        synchronized(lock) {
            if (launching || process?.isAlive == true) return
            launching = true
            token = ++generation
        }

        val executable = binary(context)
        if (!executable.exists()) {
            fail(context, "找不到 $BINARY_NAME（预期路径 ${executable.absolutePath}）", token)
            return
        }
        if (!executable.canExecute()) {
            fail(context, "$BINARY_NAME 不可执行，检查是否漏开 useLegacyPackaging", token)
            return
        }

        _state.value = ServerState.Starting
        appendLog(
            context,
            "启动：serve ${ports.joinToString(",")}" +
                if (persistentKey) "（固定地址）" else "（临时地址）",
        )

        // genkey 要联网探测最近的 DERP 区域，可能几秒，不能卡在主线程上。
        thread(name = "tailcat-launch", isDaemon = true) {
            try {
                if (persistentKey) ensurePersistentKey(context, executable, token)
                if (isStale(token)) return@thread
                spawnServe(context, executable, ports, persistentKey, token)
            } catch (t: Throwable) {
                Log.w(TAG, "启动失败", t)
                fail(context, "启动失败：${t.message}", token)
            } finally {
                synchronized(lock) { if (token == generation) launching = false }
            }
        }
    }

    fun stop(context: Context) {
        val current: Process?
        synchronized(lock) {
            generation++          // 让启动途中的流程作废
            launching = false
            current = process
            process = null
        }
        // **立刻**置为已停止，而不是等进程退出。
        // 之前这里只是 destroy()，状态由下面 wait 线程按**退出码**判定：SIGTERM 的退出码非 0，
        // 于是一次用户主动的停止会显示成「tailcat 已退出（退出码 143），详见日志」——
        // 明明是正常退出，却让人以为出错了。先置 Stopped 之后，wait 线程那条
        // 「只有在 Starting/Running 时才改写状态」的判断就不会再把它覆盖成失败。
        _state.value = ServerState.Stopped
        if (current == null) return
        appendLog(context, "停止服务端")
        current.destroy()
        thread(isDaemon = true) {
            repeat(20) {
                if (!current.isAlive) return@thread
                Thread.sleep(100)
            }
            if (current.isAlive) current.destroyForcibly()
        }
    }

    /** 丢弃已落盘的持久密钥。下次开启「固定地址」会生成一个新的（地址也会变）。 */
    fun forgetPersistentKey(context: Context) {
        runCatching { keyFile(context).delete() }
        appendLog(context, "已删除持久密钥")
    }

    fun ensureRunning(context: Context, ports: List<Int>, persistentKey: Boolean) {
        if (!isRunning && !launching) start(context, ports, persistentKey)
    }

    // ————————————————— 内部 —————————————————

    private fun isStale(token: Int): Boolean = synchronized(lock) { token != generation }

    private fun fail(context: Context, message: String, token: Int) {
        synchronized(lock) {
            if (token == generation) {
                launching = false
                _state.value = ServerState.Failed(message)
            }
        }
        appendLog(context, message)
    }

    private fun ensurePersistentKey(context: Context, executable: File, token: Int) {
        if (hasPersistentKey(context)) {
            appendLog(context, "复用已有的持久密钥")
            return
        }
        appendLog(context, "生成持久密钥（需探测最近的 DERP 区域，可能要几秒）")
        val genkey = ProcessBuilder(
            executable.absolutePath, "genkey", "--key=$KEY_NAME", "--fixed-region",
        ).redirectErrorStream(true).withAppEnvironment(context).start()
        val output = genkey.inputStream.bufferedReader().use { it.readText() }.trim()
        val code = genkey.waitFor()
        if (output.isNotEmpty()) appendLog(context, output)
        if (isStale(token)) return
        if (code != 0) throw IllegalStateException("genkey 退出码 $code：$output")
        if (!hasPersistentKey(context)) throw IllegalStateException("genkey 完成但没有生成密钥文件")
    }

    private fun spawnServe(
        context: Context,
        executable: File,
        ports: List<Int>,
        persistentKey: Boolean,
        token: Int,
    ) {
        val arguments = buildList {
            add(executable.absolutePath)
            add("serve")
            // 关掉开关时显式要临时密钥——否则只要 default 密钥还在，serve 就会继续用它。
            // 这样来回切换不会把已生成的地址弄丢。
            if (!persistentKey) add("--key=new")
            add(ports.joinToString(","))
        }
        if (isStale(token)) return

        val started = ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .withAppEnvironment(context)
            .start()

        synchronized(lock) {
            if (token != generation) {
                started.destroy()
                return
            }
            process = started
        }

        thread(name = "tailcat-output", isDaemon = true) {
            try {
                started.inputStream.bufferedReader().forEachLine { line ->
                    appendLog(context, line)
                    val address = extractAddress(line)
                    if (address != null && _state.value !is ServerState.Running) {
                        _state.value = ServerState.Running(
                            address = address,
                            ports = ports,
                            startedAtMillis = System.currentTimeMillis(),
                            persistent = persistentKey,
                        )
                    }
                }
            } catch (_: Throwable) {
                // 进程被杀时管道会断开，属正常路径
            }
        }

        thread(name = "tailcat-wait", isDaemon = true) {
            val code = try {
                started.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            synchronized(lock) {
                if (process === started) process = null
            }
            appendLog(context, "进程退出，退出码 $code")
            if (_state.value is ServerState.Starting || _state.value is ServerState.Running) {
                _state.value = if (code == 0) {
                    ServerState.Stopped
                } else {
                    ServerState.Failed("tailcat 已退出（退出码 $code），详见日志")
                }
            }
        }
    }

    /** App 进程默认没有 HOME，而 Go 的 os.UserConfigDir() 在 XDG_CONFIG_HOME 和 HOME 都空时会直接报错退出。 */
    private fun ProcessBuilder.withAppEnvironment(context: Context): ProcessBuilder = apply {
        val xdgConfig = File(context.filesDir, "xdg-config").apply { mkdirs() }
        val xdgState = File(context.filesDir, "xdg-state").apply { mkdirs() }
        val xdgCache = File(context.cacheDir, "xdg-cache").apply { mkdirs() }
        environment()["HOME"] = context.filesDir.absolutePath
        environment()["XDG_CONFIG_HOME"] = xdgConfig.absolutePath
        environment()["XDG_STATE_HOME"] = xdgState.absolutePath
        environment()["XDG_CACHE_HOME"] = xdgCache.absolutePath
        environment()["TMPDIR"] = context.cacheDir.absolutePath
    }

    private fun extractAddress(line: String): String? =
        if (line.contains("listening", ignoreCase = true)) ADDRESS.find(line)?.value else null

    fun readLog(context: Context): List<String> {
        val file = File(context.filesDir, LOG_NAME)
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines().takeLast(MAX_LIVE_LINES) }.getOrDefault(emptyList())
    }

    fun clearLog(context: Context) {
        runCatching { File(context.filesDir, LOG_NAME).delete() }
        _log.value = emptyList()
    }

    private fun appendLog(context: Context, line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        _log.update { (it + stamped).takeLast(MAX_LIVE_LINES) }
        runCatching {
            val file = File(context.filesDir, LOG_NAME)
            if (file.length() > MAX_LOG_BYTES) {
                val kept = file.readLines().takeLast(MAX_LIVE_LINES)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            file.appendText(stamped + "\n")
        }
    }
}
