package cc.coauto.tailcat.shell.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ConfigurationStore
import cc.coauto.tailcat.core.creds.CredentialStore
import cc.coauto.tailcat.core.model.ManagerException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备连接地址（`tc...`，本身是含预共享密钥的凭据）的存放。
 *
 * 与 Windows 端的 DPAPI 对应：密钥由 **Android Keystore** 持有（不出安全硬件/系统密钥库），
 * 用 AES-256-GCM 加密整份映射后写成单个 `secrets.dat`。
 * 之所以整份加密而不是逐条，是为了让「写临时文件再原子改名」一次覆盖全部内容。
 */
class AndroidCredentialStore(context: Context) : CredentialStore {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    private val key: SecretKey by lazy { loadOrCreateKey() }

    override fun read(id: String): String? = loadAll()[id]

    override fun write(address: String, id: String) {
        val next = loadAll().toMutableMap()
        next[id] = address
        saveAll(next)
    }

    override fun delete(id: String) {
        val next = loadAll().toMutableMap()
        if (next.remove(id) == null) return
        saveAll(next)
    }

    private fun loadAll(): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return try {
            val raw = decrypt(file.readBytes())
            json.decodeFromString<Map<String, String>>(raw)
        } catch (t: Throwable) {
            // 解不开（换了签名、密钥被清）时不能把应用卡死，丢弃后重新开始。
            // 代价是用户要重新填一次地址，但比每次启动都崩好。
            runCatching { file.delete() }
            emptyMap()
        }
    }

    private fun saveAll(values: Map<String, String>) {
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        temporary.writeBytes(encrypt(json.encodeToString(values)))
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        // 前 12 字节是 IV，其余是密文；IV 不需要保密
        return iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(bytes: ByteArray): String {
        if (bytes.size <= IV_LENGTH) throw ManagerException("凭据文件损坏")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：应用要在后台自己读写，弹指纹会把重连流程卡住
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "secrets.dat"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tailcat-manager-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/**
 * `filesDir/configuration.json`。与 Windows 端同样**不含凭据**，只有设备名称、端口、工具路径。
 *
 * 原子落盘（写临时文件再改名），并拒绝符号链接 —— 与 macOS/Windows 版保持同一套语义。
 */
class AndroidConfigurationStore(context: Context) : ConfigurationStore {

    private val root = File(context.filesDir, "manager").apply { mkdirs() }
    override val directory: String get() = root.absolutePath

    private val file = File(root, "configuration.json")
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override fun load(): AppConfiguration {
        if (!file.isFile) return AppConfiguration()
        if (Files.isSymbolicLink(file.toPath())) {
            throw ManagerException("为保护配置，拒绝读取符号链接：${file.name}")
        }
        return try {
            json.decodeFromString<AppConfiguration>(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            throw ManagerException("配置文件无法解析：${t.message}")
        }
    }

    override fun save(configuration: AppConfiguration) {
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(file.toPath())) {
            throw ManagerException("为保护配置，拒绝覆盖符号链接：${file.name}")
        }
        root.mkdirs()
        val temporary = File(root, "configuration.json.tmp")
        temporary.writeText(json.encodeToString(configuration), Charsets.UTF_8)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
