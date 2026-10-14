package cc.coauto.tailcat.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 生成一个 UUID 形状的随机 id。仅需在本机内唯一，格式沿用 8-4-4-4-12 便于阅读。
 */
public fun newId(): String {
    val bytes = Random.nextBytes(16)
    val hex = bytes.joinToString("") { b -> ((b.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
    return buildString(36) {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
}

@Serializable
public data class DeviceProfile(
    val id: String,
    val name: String,
    val legacyUsername: String? = null,
    val preferredPort: Int? = null,
    val remotePort: Int = 5555,
)

public enum class ConnectionState(public val label: String) {
    Disconnected("未连接"),
    Connecting("正在连接"),
    Online("ADB 已连接"),
    Offline("ADB 离线"),
    Unauthorized("ADB 待授权"),
    Disconnecting("正在断开"),
    Failed("连接失败");

    public val isBusy: Boolean get() = this == Connecting || this == Disconnecting
}

public data class SessionLog(val atMillis: Long, val text: String)

/**
 * 用 data class + 全 val + ImmutableList：[logs] 若是普通 List，Compose 会推断为 unstable，
 * 跳过优化失效，每次日志追加都会重组整行。
 */
public data class DeviceSession(
    val state: ConnectionState = ConnectionState.Disconnected,
    val serial: String? = null,
    val pid: Long? = null,
    val model: String = "—",
    val product: String = "—",
    val android: String = "—",
    val deviceSerial: String = "—",
    val identity: String = "—",
    val route: String = "未探测",
    val error: String? = null,
    val logs: ImmutableList<SessionLog> = persistentListOf(),
)

public enum class TaskState(public val label: String) {
    Running("执行中"),
    Succeeded("已完成"),
    Failed("失败"),
    Cancelled("已取消"),
}

public data class DeviceTask(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val serial: String,
    val title: String,
    val atMillis: Long,
    val state: TaskState = TaskState.Running,
    val output: String = "",
)

public class ManagerException(message: String) : Exception(message)

/** 配置校验失败等可预期的错误，界面上直接展示 message。 */
public fun managerError(message: String): Nothing = throw ManagerException(message)
