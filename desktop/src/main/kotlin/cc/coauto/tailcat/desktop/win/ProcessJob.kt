package cc.coauto.tailcat.desktop.win

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT

/**
 * kernel32 里 Job Object 相关的那三个调用。
 *
 * JNA 自带的 `Kernel32` 只有 `OpenProcess` / `CloseHandle`，**没有**这几个，也没有
 * `JOBOBJECT_*` 结构体（已对 5.19.1 的 jar 逐个 `javap` 确认过），所以照
 * [ConsoleHost] 的做法自己声明一个 `Library`。
 */
private interface Kernel32Job : Library {
    fun CreateJobObjectW(attributes: Pointer?, name: Pointer?): Pointer?
    fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Structure, length: Int): Boolean
    fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
}

/** `JOBOBJECT_BASIC_LIMIT_INFORMATION`，x64 上 64 字节。 */
@Structure.FieldOrder(
    "perProcessUserTimeLimit", "perJobUserTimeLimit", "limitFlags",
    "minimumWorkingSetSize", "maximumWorkingSetSize", "activeProcessLimit",
    "affinity", "priorityClass", "schedulingClass",
)
internal class BasicLimitInformation : Structure() {
    @JvmField var perProcessUserTimeLimit: Long = 0
    @JvmField var perJobUserTimeLimit: Long = 0
    @JvmField var limitFlags: Int = 0

    // SIZE_T —— x64 上是 8 字节，且**要求 8 字节对齐**，所以 limitFlags 后面有 4 字节空洞。
    // JNA 按 Java 类型给 long 做 8 字节对齐，正好和 C 的布局一致（下面用尺寸断言钉住）。
    @JvmField var minimumWorkingSetSize: Long = 0
    @JvmField var maximumWorkingSetSize: Long = 0
    @JvmField var activeProcessLimit: Int = 0
    @JvmField var affinity: Long = 0
    @JvmField var priorityClass: Int = 0
    @JvmField var schedulingClass: Int = 0
}

/** `IO_COUNTERS`，48 字节。我们不用它，但它在结构体中间，不能省。 */
@Structure.FieldOrder(
    "readOperationCount", "writeOperationCount", "otherOperationCount",
    "readTransferCount", "writeTransferCount", "otherTransferCount",
)
internal class IoCounters : Structure() {
    @JvmField var readOperationCount: Long = 0
    @JvmField var writeOperationCount: Long = 0
    @JvmField var otherOperationCount: Long = 0
    @JvmField var readTransferCount: Long = 0
    @JvmField var writeTransferCount: Long = 0
    @JvmField var otherTransferCount: Long = 0
}

/**
 * `JOBOBJECT_EXTENDED_LIMIT_INFORMATION`，x64 上 144 字节（64 + 48 + 4×8）。
 *
 * 我们只用它的 `limitFlags` 一个字段，但**结构体的形状必须完整** ——
 * `SetInformationJobObject` 是按 `infoClass` 去解释这块内存的，
 * 少一个字段就会读到别人的数据，而失败是静默的。
 */
@Structure.FieldOrder(
    "basicLimitInformation", "ioInfo",
    "processMemoryLimit", "jobMemoryLimit",
    "peakProcessMemoryUsed", "peakJobMemoryUsed",
)
internal class ExtendedLimitInformation : Structure() {
    @JvmField var basicLimitInformation = BasicLimitInformation()
    @JvmField var ioInfo = IoCounters()
    @JvmField var processMemoryLimit: Long = 0
    @JvmField var jobMemoryLimit: Long = 0
    @JvmField var peakProcessMemoryUsed: Long = 0
    @JvmField var peakJobMemoryUsed: Long = 0
}

/**
 * 一个 Job Object 句柄。
 *
 * **句柄必须一直开着** —— `KILL_ON_JOB_CLOSE` 的语义是「最后一个句柄关闭时杀掉组内所有进程」。
 * 应用正常退出、被任务管理器强杀、或者崩溃，内核都会关掉这个句柄，于是组内的
 * `tailcat` / `adb` / `scrcpy` 子进程被一并回收。这正是「正常退出我们已经会收子进程树，
 * 但强杀/崩溃会留下孤儿」这个缺口的正解。
 */
internal class JobHandle private constructor(private val handle: Pointer) {

    /** 把某个子进程纳入这个 Job。返回是否成功。 */
    fun assign(pid: Long): Boolean {
        // 只需要这两个权限；用不到别的，少要一点是一点。
        val access = PROCESS_SET_QUOTA or PROCESS_TERMINATE
        val process = Kernel32.INSTANCE.OpenProcess(access, false, pid.toInt()) ?: return false
        return try {
            kernel32.AssignProcessToJobObject(handle, process.pointer)
        } finally {
            // OpenProcess 拿到的句柄要还回去；不还就是每次 fork 泄漏一个。
            Kernel32.INSTANCE.CloseHandle(process)
        }
    }

    /** 主动关闭句柄（会顺带杀掉组内进程）。应用运行期间**不要**调，只有测试会用到。 */
    fun close() {
        Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(handle))
    }

    companion object {
        private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000
        private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
        private const val PROCESS_TERMINATE = 0x0001
        private const val PROCESS_SET_QUOTA = 0x0100

        private val kernel32: Kernel32Job by lazy {
            Native.load("kernel32", Kernel32Job::class.java)
        }

        fun create(): JobHandle? {
            // 尺寸断言：结构体布局一旦写错，SetInformationJobObject 会**静默失败**，
            // 表现为「什么都没发生、孤儿照样留着」，极难查。这里让它在启动时就炸出来。
            val info = ExtendedLimitInformation()
            check(info.size() == 144) {
                "JOBOBJECT_EXTENDED_LIMIT_INFORMATION 的布局不对：期望 144 字节，实际 ${info.size()} 字节"
            }

            val handle = kernel32.CreateJobObjectW(null, null) ?: return null
            info.basicLimitInformation.limitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            val ok = kernel32.SetInformationJobObject(
                handle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                info,
                info.size(),
            )
            if (!ok) {
                Kernel32.INSTANCE.CloseHandle(WinNT.HANDLE(handle))
                return null
            }
            return JobHandle(handle)
        }
    }
}

/**
 * 子进程的「生命线」：把每个由本应用启动的进程纳入同一个 Job Object。
 *
 * 为什么不能只靠 `JavaRunningProcess.stop`：那只覆盖「应用正常退出」这一条路径。
 * 用户从任务管理器强杀、或者应用自己崩了，回收代码根本没机会跑，被遗弃的
 * `tailcat forward` 会继续占着本地监听端口，下次启动就会撞上「端口被占用」。
 */
public object ProcessJob {

    private val job: JobHandle? by lazy {
        // JNA 不可用时 JobHandle.create() 会抛 ExceptionInInitializerError，
        // 以前这一下会让**每一次**起进程都失败（用户看到的就是那句
        // "Could not initialize class com.sun.jna.Native"）。现在只丢兜底，不影响功能。
        val created = runCatching { JobHandle.create() }
            .onFailure { JnaEnvironment.record("ProcessJob.create", it) }
            .getOrNull()
        if (created == null) {
            // 只报一次，且不抛：拿不到 Job 只是少了兜底，不该拦着应用启动。
            System.err.println("[tailcat] 无法创建 Job Object，强杀后的子进程将不会被自动回收")
        }
        created
    }

    /** 尽力而为：失败不抛异常，只说明这个子进程失去了强杀兜底。 */
    public fun assign(pid: Long) {
        runCatching { job?.assign(pid) }
            .onFailure { JnaEnvironment.record("ProcessJob.assign", it) }
    }
}
