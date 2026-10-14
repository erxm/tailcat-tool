package cc.coauto.tailcat.core.proc

import cc.coauto.tailcat.core.model.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 带超时与取消的命令执行器。
 *
 * 线程模型：所有内部状态（[jobs]）只在 [ProcessSpawner.callbackDispatcher] 这一个线程上读写，
 * 因此无需加锁。调用方可以来自任意线程。
 */
public class ProcessRunner(private val spawner: ProcessSpawner) : CommandExecuting {

    private val callbacks = spawner.callbackDispatcher
    private val scope = CoroutineScope(callbacks + SupervisorJob())

    private class PendingJob(
        val onOutput: (String) -> Unit,
    ) {
        var handle: RunningProcess? = null
        var continuation: Continuation<CommandResult>? = null
        var output: String = ""
        var timedOut: Boolean = false
        var cancelled: Boolean = false
        var timeoutJob: Job? = null
    }

    /** 只在 [callbacks] 线程访问。 */
    private val jobs = HashMap<String, PendingJob>()

    override suspend fun start(
        command: Command,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RunningProcess = withContext(callbacks) {
        spawner.start(command, onOutput, onExit)
    }

    override suspend fun run(command: Command, onOutput: (String) -> Unit): CommandResult {
        val id = newId()
        return suspendCancellableCoroutine { continuation ->
            scope.launch {
                if (!continuation.isActive) return@launch
                val job = PendingJob(onOutput)
                jobs[id] = job
                job.continuation = continuation
                job.timeoutJob = scope.launch {
                    delay(command.timeoutMillis.coerceAtLeast(100))
                    job.timedOut = true
                    job.handle?.stop()
                }
                try {
                    job.handle = spawner.start(
                        command,
                        onOutput = { chunk ->
                            job.output = (job.output + chunk).takeLast(1_048_576)
                            job.onOutput(chunk)
                        },
                        onExit = { code ->
                            if (jobs.remove(id) == null) return@start
                            job.timeoutJob?.cancel()
                            val pending = job.continuation
                            job.continuation = null
                            job.handle = null
                            pending?.resume(
                                CommandResult(
                                    exitCode = code,
                                    output = job.output,
                                    timedOut = job.timedOut,
                                    cancelled = job.cancelled,
                                ),
                            )
                        },
                    )
                    // 启动期间就被取消：立刻回收，否则进程会泄漏。
                    if (!continuation.isActive) {
                        job.handle?.stop()
                    }
                } catch (t: Throwable) {
                    jobs.remove(id)
                    job.timeoutJob?.cancel()
                    val pending = job.continuation
                    job.continuation = null
                    pending?.resumeWithException(t)
                }
            }
            continuation.invokeOnCancellation {
                // invokeOnCancellation 不能挂起，也不能直接改状态：投回 callbacks 线程处理。
                scope.launch {
                    jobs[id]?.let { job ->
                        job.cancelled = true
                        job.handle?.stop()
                    }
                }
            }
        }
    }
}
