package cc.coauto.tailcat.shell.platform

import android.content.Context
import android.provider.Settings
import android.util.Log
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.shell.PortProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 让 adbd 在某个端口上听 TCP。
 *
 * 为什么需要它：tailcat 只是把**本机端口**暴露出去，隧道通不通和「本机这个端口上有没有人听」
 * 是两回事。而 adbd 默认只在 USB 上工作，TCP 模式（`service.adb.tcp.port`）默认是关的 ——
 * 于是「服务端显示运行中、对端却连不上」几乎都是这个原因。
 *
 * **两种环境自动区分，用户不需要选**：
 *
 * | 环境 | 做法 | 代价 |
 * |---|---|---|
 * | 有 root | `su -c 'setprop service.adb.tcp.port <端口>; setprop ctl.restart adbd'` | 一步到位，第一次会弹一次 root 授权 |
 * | 无 root | 借**无线调试**的 TLS 通道：`adb mdns services` 找到端口 → `adb connect` → `adb tcpip <端口>` | 需要用户开过一次无线调试（系统开关，应用没有权限代劳） |
 *
 * 顺序是**先 root 后无线调试**：有 root 时不需要用户去开发者选项里开任何东西。
 * root 不可用时（没装、被拒绝、命令失败）静默回退，界面上只会看到最终结果，不会看到「在试 root」。
 *
 * ## 两个实测出来的细节
 *
 * - **无线调试的端口每次 adbd 重启都会变**（实测 40319 → 37237），所以每次都要重新用
 *   `adb mdns services` 发现，不能缓存。
 * - 执行 `tcpip` 之后无线调试那条连接会变 `offline`（adbd 换了模式），但系统的无线调试
 *   **开关仍然是开着的**，所以重启后 adbd 会回到无线调试模式，这里可以再切一次。
 */
internal object AdbTcpMode {

    private const val TAG = "TailcatTcpMode"

    /** 结果。界面按这个决定提示语，不区分「用户当前是哪种环境」。 */
    internal sealed interface Outcome {
        /** 本来就在监听，什么都没做。 */
        data object AlreadyReady : Outcome

        /** 通过 root 切换成功。 */
        data object ViaRoot : Outcome

        /** 通过无线调试切换成功。 */
        data object ViaWireless : Outcome

        /** 端口找到了、但本应用还没和这台设备的无线调试配对过（需要一次配对）。 */
        data class NeedsPairing(val port: Int) : Outcome

        /** 都没成功，[reason] 会直接显示给用户。 */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * 尽力让 [port] 进入监听状态。
     *
     * 幂等、有超时：界面在「启动服务」之后调用它，失败也不会拦住隧道本身。
     */
    internal suspend fun ensure(
        context: Context,
        port: Int,
        runner: CommandExecuting,
        adbPath: String?,
    ): Outcome = withContext(Dispatchers.IO) {
        if (PortProbe.isListening(port)) {
            Log.i(TAG, "$port 已在监听，无需处理")
            return@withContext Outcome.AlreadyReady
        }
        Log.i(
            TAG,
            "开始处理 $port：su=${rootBinary() ?: "无"} 无线调试=${wirelessDebuggingEnabled(context)} " +
                "adb=${adbPath ?: "无"}",
        )

        // ——— 1) root：直接改属性并重启 adbd ———
        val su = rootBinary()
        if (su != null) {
            val script = "setprop service.adb.tcp.port $port; setprop ctl.restart adbd"
            // 第一次会弹 root 授权；用户在弹窗上停留的时间也算在这段超时里。
            if (runQuietly(listOf(su, "-c", script), timeoutMillis = 15_000) && awaitListening(port)) {
                Log.i(TAG, "已通过 root 开启 $port")
                return@withContext Outcome.ViaRoot
            }
            Log.i(TAG, "root 方式没有生效，回退到无线调试")
        }

        // ——— 2) 无线调试：让 adbd 自己切到 TCP 模式 ———
        if (!wirelessDebuggingEnabled(context)) {
            return@withContext Outcome.Failed(
                if (su == null) {
                    "端口 $port 没有监听：这台设备没有 root，需要先在开发者选项里开启「无线调试」一次"
                } else {
                    "端口 $port 没有监听：root 授权未通过，且无线调试没有开启"
                },
            )
        }
        if (adbPath.isNullOrEmpty()) return@withContext Outcome.Failed("找不到 adb，无法借无线调试切换")

        // 端口用系统 mDNS 找 —— 我们打包的 adb 编译时关掉了 mdns，`adb mdns services` 用不了。
        val tlsPort = MdnsDiscovery.findConnectPort(context)
            ?: return@withContext Outcome.Failed("没发现无线调试端口：确认「无线调试」开着且已连上 WLAN")
        val tls = "127.0.0.1:$tlsPort"

        val connected = runner.run(Command(adbPath, listOf("connect", tls), timeoutMillis = 8_000))
        Log.i(TAG, "connect $tls → ${connected.output.trim().take(160)}")
        // 未配对时 TLS 握手过不去，这里要能区分「发现不到端口」和「还没配对」
        if (!connected.output.contains("connected to")) {
            return@withContext Outcome.NeedsPairing(tlsPort)
        }
        val tcpip = runner.run(
            Command(adbPath, listOf("-s", tls, "tcpip", port.toString()), timeoutMillis = 10_000),
        )
        if (tcpip.exitCode != 0) {
            Log.w(TAG, "tcpip 失败：${tcpip.output.trim().take(200)}")
            return@withContext Outcome.Failed("切换失败：" + tcpip.output.trim().take(160))
        }
        if (awaitListening(port)) {
            Log.i(TAG, "已通过无线调试开启 $port")
            Outcome.ViaWireless
        } else {
            Log.w(TAG, "tcpip 返回成功但 $port 仍未监听")
            Outcome.Failed("已请求切换，但 $port 仍未监听")
        }
    }

    /** 给界面显示的一句话能力描述；不影响上面自动选择。 */
    internal fun capability(context: Context): String = when {
        rootBinary() != null -> "root"
        wirelessDebuggingEnabled(context) -> "无线调试"
        else -> "不可用"
    }

    private fun rootBinary(): String? =
        listOf("/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su")
            .firstOrNull { File(it).canExecute() }

    private fun wirelessDebuggingEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
    }.getOrDefault(false)

    private suspend fun awaitListening(port: Int, timeoutMillis: Long = 8_000): Boolean {
        var waited = 0L
        while (waited < timeoutMillis) {
            if (PortProbe.isListening(port)) return true
            delay(300)
            waited += 300
        }
        return PortProbe.isListening(port)
    }

    private fun runQuietly(command: List<String>, timeoutMillis: Long): Boolean = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        runCatching { process.outputStream.close() }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    } catch (t: Throwable) {
        Log.w(TAG, "执行 ${command.first()} 失败", t)
        false
    }
}
