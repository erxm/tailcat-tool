package cc.coauto.tailcat.core.adb

import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.proc.Command

public data class AdbDeviceInfo(
    val serial: String,
    val state: ConnectionState,
    val model: String = "—",
    val product: String = "—",
)

/**
 * 把 ADB 操作收口到接口——**这是相对原项目最重要的结构改动**。
 *
 * 原 Swift 代码把 `adb` 可执行文件路径和 `-s <serial>` 语义硬编码进了 `OperationTarget`
 * 和约十处调用点。Android 端没有系统 `adb`、也没有 `-s`，将来要直接说 ADB 协议，
 * 因此桌面实现只负责**拼命令行 + 解析输出**，把「怎么连上设备」这件事留在实现里。
 */
public interface AdbClient {
    public val executablePath: String

    public fun connectCommand(serial: String): Command
    public fun disconnectCommand(serial: String): Command
    public fun stateCommand(serial: String): Command
    public fun devicesCommand(): Command

    public fun shellCommand(serial: String, command: String, timeoutMillis: Long = 6_000): Command

    public fun installCommand(serial: String, apkPath: String, timeoutMillis: Long = 600_000): Command

    public fun pushCommand(
        serial: String,
        local: String,
        remote: String,
        timeoutMillis: Long = 1_800_000,
    ): Command

    public fun pullCommand(
        serial: String,
        remote: String,
        local: String,
        timeoutMillis: Long = 1_800_000,
    ): Command

    /**
     * `adb forward <local> <remote>`：把设备上的一个 socket 映射到本机端口。
     *
     * 屏幕控制要用它把 scrcpy-server 的 abstract socket 拉回本地：
     * `forwardCommand(serial, "tcp:27183", "localabstract:scrcpy_<scid>")`。
     *
     * ⚠️ 客户端随后要对**同一个本地端口**开多条 TCP 连接（video 一条、control 一条），
     * 不是多个端口 —— 服务端是按连接先后 accept 的。
     */
    public fun forwardCommand(serial: String, local: String, remote: String): Command

    /** 一次 shell 拿回型号/代号/版本/序列号/Shell 身份。 */
    public fun deviceInfoCommand(serial: String): Command = shellCommand(serial, DEVICE_INFO_SHELL)

    public fun parseState(output: String, exitCode: Int): ConnectionState {
        val text = output.trim()
        return when {
            exitCode == 0 && text == "device" -> ConnectionState.Online
            text.contains("unauthorized") -> ConnectionState.Unauthorized
            else -> ConnectionState.Offline
        }
    }

    public fun parseDevices(output: String): List<AdbDeviceInfo> = parseAdbDevices(output)

    public companion object {
        public const val DEVICE_INFO_SHELL: String =
            "getprop ro.product.model; getprop ro.product.device; " +
                "getprop ro.build.version.release; getprop ro.serialno; id"
    }
}

/** `adb devices -l` 解析。容忍 CRLF。 */
public fun parseAdbDevices(output: String): List<AdbDeviceInfo> {
    val result = ArrayList<AdbDeviceInfo>()
    for (raw in output.split('\n')) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("List of devices")) continue
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) continue
        val state = when (parts[1]) {
            "device" -> ConnectionState.Online
            "unauthorized" -> ConnectionState.Unauthorized
            else -> ConnectionState.Offline
        }
        result += AdbDeviceInfo(
            serial = parts[0],
            state = state,
            model = parts.firstOrNull { it.startsWith("model:") }?.removePrefix("model:") ?: "—",
            product = parts.firstOrNull { it.startsWith("product:") }?.removePrefix("product:") ?: "—",
        )
    }
    return result
}

/**
 * 桌面实现：拼 `adb [-P 端口] -s <serial> …` 命令行。
 *
 * [serverPort] 为 null 时使用共享的默认 5037 服务器（与原项目行为一致，Android Studio 也能看到同一批设备）。
 */
public class ProcessAdbClient(
    override val executablePath: String,
    private val serverPort: Int? = null,
) : AdbClient {

    private fun env(): Map<String, String> =
        serverPort?.let { mapOf("ANDROID_ADB_SERVER_PORT" to it.toString()) } ?: emptyMap()

    private fun global(): List<String> = serverPort?.let { listOf("-P", it.toString()) } ?: emptyList()

    private fun command(vararg args: String, timeoutMillis: Long): Command = Command(
        executable = executablePath,
        arguments = global() + args.toList(),
        timeoutMillis = timeoutMillis,
        environment = env(),
    )

    override fun connectCommand(serial: String): Command =
        command("connect", serial, timeoutMillis = 15_000)

    override fun disconnectCommand(serial: String): Command =
        command("disconnect", serial, timeoutMillis = 3_000)

    override fun stateCommand(serial: String): Command =
        command("-s", serial, "get-state", timeoutMillis = 4_000)

    override fun devicesCommand(): Command =
        command("devices", "-l", timeoutMillis = 4_000)

    override fun shellCommand(serial: String, command: String, timeoutMillis: Long): Command =
        command("-s", serial, "shell", command, timeoutMillis = timeoutMillis)

    override fun installCommand(serial: String, apkPath: String, timeoutMillis: Long): Command =
        command("-s", serial, "install", "-r", apkPath, timeoutMillis = timeoutMillis)

    override fun pushCommand(serial: String, local: String, remote: String, timeoutMillis: Long): Command =
        command("-s", serial, "push", local, remote, timeoutMillis = timeoutMillis)

    override fun pullCommand(serial: String, remote: String, local: String, timeoutMillis: Long): Command =
        command("-s", serial, "pull", remote, local, timeoutMillis = timeoutMillis)

    override fun forwardCommand(serial: String, local: String, remote: String): Command =
        command("-s", serial, "forward", local, remote, timeoutMillis = 10_000)
}
