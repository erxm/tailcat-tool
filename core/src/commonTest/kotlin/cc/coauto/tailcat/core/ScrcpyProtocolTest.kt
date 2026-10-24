package cc.coauto.tailcat.core

import cc.coauto.tailcat.core.screen.AndroidKeyCode
import cc.coauto.tailcat.core.screen.KeyAction
import cc.coauto.tailcat.core.screen.POINTER_ID_GENERIC_FINGER
import cc.coauto.tailcat.core.screen.ScrcpyPacket
import cc.coauto.tailcat.core.screen.StreamHeader
import cc.coauto.tailcat.core.screen.TouchAction
import cc.coauto.tailcat.core.screen.encodeBackOrScreenOn
import cc.coauto.tailcat.core.screen.encodeKeyCode
import cc.coauto.tailcat.core.screen.encodeScroll
import cc.coauto.tailcat.core.screen.encodeTouch
import cc.coauto.tailcat.core.screen.floatToU16Fixed
import cc.coauto.tailcat.core.screen.parseDeviceName
import cc.coauto.tailcat.core.screen.parsePacketHeader
import cc.coauto.tailcat.core.screen.parseStreamHeader
import cc.coauto.tailcat.core.screen.scrollToI16Fixed
import cc.coauto.tailcat.core.screen.splitCsd
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * scrcpy 线上格式的解析与构造。
 *
 * 这些是最值得钉死的部分：位域读错一个 bit，真机上的表现是花屏或者「点了没反应」，
 * 两种都没有报错，只能靠肉眼反查。
 */
class ScrcpyProtocolTest {

    // ————————————— 12 字节头 —————————————

    @Test
    fun parsesKeyFrameHeader() {
        // 0x2000_0000_0000_0000 = key frame 位；低位是 PTS
        val pts = 0x0000_0000_1234_5678L
        val header = header(packed = PTS_FLAG_KEY or pts, size = 4096)
        val packet = parsePacketHeader(header)
        assertTrue(packet is ScrcpyPacket.Media)
        assertTrue(packet.isKeyFrame)
        assertTrue(!packet.isConfig)
        assertEquals(pts, packet.ptsMicros)
        assertEquals(4096, packet.size)
    }

    @Test
    fun parsesConfigPacket() {
        // config 包只有标志位、没有 PTS
        val header = header(packed = PTS_FLAG_CONFIG, size = 33)
        val packet = parsePacketHeader(header)
        assertTrue(packet is ScrcpyPacket.Media)
        assertTrue(packet.isConfig)
        assertTrue(!packet.isKeyFrame)
        assertEquals(0L, packet.ptsMicros)
        assertEquals(33, packet.size)
    }

    @Test
    fun ptsIsMaskedTo61Bits() {
        // PTS 占低 61 位，三个高位都是标志。取满值验证掩码没多切也没少切。
        val maxPts = 0x1FFF_FFFF_FFFF_FFFFL
        val packet = parsePacketHeader(header(packed = PTS_FLAG_KEY or maxPts, size = 1))
        assertEquals(maxPts, (packet as ScrcpyPacket.Media).ptsMicros)
    }

    @Test
    fun parsesSessionPacket() {
        // session packet 布局完全不同：[0..3] flags、[4..7] 宽、[8..11] 高
        // （官方 doc/develop.md 描述的是 4.0 的协议，和实际不符 —— 以 v5.0.1 源码为准）
        val bytes = ByteArray(12)
        bytes[0] = 0x80.toByte()          // session 标志在最高位
        writeInt(bytes, 4, 1280)
        writeInt(bytes, 8, 720)
        val packet = parsePacketHeader(bytes)
        assertTrue(packet is ScrcpyPacket.Session)
        assertEquals(1280, packet.width)
        assertEquals(720, packet.height)
        assertTrue(!packet.clientResized)
    }

    @Test
    fun parsesClientResizedFlag() {
        val bytes = ByteArray(12)
        bytes[0] = 0x80.toByte()
        bytes[3] = 0x01                    // client resized 在最低字节的最低位
        writeInt(bytes, 4, 1080)
        writeInt(bytes, 8, 1920)
        val packet = parsePacketHeader(bytes) as ScrcpyPacket.Session
        assertTrue(packet.clientResized)
        assertEquals(1080, packet.width)
    }

    // ————————————— 视频流头 / 设备名 —————————————

    @Test
    fun parsesStreamHeader() {
        assertContentEquals(
            listOf("h264"),
            listOf((parseStreamHeader(byteArrayOf(0x68, 0x32, 0x36, 0x34)) as StreamHeader.Codec).id),
        )
        // 两种「禁流」码要区分开：显式禁流是正常的，配置错误才是真出问题
        assertTrue(parseStreamHeader(byteArrayOf(0, 0, 0, 0)) is StreamHeader.DisabledByClient)
        assertTrue(parseStreamHeader(byteArrayOf(0, 0, 0, 1)) is StreamHeader.ConfigError)
        assertEquals(
            0x12345678,
            (parseStreamHeader(byteArrayOf(0x12, 0x34, 0x56, 0x78)) as StreamHeader.Unknown).raw,
        )
    }

    @Test
    fun parsesDeviceName() {
        val bytes = ByteArray(64)
        val name = "Xiaomi 2512BPNDAC"
        name.encodeToByteArray().copyInto(bytes)
        assertEquals(name, parseDeviceName(bytes))
        // 全 0 的字段应当得到空串，而不是一堆 NUL
        assertEquals("", parseDeviceName(ByteArray(64)))
    }

    // ————————————— SPS / PPS 切分 —————————————

    @Test
    fun splitsCsdWithFourByteStartCodes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val csd = splitCsd(sps + pps)
        assertTrue(csd != null)
        assertContentEquals(sps, csd.first)
        assertContentEquals(pps, csd.second)
    }

    @Test
    fun splitsCsdWithThreeByteStartCodes() {
        val sps = byteArrayOf(0, 0, 1, 0x67, 0x42)
        val pps = byteArrayOf(0, 0, 1, 0x68, 0xCE.toByte())
        val csd = splitCsd(sps + pps)
        assertTrue(csd != null)
        assertContentEquals(sps, csd.first)
        assertContentEquals(pps, csd.second)
    }

    @Test
    fun rejectsCsdWithoutPps() {
        // 只有 SPS：切不出来就返回 null，调用方退回「把 config 拼到下一帧前面」那条路
        assertNull(splitCsd(byteArrayOf(0, 0, 0, 1, 0x67, 0x42)))
        assertNull(splitCsd(byteArrayOf(1, 2, 3, 4)))
        assertNull(splitCsd(ByteArray(0)))
    }

    // ————————————— 触摸消息 —————————————

    /**
     * 逐字节比对 scrcpy 官方单测里的向量（`app/tests/test_control_msg_serialize.c`
     * 的 `test_serialize_inject_touch_event`）。
     *
     * 官方那个用例把 actionButton/buttons 设成了 PRIMARY(1)，而手指事件必须为 0
     * （服务端 `Controller` 会强制清零），所以只比前 24 字节，尾部单独断言为 0。
     */
    @Test
    fun encodesTouchLikeUpstreamVector() {
        val message = encodeTouch(
            action = TouchAction.Down,
            x = 100,
            y = 200,
            screenWidth = 1080,
            screenHeight = 1920,
            pressure = 1.0f,
            pointerId = 0x1234567887654321L,
        )

        assertEquals(32, message.size)
        val expectedPrefix = byteArrayOf(
            0x02,                                            // INJECT_TOUCH_EVENT
            0x00,                                            // ACTION_DOWN
            0x12, 0x34, 0x56, 0x78, 0x87.toByte(), 0x65, 0x43, 0x21, // pointer id
            0x00, 0x00, 0x00, 0x64,                          // x = 100
            0x00, 0x00, 0x00, (0xC8).toByte(),               // y = 200
            0x04, 0x38,                                      // width = 1080
            0x07, (0x80).toByte(),                           // height = 1920
            (0xFF).toByte(), (0xFF).toByte(),                // pressure = 1.0
        )
        assertContentEquals(expectedPrefix, message.copyOfRange(0, 24))
        // 手指事件不带按钮位
        assertContentEquals(ByteArray(8), message.copyOfRange(24, 32))
    }

    @Test
    fun encodesTouchActionsAndPressure() {
        val down = encodeTouch(TouchAction.Down, 0, 0, 100, 100, 1f)
        val move = encodeTouch(TouchAction.Move, 0, 0, 100, 100, 1f)
        val up = encodeTouch(TouchAction.Up, 0, 0, 100, 100, 0f)

        // ⚠️ UP 是 1、MOVE 是 2，和直觉顺序相反 —— 这里钉死
        assertEquals(0, down[1].toInt())
        assertEquals(2, move[1].toInt())
        assertEquals(1, up[1].toInt())

        assertEquals(0xFFFF, ((down[22].toInt() and 0xFF) shl 8) or (down[23].toInt() and 0xFF))
        assertEquals(0, up[22].toInt())
        assertEquals(0, up[23].toInt())
    }

    @Test
    fun encodesGenericFingerPointerIdAsTwosComplement() {
        val message = encodeTouch(TouchAction.Down, 0, 0, 100, 100, 1f)
        // -2 按 u64 补码应当是 0xFFFF_FFFF_FFFF_FFFE
        val expected = byteArrayOf(
            (0xFF).toByte(), (0xFF).toByte(), (0xFF).toByte(), (0xFF).toByte(),
            (0xFF).toByte(), (0xFF).toByte(), (0xFF).toByte(), (0xFE).toByte(),
        )
        assertContentEquals(expected, message.copyOfRange(2, 10))
        assertEquals(POINTER_ID_GENERIC_FINGER, -2L)
    }

    @Test
    fun pressureIsClampedToUnitRange() {
        assertEquals(0, floatToU16Fixed(-5f))
        assertEquals(0, floatToU16Fixed(0f))
        assertEquals(0x8000, floatToU16Fixed(0.5f))
        assertEquals(0xFFFF, floatToU16Fixed(1f))
        assertEquals(0xFFFF, floatToU16Fixed(9f))
    }

    // ————————————— 滚动消息 —————————————

    /** 同样比官方向量（`test_serialize_inject_scroll_event`），尾部 buttons 我们发 0。 */
    @Test
    fun encodesScrollLikeUpstreamVector() {
        val message = encodeScroll(
            x = 260,
            y = 1026,
            screenWidth = 1080,
            screenHeight = 1920,
            hscroll = 16f,
            vscroll = -16f,
        )

        assertEquals(21, message.size)
        val expectedPrefix = byteArrayOf(
            0x03,                                      // INJECT_SCROLL_EVENT
            0x00, 0x00, 0x01, 0x04,                    // x = 260
            0x00, 0x00, 0x04, 0x02,                    // y = 1026
            0x04, 0x38,                                // width = 1080
            0x07, (0x80).toByte(),                     // height = 1920
            0x7F, (0xFF).toByte(),                     // hscroll = 16 → 归一化到 1 → 0x7FFF
            (0x80).toByte(), 0x00,                     // vscroll = -16 → -1 → 0x8000
        )
        assertContentEquals(expectedPrefix, message.copyOfRange(0, 17))
        assertContentEquals(ByteArray(4), message.copyOfRange(17, 21))
    }

    @Test
    fun scrollIsNormalizedAndClamped() {
        // 规则是「先 /16 再夹到 [-1,1]」：所以 1 和 16 并**不**等价（1 只有 1/16 的力度），
        // 只有 ≥16 的值才会一起撞到上限。
        assertEquals(0x7FFF, scrollToI16Fixed(16f))
        assertEquals(0x7FFF, scrollToI16Fixed(100f))
        assertEquals(2048, scrollToI16Fixed(1f))
        assertEquals(-0x8000, scrollToI16Fixed(-16f))
        assertEquals(0x7FFF, scrollToI16Fixed(1000f))
        assertEquals(-0x8000, scrollToI16Fixed(-1000f))
        assertEquals(0, scrollToI16Fixed(0f))

        // ⚠️ 中间值必须单独断言。定点换算的常数一旦写错（比如把 32768f 误写成十六进制的
        // `0x8000f` —— 那个 `f` 是合法的十六进制数字，整个会被当成 Int），
        // 边界值会被后面的 clamp **掩盖过去**，只有中间值才暴露得出来。
        assertEquals(0x4000, scrollToI16Fixed(8f))
        assertEquals(-0x4000, scrollToI16Fixed(-8f))
    }

    // ————————————— 按键消息 —————————————

    /** 逐字节比对官方向量（`test_serialize_inject_keycode`）。 */
    @Test
    fun encodesKeyCodeLikeUpstreamVector() {
        val message = encodeKeyCode(
            action = KeyAction.UP,
            keyCode = 66, // AKEYCODE_ENTER
            repeat = 5,
            metaState = 0x41, // AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON
        )

        assertEquals(14, message.size)
        assertContentEquals(
            byteArrayOf(
                0x00,                   // INJECT_KEYCODE
                0x01,                   // AKEY_EVENT_ACTION_UP
                0x00, 0x00, 0x00, 0x42, // AKEYCODE_ENTER
                0x00, 0x00, 0x00, 0x05, // repeat
                0x00, 0x00, 0x00, 0x41, // metastate
            ),
            message,
        )
    }

    /** 逐字节比对官方向量（`test_serialize_back_or_screen_on`）。 */
    @Test
    fun encodesBackOrScreenOnLikeUpstreamVector() {
        val message = encodeBackOrScreenOn(KeyAction.UP)
        assertEquals(2, message.size)
        assertContentEquals(byteArrayOf(0x04, 0x01), message)
    }

    /**
     * 发给**被控端**的三个导航键。
     *
     * 返回走专用消息（屏幕熄灭时它的语义是「点亮屏幕」，只有服务端判断得了）；
     * 桌面/多任务走按键注入，且必须成对发 DOWN + UP —— 只发 DOWN 等于一直按着不放。
     */
    @Test
    fun encodesNavigationKeys() {
        assertEquals(3, AndroidKeyCode.HOME)
        assertEquals(187, AndroidKeyCode.APP_SWITCH)

        // [type=0][action=DOWN=0][keycode:4][repeat:4][metastate:4]
        assertContentEquals(
            byteArrayOf(
                0x00, 0x00,
                0x00, 0x00, 0x00, 0x03, // KEYCODE_HOME
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            ),
            encodeKeyCode(KeyAction.DOWN, AndroidKeyCode.HOME),
        )
        assertContentEquals(
            byteArrayOf(
                0x00, 0x00,
                0x00, 0x00, 0x00, 0xBB.toByte(), // KEYCODE_APP_SWITCH = 187
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
            ),
            encodeKeyCode(KeyAction.DOWN, AndroidKeyCode.APP_SWITCH),
        )
        // 同键的 UP 只有第 1 字节不同
        assertEquals(
            KeyAction.UP.toByte(),
            encodeKeyCode(KeyAction.UP, AndroidKeyCode.HOME)[1],
        )
    }

    // ————————————— 辅助 —————————————

    private fun header(packed: Long, size: Int): ByteArray {
        val bytes = ByteArray(12)
        for (i in 0 until 8) bytes[i] = (packed ushr (56 - 8 * i)).toByte()
        writeInt(bytes, 8, size)
        return bytes
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private companion object {
        const val PTS_FLAG_CONFIG = 1L shl 62
        const val PTS_FLAG_KEY = 1L shl 61
    }
}
