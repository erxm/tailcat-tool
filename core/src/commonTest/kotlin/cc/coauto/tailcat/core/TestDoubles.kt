package cc.coauto.tailcat.core

import cc.coauto.tailcat.core.adb.AdbClient
import cc.coauto.tailcat.core.adb.ProcessAdbClient
import cc.coauto.tailcat.core.config.ConfigurationStore
import cc.coauto.tailcat.core.config.LegacyImport
import cc.coauto.tailcat.core.creds.ClientIdentityStore
import cc.coauto.tailcat.core.creds.CredentialStore
import cc.coauto.tailcat.core.creds.MemoryCredentialStore
import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.proc.CommandResult
import cc.coauto.tailcat.core.proc.RunningProcess
import cc.coauto.tailcat.core.session.ArchiveFetcher
import cc.coauto.tailcat.core.session.PlatformServices
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.session.SessionManagerDependencies
import cc.coauto.tailcat.core.tools.DirectDownload
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.core.tools.ToolInstaller
import cc.coauto.tailcat.core.transport.ForwardConnection
import cc.coauto.tailcat.core.transport.ForwardTransport
import cc.coauto.tailcat.core.transport.PortReservations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * `runTest` 里的 `delay` 走的是虚拟时间，**不会**给跑在真实调度器上的协程任何执行机会。
 * 等待跨线程投递的回调（例如转发进程退出）必须用真实延迟。
 */
suspend fun realDelay(millis: Long) {
    withContext(Dispatchers.Default) { delay(millis) }
}

/** 轮询等待条件成立，最多 [timeoutMillis] 毫秒（真实时间）。 */
suspend fun awaitUntil(timeoutMillis: Long = 3_000, condition: () -> Boolean): Boolean {
    var waited = 0L
    while (waited < timeoutMillis) {
        if (condition()) return true
        realDelay(20)
        waited += 20
    }
    return condition()
}

/** 一个不会真的跑进程的句柄，用来断言「监听是否被保留 / 是否被停掉」。 */
class FakeProcess(override val pid: Long) : RunningProcess {
    var stopCount = 0
        private set
    private var alive = true
    override val isRunning: Boolean get() = alive
    override suspend fun stop() {
        stopCount++
        alive = false
    }

    /** 模拟转发进程意外退出。 */
    fun crash() { alive = false }
}

/** 记录每一次凭据读写，用来断言「临时设备绝不碰持久凭据」。 */
class SpyCredentialStore : CredentialStore {
    private val memory = MemoryCredentialStore()
    var reads = 0; private set
    var writes = 0; private set
    var deletes = 0; private set

    override fun read(id: String): String? { reads++; return memory.read(id) }
    override fun write(address: String, id: String) { writes++; memory.write(address, id) }
    override fun delete(id: String) { deletes++; memory.delete(id) }
    fun resetCounts() { reads = 0; writes = 0; deletes = 0 }
}

class FakeTransport(private val startPort: Int = 5556) : ForwardTransport {
    val handles = mutableMapOf<Int, FakeProcess>()
    private val exits = mutableMapOf<Int, (Int) -> Unit>()
    var openDelayMillis: Long = 0
    private var nextPort = startPort

    override suspend fun open(
        address: String,
        preferredPort: Int?,
        remotePort: Int,
        onLog: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): ForwardConnection {
        if (openDelayMillis > 0) delay(openDelayMillis)
        val port = preferredPort ?: nextPort
        nextPort++
        val process = FakeProcess(port.toLong())
        handles[port] = process
        exits[port] = onExit
        return ForwardConnection(process, port)
    }

    /** 模拟转发进程崩溃。 */
    fun crash(port: Int) {
        handles[port]?.crash()
        exits[port]?.invoke(9)
    }
}

class FakeRunner : CommandExecuting {
    val calls = mutableListOf<List<String>>()
    val unauthorized = mutableSetOf<String>()
    var operationDelayMillis: Long = 0
    var onDisconnect: ((String) -> Unit)? = null

    /** 已「落盘」的客户端身份名，供 `printpub` / `genkey` 模拟用（与真实 tailcat 语义对齐）。 */
    val clientKeys = mutableSetOf<String>()

    override suspend fun start(
        command: Command,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): RunningProcess = FakeProcess(1)

    override suspend fun run(command: Command, onOutput: (String) -> Unit): CommandResult {
        val args = command.arguments
        calls += args
        if (args.firstOrNull() == "disconnect" && args.size > 1) onDisconnect?.invoke(args[1])
        if (args.firstOrNull() in listOf("parse", "connect", "disconnect")) return CommandResult()
        // `--key=<名字> printpub`：真实 tailcat 在名字不存在时非零退出，
        // 身份已落盘时打印 `nodekey:<64 hex>`。
        if (args.firstOrNull()?.startsWith("--key=") == true && args.getOrNull(1) == "printpub") {
            val name = args[0].removePrefix("--key=")
            return if (name in clientKeys) {
                CommandResult(output = "nodekey:" + "0".repeat(63) + "1\n")
            } else {
                CommandResult(exitCode = 1, output = "open $name.private.json: no such file\n")
            }
        }
        // `genkey --client --key=<名字>`：**不带 --force 时拒绝覆盖**，和真实程序一致。
        if (args.firstOrNull() == "genkey") {
            val name = args.firstOrNull { it.startsWith("--key=") }?.removePrefix("--key=").orEmpty()
            return if (clientKeys.add(name)) {
                CommandResult(output = "nodekey:" + "0".repeat(63) + "1\n")
            } else {
                CommandResult(exitCode = 1, output = "$name already exists; use --force to overwrite\n")
            }
        }
        if (args.contains("get-state")) {
            val serial = args.getOrNull(args.indexOf("-s") + 1).orEmpty()
            return if (serial in unauthorized) {
                CommandResult(exitCode = 1, output = "error: device unauthorized\n")
            } else {
                CommandResult(output = "device\n")
            }
        }
        if (args.any { it.contains("getprop ro.product.model") }) {
            return CommandResult(output = "TestModel\nproduct\n11\nSERIAL-TEST\nuid=2000(shell)\n")
        }
        // ping 现在带 `--key=<身份>` 前缀，所以要按位置找裸子命令而不是只看第一个参数。
        if (args.getOrNull(0) == "ping" || args.getOrNull(1) == "ping") {
            return CommandResult(output = "pong in 12.3ms via DERP(test)\n")
        }
        if (operationDelayMillis > 0) delay(operationDelayMillis)
        onOutput("Success\n")
        return CommandResult(output = "Success\n")
    }
}

/** 总是「找得到」工具，避免测试依赖真实文件系统。 */
class AlwaysFoundLocator : ExecutableLocator {
    override fun find(name: String): String = "/test/bin/$name"
    override fun isExecutable(path: String): Boolean = path.isNotEmpty()
}

class NoopInstaller(
    /** 模拟「包管理器不可用」，用来验证直链兜底会接手。 */
    override val isAvailable: Boolean = true,
    /** 直链兜底是否存在。 */
    private val direct: Boolean = true,
) : ToolInstaller {
    override val bootstrapLabel: String = "test"
    override val bootstrapUrl: String? = null
    override fun description(component: ToolComponent): String = "test install ${component.executableName}"
    override fun installCommand(component: ToolComponent): Command = Command("/test/bin/installer", listOf())
    override fun directDownload(component: ToolComponent): DirectDownload? =
        if (direct) {
            DirectDownload(
                url = "https://example.invalid/${component.executableName}.zip",
                directory = component.executableName,
                executableInArchive = "${component.executableName}.exe",
            )
        } else {
            null
        }
}

/** 记录被请求的直链，返回一个假的可执行文件路径。 */
class FakeArchiveFetcher : ArchiveFetcher {
    val fetched = mutableListOf<String>()
    override suspend fun fetch(download: DirectDownload, onProgress: (String) -> Unit): String {
        onProgress("下载 ${download.url}")
        fetched += download.url
        return "/test/tools/${download.directory}/${download.executableInArchive}"
    }
}

class TestServices(
    val runner: FakeRunner,
    val transport: FakeTransport,
    override val locator: ExecutableLocator = AlwaysFoundLocator(),
    override val installer: ToolInstaller = NoopInstaller(),
    override val archiveFetcher: ArchiveFetcher = FakeArchiveFetcher(),
    var addressBook: () -> LegacyImport = { LegacyImport(emptyList(), 0) },
) : PlatformServices {
    private val adb: AdbClient = ProcessAdbClient("/test/bin/adb")
    override val defaultAddressBookDirectory: String = "/test/address-book"

    override fun createRunner(): CommandExecuting = runner
    override fun adbClient(executablePath: String): AdbClient = adb
    override fun transport(
        executablePath: String,
        runner: CommandExecuting,
        reservations: PortReservations,
        deviceId: String,
        identities: ClientIdentityStore,
    ): ForwardTransport = transport

    override suspend fun readLegacyAddressBook(root: String): LegacyImport = addressBook()
}

/** 内存配置存储，用来断言「配置文件里绝不含凭据」。 */
class MemoryConfigurationStore(override val directory: String = "/test/config") : ConfigurationStore {
    var saved: cc.coauto.tailcat.core.config.AppConfiguration? = null
    var failOnSave: Boolean = false
    private var loaded: cc.coauto.tailcat.core.config.AppConfiguration =
        cc.coauto.tailcat.core.config.AppConfiguration()

    override fun load(): cc.coauto.tailcat.core.config.AppConfiguration = loaded
    override fun save(configuration: cc.coauto.tailcat.core.config.AppConfiguration) {
        if (failOnSave) throw ManagerException("配置写入失败")
        saved = configuration
        loaded = configuration
    }
}
