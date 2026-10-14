package cc.coauto.tailcat.core.reach

import cc.coauto.tailcat.core.creds.Credentials
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.model.nowMillis
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

public enum class ReachabilityState(public val label: String) {
    Queued("等待测试"),
    Testing("正在测试"),
    Reachable("可达"),
    Unreachable("未收到 pong"),
    Failed("测试失败"),
    Cancelled("已取消"),
}

public data class ReachabilityResult(
    val id: String,
    val name: String,
    val temporary: Boolean,
    val state: ReachabilityState = ReachabilityState.Queued,
    val latency: String = "—",
    val route: String = "—",
    val detail: String = "",
    val checkedAtMillis: Long? = null,
    val adbState: ConnectionState = ConnectionState.Disconnected,
)

private val PONG_LATENCY = Regex("^[0-9]+(?:\\.[0-9]+)?ms$")

/**
 * 批量 ping 可达性测试。每台 5 秒超时、外层最多 8 秒、并发上限 3 台，
 * 不创建转发、不执行 adb connect/disconnect。
 */
public class ReachabilityChecker(
    private val runner: CommandExecuting,
    private val scope: CoroutineScope,
    private val concurrency: Int = 3,
) {
    private val _results = MutableStateFlow<List<ReachabilityResult>>(emptyList())
    public val results: StateFlow<List<ReachabilityResult>> = _results.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    public val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var job: Job? = null

    public val reachableCount: Int get() = _results.value.count { it.state == ReachabilityState.Reachable }
    public val completedCount: Int
        get() = _results.value.count {
            it.state != ReachabilityState.Queued && it.state != ReachabilityState.Testing
        }
    public val adbOnlineCount: Int get() = _results.value.count { it.adbState == ConnectionState.Online }

    public fun start(
        profiles: List<DeviceProfile>,
        tailcatPath: String,
        temporaryIds: Set<String> = emptySet(),
        adbState: (String) -> ConnectionState = { ConnectionState.Disconnected },
        address: (String) -> String?,
    ) {
        if (_isRunning.value) return
        _results.value = profiles.map {
            ReachabilityResult(
                id = it.id,
                name = it.name,
                temporary = it.id in temporaryIds,
                adbState = adbState(it.id),
            )
        }
        if (profiles.isEmpty()) return
        _isRunning.value = true
        job = scope.launch {
            try {
                val semaphore = Semaphore(concurrency)
                val jobs = profiles.mapIndexed { index, profile ->
                    launch {
                        semaphore.withPermit {
                            check(index, profile, tailcatPath, adbState, address)
                        }
                    }
                }
                jobs.joinAll()
                // 被取消时，未开始的条目收敛为「已取消」。
                _results.update { list ->
                    list.map {
                        if (it.state == ReachabilityState.Queued || it.state == ReachabilityState.Testing) {
                            it.copy(state = ReachabilityState.Cancelled)
                        } else {
                            it
                        }
                    }
                }
            } finally {
                _isRunning.value = false
                job = null
            }
        }
    }

    public fun cancel() {
        if (!_isRunning.value) return
        job?.cancel()
    }

    public suspend fun shutdown() {
        cancel()
        job?.join()
    }

    private fun update(index: Int, block: (ReachabilityResult) -> ReachabilityResult) {
        _results.update { list ->
            list.mapIndexed { i, item -> if (i == index) block(item) else item }
        }
    }

    private suspend fun check(
        index: Int,
        profile: DeviceProfile,
        tailcatPath: String,
        adbState: (String) -> ConnectionState,
        address: (String) -> String?,
    ) {
        update(index) { it.copy(state = ReachabilityState.Testing) }
        try {
            val credential = address(profile.id)
            if (credential == null || !Credentials.isPlausible(credential)) {
                throw ManagerException("没有有效连接凭据，请编辑设备或导入地址簿")
            }
            if (tailcatPath.isEmpty()) throw ManagerException("未找到 Tailcat，请在工具设置中指定路径")
            val result = runner.run(
                Command(
                    executable = tailcatPath,
                    arguments = listOf("ping", "--timeout=5s", credential),
                    timeoutMillis = 8_000,
                ),
            )
            if (result.cancelled) throw CancellationException("已取消")
            val output = Credentials.redact(result.output, credential)
            val detail = output.trim().take(1000)
            val pong = output.split('\n').lastOrNull { it.startsWith("pong in ") }
            val separator = pong?.indexOf(" via ")
            val latency = if (pong != null && separator != null && separator > 8) {
                pong.substring(8, separator)
            } else {
                null
            }
            if (!result.timedOut && result.exitCode == 0 && latency != null && PONG_LATENCY.matches(latency)) {
                val path = pong!!.substring(separator!! + 5)
                update(index) {
                    it.copy(
                        state = ReachabilityState.Reachable,
                        latency = latency,
                        route = if (path.startsWith("DERP(")) "中继 · $path" else "直连 · $path",
                        detail = detail,
                    )
                }
            } else {
                update(index) {
                    it.copy(
                        state = if (result.timedOut || result.exitCode != 0) {
                            ReachabilityState.Unreachable
                        } else {
                            ReachabilityState.Failed
                        },
                        detail = detail.ifEmpty {
                            if (result.timedOut) "测试超时" else "未收到有效 pong"
                        },
                    )
                }
            }
        } catch (e: CancellationException) {
            update(index) { it.copy(state = ReachabilityState.Cancelled) }
            throw e
        } catch (t: Throwable) {
            update(index) {
                it.copy(state = ReachabilityState.Failed, detail = (t.message ?: "测试失败").take(1000))
            }
        } finally {
            update(index) { it.copy(checkedAtMillis = nowMillis(), adbState = adbState(profile.id)) }
        }
    }
}
