package cc.coauto.tailcat.core.config

import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.tools.ToolComponent
import kotlinx.serialization.Serializable

@Serializable
public data class ToolSettings(
    val tailcat: String = "",
    val adb: String = "",
    val scrcpy: String = "",
    /** 兼容导入用的 tailcat-adb 地址簿目录；留空则用平台默认值。 */
    val addressBookDirectory: String = "",
) {
    public operator fun get(component: ToolComponent): String = when (component) {
        ToolComponent.Tailcat -> tailcat
        ToolComponent.Adb -> adb
        ToolComponent.Scrcpy -> scrcpy
    }

    public fun with(component: ToolComponent, path: String): ToolSettings = when (component) {
        ToolComponent.Tailcat -> copy(tailcat = path)
        ToolComponent.Adb -> copy(adb = path)
        ToolComponent.Scrcpy -> copy(scrcpy = path)
    }
}

@Serializable
public data class AppConfiguration(
    val profiles: List<DeviceProfile> = emptyList(),
    val tools: ToolSettings = ToolSettings(),
)

/**
 * 配置读写。实现负责原子落盘（写临时文件再 rename）与目录权限收紧。
 *
 * 配置目录固定在 `%LOCALAPPDATA%\TailcatManager\`，**不用会漫游的 `%APPDATA%`**：
 * DPAPI 的主密钥实际不随漫游迁移，跨机解密会失败且报错晦涩。
 */
public interface ConfigurationStore {
    public val directory: String

    public fun load(): AppConfiguration

    public fun save(configuration: AppConfiguration)
}
