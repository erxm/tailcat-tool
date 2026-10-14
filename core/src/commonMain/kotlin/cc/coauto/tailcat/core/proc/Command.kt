package cc.coauto.tailcat.core.proc

import cc.coauto.tailcat.core.model.ManagerException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

public data class Command(
    val executable: String,
    val arguments: List<String>,
    val timeoutMillis: Long = 20_000,
    val environment: Map<String, String> = emptyMap(),
)

public data class CommandResult(
    val exitCode: Int = 0,
    val output: String = "",
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
) {
    public fun requireSuccess() {
        if (cancelled) throw CancellationException("操作已取消")
        if (timedOut) throw ManagerException("命令超时，已停止本次操作")
        if (exitCode != 0) {
            val text = output.trim()
            throw ManagerException(if (text.isEmpty()) "命令退出码 $exitCode" else text)
        }
    }
}

public interface RunningProcess {
    public val pid: Long
    public val isRunning: Boolean
    public suspend fun stop()
}

/**
 * 平台进程启动器。
 *
 * **回调线程契约**（[ProcessRunner] 依赖它做到无锁）：[start] 交付的 `onOutput` / `onExit`
 * 必须在 [callbackDispatcher] 这**同一个单线程**上按序派发，且某个进程的 `onExit`
 * 一定晚于它自己的全部 `onOutput`。
 */
public interface ProcessSpawner {
    public val callbackDispatcher: CoroutineDispatcher
    public fun start(command: Command, onOutput: (String) -> Unit, onExit: (Int) -> Unit): RunningProcess
}

public interface CommandExecuting {
    public suspend fun start(
        command: Command,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RunningProcess

    public suspend fun run(command: Command, onOutput: (String) -> Unit = {}): CommandResult
}

/** 供没有真实进程层的测试与演示环境复用的默认回调调度器。 */
public val defaultCallbackDispatcher: CoroutineDispatcher
    get() = Dispatchers.Default.limitedParallelism(1)
