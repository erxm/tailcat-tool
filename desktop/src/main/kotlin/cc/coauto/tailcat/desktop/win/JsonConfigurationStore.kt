package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ConfigurationStore
import cc.coauto.tailcat.core.model.ManagerException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * `%LOCALAPPDATA%\TailcatManager\configuration.json`
 *
 * 用 `%LOCALAPPDATA%` 而不是会漫游的 `%APPDATA%`：凭据用 DPAPI 加密，而 DPAPI 的主密钥
 * 实际不随漫游迁移，跨机解密会失败且报错晦涩。配置和凭据放一起、都不漫游，行为才可预期。
 *
 * 配置文件本身**不含凭据**，只有设备名称/端口/工具路径。
 */
public class JsonConfigurationStore(override val directory: String) : ConfigurationStore {

    private val file = File(directory, "configuration.json")
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun load(): AppConfiguration {
        if (!file.isFile) return AppConfiguration()
        rejectSymlink(file)
        return try {
            json.decodeFromString<AppConfiguration>(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw ManagerException("配置文件无法解析：${t.message}")
        }
    }

    override fun save(configuration: AppConfiguration) {
        val target = File(directory)
        rejectSymlink(target)
        rejectSymlink(file)
        target.mkdirs()
        val temporary = File(directory, "configuration.json.tmp")
        temporary.writeText(json.encodeToString(configuration), Charsets.UTF_8)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    /** 与 macOS 版一致：拒绝读写符号链接，避免配置被重定向到别处。 */
    private fun rejectSymlink(target: File) {
        if (Files.isSymbolicLink(target.toPath())) {
            throw ManagerException("为保护配置，拒绝读取或覆盖符号链接：${target.name}")
        }
    }
}

/**
 * 只读导入 `tailcat-adb/users/` 地址簿。
 * 本应用从不改写地址簿，也不接管其中记录的 CLI 通道。
 */
public object LegacyAddressBookReader {

    private val USERNAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$")
    private val ADDRESS = Regex("^tc[A-Za-z0-9_-]{20,}$")

    public fun read(root: String): Pair<List<Pair<String, String>>, Int> {
        val users = File(root, "users")
        // 与 macOS 版的 `Persistence.swift` 一致：根目录可以由用户随便指定（哪怕是链接），
        // 但其中的 users 必须是真的目录——否则一个符号链接就能把读取重定向到任意位置。
        if (Files.isSymbolicLink(users.toPath())) {
            throw ManagerException("为保护地址簿，拒绝读取符号链接目录：${users.path}")
        }
        if (!users.isDirectory) {
            throw ManagerException("未找到 tailcat-adb 地址簿：${users.path}")
        }
        val files = users.listFiles()?.sortedBy { it.name }.orEmpty()
        val entries = ArrayList<Pair<String, String>>()
        var skipped = 0
        for (entry in files) {
            val name = entry.name
            if (!USERNAME.matches(name) ||
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
            entries += name to address
        }
        return entries to skipped
    }
}
