package cc.coauto.tailcat.shell.platform

import android.content.Context
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolComponent
import java.io.File

/**
 * 手机上的工具定位：两个二进制都随 APK 打包，落在 `nativeLibraryDir`。
 *
 * 这里**不扫 PATH**：Android 的 PATH 里没有任何可用的 adb/tailcat，
 * 而有用的东西全都由 App 自己携带。
 *
 * `scrcpy` 找不到 —— 它是桌面程序，客户端依赖桌面显示，手机上不做屏幕控制。
 */
class AndroidExecutableLocator(private val context: Context) : ExecutableLocator {

    private fun nativeFile(name: String): File =
        File(context.applicationInfo.nativeLibraryDir, name)

    override fun find(name: String): String? = when (name) {
        ToolComponent.Tailcat.executableName -> nativeFile(TAILCAT).takeIf { it.isFile }?.absolutePath
        // adb 运行时是「一个可执行文件 + 一堆依赖库」，依赖库由 AdbRuntime 铺好符号链接、
        // 由 spawner 注入 LD_LIBRARY_PATH；这里只负责给出可执行文件本身。
        ToolComponent.Adb.executableName -> File(AdbRuntime.adbPath(context)).takeIf { it.isFile }?.absolutePath
        else -> null
    }

    override fun isExecutable(path: String): Boolean {
        if (path.isEmpty()) return false
        val file = File(path)
        return file.isFile && file.canExecute()
    }

    private companion object {
        const val TAILCAT = "libtailcat.so"
    }
}
