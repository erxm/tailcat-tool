package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.tools.ExecutableLocator
import java.io.File

/**
 * 可执行文件查找。
 *
 * 关键点：**直接扫目录，不用 `where.exe` 也不依赖 PATH**。
 * winget 装完工具后会把别名放到 `%LOCALAPPDATA%\Microsoft\WinGet\Links`，但那个目录要到
 * 重新启动 shell 才进入 PATH —— 本进程的 PATH 是启动时快照，永远看不到，
 * 所以「安装完成 → 立刻配置路径」必须靠自己扫目录。
 */
public class WindowsExecutableLocator(
    /**
     * 应用自己下载并解压工具的位置。
     *
     * 位置本身和它的**下一层子目录**都会被扫描：官方发布包解压后大多还带一层目录
     * （platform-tools 是 `platform-tools/`，scrcpy 是 `scrcpy-win64-vX.Y/`）。
     */
    private val appToolsRoot: String? = null,
    private val extraDirectories: List<String> = emptyList(),
) : ExecutableLocator {

    private companion object {
        /** PATHEXT 的常见子集；顺序即优先级。 */
        val EXTENSIONS = listOf(".exe", ".cmd", ".bat", ".com")
    }

    private val directories: List<String> by lazy { buildDirectories() }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun buildDirectories(): List<String> {
        val local = env("LOCALAPPDATA")
        val roaming = env("APPDATA")
        val profile = env("USERPROFILE")

        val candidates = buildList {
            // 应用自己下载/解压的工具目录优先，连它的下一层一起扫。
            appToolsRoot?.let { root ->
                add(root)
                File(root).listFiles()?.filter { it.isDirectory }?.forEach { add(it.absolutePath) }
            }
            // winget 的 shim 目录（用户级）。
            local?.let { add("$it\\Microsoft\\WinGet\\Links") }
            roaming?.let { add("$it\\..\\Local\\Microsoft\\WinGet\\Links") }
            // Scoop / Chocolatey
            profile?.let { add("$it\\scoop\\shims") }
            add("C:\\ProgramData\\chocolatey\\bin")
            // Android SDK 的 adb
            env("ANDROID_HOME")?.let { add("$it\\platform-tools") }
            env("ANDROID_SDK_ROOT")?.let { add("$it\\platform-tools") }
            local?.let { add("$it\\Android\\Sdk\\platform-tools") }
            addAll(extraDirectories)
            // 最后才轮到 PATH
            env("PATH")?.split(';')?.forEach { if (it.isNotBlank()) add(it.trim()) }
        }
        return candidates.distinct()
    }

    override fun find(name: String): String? {
        for (directory in directories) {
            for (extension in EXTENSIONS) {
                val candidate = File(directory, name + extension)
                if (candidate.isFile) return candidate.absolutePath
            }
            // 已经带扩展名的情况
            if (name.contains('.')) {
                val candidate = File(directory, name)
                if (candidate.isFile) return candidate.absolutePath
            }
        }
        return null
    }

    override fun isExecutable(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        return file.isFile
    }
}
