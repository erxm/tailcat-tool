package cc.coauto.tailcat.core.tools

import cc.coauto.tailcat.core.model.managerError
import cc.coauto.tailcat.core.proc.Command

public enum class ToolComponent(public val label: String, public val executableName: String) {
    Tailcat("Tailcat", "tailcat"),
    Adb("ADB", "adb"),
    Scrcpy("scrcpy", "scrcpy"),
}

/**
 * 可执行文件定位。原 macOS 版把扫描路径写死成 `/opt/homebrew/bin` 等，
 * 这里抽成接口，由各平台提供自己的查找目录。
 */
public interface ExecutableLocator {
    public fun find(name: String): String?

    public fun isExecutable(path: String): Boolean

    public fun require(path: String, label: String): String {
        if (path.isEmpty() || !isExecutable(path)) {
            managerError("未找到 $label，请在工具设置中指定可执行文件的路径")
        }
        return path
    }
}

/**
 * 组件安装来源。Windows 用 winget，macOS 用 Homebrew。
 *
 * 安装完成后必须**重新扫描目录**而不是查 PATH：winget 装好的工具在当前进程的 PATH 里看不到。
 *
 * 除包管理器外还必须提供**官方直链下载**作为兜底：新电脑上可能既没有 winget、也没有
 * 任何包管理器，甚至从没装过 Android SDK。只有直链才能让新手一键把环境配齐。
 */
public interface ToolInstaller {
    public val isAvailable: Boolean

    /** 包管理器本身缺失时，引导用户去哪里装。 */
    public val bootstrapLabel: String
    public val bootstrapUrl: String?

    /** 供界面展示的命令描述，例如 `winget install --id Tailscale.tailcat`。 */
    public fun description(component: ToolComponent): String

    public fun installCommand(component: ToolComponent): Command

    /** 官方直链。返回 null 表示该组件没有可用的直链，只能手动下载。 */
    public fun directDownload(component: ToolComponent): DirectDownload?
}

/**
 * 从官方发布包直接获取组件。
 *
 * 压缩包会被**整体解压**，而不是只挑出可执行文件：scrcpy 需要随包的 DLL 和 scrcpy-server，
 * platform-tools 需要 AdbWinApi.dll 之类，只拿 exe 出来是跑不起来的。
 */
public data class DirectDownload(
    /** 官方下载地址。 */
    public val url: String,
    /** 落到应用 tools 目录下的子目录名。 */
    public val directory: String,
    /** 压缩包内可执行文件的相对路径，用 `/` 分隔。 */
    public val executableInArchive: String,
)
