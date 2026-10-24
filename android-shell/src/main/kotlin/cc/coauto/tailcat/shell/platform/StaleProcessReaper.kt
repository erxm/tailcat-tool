package cc.coauto.tailcat.shell.platform

import android.os.Process
import android.util.Log
import java.io.File

/**
 * 收拾上一次运行可能遗留的子进程。
 *
 * ## 先说实话：Android 上这件事**大概率不需要我们做**
 *
 * 桌面端是真的有洞 —— 应用被强杀或崩溃时，`tailcat forward` / `adb` / `scrcpy` 会被系统
 * 丢下继续占着端口，所以那边引入了 Job Object（`KILL_ON_JOB_CLOSE`，内核在句柄关闭时
 * 连带收掉组内进程，见 desktop 的 `ProcessJob`）。
 *
 * **Android 由平台自己保证同一件事**：每个应用进程被放进独立 cgroup
 * （`/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>`，cgroup v2 统一层级），fork 出来的子进程
 * 继承该 cgroup；应用进程被移除时 AMS 走 `Process.killProcessGroup`，把整棵子树收掉 ——
 * 子进程即使已经被 init 收养（PPID=1）、看起来完全「独立」，也一样在 cgroup 里被带走。
 *
 * 这不是推断，是实测：在设备上依次用 `am force-stop`、`am crash`、`am kill`、以及对应用
 * 进程单独发 `SIGKILL`（借 `run-as` 以应用 uid 执行）四种方式结束应用，
 * **四次都没有留下任何同 uid 进程**；其中 adb 守护进程是自我 detach 成 PPID=1 的最难缠形态，
 * 照样一起消失。当时读到的 cgroup 是应用与子进程同为 `0::/apps/uid_10399/pid_20419`。
 *
 * 因此这个类的作用是**兜底**而不是修复：真出现孤儿，说明平台那层没生效
 * （别的 OEM、别的版本、或者某种绕开 AMS 的死法），那时它至少能在下次启动时清掉。
 * 代价只有一次 `/proc` 扫描。
 *
 * ## 判据必须精确，否则会误杀**正在用**的子进程
 *
 * 最初只判 `uid + comm`，有真实缺陷：
 *  - `adb` 守护进程天然就是 **PPID=1**（它 fork 之后自我 detach，父进程退出）——
 *    用 PPID==1 认孤儿会把应用自己正在用的 adb server 判成遗留物；
 *  - `startup()` 挂在 `LaunchedEffect(Unit)` 上，**Activity 每次重建都会再跑一遍**
 *    （转屏、重新拉起界面），届时应用（release）或在跑的连接进程可能已经拉起了子进程。
 *    那样一杀，所有设备连接当场断掉。
 *
 * 所以用**进程启动时间的先后**：本进程 fork 出来的子进程一定比本进程晚启动，
 * 早于本进程的只可能是上一次运行的遗留。这条判据与「重建后重启」无关 ——
 * 无论调用多少次，本进程自己的子进程永远比本进程晚，永远不会被选中。
 *
 * 其余两条仍保留：`uid` 必须是本应用（我们起的进程都是应用私有的），
 * `comm` 必须在真正会拉起的那几个二进制里（防将来加了多进程组件时误伤自己人）。
 */
internal object StaleProcessReaper {

    private const val TAG = "TailcatReaper"

    /** `SIGKILL`。`android.os.Process.SIGNAL_KILL` 是 @hide，这里直接写数值。 */
    private const val SIGKILL = 9

    /**
     * 应用会拉起的二进制名。
     *
     * 取的是 `/proc/<pid>/comm`：**不含路径，且被内核截断到 15 字符**。
     * ⚠️ 这个截断是取**尾部**而不是头部 —— 实测应用自己的 comm 是 `t.manager.debug`
     * （`cc.coauto.tailcat.manager.debug` 的最后 15 字符）。所以这里每条都必须写成
     * **完整且不超过 15 字符**的形式，写长了永远匹配不上：
     * `libtailcat.so`(13) / `libadb.so.so`(13) 都是原生库目录里的真实文件名，天然满足。
     */
    private val REAPABLE = setOf(
        "libtailcat.so", // nativeLibraryDir 里的 tailcat
        "tailcat",       // 手工放进 /data/local/tmp 的那份
        "libadb.so.so",  // nativeLibraryDir 里的 adb 本体
        "libadb.so",
        "adb",           // adb 守护进程会把自己改名成这个
    )

    /**
     * 杀掉所有属于本应用、且**启动早于本进程**的遗留进程。返回被清理的 pid 列表。
     *
     * 失败一律吞掉：这是尽力而为的兜底，不该因为它拦着应用启动。
     */
    fun reap(): List<Int> {
        val myPid = Process.myPid()
        val myUid = Process.myUid()
        // 拿不到自己的启动时间就**不动手**：没有参照物时，「早于本进程」这条判据就没法成立，
        // 退化成最初那个会误杀的版本。
        val myStart = readStat(myPid)?.startTime ?: return emptyList()

        val entries = runCatching { File("/proc").listFiles() }.getOrNull() ?: return emptyList()
        val killed = mutableListOf<Int>()
        var scanned = 0

        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) continue

            // 顺序是**从廉价到昂贵**：stat 一次读齐 comm/starttime，绝大多数进程在这一步
            // 就被 comm 挡掉，只有命中的少数几个才去开 status 读 uid。
            val stat = readStat(pid) ?: continue
            scanned++
            if (stat.comm !in REAPABLE) continue
            if (stat.startTime >= myStart) continue
            if (readUid(pid) != myUid) continue

            // 同 uid 之间互相 kill 是允许的；失败只说明它刚好自己退了。
            val ok = runCatching {
                Process.sendSignal(pid, SIGKILL)
                true
            }.getOrElse { false }
            if (ok) killed.add(pid)
        }

        if (killed.isNotEmpty()) {
            Log.i(TAG, "清理了上次运行遗留的子进程：$killed")
        } else {
            // 留一行证明它确实跑过 —— 排查「到底有没有执行」时不用再翻代码。
            Log.d(TAG, "扫描 $scanned 个进程，未发现遗留子进程")
        }
        return killed
    }

    /** `/proc/<pid>/stat` 里我们关心的三个字段。 */
    private class Stat(val comm: String, val startTime: Long)

    /**
     * 解析 `/proc/<pid>/stat`。
     *
     * 格式是 `pid (comm) state ppid pgrp ...`，**`comm` 里可能有空格甚至括号**，
     * 所以不能直接按空格切：从第一个 `(` 切到**最后一个** `)`，剩下那段才按空格切。
     *
     * 切出来之后：第 0 项是 state（总第 3 字段），因此总第 N 字段 = 第 N-3 项。
     * `starttime` 是总第 22 字段 → 第 19 项。单位是 100 Hz 的 tick，
     * 同一台机器上可直接比较大小（实测 init=0、应用=30172552、其子进程=30187121，
     * 与 `/proc/uptime` 换算一致）。
     */
    private fun readStat(pid: Int): Stat? = runCatching {
        val text = File("/proc/$pid/stat").readText()
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val comm = text.substring(open + 1, close)
        val rest = text.substring(close + 1).trim().split(' ')
        val startTime = rest.getOrNull(19)?.toLongOrNull() ?: return null
        Stat(comm, startTime)
    }.getOrNull()

    /** 真实 uid（`Uid:` 行有 real/effective/saved/fs 四个，取第一个）。读不到就当不是我们的。 */
    private fun readUid(pid: Int): Int = runCatching {
        File("/proc/$pid/status").readLines()
            .firstOrNull { it.startsWith("Uid:") }
            ?.split('\t', ' ')
            ?.filter { it.isNotBlank() }
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: -1
    }
        // 别的 uid 的 /proc/<pid> 读不了（也会被 hidepid 挡掉）
        .getOrElse { -1 }
}
