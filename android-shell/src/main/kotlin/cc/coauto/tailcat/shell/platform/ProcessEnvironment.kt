package cc.coauto.tailcat.shell.platform

import android.content.Context
import java.io.File

/**
 * 子进程必须继承的环境变量。
 *
 * 两件不能省的事（`AndroidProcessSpawner` 与 Shell 窗口共用这一份，别各写各的）：
 *
 *  1. **`HOME`**。App 进程默认没有它，而 adb 要在 `$HOME/.android/adbkey` 生成密钥 ——
 *     缺了直接报错退出（实测：裸 `ProcessBuilder` 起 adb 会以退出码 1 结束、一个字节都不输出）。
 *  2. **`LD_LIBRARY_PATH`**。adb 不是单文件程序，abseil/protobuf/zstd 这些依赖只有放在
 *     [AdbRuntime.linksDir] 里才是动态链接器认识的真名；末尾补 `/system/lib64` 是为了让
 *     libc/libdl/libm 仍走设备自己的。
 */
internal fun processEnvironment(context: Context): Map<String, String> {
    val files = context.filesDir.apply { mkdirs() }
    val cache = context.cacheDir.apply { mkdirs() }
    val xdgConfig = File(files, "xdg-config").apply { mkdirs() }
    val xdgState = File(files, "xdg-state").apply { mkdirs() }
    val xdgCache = File(cache, "xdg-cache").apply { mkdirs() }
    return mapOf(
        "HOME" to files.absolutePath,
        "XDG_CONFIG_HOME" to xdgConfig.absolutePath,
        "XDG_STATE_HOME" to xdgState.absolutePath,
        "XDG_CACHE_HOME" to xdgCache.absolutePath,
        "TMPDIR" to cache.absolutePath,
        "LD_LIBRARY_PATH" to AdbRuntime.linksDir(context).absolutePath + ":/system/lib64",
    )
}

/**
 * 一条长驻的 **带 pty** 的 `adb shell` 的构建器。
 *
 * 为什么不走 `ProcessRunner`：它只暴露 pid / 是否在跑 / stop，**没有 stdin** ——
 * 而 Shell 窗口要往同一条会话里反复写命令。所以这里自己起进程，环境变量与任务那条路一致。
 *
 * 三个参数都不是随手写的（都在真机上验证过）：
 *
 * - **`-tt`**：强制分配伪终端。不带它时 `adb shell` 对管道 stdin **不分配 pty**
 *   （`tty` 答 `not a tty`、`$-` 是 `lsh`），于是没有提示符、没有回显、没有 Ctrl-C。
 *   带上之后拿到 `/dev/pts/N`、`$-` 含 `i`、`intr=^C / eof=^D / erase=^?` 全在。
 * - **`stty rows/cols`**：adb 不知道客户端的屏幕尺寸，pty 默认是 `rows 0; columns 0`，
 *   `top`、`less` 这类会算错。这里按界面上终端区的实际大小设置一次。
 * - **`clear`**：启动那条 `stty` 命令会被 pty 回显出来，顺手清屏，用户看到的就是一个干净提示符。
 *   `exec sh -i` 让 shell 保持交互（提示符 / 行编辑 / 历史），且不再多一层进程。
 */
internal fun adbConsoleBuilder(
    context: Context,
    adbPath: String,
    serial: String,
    rows: Int,
    columns: Int,
): ProcessBuilder {
    val startup = "stty rows $rows cols $columns 2>/dev/null; clear; exec sh -i"
    return ProcessBuilder(adbPath, "-s", serial, "shell", "-tt", startup)
        .redirectErrorStream(true)
        .directory(context.filesDir)
        .apply { environment().putAll(processEnvironment(context)) }
}
