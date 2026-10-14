package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.tools.DirectDownload
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 直链下载 + 解压的实测。
 *
 * **需要联网，默认跳过**（用 `-Dtailcat.networkTests=true` 开启），
 * 否则常规测试会因网络原因随机失败。
 *
 * 这个测试的价值在于：上游一旦改了发布包的目录结构或资产名，它会立刻失败——
 * 而那正是「新电脑一键配环境」这条路的唯一外部依赖。
 */
class WindowsArchiveFetcherTest {

    private val enabled = System.getProperty("tailcat.networkTests") == "true"

    private fun fetchInto(download: DirectDownload): File {
        val root = Files.createTempDirectory("tailcat-fetch-test").toFile()
        val fetcher = WindowsArchiveFetcher(root.absolutePath)
        val path = runBlocking { fetcher.fetch(download) { } }
        val file = File(path)
        assertTrue(file.isFile, "应解出可执行文件，实际得到：$path")
        assertTrue(file.length() > 100_000, "解出的文件太小，可能是残缺下载：${file.length()}")
        // 解压时剥掉了 zips 的公共顶层目录，可执行文件应正好落在 <tools>/<directory>/ 下
        assertTrue(
            file.absolutePath.startsWith(File(root, download.directory).absolutePath),
            "可执行文件不在预期目录下：${file.absolutePath}",
        )
        return file
    }

    @Test
    fun tailcatArchiveIsFlat() {
        if (!enabled) return
        fetchInto(
            DirectDownload(
                url = "https://github.com/tailscale/tailcat/releases/download/v0.7.0/tailcat_0.7.0_windows_amd64.zip",
                directory = "tailcat",
                executableInArchive = "tailcat.exe",
            ),
        )
    }

    @Test
    fun scrcpyArchiveHasVersionedTopLevelDirectory() {
        if (!enabled) return
        // scrcpy 的包里是一层 scrcpy-win64-vX.Y/，且依赖随包的 DLL，必须整包解压
        fetchInto(
            DirectDownload(
                url = "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-win64-v4.1.zip",
                directory = "scrcpy",
                executableInArchive = "scrcpy.exe",
            ),
        )
    }

    @Test
    fun platformToolsArchiveHasTopLevelDirectory() {
        if (!enabled) return
        // platform-tools 的包里多一层 platform-tools/，用来验证公共顶层目录剥离
        fetchInto(
            DirectDownload(
                url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip",
                directory = "platform-tools",
                executableInArchive = "adb.exe",
            ),
        )
    }
}
