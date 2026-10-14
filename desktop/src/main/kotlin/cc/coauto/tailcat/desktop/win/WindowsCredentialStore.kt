package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.creds.CredentialStore
import cc.coauto.tailcat.core.model.ManagerException
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 用 **DPAPI**（`CryptProtectData`）加密的连接凭据存储，整份保存在单个 `secrets.dat` 里。
 *
 * 为什么不是 Windows 凭据管理器：`CredWrite` 对 `CredentialBlob` 有大小上限，而 tc 地址按
 * 规范可以长到 8192 字符；DPAPI 没有这个限制。
 *
 * 为什么可以整块删掉 macOS 版的「凭据助手子进程」：DPAPI 天然绑定当前用户账户、**永不弹窗**，
 * 所以 `readForObservation` / `allowCredentialPrompt` 那一整条分支和它存在的理由一起消失了。
 *
 * 单一文件还有个附带好处：写临时文件再原子 rename，配置与凭据天然一致，
 * 原 macOS 版在 `saveDevice` / `importLegacy` 里手写的凭据回滚逻辑不再必要。
 *
 * 安全边界：DPAPI 的强度是「同一用户账户」，与原 Keychain 同级，不宣称更强。
 */
public class WindowsCredentialStore(private val directory: String) : CredentialStore {

    private val file = File(directory, "secrets.dat")
    private val entropy = "cc.coauto.tailcat-manager/v1".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** 仅在持有锁时访问。 */
    private var cache: MutableMap<String, String>? = null

    /** CRYPTPROTECT_UI_FORBIDDEN：需要交互时直接失败，绝不弹窗（后台任务里弹窗是灾难）。 */
    private val flags = 0x1

    @Synchronized
    private fun all(): MutableMap<String, String> {
        cache?.let { return it }
        val loaded = if (file.isFile) {
            val plain = try {
                Crypt32Util.cryptUnprotectData(file.readBytes(), entropy, flags, null)
            } catch (t: Throwable) {
                // 不能静默当空：那会让用户以为设备全都丢了。
                throw ManagerException(
                    "连接凭据解密失败（${t.message}）。secrets.dat 只能在加密它的同一台机器的同一用户下读取。",
                )
            }
            json.decodeFromString<Map<String, String>>(String(plain, Charsets.UTF_8)).toMutableMap()
        } else {
            mutableMapOf()
        }
        cache = loaded
        return loaded
    }

    @Synchronized
    private fun persist(map: Map<String, String>) {
        File(directory).mkdirs()
        val blob = Crypt32Util.cryptProtectData(
            json.encodeToString(map).toByteArray(Charsets.UTF_8),
            entropy,
            flags,
            "Tailcat Manager 连接凭据",
            null,
        )
        val temporary = File(directory, "secrets.dat.tmp")
        temporary.writeBytes(blob)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun read(id: String): String? = all()[id]

    override fun write(address: String, id: String) {
        val map = all()
        map[id] = address
        persist(map)
    }

    override fun delete(id: String) {
        val map = all()
        if (map.remove(id) == null) return
        persist(map)
    }
}
