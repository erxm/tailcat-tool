package cc.coauto.tailcat.core.creds

/**
 * 连接凭据存储。
 *
 * 相对原 macOS 版**去掉了 `readForObservation` / `allowCredentialPrompt` 这一整条分支**：
 * macOS 的 Keychain 对 ad-hoc 签名程序会弹授权框，才需要区分「可弹窗」与「后台静默」；
 * Windows 的 DPAPI 天然绑定当前用户且永不提示，该维度不存在。
 */
public interface CredentialStore {
    fun read(id: String): String?
    fun write(address: String, id: String)
    fun delete(id: String)
}

/** 单次运行内缓存已授权的读取结果，绝不落盘。 */
public class CachedCredentialStore(private val base: CredentialStore) : CredentialStore {
    private val cache = HashMap<String, String?>()

    @Synchronized
    override fun read(id: String): String? =
        if (cache.containsKey(id)) cache[id] else base.read(id).also { cache[id] = it }

    @Synchronized
    override fun write(address: String, id: String) {
        base.write(address, id)
        cache[id] = address
    }

    @Synchronized
    override fun delete(id: String) {
        base.delete(id)
        cache[id] = null
    }
}

public class MemoryCredentialStore : CredentialStore {
    private val values = HashMap<String, String>()
    override fun read(id: String): String? = values[id]
    override fun write(address: String, id: String) { values[id] = address }
    override fun delete(id: String) { values.remove(id) }
}
