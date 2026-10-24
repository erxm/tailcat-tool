package cc.coauto.tailcat.core.session

import cc.coauto.tailcat.core.adb.AdbClient
import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.creds.CachedCredentialStore
import cc.coauto.tailcat.core.creds.ClientIdentityRole
import cc.coauto.tailcat.core.creds.ClientIdentityStore
import cc.coauto.tailcat.core.creds.Credentials
import cc.coauto.tailcat.core.creds.OutputRedactor
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.model.DeviceSession
import cc.coauto.tailcat.core.model.DeviceTask
import cc.coauto.tailcat.core.model.SessionLog
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.model.managerError
import cc.coauto.tailcat.core.model.newId
import cc.coauto.tailcat.core.model.nowMillis
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.reach.ReachabilityChecker
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.core.transport.ForwardConnection
import cc.coauto.tailcat.core.transport.PortReservations
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

/** 快速重试的次数上限；用尽后转入慢速后台重探，但**不会停止**。 */
private const val FAST_RETRY_LIMIT = 6
private const val SLOW_RETRY_SECONDS = 30L

/**
 * 会话状态机。
 *
 * **线程模型（本类正确性的前提）**：
 *  - 对外暴露的状态一律是 StateFlow（`profiles` / `tasks` / `sessionFlow(id)` …），任何线程都能安全读；
 *  - 所有内部可变状态（[runtimes] / [connections] / [monitors] / [generations] / [taskJobs] …）
 *    **只在 [scope] 上访问**。因此本类里所有触碰这些 Map 的逻辑都必须写在 `scope.launch { }`
 *    或 `withContext(stateDispatcher) { }` 里，绝不能出现在普通函数体中。
 *    任何把 Map 操作写回调用者线程的改动都会引入数据竞争——进程回调、监视循环都在调度器线程上。
 *
 * **取消语义**（Kotlin 与 Swift 差异最大的地方）：
 *  - 每个 `catch` 都必须先把 `CancellationException` 重抛，否则 `catch (e: Exception)`
 *    会静默吞掉取消，留下仍在改状态的僵尸协程；
 *  - 取消之后的清理一律走 `NonCancellable`，对应 Swift 里用 `Task { }` 与取消者解耦的 `close()`。
 */
public class SessionManager(
    configuration: AppConfiguration,
    private val deps: SessionManagerDependencies,
    private val monitorIntervalMillis: Long = 3_000,
    public val allowsComponentInstallation: Boolean = true,
) {
    private val services = deps.services
    private val credentials = CachedCredentialStore(deps.credentials)
    private val runner: CommandExecuting = services.createRunner()
    private val reservations = PortReservations()

    /**
     * 每台设备一个持久 tailcat 客户端身份。**必须整个 SessionManager 共用一份**：
     * 串行化是按身份名在进程内做的，转发、心跳探测、批量测试若各建一个新实例，
     * 就会有两个进程同时用同一个 node key —— 那正是要避免的事。
     */
    private val identities = ClientIdentityStore(runner)

    private val stateDispatcher = Dispatchers.Default.limitedParallelism(1)
    public val scope: CoroutineScope = CoroutineScope(stateDispatcher + SupervisorJob())

    private val _profiles = MutableStateFlow(configuration.profiles)
    public val profiles: StateFlow<List<DeviceProfile>> = _profiles.asStateFlow()

    private val _temporaryProfiles = MutableStateFlow<List<DeviceProfile>>(emptyList())
    public val temporaryProfiles: StateFlow<List<DeviceProfile>> = _temporaryProfiles.asStateFlow()

    private val _tools = MutableStateFlow(configuration.tools)
    public val tools: StateFlow<ToolSettings> = _tools.asStateFlow()

    private val _tasks = MutableStateFlow<List<DeviceTask>>(emptyList())
    public val tasks: StateFlow<List<DeviceTask>> = _tasks.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    public val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * 每台设备一个独立的 StateFlow。
     * 刻意**不**用一个 `StateFlow<Map<String, DeviceSession>>` 兜住全部——那样每写一行日志
     * 都会重发整张表，导致所有设备行重组。Map 本身用 StateFlow 承载，天然线程安全。
     */
    private val sessionFlows = MutableStateFlow<Map<String, MutableStateFlow<DeviceSession>>>(emptyMap())
    private val fallbackSession = MutableStateFlow(DeviceSession())

    public val reachability: ReachabilityChecker = ReachabilityChecker(runner, identities, scope)
    public val componentInstaller: ComponentInstaller = ComponentInstaller(runner, services)

    // ——— 以下全部只在 scope（stateDispatcher）上访问 ———

    private class Runtime(
        val connection: ForwardConnection,
        val adb: AdbClient,
        val generation: String,
    )

    private data class OperationTarget(
        val serial: String,
        val adb: AdbClient,
        val generation: String,
    )

    private val runtimes = HashMap<String, Runtime>()
    private val connections = HashMap<String, Job>()
    private val monitors = HashMap<String, Job>()
    private val generations = HashMap<String, String>()
    private val taskJobs = HashMap<String, Job>()
    private val diagnosticJobs = HashMap<String, Job>()
    private val informationJobs = HashMap<String, Job>()
    private val retryCounts = HashMap<String, Int>()
    private val nextRetries = HashMap<String, Long>()
    private val temporaryCredentials = HashMap<String, String>()
    private var nextTemporaryNumber = 1
    private var shutdown = false

    init {
        sessionFlows.value = configuration.profiles.associate { it.id to MutableStateFlow(DeviceSession()) }
    }

    // ————————————————— 对外只读访问（任意线程安全） —————————————————

    public fun sessionFlow(id: String): StateFlow<DeviceSession> = sessionFlows.value[id] ?: fallbackSession

    public fun session(id: String): DeviceSession = sessionFlow(id).value

    public fun allProfiles(): List<DeviceProfile> = _profiles.value + _temporaryProfiles.value

    public fun profile(id: String): DeviceProfile? = allProfiles().firstOrNull { it.id == id }

    public fun isTemporary(id: String): Boolean = _temporaryProfiles.value.any { it.id == id }

    public fun isToolAvailable(component: ToolComponent): Boolean =
        services.locator.isExecutable(_tools.value[component])

    /** 重新在磁盘上查找工具（winget 安装完成后新工具不会出现在本进程的 PATH 里）。 */
    public fun findTool(executableName: String): String? = services.locator.find(executableName)

    public val ownedActiveCount: Int
        get() = sessionFlows.value.values.count { it.value.serial != null }

    public val activeCount: Int get() = ownedActiveCount

    public fun setNotice(message: String?) {
        _notice.value = message
    }

    // ————————————————— 内部状态写入 —————————————————

    private fun updateSession(id: String, block: (DeviceSession) -> DeviceSession) {
        sessionFlows.value[id]?.update(block)
    }

    private fun ensureSession(id: String) {
        sessionFlows.update { current ->
            if (current.containsKey(id)) current else current + (id to MutableStateFlow(DeviceSession()))
        }
    }

    private fun log(id: String, text: String) {
        val safe = Credentials.redact(text).trim()
        if (safe.isEmpty()) return
        updateSession(id) { session ->
            val logs = session.logs + SessionLog(nowMillis(), safe.takeLast(8192))
            val trimmed = if (logs.size > 200) logs.drop(logs.size - 200) else logs
            session.copy(logs = trimmed.toImmutableList())
        }
    }

    private fun isCurrent(id: String, generation: String): Boolean = generations[id] == generation

    // ————————————————— 设备增删改 —————————————————

    private fun validated(name: String, address: String, preferredPort: Int?, remotePort: Int): Pair<String, String> {
        val cleanName = name.trim()
        val cleanAddress = address.trim()
        if (cleanName.isEmpty() || cleanName.length > 80) managerError("设备名称需要 1–80 个字符")
        if (!Credentials.isPlausible(cleanAddress)) managerError("请填写以 tc 开头的完整 Tailcat 连接地址")
        if (remotePort !in 1..65535) managerError("端口必须在 1–65535 之间；本地端口留空表示自动分配")
        if (preferredPort != null && preferredPort !in 1..65535) {
            managerError("端口必须在 1–65535 之间；本地端口留空表示自动分配")
        }
        return cleanName to cleanAddress
    }

    public suspend fun saveDevice(
        id: String?,
        name: String,
        address: String,
        preferredPort: Int?,
        remotePort: Int,
    ): String = withContext(stateDispatcher) {
        val (cleanName, cleanAddress) = validated(name, address, preferredPort, remotePort)
        val deviceId = id ?: newId()
        if (isTemporary(deviceId)) managerError("请使用「保存到我的设备」保存临时会话")
        val current = session(deviceId)
        if (current.serial != null || current.state.isBusy) managerError("请先断开该设备，再修改连接配置")

        val previous = runCatching { credentials.read(deviceId) }.getOrNull()
        val existing = _profiles.value.firstOrNull { it.id == deviceId }
        val profile = (existing ?: DeviceProfile(id = deviceId, name = cleanName)).copy(
            name = cleanName,
            preferredPort = preferredPort,
            remotePort = remotePort,
        )
        val updated = _profiles.value.toMutableList().apply {
            val index = indexOfFirst { it.id == deviceId }
            if (index >= 0) this[index] = profile else add(profile)
        }
        credentials.write(cleanAddress, deviceId)
        try {
            persist(updated)
        } catch (t: Throwable) {
            // 落盘失败就回滚凭据，避免配置与凭据不一致。
            if (previous != null) runCatching { credentials.write(previous, deviceId) }
            else runCatching { credentials.delete(deviceId) }
            throw t
        }
        _profiles.value = updated
        ensureSession(deviceId)
        deviceId
    }

    @Suppress("LongParameterList")
    public suspend fun saveTemporaryDevice(
        id: String?,
        name: String,
        address: String,
        preferredPort: Int?,
        remotePort: Int,
    ): String = withContext(stateDispatcher) {
        val supplied = name.trim()
        val defaultName = id?.let { existing -> _temporaryProfiles.value.firstOrNull { it.id == existing }?.name }
            ?: "临时设备 $nextTemporaryNumber"
        val (cleanName, cleanAddress) =
            validated(supplied.ifEmpty { defaultName }, address, preferredPort, remotePort)
        if (id != null) {
            if (!isTemporary(id)) managerError("临时设备已关闭或已保存")
            val current = session(id)
            if (current.serial != null || current.state.isBusy) managerError("请先断开该临时设备，再修改连接配置")
        }
        val deviceId = id ?: newId()
        val profile = DeviceProfile(deviceId, cleanName, preferredPort = preferredPort, remotePort = remotePort)
        temporaryCredentials[deviceId] = cleanAddress
        val existing = _temporaryProfiles.value.indexOfFirst { it.id == deviceId }
        if (existing >= 0) {
            _temporaryProfiles.update { list -> list.toMutableList().apply { this[existing] = profile } }
        } else {
            _temporaryProfiles.update { it + profile }
            ensureSession(deviceId)
            nextTemporaryNumber++
        }
        deviceId
    }

    /** 临时设备转正：UUID、代数、进程句柄与正在执行的任务全部保持不变。 */
    public suspend fun promoteTemporaryDevice(id: String, name: String): Unit = withContext(stateDispatcher) {
        val profile = _temporaryProfiles.value.firstOrNull { it.id == id }
            ?: managerError("临时设备已关闭或已保存")
        val address = temporaryCredentials[id] ?: managerError("临时设备已关闭或已保存")
        if (session(id).state == ConnectionState.Disconnecting) {
            managerError("临时设备正在关闭，请稍后再保存")
        }
        val (cleanName, _) = validated(name, address, profile.preferredPort, profile.remotePort)
        val updated = _profiles.value + profile.copy(name = cleanName)
        credentials.write(address, id)
        try {
            persist(updated)
        } catch (t: Throwable) {
            runCatching { credentials.delete(id) }
            throw t
        }
        _profiles.value = updated
        _temporaryProfiles.update { list -> list.filterNot { it.id == id } }
        temporaryCredentials.remove(id)
        _notice.value = "已保存到我的设备，当前会话保持"
    }

    private fun addressFor(id: String): String? =
        if (isTemporary(id)) temporaryCredentials[id] else credentials.read(id)

    public suspend fun addressForEditing(id: String): String = withContext(stateDispatcher) {
        addressFor(id).orEmpty()
    }

    public suspend fun removeDevice(id: String): Unit = withContext(stateDispatcher) {
        cancelDeviceTasks(id).forEach { it.join() }
        informationJobs.remove(id)?.cancel()
        disconnect(id).join()
        if (isTemporary(id)) {
            _temporaryProfiles.update { list -> list.filterNot { it.id == id } }
            temporaryCredentials.remove(id)
            sessionFlows.update { it - id }
            generations.remove(id)
            retryCounts.remove(id)
            nextRetries.remove(id)
            return@withContext
        }
        val updated = _profiles.value.filterNot { it.id == id }
        persist(updated)
        _profiles.value = updated
        sessionFlows.update { it - id }
        runCatching { credentials.delete(id) }
    }

    /**
     * 只读导入既有 `tailcat-adb/users/` 地址簿：只添加缺失用户，**不**改写地址簿本身，
     * 也不接管任何既有 CLI 通道。
     */
    public suspend fun importLegacy(): Int = withContext(stateDispatcher) {
        val root = _tools.value.addressBookDirectory.ifEmpty { services.defaultAddressBookDirectory }
        val result = services.readLegacyAddressBook(root)
        val additions = result.entries.filter { entry ->
            _profiles.value.none { it.legacyUsername == entry.username }
        }
        val updated = _profiles.value.toMutableList()
        val written = mutableListOf<String>()
        try {
            for (entry in additions) {
                val profile = DeviceProfile(
                    id = newId(),
                    name = entry.username,
                    legacyUsername = entry.username,
                )
                credentials.write(entry.address, profile.id)
                written += profile.id
                updated += profile
            }
            persist(updated)
        } catch (t: Throwable) {
            written.forEach { runCatching { credentials.delete(it) } }
            throw t
        }
        _profiles.value = updated
        written.forEach { ensureSession(it) }
        val skipped = if (result.skipped > 0) "；跳过 ${result.skipped} 个无效条目" else ""
        _notice.value = "已导入 ${additions.size} 台设备，已存在的条目保留$skipped"
        additions.size
    }

    private fun persist(profiles: List<DeviceProfile>) {
        deps.store?.save(AppConfiguration(profiles, _tools.value))
    }

    // ————————————————— 工具设置 —————————————————

    public suspend fun updateTools(settings: ToolSettings): Unit = withContext(stateDispatcher) {
        if (ownedActiveCount != 0 || sessionFlows.value.values.any { it.value.state.isBusy }) {
            managerError("请先断开应用管理的连接，再修改工具路径")
        }
        deps.store?.save(AppConfiguration(_profiles.value, settings))
        _tools.value = settings
    }

    public suspend fun configureInstalledTool(component: ToolComponent, path: String): Unit =
        withContext(stateDispatcher) {
            services.locator.require(path, component.label)
            // 已在运行的会话各自捕获了可执行文件路径与进程句柄，改这里的设置不影响它们，
            // 所以不该有「先断开再保存」的限制——那个 guard 会导致装完工具却不生效。
            val settings = _tools.value.with(component, path)
            deps.store?.save(AppConfiguration(_profiles.value, settings))
            _tools.value = settings
        }

    // ————————————————— 连接 / 断开 —————————————————

    /**
     * 连接一台设备。返回的 Job 即整个连接流程，可供等待。
     *
     * 同步部分只读 StateFlow（线程安全）做前置判断；**全部内部状态操作都在 scope 内**，
     * 也就是只在 stateDispatcher 这一个线程上执行。
     */
    public fun connect(id: String): Job? {
        if (profile(id) == null || shutdown) return null
        if (session(id).state.isBusy) return null
        return scope.launch { connectNow(id) }
    }

    private suspend fun connectNow(id: String) {
        // 二次确认：两次快速点击「连接」时，后到的那个在这里被挡下。
        if (shutdown || session(id).state.isBusy) return
        val profile = profile(id) ?: return

        val previous = connections.remove(id)
        previous?.cancel()
        monitors.remove(id)?.cancel()
        diagnosticJobs.remove(id)?.cancel()
        val oldTasks = cancelDeviceTasks(id)
        val previousRuntime = runtimes.remove(id)

        val generation = newId()
        generations[id] = generation
        retryCounts[id] = 0
        nextRetries.remove(id)
        updateSession(id) { it.copy(state = ConnectionState.Connecting, error = null) }

        val job = coroutineContext[Job]
        if (job != null) connections[id] = job

        try {
            previous?.join()
            oldTasks.forEach { it.join() }
            previousRuntime?.let { close(it) }
            if (!isCurrent(id, generation)) return

            val tailcat = services.locator.require(_tools.value.tailcat, "Tailcat")
            val adbPath = services.locator.require(_tools.value.adb, "ADB")
            val address = addressFor(id)
            if (address == null || !Credentials.isPlausible(address)) {
                managerError("设备没有有效连接凭据，请编辑设备或重新导入地址簿")
            }
            // 用原生解析器先拒掉格式错误的地址，再启动监听。
            val parsed = runner.run(Command(tailcat, listOf("parse", address), timeoutMillis = 5_000))
            parsed.requireSuccess()
            coroutineContext.ensureActive()
            if (!isCurrent(id, generation)) return

            log(id, "正在建立独立 Tailcat 转发通道")
            val transport = services.transport(tailcat, runner, reservations, id, identities)
            val connection = transport.open(
                address = address,
                preferredPort = profile.preferredPort,
                remotePort = profile.remotePort,
                onLog = { chunk ->
                    // 回调来自进程线程：投回状态线程处理。
                    scope.launch { if (isCurrent(id, generation)) log(id, Credentials.redact(chunk, address)) }
                },
                onExit = { code ->
                    scope.launch { transportExited(id, generation, code) }
                },
            )
            if (!isCurrent(id, generation)) {
                connection.handle.stop()
                return
            }
            val adb = services.adbClient(adbPath)
            runtimes[id] = Runtime(connection, adb, generation)
            updateSession(id) { it.copy(serial = connection.serial, pid = connection.handle.pid) }

            val result = runner.run(adb.connectCommand(connection.serial))
            coroutineContext.ensureActive()
            if (!isCurrent(id, generation)) return
            log(id, Credentials.redact(result.output, address))
            if (result.timedOut) log(id, "ADB 连接等待超时，通道保留，可重试或等待设备上线")

            repeat(10) {
                if (!isCurrent(id, generation)) return
                refresh(id, generation)
                val state = session(id).state
                if (state == ConnectionState.Online || state == ConnectionState.Unauthorized) return@repeat
                delay(400)
            }
            if (!isCurrent(id, generation)) return
            startMonitor(id, generation)
        } catch (e: CancellationException) {
            // 断开 / 重连负责替换状态与清理，这里不做事。
            throw e
        } catch (t: Throwable) {
            if (!isCurrent(id, generation)) return
            runtimes.remove(id)?.let { close(it) }
            if (!isCurrent(id, generation)) return
            updateSession(id) {
                it.copy(
                    state = ConnectionState.Failed,
                    serial = null,
                    pid = null,
                    error = Credentials.redact(t.message.orEmpty()),
                )
            }
            log(id, "连接失败：${Credentials.redact(t.message.orEmpty())}")
        } finally {
            if (isCurrent(id, generation)) connections.remove(id)
        }
    }

    /** 断开一台设备。返回的 Job 即整个断开流程。 */
    public fun disconnect(id: String): Job = scope.launch { disconnectNow(id) }

    private suspend fun disconnectNow(id: String) {
        val hasRuntime = runtimes.containsKey(id) || connections.containsKey(id) || session(id).serial != null
        if (!hasRuntime) return

        val generation = newId()
        generations[id] = generation
        val connectionJob = connections.remove(id)
        connectionJob?.cancel()
        monitors.remove(id)?.cancel()
        diagnosticJobs.remove(id)?.cancel()
        val jobs = cancelDeviceTasks(id)
        val runtime = runtimes.remove(id)
        updateSession(id) { it.copy(state = ConnectionState.Disconnecting) }

        val job = coroutineContext[Job]
        if (job != null) connections[id] = job

        connectionJob?.join()
        jobs.forEach { it.join() }
        runtime?.let { close(it) }
        if (!isCurrent(id, generation)) return
        updateSession(id) {
            it.copy(
                state = ConnectionState.Disconnected,
                serial = null,
                pid = null,
                error = null,
                route = "未探测",
            )
        }
        log(id, "已断开该设备，其他会话保持")
        connections.remove(id)
    }

    public suspend fun disconnectAll() {
        allProfiles().map { disconnect(it.id) }.forEach { it.join() }
    }

    /**
     * 收尾：先摘掉 ADB 入口再释放端口——否则另一个会话可能在旧 disconnect 命令还在飞的时候
     * 就复用了同一个 serial。
     */
    private suspend fun close(runtime: Runtime) {
        withContext(NonCancellable) {
            runCatching { runner.run(runtime.adb.disconnectCommand(runtime.connection.serial)) }
            runtime.connection.handle.stop()
        }
    }

    private suspend fun transportExited(id: String, generation: String, code: Int) {
        if (!isCurrent(id, generation)) return
        generations[id] = newId()
        connections.remove(id)?.cancel()
        monitors.remove(id)?.cancel()
        diagnosticJobs.remove(id)?.cancel()
        cancelDeviceTasks(id)
        val runtime = runtimes.remove(id)
        updateSession(id) {
            it.copy(
                state = ConnectionState.Failed,
                serial = null,
                pid = null,
                error = "Tailcat 转发进程已退出（$code），请重新连接",
            )
        }
        log(id, "转发进程退出，退出码 $code")
        runtime?.let { close(it) }
    }

    private fun startMonitor(id: String, generation: String) {
        monitors.remove(id)?.cancel()
        monitors[id] = scope.launch {
            while (isCurrent(id, generation) && !shutdown) {
                delay(monitorIntervalMillis)
                refresh(id, generation)
                retryOffline(id, generation)
            }
        }
    }

    private suspend fun refresh(id: String, generation: String) {
        val runtime = runtimes[id] ?: return
        if (!isCurrent(id, generation) || !runtime.connection.handle.isRunning) return
        val result = runCatching { runner.run(runtime.adb.stateCommand(runtime.connection.serial)) }
            .getOrElse { if (it is CancellationException) throw it else return }
        if (!isCurrent(id, generation) || runtimes[id] == null) return
        val state = runtime.adb.parseState(result.output, result.exitCode)
        val previous = session(id).state
        updateSession(id) {
            it.copy(
                state = state,
                error = if (state == ConnectionState.Offline) offlineMessage(id) else null,
            )
        }
        if (previous != state) log(id, state.label)
        if (state == ConnectionState.Online) {
            retryCounts[id] = 0
            nextRetries.remove(id)
        }
        if (state == ConnectionState.Online && (previous != ConnectionState.Online || session(id).model == "—")) {
            val info = runCatching { runner.run(runtime.adb.deviceInfoCommand(runtime.connection.serial)) }
                .getOrElse { if (it is CancellationException) throw it else return }
            if (!isCurrent(id, generation)) return
            if (info.exitCode != 0) return
            applyDeviceInfo(id, info.output)
        }
    }

    private fun applyDeviceInfo(id: String, output: String) {
        val lines = output.replace("\r", "").split('\n')
        if (lines.size < 5) return
        updateSession(id) {
            it.copy(
                model = lines[0],
                product = lines[1],
                android = lines[2],
                deviceSerial = lines[3],
                identity = lines[4],
            )
        }
    }

    /**
     * 离线提示。把「重试到哪一步了」直接告诉用户，而不是笼统一句「ADB 尚未上线」。
     */
    private fun offlineMessage(id: String): String {
        val count = retryCounts[id] ?: 0
        return when {
            count == 0 -> "本地通道运行中，ADB 尚未上线"
            count < FAST_RETRY_LIMIT -> "本地通道运行中，ADB 尚未上线（正在重试 $count/$FAST_RETRY_LIMIT）"
            else -> "本地通道运行中，ADB 仍未上线；已转为每 $SLOW_RETRY_SECONDS 秒后台重探，" +
                "接通后会自动恢复，无需手动重连"
        }
    }

    /**
     * ADB 掉线后的自动恢复。
     *
     * 前 [FAST_RETRY_LIMIT] 次按指数退避快速重试；用尽后**不放弃**，改为每 [SLOW_RETRY_SECONDS]
     * 秒一次的慢速重探。原因是「问题已被外部修好」这件事本地感知不到——比如手机端刚补开了
     * ADB over TCP，或换过地址后手机端重启了服务端——一旦停手就再也不会自己恢复，
     * 只能干等用户手动点一次重连。
     *
     * 待授权（Unauthorized）不走这条路：那要等设备侧点授权，重试没有意义。
     */
    private suspend fun retryOffline(id: String, generation: String) {
        if (!isCurrent(id, generation)) return
        if (session(id).state != ConnectionState.Offline) return
        val runtime = runtimes[id] ?: return
        if (!runtime.connection.handle.isRunning) return
        val count = retryCounts[id] ?: 0
        val due = nextRetries[id] ?: 0L
        if (due > nowMillis()) return

        retryCounts[id] = count + 1
        val waitSeconds = if (count < FAST_RETRY_LIMIT) {
            minOf(30.0, 2.0.pow(count + 1)).toLong()
        } else {
            if (count == FAST_RETRY_LIMIT) {
                log(id, "快速重试已用尽，转为每 $SLOW_RETRY_SECONDS 秒后台重探一次；接通后会自动恢复")
            }
            SLOW_RETRY_SECONDS
        }
        nextRetries[id] = nowMillis() + waitSeconds * 1000
        if (count < FAST_RETRY_LIMIT) log(id, "尝试恢复 ADB 连接（${count + 1}/$FAST_RETRY_LIMIT）")

        runCatching { runner.run(runtime.adb.connectCommand(runtime.connection.serial)) }
        if (!isCurrent(id, generation)) return
        refresh(id, generation)
    }

    public fun recheckConnections() {
        scope.launch {
            runtimes.forEach { (id, runtime) ->
                retryCounts[id] = 0
                nextRetries.remove(id)
                startMonitor(id, runtime.generation)
            }
        }
    }

    public fun testAllDevices() {
        reachability.start(
            profiles = allProfiles(),
            tailcatPath = _tools.value.tailcat,
            temporaryIds = _temporaryProfiles.value.map { it.id }.toSet(),
            adbState = { session(it).state },
            address = { addressFor(it) },
        )
    }

    public fun diagnose(id: String) {
        scope.launch {
            if (diagnosticJobs.containsKey(id)) return@launch
            val target = runCatching { operationTarget(id, requireOnline = false) }.getOrNull() ?: return@launch
            val job = coroutineContext[Job]
            if (job != null) diagnosticJobs[id] = job
            try {
                val address = addressFor(id) ?: return@launch
                val executable = services.locator.require(_tools.value.tailcat, "Tailcat")
                // 心跳探测走 probe 身份，并和「测试全部设备」共用同一条道 ——
                // 同一个持久身份不能被两个 ping 进程同时用。
                val result = identities.usingIdentity(executable, id, ClientIdentityRole.Probe) { identity ->
                    runner.run(
                        Command(executable, listOf(identity, "ping", "--timeout=3s", address), timeoutMillis = 5_000),
                    )
                }
                if (!targetIsCurrent(id, target)) return@launch
                val output = Credentials.redact(result.output, address)
                val pong = output.split('\n').lastOrNull { it.startsWith("pong in ") }
                updateSession(id) { it.copy(route = pong ?: "探测失败") }
                log(id, output.ifEmpty { "Tailcat 探测未返回结果" })
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (targetIsCurrent(id, target)) {
                    log(id, "链路探测：${Credentials.redact(t.message.orEmpty())}")
                }
            } finally {
                diagnosticJobs.remove(id)
            }
        }
    }

    // ————————————————— 设备操作 —————————————————

    private fun operationTarget(id: String, requireOnline: Boolean = true): OperationTarget {
        if (profile(id) == null) managerError("设备已移除")
        val current = session(id)
        if (current.state.isBusy) managerError("该设备正在连接或断开，请稍后再试")
        if (requireOnline && current.state != ConnectionState.Online) {
            managerError("该设备的 ADB 尚未进入 device 状态")
        }
        val runtime = runtimes[id] ?: managerError("设备通道已结束，请重新连接")
        if (!runtime.connection.handle.isRunning) managerError("设备通道已结束，请重新连接")
        return OperationTarget(runtime.connection.serial, runtime.adb, runtime.generation)
    }

    private fun targetIsCurrent(id: String, target: OperationTarget): Boolean {
        if (profile(id) == null) return false
        val runtime = runtimes[id] ?: return false
        return runtime.generation == target.generation &&
            runtime.connection.handle.isRunning &&
            session(id).serial == target.serial
    }

    private suspend fun validateTarget(id: String, target: OperationTarget) {
        if (!targetIsCurrent(id, target)) throw CancellationException("任务目标已变化")
        val state = runner.run(target.adb.stateCommand(target.serial))
        if (!targetIsCurrent(id, target)) throw CancellationException("任务目标已变化")
        if (state.exitCode != 0 || state.output.trim() != "device") {
            managerError("任务目标已离线，未执行设备操作")
        }
    }

    public suspend fun terminalCommand(id: String): Pair<String, String> = withContext(stateDispatcher) {
        val target = operationTarget(id)
        validateTarget(id, target)
        services.locator.require(_tools.value.adb, "ADB") to target.serial
    }

    public fun refreshDeviceInformation(id: String) {
        scope.launch {
            informationJobs.remove(id)?.cancel()
            val target = runCatching { operationTarget(id) }.getOrNull() ?: return@launch
            val job = coroutineContext[Job]
            if (job != null) informationJobs[id] = job
            try {
                validateTarget(id, target)
                val info = runner.run(target.adb.deviceInfoCommand(target.serial))
                info.requireSuccess()
                if (!targetIsCurrent(id, target)) return@launch
                applyDeviceInfo(id, info.output)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (targetIsCurrent(id, target)) {
                    log(id, "设备信息读取失败：${Credentials.redact(t.message.orEmpty())}")
                }
            } finally {
                informationJobs.remove(id)
            }
        }
    }

    /**
     * 启动一个设备任务。
     *
     * 目标（设备 + ADB 入口 + serial）在任务启动时固定下来；之后即使切换设备或重连，
     * 这个任务也不会改指到别处。
     */
    @Suppress("LongParameterList")
    private fun beginTask(id: String, title: String, build: (AdbClient, String) -> Command): String {
        val taskId = newId()
        scope.launch {
            val target = runCatching { operationTarget(id) }.getOrElse { t ->
                if (t is CancellationException) throw t
                appendTask(taskId, id, title, "—")
                updateTask(taskId) {
                    it.copy(state = TaskState.Failed, output = Credentials.redact(t.message.orEmpty()))
                }
                log(id, "$title 失败：${Credentials.redact(t.message.orEmpty())}")
                return@launch
            }
            appendTask(taskId, id, title, target.serial)
            val job = coroutineContext[Job]
            if (job != null) taskJobs[taskId] = job
            try {
                validateTarget(id, target)
                val redactor = OutputRedactor()
                val result = runner.run(build(target.adb, target.serial)) { chunk ->
                    val safe = redactor.append(chunk)
                    if (safe.isNotEmpty()) {
                        updateTask(taskId) { it.copy(output = (it.output + safe).takeLast(32768)) }
                    }
                }
                coroutineContext.ensureActive()
                if (!targetIsCurrent(id, target)) throw CancellationException("任务目标已变化")
                result.requireSuccess()
                updateTask(taskId) {
                    it.copy(
                        state = TaskState.Succeeded,
                        output = Credentials.redact(result.output).takeLast(32768),
                    )
                }
                log(id, "$title 已完成 · ${target.serial}")
            } catch (e: CancellationException) {
                updateTask(taskId) { it.copy(state = TaskState.Cancelled) }
                // 取消是正常路径，不向上抛：这是挂在管理器作用域上的顶层任务。
            } catch (t: Throwable) {
                updateTask(taskId) {
                    it.copy(state = TaskState.Failed, output = Credentials.redact(t.message.orEmpty()))
                }
                log(id, "$title 失败：${Credentials.redact(t.message.orEmpty())}")
            } finally {
                taskJobs.remove(taskId)
            }
        }
        return taskId
    }

    private fun appendTask(taskId: String, id: String, title: String, serial: String) {
        val name = profile(id)?.name ?: "设备"
        _tasks.update { current ->
            val next = current + DeviceTask(
                id = taskId,
                deviceId = id,
                deviceName = name,
                serial = serial,
                title = title,
                atMillis = nowMillis(),
            )
            // 只裁剪已完成的历史；正在执行的任务必须始终可见、可取消。
            val trimmed = next.toMutableList()
            while (trimmed.size > 100) {
                val index = trimmed.indexOfFirst { it.state != TaskState.Running }
                if (index < 0) break
                trimmed.removeAt(index)
            }
            trimmed
        }
    }

    public fun beginShell(id: String, command: String): String =
        beginTask(id, "Shell 命令") { adb, serial ->
            adb.shellCommand(serial, command, timeoutMillis = 120_000)
        }

    public fun beginInstall(id: String, apkPath: String): String {
        val name = apkPath.substringAfterLast('\\').substringAfterLast('/')
        return beginTask(id, "安装 $name") { adb, serial -> adb.installCommand(serial, apkPath) }
    }

    public fun beginPush(id: String, localPath: String, remotePath: String): String {
        val name = localPath.substringAfterLast('\\').substringAfterLast('/')
        return beginTask(id, "上传 $name") { adb, serial -> adb.pushCommand(serial, localPath, remotePath) }
    }

    public fun beginPull(id: String, remotePath: String, localPath: String): String =
        beginTask(id, "下载 ${remotePath.substringAfterLast('/')}") { adb, serial ->
            adb.pullCommand(serial, remotePath, localPath)
        }

    public fun beginScreen(id: String): String {
        val taskId = newId()
        scope.launch {
            val target = runCatching { operationTarget(id) }.getOrElse { t ->
                if (t is CancellationException) throw t
                appendTask(taskId, id, "屏幕控制", "—")
                updateTask(taskId) {
                    it.copy(state = TaskState.Failed, output = Credentials.redact(t.message.orEmpty()))
                }
                return@launch
            }
            val scrcpy = services.locator.find(ToolComponent.Scrcpy.executableName)
            if (scrcpy == null) {
                appendTask(taskId, id, "屏幕控制", target.serial)
                updateTask(taskId) {
                    it.copy(state = TaskState.Failed, output = "未找到 scrcpy，请在工具设置中指定路径")
                }
                return@launch
            }
            appendTask(taskId, id, "屏幕控制", target.serial)
            val job = coroutineContext[Job]
            if (job != null) taskJobs[taskId] = job
            try {
                validateTarget(id, target)
                val command = Command(
                    executable = scrcpy,
                    arguments = listOf(
                        "-s", target.serial,
                        "--max-size=1280",
                        "--video-bit-rate=4M",
                        "--max-fps=30",
                    ),
                    timeoutMillis = 86_400_000,
                    environment = mapOf("ADB" to services.locator.require(_tools.value.adb, "ADB")),
                )
                val result = runner.run(command)
                if (!targetIsCurrent(id, target)) throw CancellationException("任务目标已变化")
                result.requireSuccess()
                updateTask(taskId) { it.copy(state = TaskState.Succeeded) }
            } catch (e: CancellationException) {
                updateTask(taskId) { it.copy(state = TaskState.Cancelled) }
            } catch (t: Throwable) {
                updateTask(taskId) {
                    it.copy(state = TaskState.Failed, output = Credentials.redact(t.message.orEmpty()))
                }
            } finally {
                taskJobs.remove(taskId)
            }
        }
        return taskId
    }

    public fun cancelTask(taskId: String) {
        scope.launch { taskJobs[taskId]?.cancel() }
    }

    /** 等任务落定。用 StateFlow 轮询而不是读内部 Map，因此任意线程调用都安全。 */
    public suspend fun waitForTask(taskId: String) {
        _tasks.first { list -> list.any { it.id == taskId && it.state != TaskState.Running } }
    }

    private fun cancelDeviceTasks(id: String): List<Job> {
        val jobs = _tasks.value
            .filter { it.deviceId == id && it.state == TaskState.Running }
            .mapNotNull { taskJobs[it.id] }
        jobs.forEach { it.cancel() }
        return jobs
    }

    private fun updateTask(taskId: String, block: (DeviceTask) -> DeviceTask) {
        _tasks.update { list ->
            val index = list.indexOfFirst { it.id == taskId }
            if (index < 0) list else list.toMutableList().apply { this[index] = block(this[index]) }
        }
    }

    // ————————————————— 组件安装 —————————————————

    public fun installComponent(component: ToolComponent, onConfigured: (String) -> Unit = {}) {
        if (!allowsComponentInstallation) {
            _notice.value = "演示模式不安装真实组件"
            return
        }
        componentInstaller.install(component) { path ->
            scope.launch { runCatching { configureInstalledTool(component, path) } }
            onConfigured(path)
        }
    }

    public fun installMissingComponents(onConfigured: (ToolComponent, String) -> Unit = { _, _ -> }) {
        if (!allowsComponentInstallation) {
            _notice.value = "演示模式不安装真实组件"
            return
        }
        val missing = ToolComponent.entries.filter { !services.locator.isExecutable(_tools.value[it]) }
        componentInstaller.install(missing) { component, path ->
            scope.launch { runCatching { configureInstalledTool(component, path) } }
            onConfigured(component, path)
        }
    }

    // ————————————————— 退出 —————————————————

    public suspend fun shutdown() {
        shutdown = true
        withContext(stateDispatcher) {
            monitors.values.forEach { it.cancel() }
            monitors.clear()
            (taskJobs.values + diagnosticJobs.values + informationJobs.values).forEach { it.cancel() }
        }
        reachability.shutdown()
        componentInstaller.shutdown()
        withContext(NonCancellable) {
            disconnectAll()
        }
        // 临时设备与临时凭据只存在于本次运行中，退出时一并清除。
        withContext(stateDispatcher) {
            val temporary = _temporaryProfiles.value
            _temporaryProfiles.value = emptyList()
            temporaryCredentials.clear()
            sessionFlows.update { flows -> flows - temporary.map { it.id }.toSet() }
            temporary.forEach { profile ->
                generations.remove(profile.id)
                retryCounts.remove(profile.id)
                nextRetries.remove(profile.id)
            }
        }
    }
}
