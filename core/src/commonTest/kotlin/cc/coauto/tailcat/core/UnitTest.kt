package cc.coauto.tailcat.core

import cc.coauto.tailcat.core.adb.parseAdbDevices
import cc.coauto.tailcat.core.adb.ProcessAdbClient
import cc.coauto.tailcat.core.creds.Credentials
import cc.coauto.tailcat.core.creds.OutputRedactor
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.newId
import cc.coauto.tailcat.core.transport.ForwardOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialsTest {

    private val address = "tc" + "A".repeat(30)

    @Test
    fun plausibility() {
        assertTrue(Credentials.isPlausible(address))
        assertFalse(Credentials.isPlausible("tcSHORT"))
        assertFalse(Credentials.isPlausible("http://example.com"))
        assertFalse(Credentials.isPlausible(""))
        // 规范上限 8192，超过即拒绝
        assertFalse(Credentials.isPlausible("tc" + "A".repeat(8192)))
    }

    @Test
    fun redactionRemovesAddresses() {
        val text = "connecting to $address now"
        val redacted = Credentials.redact(text, address)
        assertFalse(redacted.contains(address))
        assertTrue(redacted.contains(Credentials.REDACTED))
    }

    @Test
    fun redactionCatchesUnknownAddresses() {
        val other = "tc" + "B".repeat(40)
        assertFalse(Credentials.redact("value=$other").contains(other))
    }

    @Test
    fun redactorNeverLeaksAcrossChunkBoundaries() {
        // 凭据被管道恰好切成两半时，绝不能把前半段送到界面
        val redactor = OutputRedactor(address)
        val half = address.length / 2
        val first = redactor.append("prefix " + address.substring(0, half))
        val second = redactor.append(address.substring(half) + " suffix\n")
        assertFalse(first.contains(address.substring(0, half)))
        assertFalse(second.contains(address))
        assertTrue(second.contains(Credentials.REDACTED))
    }

    @Test
    fun redactorFlushEmitsTrailingPartialLine() {
        val redactor = OutputRedactor(address)
        redactor.append("no newline here")
        assertEquals("no newline here", redactor.flush())
        assertEquals("", redactor.flush())
    }

    @Test
    fun newIdIsUniqueAndWellFormed() {
        val a = newId()
        val b = newId()
        assertNotEquals(a, b)
        assertEquals(36, a.length)
        assertEquals(4, a.count { it == '-' })
    }
}

class ForwardOutputTest {

    @Test
    fun parsesListeningPort() {
        val output = "# forwarding 127.0.0.1:5556 -> remote localhost:5555\n"
        assertEquals(5556, ForwardOutput.listeningPort(output, 5555))
    }

    @Test
    fun toleratesCarriageReturn() {
        // Windows 上子进程输出是 CRLF，正则必须能收尾匹配
        val output = "# forwarding 127.0.0.1:5556 -> remote localhost:5555\r\n"
        assertEquals(5556, ForwardOutput.listeningPort(output, 5555))
    }

    @Test
    fun ignoresMismatchedRemotePort() {
        val output = "# forwarding 127.0.0.1:5556 -> remote localhost:5555\n"
        assertNull(ForwardOutput.listeningPort(output, 5554))
    }

    @Test
    fun ignoresUnrelatedOutput() {
        assertNull(ForwardOutput.listeningPort("starting up\n", 5555))
        assertNull(ForwardOutput.listeningPort("", 5555))
    }
}

class AdbOutputTest {

    @Test
    fun parsesDevicesList() {
        val output = """
            List of devices attached
            127.0.0.1:5556	device product:fuxi model:2211133C device:fuxi transport_id:1
            127.0.0.1:5557	unauthorized
        """.trimIndent()
        val devices = parseAdbDevices(output)
        assertEquals(2, devices.size)
        assertEquals(ConnectionState.Online, devices[0].state)
        assertEquals("2211133C", devices[0].model)
        assertEquals("fuxi", devices[0].product)
        assertEquals(ConnectionState.Unauthorized, devices[1].state)
        assertEquals("—", devices[1].model)
    }

    @Test
    fun parsesStateOutput() {
        val adb = ProcessAdbClient("/test/adb")
        assertEquals(ConnectionState.Online, adb.parseState("device\n", 0))
        assertEquals(ConnectionState.Unauthorized, adb.parseState("error: device unauthorized\n", 1))
        assertEquals(ConnectionState.Offline, adb.parseState("error: device offline\n", 1))
        assertEquals(ConnectionState.Offline, adb.parseState("", 1))
    }

    @Test
    fun buildsSerialScopedCommands() {
        val adb = ProcessAdbClient("/test/adb")
        assertEquals(listOf("-s", "127.0.0.1:5556", "get-state"), adb.stateCommand("127.0.0.1:5556").arguments)
        assertEquals(listOf("connect", "127.0.0.1:5556"), adb.connectCommand("127.0.0.1:5556").arguments)
        assertEquals(listOf("disconnect", "127.0.0.1:5556"), adb.disconnectCommand("127.0.0.1:5556").arguments)
        assertEquals(
            listOf("-s", "127.0.0.1:5556", "install", "-r", "/tmp/a b.apk"),
            adb.installCommand("127.0.0.1:5556", "/tmp/a b.apk").arguments,
        )
    }

    @Test
    fun privateServerPortIsAppliedToEveryCommand() {
        val adb = ProcessAdbClient("/test/adb", serverPort = 5038)
        assertEquals(listOf("-P", "5038", "connect", "ser"), adb.connectCommand("ser").arguments)
        assertEquals("5038", adb.connectCommand("ser").environment["ANDROID_ADB_SERVER_PORT"])
    }
}
