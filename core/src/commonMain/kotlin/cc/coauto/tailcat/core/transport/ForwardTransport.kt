package cc.coauto.tailcat.core.transport

import cc.coauto.tailcat.core.creds.ClientIdentityRole
import cc.coauto.tailcat.core.creds.ClientIdentityStore
import cc.coauto.tailcat.core.creds.Credentials
import cc.coauto.tailcat.core.creds.OutputRedactor
import cc.coauto.tailcat.core.model.managerError
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.proc.RunningProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

public object ForwardOutput {
    private val FORWARDING =
        Regex("# forwarding 127\\.0\\.0\\.1:([0-9]+) -> remote localhost:([0-9]+)(?:\\s|$)")

    public fun listeningPort(output: String, remotePort: Int): Int? {
        val match = FORWARDING.find(output) ?: return null
        val local = match.groupValues[1].toIntOrNull() ?: return null
        if (match.groupValues[2].toIntOrNull() != remotePort) return null
        return local.takeIf { it in 1..65535 }
    }
}

public class ForwardConnection(
    public val handle: RunningProcess,
    public val port: Int,
) {
    public val serial: String get() = "127.0.0.1:$port"
}

public interface ForwardTransport {
    public suspend fun open(
        address: String,
        preferredPort: Int?,
        remotePort: Int,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): ForwardConnection
}

/**
 * 已保留的本地端口集合。
 *
 * 原 Swift 版把 `reservedPorts` 做成 `@MainActor` 类上的 `static` 可变集合，是个跨会话全局量。
 * 这里改成显式对象，并**约定只在 SessionManager 的串行调度器上访问**，因此无需加锁。
 */
public class PortReservations {
    private val reserved = HashSet<Int>()

    public fun reserve(port: Int): Boolean = reserved.add(port)

    public fun release(port: Int) {
        reserved.remove(port)
    }
}

public class TailcatTransport(
    private val executable: String,
    private val runner: CommandExecuting,
    private val reservations: PortReservations,
    private val identities: ClientIdentityStore,
    private val deviceId: String,
) : ForwardTransport {

    private class Startup {
        var output = ""
        var exitCode: Int? = null
    }

    private sealed interface Attempt {
        data class Success(val connection: ForwardConnection) : Attempt
        data object AddressInUse : Attempt
        data class Failure(val detail: String) : Attempt
    }

    override suspend fun open(
        address: String,
        preferredPort: Int?,
        remotePort: Int,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): ForwardConnection {
        val auto = preferredPort == null
        val candidates: List<Int> = preferredPort?.let { listOf(it) } ?: (5556..5655).toList()
        // 显式指定身份：不给 `--key` 的话，tailcat 会用已落盘的默认客户端密钥，
        // 多个并发转发就共享同一个 node key 抢 DERP 路径；没有默认密钥时又退回临时密钥，
        // 每次重连都换一个身份。两种都不是我们要的。
        // 只取一次：换端口重试时身份当然还是同一个，犯不着反复去读密钥文件。
        val identity = identities.argument(executable, deviceId, ClientIdentityRole.Connection)
        for (candidate in candidates) {
            coroutineContext.ensureActive()
            if (!reservations.reserve(candidate)) {
                if (!auto) managerError("本地端口 $candidate 已由另一会话使用或正在释放")
                continue
            }
            when (val attempt = attempt(candidate, address, remotePort, auto, identity, onLog, onExit)) {
                is Attempt.Success -> return attempt.connection
                is Attempt.AddressInUse -> continue // 自动分配：端口被别的程序占用，换下一个候选
                is Attempt.Failure -> managerError(attempt.detail)
            }
        }
        managerError("5556–5655 均被占用，请为设备指定其他本地端口")
    }

    private suspend fun attempt(
        candidate: Int,
        address: String,
        remotePort: Int,
        auto: Boolean,
        identity: String,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): Attempt {
        val startup = Startup()
        val redactor = OutputRedactor(address)
        val command = Command(
            executable = executable,
            arguments = listOf(identity, "forward", "--bind=127.0.0.1", address, "$candidate:$remotePort"),
        )
        val handle: RunningProcess = try {
            runner.start(
                command,
                onOutput = { chunk ->
                    startup.output = (startup.output + chunk).takeLast(16384)
                    val safe = redactor.append(chunk)
                    if (safe.isNotEmpty()) onLog(safe)
                },
                onExit = { code ->
                    val tail = redactor.flush()
                    if (tail.isNotEmpty()) onLog(tail)
                    startup.exitCode = code
                    onExit(code)
                },
            )
        } catch (t: Throwable) {
            reservations.release(candidate)
            throw t
        }

        var ready = false
        try {
            for (ignored in 0 until 50) {
                coroutineContext.ensureActive()
                val port = ForwardOutput.listeningPort(startup.output, remotePort)
                if (port != null && handle.isRunning) {
                    ready = true
                    return Attempt.Success(ForwardConnection(handle, port))
                }
                if (startup.exitCode != null) break
                delay(100)
            }
            handle.stop()
            if (auto && startup.output.contains("address already in use", ignoreCase = true)) {
                return Attempt.AddressInUse
            }
            val detail = Credentials.redact(startup.output, address).trim()
            return Attempt.Failure(
                if (detail.isEmpty()) {
                    "转发未在 5 秒内报告端口就绪；请确认 Tailcat 支持 forward 命令"
                } else {
                    detail
                },
            )
        } catch (t: Throwable) {
            if (!ready) handle.stop()
            throw t
        }
    }
}

private class ReservedForwardProcess(
    private val process: RunningProcess,
    private val port: Int,
    private val reservations: PortReservations,
) : RunningProcess {
    override val pid: Long get() = process.pid
    override val isRunning: Boolean get() = process.isRunning

    override suspend fun stop() {
        process.stop()
        if (!process.isRunning) reservations.release(port)
    }
}
