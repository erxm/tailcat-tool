package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.session.ArchiveFetcher
import cc.coauto.tailcat.core.tools.DirectDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipFile

/**
 * 从官方地址下载发布包并解压到应用自己的 `tools` 目录。
 *
 * 三个组件在 Windows 上发布的都是 zip，所以只实现 zip 即可。
 *
 * **整包解压，而不是只挑出那个可执行文件**：scrcpy 依赖随包的 DLL 和 `scrcpy-server`，
 * platform-tools 依赖 `AdbWinApi.dll` / `AdbWinUsbApi.dll`，只复制 exe 出来是跑不起来的。
 */
public class WindowsArchiveFetcher(private val toolsRoot: String) : ArchiveFetcher {

    override suspend fun fetch(download: DirectDownload, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val target = File(toolsRoot, download.directory)
            val archive = File.createTempFile("tailcat-download-", ".zip")
            try {
                onProgress("正在下载…")
                download(download.url, archive, onProgress)
                onProgress("正在解压…")
                extract(archive, target)
                val executable = File(target, download.executableInArchive.replace('/', File.separatorChar))
                if (!executable.isFile) {
                    throw ManagerException(
                        "解压完成，但没有找到 ${download.executableInArchive}——" +
                            "上游发布包的目录结构可能变了。位置：${target.absolutePath}",
                    )
                }
                onProgress("已就绪：${executable.absolutePath}")
                executable.absolutePath
            } finally {
                runCatching { archive.delete() }
            }
        }

    private fun download(url: String, target: File, onProgress: (String) -> Unit) {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // 部分 CDN（含 GitHub Releases）对无 UA 的请求不友好。
            setRequestProperty("User-Agent", "TailcatManager")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw ManagerException("下载失败：HTTP $code\n$url")
            val total = connection.contentLengthLong
            var read = 0L
            var reported = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt()
                            // 每 10% 报一次，避免刷屏
                            if (percent / 10 != reported / 10) {
                                reported = percent
                                onProgress("  $percent%（${read / 1048576} / ${total / 1048576} MB）")
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extract(archive: File, target: File) {
        target.mkdirs()
        ZipFile(archive).use { zip ->
            val files = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            if (files.isEmpty()) throw ManagerException("压缩包是空的：${archive.name}")
            val prefix = commonTopLevelDirectory(files.map { it.name })
            for (entry in files) {
                val relative = if (prefix != null) entry.name.removePrefix(prefix) else entry.name
                if (relative.isEmpty()) continue
                val out = File(target, relative.replace('/', File.separatorChar))
                // 防目录穿越：条目名里出现 .. 时直接丢弃
                if (!out.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    /**
     * 若所有条目都位于同一个顶层目录下，返回 `"该目录/"`，否则返回 null。
     *
     * 用来把不同发布包的结构抹平：platform-tools 的包多一层 `platform-tools/`，
     * scrcpy 的包多一层 `scrcpy-win64-vX.Y/`，而 tailcat 的包是平铺的。
     * 剥掉之后三者的可执行文件都落在目标目录根部，配置路径就统一了。
     */
    private fun commonTopLevelDirectory(names: List<String>): String? {
        if (names.isEmpty()) return null
        val heads = names.map { it.substringBefore('/') }.distinct()
        val allNested = names.all { it.contains('/') }
        return if (heads.size == 1 && allNested) heads.first() + "/" else null
    }
}
