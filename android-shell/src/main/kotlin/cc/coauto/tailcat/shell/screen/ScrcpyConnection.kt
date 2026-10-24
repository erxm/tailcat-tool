package cc.coauto.tailcat.shell.screen

import cc.coauto.tailcat.core.screen.SCRCPY_DEVICE_NAME_SIZE
import cc.coauto.tailcat.core.screen.SCRCPY_HEADER_SIZE
import cc.coauto.tailcat.core.screen.ScrcpyPacket
import cc.coauto.tailcat.core.screen.StreamHeader
import cc.coauto.tailcat.core.screen.parseDeviceName
import cc.coauto.tailcat.core.screen.parsePacketHeader
import cc.coauto.tailcat.core.screen.parseStreamHeader
import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** 一个视频包。session packet 的 payload 是空的（它的信息全在头里）。 */
data class VideoPacket(val packet: ScrcpyPacket, val payload: ByteArray)

/**
 * 和 scrcpy-server 之间的两条 TCP 连接。
 *
 * 几个必须守住的点，错了都表现为「画面不出来」而不是报错：
 *
 *  - **两条连接连的是同一个本地端口**（`adb forward` 出来的那个），不是两个端口。
 *    服务端按 video → control 的顺序 accept，所以连接顺序不能反。
 *  - 第一条（video）连上后服务端会先发 **1 个 dummy 字节**，再发 **64 字节设备名**。
 *    dummy 只在 forward 隧道模式下有。这两个读错一位，后面整条流全部错位。
 *  - 视频流开头是 4 字节 codec id，也可能是一个「禁流码」——两者要分开处理。
 *  - **TCP 是字节流，必须读满**。用一次 `read()` 拿到几个字节就当一整个头，是完全错的。
 *
 * control 连接是双向的：即使我们不用剪贴板，也必须**一直读并丢弃**，
 * 否则服务端回发的 device message 会把它的发送缓冲区塞满，把它自己卡死。
 */
class ScrcpyConnection(port: Int, connectTimeoutMillis: Int = 10_000) : Closeable {

    private val video = Socket()
    private val control = Socket()
    private val videoInput: DataInputStream
    private val controlOutput: OutputStream
    private val drain = Thread(::drainControl, "scrcpy-control-drain").apply { isDaemon = true }

    /** 控制消息的写线程。见 [sendControl] —— 调用者是主线程，不能就地写。 */
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scrcpy-control-write").apply { isDaemon = true }
    }

    /** 被控设备的机型串，服务端用它当窗口标题。读出来顺便可以验证握手没读错。 */
    val deviceName: String

    init {
        val address = InetSocketAddress("127.0.0.1", port)
        video.tcpNoDelay = true
        video.connect(address, connectTimeoutMillis)
        // 顺序不能反：服务端先 accept video，再 accept control
        control.tcpNoDelay = true
        control.connect(address, connectTimeoutMillis)

        videoInput = DataInputStream(video.getInputStream())
        controlOutput = control.getOutputStream()

        videoInput.readFully(ByteArray(1))          // dummy 字节，只用来说明「服务端真的起来了」
        val nameBytes = ByteArray(SCRCPY_DEVICE_NAME_SIZE)
        videoInput.readFully(nameBytes)
        deviceName = parseDeviceName(nameBytes)

        readStreamHeader()
        drain.start()
    }

    /**
     * 读视频流开头的 4 字节。不是 h264 就抛 —— 与其带着一个解不了的流继续跑、
     * 让用户对着黑屏猜，不如在这里就把话说清楚。
     */
    private fun readStreamHeader() {
        val bytes = ByteArray(4)
        videoInput.readFully(bytes)
        when (val header = parseStreamHeader(bytes)) {
            is StreamHeader.Codec -> check(header.id == "h264") { "暂不支持的视频编码：${header.id}" }
            StreamHeader.DisabledByClient -> error("被控设备没有提供视频流（可能屏幕处于关闭状态）")
            StreamHeader.ConfigError -> error("被控设备上启动视频采集失败，请查看 scrcpy 服务端日志")
            is StreamHeader.Unknown -> error("无法识别的视频流头：0x${header.raw.toString(16)}")
        }
    }

    /**
     * 读下一个包。流结束返回 null。
     *
     * 这是阻塞调用，要在 IO 线程上跑。session packet（旋转/改分辨率）也会照原样返回，
     * 由上层决定怎么处理 —— 解码器需要据此重建。
     */
    fun nextVideoPacket(): VideoPacket? {
        val header = ByteArray(SCRCPY_HEADER_SIZE)
        try {
            videoInput.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        val packet = parsePacketHeader(header)
        if (packet is ScrcpyPacket.Session) return VideoPacket(packet, ByteArray(0))
        val size = (packet as ScrcpyPacket.Media).size
        val payload = ByteArray(size)
        videoInput.readFully(payload)
        return VideoPacket(packet, payload)
    }

    /**
     * 发一条控制消息（触摸/滚动）。
     *
     * ⚠️ **必须在专用线程上写，不能在调用者线程上直接写。**
     * 调用者是 Compose 的 pointerInput 回调 —— 那是**主线程**，而主线程上做网络 I/O 会
     * 被 Android 抛 `NetworkOnMainThreadException`。
     *
     * 这个异常特别难查：它由 BlockGuard 用**无参构造**抛出，`getMessage()` 是 **null**，
     * 于是日志里只剩一句「发送触摸失败：null」，看不出是网络限制；表现则是
     * 「画面好得很、就是一碰没反应」——因为服务端**一个字节都没收到**，
     * 连它那条「位置事件尺寸不匹配，忽略」的日志都不会出现。
     *
     * 单线程队列顺带保证了消息顺序（按下→移动→抬起不能乱序）。
     */
    fun sendControl(message: ByteArray) {
        writer.execute {
            runCatching {
                synchronized(controlOutput) {
                    controlOutput.write(message)
                    controlOutput.flush()
                }
            }.onFailure { Log.w(TAG, "写控制消息失败", it) }
        }
    }

    private fun drainControl() {
        runCatching {
            val input = control.getInputStream()
            val buffer = ByteArray(4096)
            while (input.read(buffer) >= 0) {
                // 全部丢弃：见类注释，不读会把服务端卡住
            }
        }
    }

    override fun close() {
        drain.interrupt()
        // 先让排在队里的控制消息写出去，再关 socket —— 反过来会把最后几个事件丢掉。
        // 这里不等它跑完：close() 可能被收尾路径调用，不能在那儿阻塞。
        writer.shutdown()
        runCatching { video.close() }
        runCatching { control.close() }
    }

    private companion object {
        const val TAG = "ScrcpyControl"
    }
}
