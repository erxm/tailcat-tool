package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.adb.AdbClient
import cc.coauto.tailcat.core.adb.ProcessAdbClient
import cc.coauto.tailcat.core.config.LegacyEntry
import cc.coauto.tailcat.core.config.LegacyImport
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.session.ArchiveFetcher
import cc.coauto.tailcat.core.session.PlatformServices
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolInstaller
import cc.coauto.tailcat.core.transport.ForwardTransport
import cc.coauto.tailcat.core.transport.PortReservations
import cc.coauto.tailcat.core.transport.TailcatTransport
import java.io.File

/** Windows 端的平台实现装配。 */
public class WindowsPlatformServices(
    public val appDirectory: String,
    override val locator: ExecutableLocator = defaultLocator(appDirectory),
) : PlatformServices {

    private val spawner = JavaProcessSpawner()
    private val defaultRunner: CommandExecuting by lazy {
        cc.coauto.tailcat.core.proc.ProcessRunner(spawner)
    }

    /** 应用自己下载的工具落在这里，[locator] 会连它的下一层一起扫。 */
    public val toolsDirectory: String = File(appDirectory, "tools").absolutePath

    override val installer: ToolInstaller by lazy { WingetToolInstaller(locator) }

    override val archiveFetcher: ArchiveFetcher by lazy { WindowsArchiveFetcher(toolsDirectory) }

    /** 与 macOS 版同形：`<状态目录>/tailcat-adb`，Windows 下放在用户目录。 */
    override val defaultAddressBookDirectory: String =
        File(System.getProperty("user.home"), ".local\\state\\tailcat-adb").absolutePath

    override fun createRunner(): CommandExecuting = defaultRunner

    override fun adbClient(executablePath: String): AdbClient = ProcessAdbClient(executablePath)

    override fun transport(
        executablePath: String,
        runner: CommandExecuting,
        reservations: PortReservations,
    ): ForwardTransport = TailcatTransport(executablePath, runner, reservations)

    override suspend fun readLegacyAddressBook(root: String): LegacyImport {
        val (entries, skipped) = LegacyAddressBookReader.read(root)
        return LegacyImport(entries.map { LegacyEntry(it.first, it.second) }, skipped)
    }

    public fun shutdown() {
        spawner.shutdown()
    }

    private companion object {
        fun defaultLocator(appDirectory: String): ExecutableLocator =
            WindowsExecutableLocator(appToolsRoot = File(appDirectory, "tools").absolutePath)
    }
}
