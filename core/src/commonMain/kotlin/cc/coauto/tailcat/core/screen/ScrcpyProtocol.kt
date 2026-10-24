package cc.coauto.tailcat.core.screen

/*
 * scrcpy 协议里**纯字节**的那一半。
 *
 * 为什么放在 core：这里全是「字节数组进、数据类出」的纯逻辑，不碰 socket 也不碰 Android API，
 * 而它恰恰是最该被单测钉死的部分 —— 一个 bit 读错，表现是花屏或者「点了没反应」，
 * 两种都很难在真机上反查。socket / MediaCodec / 触摸手势那些留在 android-shell。
 *
 * 协议对照 Genymobile/scrcpy 的 **tag v5.0.1**（commit a0c27ff）核实过，不是照文档抄的 ——
 * 官方 doc 描述的是 4.0，其中「session packet 的宽高在哪几个字节」就和实际不符。
 */

/** 每个包（session packet 与 media packet）都是 12 字节头。 */
public const val SCRCPY_HEADER_SIZE: Int = 12

/** 设备名固定 64 字节，UTF-8，不足部分补 0。写在第一条 socket 上（dummy 字节之后）。 */
public const val SCRCPY_DEVICE_NAME_SIZE: Int = 64

/** big-endian 的 "h264"。 */
public const val CODEC_ID_H264: Int = 0x68323634

/** 单指触摸用的 pointerId。scrcpy 的 `SC_POINTER_ID_GENERIC_FINGER`，按 u64 存 -2。 */
public const val POINTER_ID_GENERIC_FINGER: Long = -2L

/** 触摸事件的动作码。⚠️ 注意 UP 是 1、MOVE 是 2 —— 和直觉的顺序不一样。 */
public enum class TouchAction(public val code: Int) {
    Down(0),
    Up(1),
    Move(2),
}

/** control socket 上的消息类型码（`app/src/control_msg.h` 的枚举顺序）。 */
public const val CONTROL_MSG_KEYCODE: Int = 0
public const val CONTROL_MSG_TOUCH: Int = 2
public const val CONTROL_MSG_SCROLL: Int = 3
public const val CONTROL_MSG_BACK_OR_SCREEN_ON: Int = 4

/**
 * 按键动作码。数值和 `android.view.KeyEvent.ACTION_*`、以及 scrcpy 的 `sc_action` 一致。
 */
public object KeyAction {
    public const val DOWN: Int = 0
    public const val UP: Int = 1
}

/**
 * 要发给**被控端**时用的 Android 键码（`android.view.KeyEvent.KEYCODE_*`）。
 *
 * 这里只列我们真正会发的那几个：宿主机的返回/桌面/多任务手势没法完全屏蔽，
 * 所以改成提供按钮，把这三个键**发到被控端**去执行 —— 这样操作手感才是对的。
 */
public object AndroidKeyCode {
    public const val HOME: Int = 3
    public const val APP_SWITCH: Int = 187
}

// ————————————————— 视频流的第一个 4 字节 —————————————————

/**
 * 视频流开头 4 字节的三种可能。
 *
 * 不是 codec id 的时候有两种「禁流」码，**必须区分开**：显式禁流是正常的（比如设备没解锁），
 * 配置错误才是真的出问题了。直接当成 codec id 去比对会得到一句莫名其妙的「未知编码」。
 */
public sealed interface StreamHeader {
    /** 正常：后面跟 codec 名，目前只支持 h264。 */
    public data class Codec(val id: String) : StreamHeader

    /** 客户端显式要求不传视频。 */
    public data object DisabledByClient : StreamHeader

    /** 服务端起视频失败（例如拿不到 display）。 */
    public data object ConfigError : StreamHeader

    public data class Unknown(val raw: Int) : StreamHeader
}

public fun parseStreamHeader(bytes: ByteArray): StreamHeader {
    require(bytes.size >= 4) { "视频流头至少 4 字节" }
    val raw = readInt32BE(bytes, 0)
    return when (raw) {
        CODEC_ID_H264 -> StreamHeader.Codec("h264")
        0 -> StreamHeader.DisabledByClient
        1 -> StreamHeader.ConfigError
        else -> StreamHeader.Unknown(raw)
    }
}

// ————————————————— 12 字节头 —————————————————

/**
 * 12 字节头的两种含义。
 *
 * 做成 sealed 而不是「一个带一堆 flag 的数据类」，是因为**两种包的字段布局根本不同**：
 * session packet 是 `[flags 4B][width 4B][height 4B]`，media packet 是
 * `[flags+PTS 8B][size 4B]`。用同一个数据类接，很容易把 session 的宽当成 size 用，
 * 而且不会编译报错 —— 那种错要到画面全黑才看得出来。
 */
public sealed interface ScrcpyPacket {
    public data class Session(
        val width: Int,
        val height: Int,
        /** 帧尺寸变化是客户端要求 resize 引起的（flex display）。 */
        val clientResized: Boolean,
    ) : ScrcpyPacket

    public data class Media(
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        /** 微秒。config 包里没有 PTS，此时为 0。 */
        val ptsMicros: Long,
        /** 后面跟的裸数据长度。 */
        val size: Int,
    ) : ScrcpyPacket
}

/** 解析 12 字节头。传入的数组至少 12 字节；多出来的部分不看。 */
public fun parsePacketHeader(header: ByteArray): ScrcpyPacket {
    require(header.size >= SCRCPY_HEADER_SIZE) { "包头是 12 字节" }
    if (header[0].toInt() and 0x80 != 0) {
        // session packet：[0..3] flags（bit31=session，bit0=client resized），[4..7] 宽，[8..11] 高
        return ScrcpyPacket.Session(
            width = readInt32BE(header, 4),
            height = readInt32BE(header, 8),
            clientResized = header[3].toInt() and 0x01 != 0,
        )
    }
    // media packet：头 8 字节是大端 u64，高 3 位是标志、低 61 位是 PTS
    val packed = readInt64BE(header, 0)
    return ScrcpyPacket.Media(
        isConfig = packed and FLAG_CONFIG != 0L,
        isKeyFrame = packed and FLAG_KEY_FRAME != 0L,
        ptsMicros = packed and PTS_MASK,
        size = readInt32BE(header, 8),
    )
}

private const val FLAG_SESSION: Long = 1L shl 63
private const val FLAG_CONFIG: Long = 1L shl 62
private const val FLAG_KEY_FRAME: Long = 1L shl 61
private const val PTS_MASK: Long = FLAG_KEY_FRAME - 1

// ————————————————— 握手 —————————————————

/** 设备名是 64 字节定长、NUL 填充；这里把尾部 NUL 和 UTF-8 一起处理掉。 */
public fun parseDeviceName(bytes: ByteArray, offset: Int = 0): String {
    var end = offset
    val limit = minOf(offset + SCRCPY_DEVICE_NAME_SIZE, bytes.size)
    while (end < limit && bytes[end] != 0.toByte()) end++
    return bytes.decodeToString(startIndex = offset, endIndex = end)
}

// ————————————————— H.264 参数集 —————————————————

/**
 * 从 config 包里切出 SPS 与 PPS，**都保留起始码**。
 *
 * scrcpy 传的是 Annex-B 裸流，config 包一般就是 `SPS + PPS` 两段。
 * 切不出来就返回 null —— 调用方此时应当退回 scrcpy 官方客户端的做法：
 * 把整个 config 包**拼到下一个媒体包前面**喂给解码器，让解码器自己在流里认。
 * 两条路都留着是因为不同 OEM 的 MediaCodec 对 csd 形式挑剔程度不一样。
 */
public fun splitCsd(configPacket: ByteArray): Pair<ByteArray, ByteArray>? {
    val startCodes = ArrayList<Int>(4)
    var i = 0
    while (i + 3 <= configPacket.size) {
        if (configPacket[i] == 0.toByte() && configPacket[i + 1] == 0.toByte()) {
            val fourByte = i + 4 <= configPacket.size &&
                configPacket[i + 2] == 0.toByte() && configPacket[i + 3] == 1.toByte()
            val threeByte = configPacket[i + 2] == 1.toByte()
            when {
                fourByte -> { startCodes += i; i += 4; continue }
                threeByte -> { startCodes += i; i += 3; continue }
            }
        }
        i++
    }
    if (startCodes.size < 2) return null

    fun headerLength(at: Int): Int =
        if (configPacket[at + 2] == 0.toByte()) 4 else 3

    fun nalType(at: Int): Int {
        val payload = at + headerLength(at)
        return if (payload < configPacket.size) configPacket[payload].toInt() and 0x1F else -1
    }

    val spsAt = startCodes.firstOrNull { nalType(it) == NAL_SPS } ?: return null
    val ppsAt = startCodes.firstOrNull { it > spsAt && nalType(it) == NAL_PPS } ?: return null

    val sps = configPacket.copyOfRange(spsAt, ppsAt)
    val pps = configPacket.copyOfRange(ppsAt, configPacket.size)
    if (sps.isEmpty() || pps.isEmpty()) return null
    return sps to pps
}

private const val NAL_SPS = 7
private const val NAL_PPS = 8

// ————————————————— 控制消息 —————————————————

/**
 * 触摸消息，固定 32 字节。
 *
 * 两个容易踩的点，都在服务端那边**静默**发生：
 *  - `x/y` 是**视频帧像素坐标**（0..sessionW），不是归一化值、也不是设备物理屏坐标；
 *  - `screenSize` 必须**精确等于**当前 session 的尺寸，不一致服务端直接丢弃这次事件，
 *    只在 verbose 日志里留一句 —— 表现出来就是「点了没反应」，没有任何报错。
 *
 * `actionButton` / `buttons` 一律 0：手指事件带按钮位会被服务端忽略（它自己会强制清零）。
 */
public fun encodeTouch(
    action: TouchAction,
    x: Int,
    y: Int,
    screenWidth: Int,
    screenHeight: Int,
    pressure: Float,
    pointerId: Long = POINTER_ID_GENERIC_FINGER,
): ByteArray {
    val out = ByteArray(32)
    out[0] = CONTROL_MSG_TOUCH.toByte()
    out[1] = action.code.toByte()
    writeInt64BE(out, 2, pointerId)
    writeInt32BE(out, 10, x)
    writeInt32BE(out, 14, y)
    writeInt16BE(out, 18, screenWidth)
    writeInt16BE(out, 20, screenHeight)
    writeInt16BE(out, 22, floatToU16Fixed(pressure))
    return out
}

/**
 * 滚动消息，固定 21 字节。
 *
 * 滚动量不是原样传：服务端会把它先除以 16 再夹到 [-1,1]，然后按 `f * 0x8000` 编成定点数。
 * 这里做同样的归一化，这样「一格」传 16 和传 1 出来的效果一致。
 */
public fun encodeScroll(
    x: Int,
    y: Int,
    screenWidth: Int,
    screenHeight: Int,
    hscroll: Float,
    vscroll: Float,
): ByteArray {
    val out = ByteArray(21)
    out[0] = CONTROL_MSG_SCROLL.toByte()
    writeInt32BE(out, 1, x)
    writeInt32BE(out, 5, y)
    writeInt16BE(out, 9, screenWidth)
    writeInt16BE(out, 11, screenHeight)
    writeInt16BE(out, 13, scrollToI16Fixed(hscroll))
    writeInt16BE(out, 15, scrollToI16Fixed(vscroll))
    return out
}

/**
 * 按键注入消息，固定 14 字节。
 *
 * 布局对着官方 C 客户端 `app/src/control_msg.c` 的 `sc_control_msg_serialize()` 核过：
 * `[type][action][keycode:4][repeat:4][metastate:4]`。
 *
 * 一般要**成对**发 DOWN + UP，只发 DOWN 相当于一直按着不放。
 */
public fun encodeKeyCode(
    action: Int,
    keyCode: Int,
    repeat: Int = 0,
    metaState: Int = 0,
): ByteArray {
    val out = ByteArray(14)
    out[0] = CONTROL_MSG_KEYCODE.toByte()
    out[1] = action.toByte()
    writeInt32BE(out, 2, keyCode)
    writeInt32BE(out, 6, repeat)
    writeInt32BE(out, 10, metaState)
    return out
}

/**
 * 「返回键 / 点亮屏幕」消息，固定 2 字节。
 *
 * 为什么返回键不走 [encodeKeyCode]：**屏幕熄灭时它的语义是「点亮屏幕」而不是返回**，
 * 这个判断只有服务端做得了（它才知道被控端屏幕的状态）。走专用的这条消息，
 * 服务端会自己决定是注入返回还是按电源键。
 */
public fun encodeBackOrScreenOn(action: Int): ByteArray {
    val out = ByteArray(2)
    out[0] = CONTROL_MSG_BACK_OR_SCREEN_ON.toByte()
    out[1] = action.toByte()
    return out
}

/** `f * 65536`，夹到 [0, 0xFFFF]。 */
internal fun floatToU16Fixed(value: Float): Int =
    (value.coerceIn(0f, 1f) * 65536f).toInt().coerceIn(0, 0xFFFF)

/** 先 `f/16` 夹到 [-1,1]，再 `f * 32768`。 */
internal fun scrollToI16Fixed(value: Float): Int =
    ((value / 16f).coerceIn(-1f, 1f) * 32768f).toInt().coerceIn(-0x8000, 0x7FFF)

// ————————————————— 字节读写（大端） —————————————————

internal fun readInt32BE(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

internal fun readInt64BE(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
    return value
}

internal fun writeInt32BE(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

internal fun writeInt16BE(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value ushr 8).toByte()
    bytes[offset + 1] = value.toByte()
}

/**
 * 写 u64。注意调用方可能传 -2（generic finger），要按**补码**写满 8 字节 ——
 * `ushr` 正好给出这个语义，别改成算术右移。
 */
internal fun writeInt64BE(bytes: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) bytes[offset + i] = (value ushr (56 - 8 * i)).toByte()
}
