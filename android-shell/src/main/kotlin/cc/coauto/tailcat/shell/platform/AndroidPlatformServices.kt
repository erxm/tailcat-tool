package cc.coauto.tailcat.shell.platform

import android.content.Context
import cc.coauto.tailcat.core.adb.AdbClient
import cc.coauto.tailcat.core.adb.ProcessAdbClient
import cc.coauto.tailcat.core.config.LegacyEntry
import cc.coauto.tailcat.core.config.LegacyImport
import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.creds.ClientIdentityStore
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.proc.ProcessRunner
import cc.coauto.tailcat.core.session.ArchiveFetcher
import cc.coauto.tailcat.core.session.PlatformServices
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolInstaller
import cc.coauto.tailcat.core.transport.ForwardTransport
import cc.coauto.tailcat.core.transport.PortReservations
import cc.coauto.tailcat.core.transport.TailcatTransport
import java.io.File
import java.nio.file.Files

/**
 * Android 端的平台实现装配 —— 对应桌面端的 `WindowsPlatformServices`。
 *
 * 因为两个二进制（tailcat、adb）都能在设备上直接执行，core 里的
 * `SessionManager` / `ComponentInstaller` / `ReachabilityChecker` / `ProcessRunner` /
 * `TailcatTransport` **一行都不用改**，这里只负责把它们接到 Android 的能力上。
 */
class AndroidPlatformServices(private val context: Context) : PlatformServices {

    private val spawner = AndroidProcessSpawner(context)

    /** 与桌面端同一个实现：超时、取消、进程表都在 core 里。 */
    private val runner: CommandExecuting by lazy { ProcessRunner(spawner) }

    override val locator: ExecutableLocator = AndroidExecutableLocator(context)

    override val installer: ToolInstaller = AndroidToolInstaller()

    override val archiveFetcher: ArchiveFetcher =
        AndroidArchiveFetcher(File(context.filesDir, "tools"))

    /**
     * 手机上原本没有 `~/.local/state` 这种约定，默认放在应用私有目录里。
     * 地址簿是**只读来源**，本应用从不改写它。
     */
    override val defaultAddressBookDirectory: String =
        File(context.filesDir, "tailcat-adb").absolutePath

    override fun createRunner(): CommandExecuting = runner

    override fun adbClient(executablePath: String): AdbClient = ProcessAdbClient(executablePath)

    override fun transport(
        executablePath: String,
        runner: CommandExecuting,
        reservations: PortReservations,
        deviceId: String,
        identities: ClientIdentityStore,
    ): ForwardTransport = TailcatTransport(executablePath, runner, reservations, identities, deviceId)

    override suspend fun readLegacyAddressBook(root: String): LegacyImport {
        val users = File(root, "users")
        if (Files.isSymbolicLink(users.toPath())) {
            throw ManagerException("为保护地址簿，拒绝读取符号链接目录：${users.path}")
        }
        if (!users.isDirectory) {
            throw ManagerException("未找到 tailcat-adb 地址簿：${users.path}")
        }
        val entries = ArrayList<LegacyEntry>()
        var skipped = 0
        for (entry in users.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (!USERNAME.matches(entry.name) ||
                Files.isSymbolicLink(entry.toPath()) ||
                !entry.isFile ||
                entry.length() > 8192
            ) {
                skipped++
                continue
            }
            val address = runCatching { entry.readText(Charsets.UTF_8) }.getOrNull()?.trim()
            if (address == null || !ADDRESS.matches(address)) {
                skipped++
                continue
            }
            entries += LegacyEntry(entry.name, address)
        }
        return LegacyImport(entries, skipped)
    }

    fun shutdown() {
        spawner.shutdown()
    }

    private companion object {
        val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")
        val ADDRESS = Regex("^tc[A-Za-z0-9_-]{20,}$")
    }
}
