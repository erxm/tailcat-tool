package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.proc.Command
import kotlinx.coroutines.runBlocking
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** 问内核：这个进程在不在某个 Job 里。用来验证 spawner 真的做了 assign。 */
private interface Kernel32Query : Library {
    fun IsProcessInJob(process: Pointer, job: Pointer?, result: IntByReference): Boolean
}

/**
 * Job Object 的回收语义。
 *
 * ⚠️ 这些用例**真的会起进程、也真的会杀进程**：它启动一个长命子进程、纳入 Job、
 * 然后主动关掉 Job 句柄 —— 那正是应用被强杀（或崩溃）时内核替我们做的事 ——
 * 并断言子进程随之退出。
 *
 * 为什么要这么测：`SetInformationJobObject` 失败是**静默的**（返回值被忽略的话），
 * 结构体少一个字段也会读错内存但照样「返回成功」。只有让一个真实进程去死，
 * 才能证明 `KILL_ON_JOB_CLOSE` 这条链路真的接上了。结构体尺寸另有 `create()` 里的断言兜底。
 */
class ProcessJobTest {

    @Test
    fun closingJobHandleReclaimsAssignedChildren() {
        val job = JobHandle.create() ?: error("创建 Job Object 失败")
        val child = startLongLivedChild()
        try {
            assertTrue(job.assign(child.pid()), "把子进程纳入 Job 失败")
            assertTrue(child.isAlive, "子进程应当仍在运行（否则这个用例什么都没测到）")

            job.close()

            assertTrue(
                child.waitFor(10, TimeUnit.SECONDS),
                "关掉 Job 句柄后子进程没被回收 —— KILL_ON_JOB_CLOSE 没生效",
            )
        } finally {
            child.destroyForcibly()
        }
    }

    /** 对照组：证明上一条里那个子进程是被 Job 杀的，而不是被别的什么东西误伤。 */
    @Test
    fun childrenOutsideTheJobAreUnaffected() {
        val job = JobHandle.create() ?: error("创建 Job Object 失败")
        val child = startLongLivedChild()
        try {
            job.close()
            Thread.sleep(1_000)
            assertTrue(child.isAlive, "没纳入 Job 的进程不该受关句柄影响")
        } finally {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /**
     * 集成检查：**真实的** spawner 是否把子进程纳入了 Job。
     *
     * 前两个用例证明「Job 能把进程收走」，这一个证明「我们的进程真的进了 Job」——
     * 少任何一半，孤儿都照样留着。
     */
    @Test
    fun spawnerPutsChildrenIntoAJob() {
        val kernel32 = Native.load("kernel32", Kernel32Query::class.java)
        val spawner = JavaProcessSpawner()

        val viaSpawner = spawner.start(Command("ping", listOf("-n", "60", "127.0.0.1")), {}, {})
        // 对照组：直接用 ProcessBuilder 起、不经 spawner
        val plain = startLongLivedChild()
        try {
            val spawnedInJob = inAnyJob(kernel32, viaSpawner.pid)
            val plainInJob = inAnyJob(kernel32, plain.pid())
            assertTrue(spawnedInJob, "spawner 起的子进程不在任何 Job 里 —— assign 那一行没生效")
            if (plainInJob) {
                // 说明测试进程本身就在某个 Job 里（Gradle worker 常见），子进程会继承它，
                // 那么这个用例就分不出差异了 —— 明确说出来，别让它伪装成通过。
                println("[跳过判据] 本测试进程自身处于 Job 中，两组都命中，无法区分")
            }
        } finally {
            runBlocking { viaSpawner.stop() }
            plain.destroyForcibly()
            plain.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun inAnyJob(kernel32: Kernel32Query, pid: Long): Boolean {
        val handle = com.sun.jna.platform.win32.Kernel32.INSTANCE
            .OpenProcess(0x1000, false, pid.toInt()) ?: return false // PROCESS_QUERY_LIMITED_INFORMATION
        return try {
            val result = IntByReference(0)
            kernel32.IsProcessInJob(handle.pointer, null, result)
            result.value != 0
        } finally {
            com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    private fun startLongLivedChild(): Process =
        ProcessBuilder("ping", "-n", "60", "127.0.0.1")
            .redirectErrorStream(true)
            .start()
}
