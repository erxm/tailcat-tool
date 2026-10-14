package cc.coauto.tailcat.desktop.win

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 单实例互斥。
 *
 * 用**文件锁**而不是命名互斥体：`LockFileEx` 由内核在进程退出（包括被强杀）时自动释放，
 * 不存在陈旧锁的问题；而且是纯 Java API，不必为「读 last error」去和 JNA 的调用约定纠缠
 * （JNA 声明的 `GetLastError()` 夹在其它跨越 native 边界的调用之间并不可靠）。
 *
 * 这也比原 macOS 版靠 bundle id 找同名进程更可靠。
 */
public object SingleInstance {

    private const val LOCK_FILE = "instance.lock"
    private const val ACTIVATE_REQUEST = "activate.request"

    private var channel: RandomAccessFile? = null
    private var lock: FileLock? = null
    private var directory: String? = null

    /** @return true 表示本进程是唯一实例；false 表示已有实例在运行。 */
    public fun acquire(directory: String): Boolean {
        this.directory = directory
        return try {
            val file = File(directory, LOCK_FILE)
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            val acquired = try {
                raf.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // 同一个 JVM 内重复加锁
                null
            } catch (_: IOException) {
                // 锁已被**另一个进程**持有：Windows 上这里抛 IOException 而不是返回 null
                null
            }
            if (acquired == null) {
                runCatching { raf.close() }
                false
            } else {
                channel = raf
                lock = acquired
                true
            }
        } catch (_: Throwable) {
            // 拿不到锁文件不该把用户挡在门外。
            true
        }
    }

    /**
     * 第二个实例调用：请求已有实例把主窗口显示到前台。
     *
     * 走文件而不是窗口消息（`PostMessage`/`SetForegroundWindow`）：后者要在别人的窗口过程上做文章，
     * 而且**必须是窗口自己在 Compose 里把 `visible` 置回 true**——从外部把原生窗口显示出来，
     * Compose 侧的状态是不同步的，下次关闭就再也收不回去了。
     */
    public fun requestActivation() {
        val dir = directory ?: return
        runCatching { File(dir, ACTIVATE_REQUEST).writeText("1", Charsets.UTF_8) }
    }

    /**
     * 已有实例侧调用：有唤醒请求就消费掉并返回 true。
     *
     * 删文件而不是读后清空——删除是原子的，同时到达只会有一个赢家。
     */
    public fun consumeActivationRequest(): Boolean {
        val dir = directory ?: return false
        val file = File(dir, ACTIVATE_REQUEST)
        if (!file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }
}
