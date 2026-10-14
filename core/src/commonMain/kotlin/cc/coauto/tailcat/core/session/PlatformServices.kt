package cc.coauto.tailcat.core.session

import cc.coauto.tailcat.core.adb.AdbClient
import cc.coauto.tailcat.core.config.ConfigurationStore
import cc.coauto.tailcat.core.config.LegacyImport
import cc.coauto.tailcat.core.creds.CredentialStore
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolInstaller
import cc.coauto.tailcat.core.transport.ForwardTransport
import cc.coauto.tailcat.core.transport.PortReservations

/**
 * 平台相关的全部注入点，收在一个接口里。
 * core 本身既不认识 Windows 也不认识 Android，各端在应用层提供实现。
 */
public interface PlatformServices {
    public val locator: ExecutableLocator
    public val installer: ToolInstaller

    /** 包管理器不可用（或安装失败）时的兜底：直接从官方地址下载。 */
    public val archiveFetcher: ArchiveFetcher

    /** 默认的 tailcat-adb 地址簿目录（只读来源，本应用从不改写它）。 */
    public val defaultAddressBookDirectory: String

    public fun createRunner(): CommandExecuting

    public fun adbClient(executablePath: String): AdbClient

    public fun transport(
        executablePath: String,
        runner: CommandExecuting,
        reservations: PortReservations,
    ): ForwardTransport

    /**
     * 只读导入 `tailcat-adb/users/` 地址簿。目录不存在时抛 [cc.coauto.tailcat.core.model.ManagerException]。
     * 放在平台侧是因为它要读文件系统。
     */
    public suspend fun readLegacyAddressBook(root: String): LegacyImport
}

public class SessionManagerDependencies(
    public val store: ConfigurationStore?,
    public val credentials: CredentialStore,
    public val services: PlatformServices,
)
