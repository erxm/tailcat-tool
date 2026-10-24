package cc.coauto.tailcat.shell.platform

import android.content.Context
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.ProcessSpawner
import cc.coauto.tailcat.core.proc.RunningProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android 上的子进程启动。
 *
 * 与 Windows 端不同，这里**没有隐藏控制台的需求**（没有黑框问题），
 * 但有两件必须做的事：
 *
 *  1. **补环境变量**。App 进程默认没有 `HOME`，而 adb 要在 `$HOME/.android/adbkey` 生成密钥、
 *     tailcat 要读 XDG 目录生成密钥，缺了都会直接报错退出。
 *  2. **`LD_LIBRARY_PATH` 指向 [AdbRuntime.linksDir]**。adb 不是单文件程序，
 *     它的 abseil/protobuf/zstd 等依赖只有在这个目录里才是动态链接器认识的真名。
 *     末尾补 `/system/lib64` 是为了让 libc/libdl/libm 这些仍然走设备自己的。
 *
 * 回调时序遵守 [ProcessSpawner] 的约定：`onOutput` / `onExit` 一律在同一个单线程
 * dispatcher 上按序派发，且某个进程的 `onExit` 一定晚于它自己的全部 `onOutput`
 * （读取线程 join 过之后才派发退出）。
 */
class AndroidProcessSpawner(private val context: Context) : ProcessSpawner {

    override val callbackDispatcher: CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(1)

    private val scope = CoroutineScope(callbackDispatcher + SupervisorJob())

    private val baseEnvironment: Map<String, String> by lazy {
        val files = context.filesDir.apply { mkdirs() }
        val cache = context.cacheDir.apply { mkdirs() }
        val xdgConfig = File(files, "xdg-config").apply { mkdirs() }
        val xdgState = File(files, "xdg-state").apply { mkdirs() }
        val xdgCache = File(cache, "xdg-cache").apply { mkdirs() }
        mapOf(
            "HOME" to files.absolutePath,
            "XDG_CONFIG_HOME" to xdgConfig.absolutePath,
            "XDG_STATE_HOME" to xdgState.absolutePath,
            "XDG_CACHE_HOME" to xdgCache.absolutePath,
            "TMPDIR" to cache.absolutePath,
            "LD_LIBRARY_PATH" to
                AdbRuntime.linksDir(context).absolutePath + ":" + SYSTEM_LIB_DIR,
        )
    }

    override fun start(
        command: Command,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RunningProcess {
        val builder = ProcessBuilder(listOf(command.executable) + command.arguments)
            .redirectErrorStream(true)
            .directory(context.filesDir)
        builder.environment().putAll(baseEnvironment)
        builder.environment().putAll(command.environment)
        val process = builder.start()

        val reader = Thread({ pump(process, onOutput) }, "tailcat-proc-out").apply {
            isDaemon = true
            start()
        }
        Thread({
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            // 等读取线程收尾，保证退出回调一定排在它自己的最后一段输出之后
            runCatching { reader.join() }
            scope.launch { onExit(code) }
        }, "tailcat-proc-wait").apply {
            isDaemon = true
            start()
        }

        return AndroidRunningProcess(process)
    }

    /** 按行收，行尾补回换行 —— 界面上的日志与任务输出都是按行展示的。 */
    private fun pump(process: Process, onOutput: (String) -> Unit) {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    scope.launch { onOutput(line + "\n") }
                }
            }
        } catch (_: Throwable) {
            // 进程被杀时管道断开，属正常路径
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        /** 设备的系统库目录；libc / libdl / libm 这些必须用它自己的。 */
        const val SYSTEM_LIB_DIR = "/system/lib64"
    }
}

private class AndroidRunningProcess(private val process: Process) : RunningProcess {

    /**
     * Android 的 `java.lang.Process` 没有 Java 9 的 `pid()` / `descendants()`，
     * 拿不到子进程号。PID 只用于界面展示，取不到就给 -1，不影响功能。
     */
    override val pid: Long get() = -1L

    override val isRunning: Boolean get() = process.isAlive

    override suspend fun stop() {
        if (!process.isAlive) return
        process.destroy()
        // 给一点体面退出的时间，然后强杀
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
