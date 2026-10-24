package cc.coauto.tailcat.shell.screen

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * scrcpy 服务端的准备与启动 —— 也就是「往被控设备上放什么、怎么把它跑起来」。
 *
 * 服务端不是我们写的：它是官方 release 里的 `scrcpy-server-v5.0.1`（其实是个未签名的 APK），
 * 构建期下载、放进 assets、运行时读出来推到被控设备，再由 `app_process` 起一个进程。
 *
 * 屏幕控制的整体链路（每一步都对应这里的一个函数）：
 *
 * ```
 *   本机 App ──push──▶ 被控设备 /data/local/tmp/scrcpy-server.jar
 *   本机 App ──forward tcp:27183 localabstract:scrcpy_<scid>──▶ 被控设备
 *   本机 App ──shell CLASSPATH=… app_process … Server 5.0.1 …──▶ 被控设备起 server
 *   本机 App ◀──TCP 127.0.0.1:27183（两条连接：video、control）── server
 * ```
 */
object ScrcpyServer {

    /**
     * ⚠️ 必须和构建脚本里下载的版本、以及服务端自己期望的版本串**一字不差**。
     * `Server.java` 会拿它和 `BuildConfig.VERSION_NAME` 比对，不等就直接抛异常退出 ——
     * 表现是「连不上、也没有画面」，很容易误判成网络问题。
     */
    const val VERSION: String = "5.0.1"

    /** 推到被控设备的路径。`/data/local/tmp` 对 shell 可读写且不是全局可写，scrcpy 官方也用它。 */
    const val REMOTE_JAR: String = "/data/local/tmp/scrcpy-server.jar"

    private const val ASSET_NAME = "scrcpy-server.jar"

    /** scrcpy 默认的本地端口区间起点。 */
    const val LOCAL_PORT: Int = 27183

    /**
     * 生成本次会话的 31 位随机 id，十六进制 8 位。
     *
     * 服务端的 abstract socket 名是 `scrcpy_<scid>` —— 加这个随机数是为了同一台设备上
     * 并发跑多个 scrcpy 时不撞名。
     */
    fun newScid(): String {
        val value = SecureRandom().nextInt(1 shl 30) + 1
        return String.format("%08x", value)
    }

    fun socketName(scid: String): String = "scrcpy_$scid"

    /** `adb forward` 的远端部分。 */
    fun forwardSpec(scid: String): String = "localabstract:${socketName(scid)}"

    /**
     * 把 assets 里的 jar 取出来落到 cacheDir，返回本地路径（供 `adb push` 用）。
     *
     * 不能直接把 asset 流喂给 adb —— `adb push` 要的是一个文件路径。
     * 每次都覆盖写：文件小（700KB 量级），但版本可能变，缓存旧的会得到
     * 「服务端版本不匹配」这种极难查的错误。
     */
    fun stageJar(context: Context): File {
        val target = File(context.cacheDir, ASSET_NAME)
        context.assets.open(ASSET_NAME).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    /**
     * 收尾时在被控设备上把**我们自己那一个**服务端进程杀掉。
     *
     * 为什么要显式杀：服务端是跑在**被控设备**上的独立进程，不归我们管；而断开连接
     * 并不保证它会退出 —— 实测 App 进程被系统杀掉（或直接重装）时，服务端会留在
     * 那边一直占着硬件编码器和电量。
     *
     * 按 `scid` 定位，所以同一台设备上并发的其它 scrcpy 会话不会被误伤。
     *
     * ⚠️ 模式里的 `.` 写成 `[.]` 是**故意的**：`pkill -f` 会拿模式去匹配**所有**进程的
     * 命令行，包括它自己所在的那条 `adb shell` —— 而那条命令行里就写着这个模式本身。
     * 不绕一下的话 pkill 会把执行它的 shell 一起杀掉（表现为 `adb shell` 返回 143）。
     */
    fun killCommand(scid: String): String =
        "pkill -f 'scrcpy[.]Server.*scid=$scid' >/dev/null 2>&1; true"

    /**
     * 服务端的启动命令行（`adb shell` 后面的那一整串）。
     *
     * 参数名都是 `key=value`，写错服务端只 warn 不退出但功能不生效，所以这里逐个注释来源：
     *  - `scid` 要和 `adb forward` 用的那个一致
     *  - `audio=false` —— 首期不做音频。**没有** `no-audio` 这种写法
     *  - `tunnel_forward=true` —— 用 `adb forward` 时必须为 true
     *  - 千万不要加 `raw_stream=true`：它会把包长度字段关掉，流就没法切包了
     */
    fun launchCommand(scid: String, maxSize: Int, bitRate: Int, maxFps: Int): String = buildString {
        append("CLASSPATH=").append(REMOTE_JAR)
        append(" app_process / com.genymobile.scrcpy.Server ").append(VERSION)
        append(" scid=").append(scid)
        append(" audio=false")
        append(" control=true")
        append(" tunnel_forward=true")
        append(" max_size=").append(maxSize)
        append(" video_bit_rate=").append(bitRate)
        append(" max_fps=").append(maxFps)
        // 出问题时能在 `adb logcat -s scrcpy` 里看到服务端日志
        append(" log_level=debug")
    }
}
