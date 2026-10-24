package cc.coauto.tailcat.shell.platform

import android.content.Context
import java.io.File

/**
 * 把随 APK 打包的 adb 运行时铺成一个动态链接器找得到的布局。
 *
 * adb 不是单个可执行文件：它依赖 abseil、protobuf、brotli、lz4、zstd、z、libc++_shared
 * 一大堆共享库，其中一部分在 Linux 上的名字带版本号（`libzstd.so.1`）。
 *
 * 而 Android 的 PackageManager 只把**名字以 `lib` 开头、`.so` 结尾**的文件解压到
 * `nativeLibraryDir`（那里才允许 `execve`）。所以打包时统一命名为 `<真名>.so`
 * （`libzstd.so.1` → `libzstd.so.1.so`），运行时在这里按**去掉末尾 `.so` 的真实名字**
 * 建符号链接，动态链接器才按 DT_NEEDED 里的名字找得到。
 *
 * 符号链接放在应用自己的 `filesDir` 下（可写），指向 `nativeLibraryDir`（可执行）——
 * 这条组合正是 Android 10+ 唯一允许「执行自带外部二进制」的路径。
 *
 * 系统库（`libc` / `libdl` / `liblog` / `libm` / `libc++` / `ld-android`）**有意不取也不链接**：
 * 必须用设备自己的那份。
 */
object AdbRuntime {

    /** adb 本体在 nativeLibraryDir 里的文件名（打包时多加了末尾一层 `.so`）。 */
    private const val ADB_IN_NATIVE_DIR = "libadb.so.so"

    private const val LINKS_DIR_NAME = "adb-runtime"

    /** 包内文件统一以 `.so` 结尾；这个后缀是打包层为绕开提取规则加的，要剥掉。 */
    private const val PACKAGING_SUFFIX = ".so"

    private var cachedLinksDir: File? = null

    /** adb 本体的绝对路径，交给 [cc.coauto.tailcat.core.proc.Command] 直接执行。 */
    fun adbPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, ADB_IN_NATIVE_DIR).absolutePath

    /**
     * 符号链接目录。子进程要把这个目录放进 `LD_LIBRARY_PATH`，
     * 否则动态链接器找不到 adb 依赖的那些库。
     *
     * 幂等：内容不齐时重建，齐了就复用（每次启动都遍历 50 多个文件没必要）。
     */
    fun linksDir(context: Context): File = cachedLinksDir ?: run {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val links = File(context.filesDir, LINKS_DIR_NAME).apply { mkdirs() }

        val expected = nativeDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("lib") && it.name.endsWith(PACKAGING_SUFFIX) }
            // 系统库只随包携带但**不链接**，必须让设备用自己的
            .filterNot { it.name.removeSuffix(PACKAGING_SUFFIX) in SYSTEM_LIBRARIES }

        for (source in expected) {
            val realName = source.name.removeSuffix(PACKAGING_SUFFIX)
            val link = File(links, realName)
            // 已经指对了就不动它：删了重建会让正在跑的 adb server 找不到库
            if (link.exists() && link.canonicalPath == source.canonicalPath) continue
            runCatching {
                link.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), source.toPath())
            }.onFailure {
                // 建不出链接时至少给一份拷贝，虽然占空间但能让 adb 跑起来
                runCatching { source.copyTo(link, overwrite = true) }
            }
        }
        links.also { cachedLinksDir = it }
    }

    /** 供界面自检用的说明文本。 */
    fun diagnostic(context: Context): String {
        val adb = File(adbPath(context))
        val links = linksDir(context)
        val count = links.listFiles()?.size ?: 0
        return buildString {
            append(adb.absolutePath)
            append("\n存在=").append(adb.exists())
            append("  可执行=").append(adb.canExecute())
            append("  大小=").append(adb.length())
            append("\n依赖链接=").append(count).append(" 个（").append(links.absolutePath).append("）")
        }
    }

    /**
     * 这些库虽然也在包里，但**不能**用我们的：它们必须由设备提供，
     * 否则会用错版本的 libc 直接把进程打死。
     */
    private val SYSTEM_LIBRARIES = setOf(
        "libc.so",
        "libdl.so",
        "liblog.so",
        "libm.so",
        "libc++.so",
        "ld-android.so",
    )
}
