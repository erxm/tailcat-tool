package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.tools.DirectDownload
import cc.coauto.tailcat.core.tools.ExecutableLocator
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.core.tools.ToolInstaller

/**
 * 用 **winget** 安装缺失组件 —— 相当于 macOS 版的 Homebrew。
 *
 * 全部使用用户级、静默、非交互安装：jpackage 的 app-image 没有安装器，无法申请提权，
 * 所以任何需要 UAC 的路径都走不通。
 */
public class WingetToolInstaller(private val locator: ExecutableLocator) : ToolInstaller {

    override val isAvailable: Boolean get() = locator.find("winget") != null

    override val bootstrapLabel: String = "应用安装程序（App Installer）"
    override val bootstrapUrl: String = "https://aka.ms/getwinget"

    private fun packageId(component: ToolComponent): String = when (component) {
        ToolComponent.Tailcat -> "Tailscale.tailcat"
        ToolComponent.Adb -> "Google.PlatformTools"
        ToolComponent.Scrcpy -> "Genymobile.scrcpy"
    }

    private fun arguments(component: ToolComponent): List<String> = buildList {
        add("install")
        add("--id"); add(packageId(component))
        // scrcpy 的 winget 包不支持 --scope user，其余两个支持。
        if (component != ToolComponent.Scrcpy) {
            add("--scope"); add("user")
        }
        add("--silent")
        add("--accept-source-agreements")
        add("--accept-package-agreements")
        add("--disable-interactivity")
    }

    override fun description(component: ToolComponent): String =
        "winget " + arguments(component).joinToString(" ")

    override fun installCommand(component: ToolComponent): Command = Command(
        executable = locator.find("winget") ?: "winget",
        arguments = arguments(component),
        timeoutMillis = 1_800_000,
        environment = mapOf(
            // 避免 winget 弹出进度条/交互提示。
            "WINGET_DISABLE_INTERACTIVITY" to "1",
        ),
    )

    /**
     * 官方直链兜底。
     *
     * 三条地址都是上游的正式发布地址：adb 用的是 Google 的 `platform-tools-latest`，
     * 它永远指向当前稳定版；另两个是 GitHub Releases 的固定版本（已实测可用）。
     * 上游若改了资产名，这里会失败并给出可读原因，界面同时提供手动下载入口。
     */
    override fun directDownload(component: ToolComponent): DirectDownload? = when (component) {
        ToolComponent.Tailcat -> DirectDownload(
            url = "https://github.com/tailscale/tailcat/releases/download/v0.7.0/tailcat_0.7.0_windows_amd64.zip",
            directory = "tailcat",
            executableInArchive = "tailcat.exe",
        )

        ToolComponent.Adb -> DirectDownload(
            url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
            directory = "platform-tools",
            executableInArchive = "adb.exe",
        )

        ToolComponent.Scrcpy -> DirectDownload(
            url = "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-win64-v4.1.zip",
            directory = "scrcpy",
            executableInArchive = "scrcpy.exe",
        )
    }

    /** 供界面展示「手动下载」入口。 */
    public fun downloadPage(component: ToolComponent): String = when (component) {
        ToolComponent.Tailcat -> "https://github.com/tailscale/tailcat/releases/latest"
        ToolComponent.Adb -> "https://developer.android.com/tools/releases/platform-tools"
        ToolComponent.Scrcpy -> "https://github.com/Genymobile/scrcpy/releases/latest"
    }
}
