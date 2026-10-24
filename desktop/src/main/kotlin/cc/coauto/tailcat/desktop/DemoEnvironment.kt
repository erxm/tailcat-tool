package cc.coauto.tailcat.desktop

import cc.coauto.tailcat.core.adb.AdbClient
import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.LegacyImport
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.creds.ClientIdentityStore
import cc.coauto.tailcat.core.creds.CredentialStore
import cc.coauto.tailcat.core.creds.MemoryCredentialStore
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.model.newId
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.proc.CommandResult
import cc.coauto.tailcat.core.proc.RunningProcess
import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.session.ArchiveFetcher
import cc.coauto.tailcat.core.tools.DirectDownload
import cc.coauto.tailcat.core.session.PlatformServices
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.session.SessionManagerDependencies
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.core.tools.ToolInstaller
import cc.coauto.tailcat.core.transport.ForwardConnection
import cc.coauto.tailcat.core.transport.ForwardTransport
import cc.coauto.tailcat.core.transport.PortReservations
import kotlinx.coroutines.delay

/**
 * 界面演示环境：全部使用内存配置与模拟命令，
 * **不读真实地址簿、不写 DPAPI 凭据、不执行任何真实设备操作**。
 */
public object DemoEnvironment {

    private class DemoProcess(override val pid: Long) : RunningProcess {
        private var alive = true
        override val isRunning: Boolean get() = alive
        override suspend fun stop() { alive = false }
    }

    private class DemoTransport : ForwardTransport {
        private var nextPort = 5556
        override suspend fun open(
            address: String,
            preferredPort: Int?,
            remotePort: Int,
            onLog: (String) -> Unit,
            onExit: (Int) -> Unit,
        ): ForwardConnection {
            val port = preferredPort ?: nextPort
            nextPort++
            onLog("示例通道监听 127.0.0.1:$port")
            return ForwardConnection(DemoProcess((60_000 + port).toLong()), port)
        }
    }

    private class DemoRunner : CommandExecuting {
        override suspend fun start(
            command: Command,
            onOutput: (String) -> Unit,
            onExit: (Int) -> Unit,
        ): RunningProcess = DemoProcess(65_000)

        override suspend fun run(command: Command, onOutput: (String) -> Unit): CommandResult {
            delay(100)
            val args = command.arguments
            // 演示模式：客户端身份一律视为已存在，既不生成文件也不真的读密钥。
            if (args.getOrNull(1) == "printpub" || args.firstOrNull() == "genkey") {
                return CommandResult(output = "nodekey:" + "0".repeat(63) + "1\n")
            }
            if (args.contains("get-state")) {
                return if (args.any { it.endsWith(":5557") }) {
                    CommandResult(exitCode = 1, output = "error: device unauthorized\n")
                } else {
                    CommandResult(output = "device\n")
                }
            }
            if (args.any { it.contains("getprop ro.product.model") }) {
                return CommandResult(
                    output = "E245（示例）\nantora-demo\n11\nDEMO-ONLY\nuid=0(root) gid=0(root)\n",
                )
            }
            if (args.getOrNull(0) == "ping" || args.getOrNull(1) == "ping") {
                return CommandResult(output = "pong in 18ms via DERP(example)\n")
            }
            onOutput("示例任务正在执行，目标 ${args.drop(1).firstOrNull() ?: ""}\n")
            delay(1_200)
            return CommandResult(output = "示例任务完成，未执行真实设备命令。\n")
        }
    }

    private class DemoLocator : ExecutableLocator {
        override fun find(name: String): String? = "demo://$name"
        override fun isExecutable(path: String): Boolean = true
    }

    private class DemoInstaller : ToolInstaller {
        override val isAvailable: Boolean = false
        override val bootstrapLabel: String = "包管理器"
        override val bootstrapUrl: String? = null
        override fun description(component: ToolComponent): String = "演示模式不安装"
        override fun installCommand(component: ToolComponent): Command =
            Command("demo-installer", listOf(component.executableName))

        override fun directDownload(component: ToolComponent): DirectDownload? = null
    }

    /** 演示环境绝不真的联网下载。 */
    private class DemoArchiveFetcher : ArchiveFetcher {
        override suspend fun fetch(download: DirectDownload, onProgress: (String) -> Unit): String =
            throw ManagerException("演示模式不下载真实组件")
    }

    private class DemoServices(private val runner: DemoRunner) : PlatformServices {
        private val demoAdb = DemoAdbClient()
        override val locator: ExecutableLocator = DemoLocator()
        override val installer: ToolInstaller = DemoInstaller()
        override val archiveFetcher: ArchiveFetcher = DemoArchiveFetcher()
        override val defaultAddressBookDirectory: String = "demo://address-book"
        override fun createRunner(): CommandExecuting = runner
        override fun adbClient(executablePath: String): AdbClient = demoAdb
        override fun transport(
            executablePath: String,
            runner: CommandExecuting,
            reservations: PortReservations,
            deviceId: String,
            identities: ClientIdentityStore,
        ): ForwardTransport = DemoTransport()
        override suspend fun readLegacyAddressBook(root: String): LegacyImport =
            throw cc.coauto.tailcat.core.model.ManagerException("演示模式不读取真实地址簿")
    }

    private class DemoAdbClient : AdbClient {
        override val executablePath: String = "demo://adb"
        override fun connectCommand(serial: String) =
            Command(executablePath, listOf("connect", serial))
        override fun disconnectCommand(serial: String) =
            Command(executablePath, listOf("disconnect", serial))
        override fun stateCommand(serial: String) =
            Command(executablePath, listOf("-s", serial, "get-state"))
        override fun devicesCommand() = Command(executablePath, listOf("devices", "-l"))
        override fun shellCommand(serial: String, command: String, timeoutMillis: Long) =
            Command(executablePath, listOf("-s", serial, "shell", command), timeoutMillis)
        override fun installCommand(serial: String, apkPath: String, timeoutMillis: Long) =
            Command(executablePath, listOf("-s", serial, "install", "-r", apkPath), timeoutMillis)
        override fun pushCommand(serial: String, local: String, remote: String, timeoutMillis: Long) =
            Command(executablePath, listOf("-s", serial, "push", local, remote), timeoutMillis)
        override fun pullCommand(serial: String, remote: String, local: String, timeoutMillis: Long) =
            Command(executablePath, listOf("-s", serial, "pull", remote, local), timeoutMillis)
        override fun forwardCommand(serial: String, local: String, remote: String) =
            Command(executablePath, listOf("-s", serial, "forward", local, remote))
    }

    public fun makeManager(): SessionManager {
        val profiles = listOf(
            DeviceProfile(newId(), "E245 · 示例", preferredPort = 5556),
            DeviceProfile(newId(), "EX5 · 示例", preferredPort = 5557),
            DeviceProfile(newId(), "备用设备 · 示例"),
        )
        val vault: CredentialStore = MemoryCredentialStore().apply {
            profiles.forEach { write("tc" + "A".repeat(30), it.id) }
        }
        val runner = DemoRunner()
        val manager = SessionManager(
            configuration = AppConfiguration(
                profiles = profiles,
                tools = ToolSettings("demo://tailcat", "demo://adb", "demo://scrcpy"),
            ),
            deps = SessionManagerDependencies(null, vault, DemoServices(runner)),
            allowsComponentInstallation = false,
        )
        manager.connect(profiles[0].id)
        manager.connect(profiles[1].id)
        return manager
    }
}
