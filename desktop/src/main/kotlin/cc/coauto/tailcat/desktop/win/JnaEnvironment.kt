package cc.coauto.tailcat.desktop.win

import java.io.File

/**
 * JNA 的运行环境准备与失败留痕。
 *
 * 背景：有用户报 `Could not initialize class com.sun.jna.Native` —— 这是 JNA 的
 * **静态初始化**失败（真正的根因被包在第一次异常里，界面上只剩这一句）。
 * 最常见的原因是它要往 `java.io.tmpdir`（默认 `%TEMP%`）解压 `jnidispatch.dll`，
 * 而那里被杀软、受控文件夹访问或精简系统拦住。
 *
 * 两个对策：
 *  1. 把 `jna.tmpdir` 指到应用自己的目录（可写就换，不可写就保持原样）；
 *  2. 所有 JNA 调用点都降级为"尽力而为"，并把真实堆栈写到
 *     `%LOCALAPPDATA%\TailcatManager\logs\native-error.log` —— 用户下次报错时
 *     我们能拿到根因，而不是只有一句 `Could not initialize class`。
 */
internal object JnaEnvironment {

    fun prepare() {
        // 关键：只认 jar 里自带的那份原生库。
        // JNA 默认（jna.nosys=false）会先按名字去系统路径找 jnidispatch.dll，
        // 机器上任何别处的旧版残留都会抢先生效 —— 表现就是
        // "Can't obtain static method dispose from class com.sun.jna.Native"
        //（Java 侧是新版类，原生侧是旧版 DLL）。必须在 JNA 类初始化之前设置。
        runCatching { System.setProperty("jna.nosys", "true") }
        runCatching {
            val dir = File(appDirectory(), "jna")
            if (dir.isDirectory || dir.mkdirs()) {
                if (dir.canWrite()) System.setProperty("jna.tmpdir", dir.absolutePath)
            }
        }
    }

    /** 记录一次 JNA 相关失败。写不进去就算了，绝不能再抛。 */
    fun record(where: String, t: Throwable) {
        runCatching {
            val dir = File(appDirectory(), "logs")
            dir.mkdirs()
            File(dir, "native-error.log").appendText(
                buildString {
                    appendLine("=== $where @ ${System.currentTimeMillis()}")
                    appendLine(t.stackTraceToString())
                    appendLine()
                },
            )
        }
        runCatching { System.err.println("[tailcat] JNA 不可用（$where）：${t.message}") }
    }

    /** 应用的配置目录；取不到环境变量时退回用户主目录。 */
    fun appDirectory(): File {
        val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
        return File(base, "TailcatManager").apply { runCatching { mkdirs() } }
    }
}
