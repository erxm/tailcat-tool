package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.ProcessSpawner
import cc.coauto.tailcat.core.proc.RunningProcess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 基于 [ProcessBuilder] 的进程启动器。
 *
 * **黑框问题**由 [ConsoleHost] 在应用启动时统一解决（让父进程持有一个隐藏控制台，
 * 子进程继承它而不再各自新建控制台窗口），因此这里不需要手写 `CreateProcessW`。
 *
 * **回调顺序契约**：两个读取线程 + 一个等待线程把回调全部投递到同一个单线程 executor，
 * 因此 onOutput 之间保持 FIFO，且 onExit 一定发生在两个管道的 onOutput 全部投递之后。
 * [cc.coauto.tailcat.core.proc.ProcessRunner] 依赖这一点做无锁状态维护。
 */
public class JavaProcessSpawner(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tailcat-proc").apply { isDaemon = true }
    },
) : ProcessSpawner {

    override val callbackDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    override fun start(
        command: Command,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RunningProcess {
        val builder = ProcessBuilder(listOf(command.executable) + command.arguments)
        builder.redirectErrorStream(false)
        if (command.environment.isNotEmpty()) builder.environment().putAll(command.environment)
        val process = builder.start()

        // 纳入 Job Object：应用被强杀或崩溃时由内核一并回收，不留孤儿。
        // **必须在这里做**，不能等到 stop() —— 那时代码已经没有执行机会了。
        // 尽力而为：拿不到 Job 只是少了兜底，正常退出的回收路径不受影响。
        ProcessJob.assign(process.pid())

        // 不向子进程写任何东西；关掉 stdin 让 adb 之类的工具立即 EOF。
        runCatching { process.outputStream.close() }

        val pending = CountDownLatch(2)
        for (stream in listOf(process.inputStream, process.errorStream)) {
            Thread({
                try {
                    // InputStreamReader 内部会缓冲不完整的多字节序列，跨读取的 UTF-8 字符不会撕裂。
                    stream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            val text = String(buffer, 0, read)
                            executor.execute { onOutput(text) }
                        }
                    }
                } catch (_: Throwable) {
                    // 进程被杀时管道会提前关闭，属正常路径。
                } finally {
                    pending.countDown()
                }
            }, "tailcat-pipe").apply { isDaemon = true }.start()
        }

        Thread({
            val code = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                -1
            }
            // 等两个管道排空，保证 onExit 是这批回调里的最后一个。
            pending.await()
            executor.execute { onExit(code) }
        }, "tailcat-wait").apply { isDaemon = true }.start()

        return JavaRunningProcess(process)
    }

    public fun shutdown() {
        executor.shutdownNow()
    }
}

private class JavaRunningProcess(private val process: Process) : RunningProcess {
    override val pid: Long get() = process.pid()
    override val isRunning: Boolean get() = process.isAlive

    override suspend fun stop() {
        if (!process.isAlive) return
        // 先收子进程树（例如 scrcpy 会拉起 adb），再收自己。
        process.descendants().forEach { runCatching { it.destroy() } }
        process.destroy()
        repeat(20) {
            if (!process.isAlive) return
            delay(100)
        }
        process.descendants().forEach { runCatching { it.destroyForcibly() } }
        process.destroyForcibly()
        repeat(10) {
            if (!process.isAlive) return
            delay(50)
        }
    }
}
