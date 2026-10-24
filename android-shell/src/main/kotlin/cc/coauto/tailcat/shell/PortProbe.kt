package cc.coauto.tailcat.shell

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 判断本机某个端口上到底有没有服务在监听。
 *
 * 壳只负责把端口通过隧道暴露出去；如果那个端口后面本来就是空的，隧道照样建得起来，
 * 但对端永远等不到响应。最常见的例子是忘了开 adb over TCP：
 * `service.adb.tcp.port` 没设，adbd 就不在 5555 上，于是电脑端一直停在「ADB 尚未上线」，
 * 而手机上看起来一切正常——这个提示就是为了把这种情况直接说出来。
 *
 * 做法是发起一次 TCP 连接：端口没人监听会立刻被拒（ECONNREFUSED），有人监听则连接成功，
 * 立刻关掉即可。adbd 这类服务完全能容忍这种空连接。
 */
object PortProbe {

    public fun isListening(port: Int, timeoutMillis: Int = 400): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), timeoutMillis)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 5555 是 adb 的约定端口，对它有专门的解释和指引。 */
    public const val ADB_PORT: Int = 5555
}
