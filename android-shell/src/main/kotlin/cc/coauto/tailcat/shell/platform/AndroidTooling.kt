package cc.coauto.tailcat.shell.platform

import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.session.ArchiveFetcher
import cc.coauto.tailcat.core.tools.DirectDownload
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.core.tools.ToolInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * 手机上**没有包管理器**（winget / Homebrew 那套在 Android 上不存在），
 * 两个二进制都是随 APK 打包的，所以这里没有可执行的安装动作。
 *
 * [isAvailable] 恒为 false 会让 `ComponentInstaller` 直接跳过「包管理器」这一级；
 * [directDownload] 返回 null 让「官方直链」那一级也跳过。正常情况下根本走不到这两级 ——
 * 工具始终在磁盘上（`nativeLibraryDir`），第一级就命中了。
 */
class AndroidToolInstaller : ToolInstaller {

    override val isAvailable: Boolean = false

    override val bootstrapLabel: String = "（Android 无包管理器）"
    override val bootstrapUrl: String? = null

    override fun description(component: ToolComponent): String =
        "${component.label} 已随应用打包，无需安装"

    override fun installCommand(component: ToolComponent): Command =
        throw ManagerException("${component.label} 随应用打包，不需要安装")

    override fun directDownload(component: ToolComponent): DirectDownload? = null
}

/**
 * 直链下载兜底。
 *
 * 目前**不会被调用**：`AndroidToolInstaller.directDownload` 恒为 null，
 * `ComponentInstaller` 的第二、三级都进不来（工具始终能从 `nativeLibraryDir` 找到）。
 * 留一个能跑的朴素实现是为了让接口语义完整，将来若要支持在线更新 tailcat 可以直接用。
 *
 * 只支持 zip 与裸文件：tar.gz 需要额外的解包库，真要用到时再引。
 */
class AndroidArchiveFetcher(private val toolsRoot: File) : ArchiveFetcher {

    override suspend fun fetch(download: DirectDownload, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val directory = File(toolsRoot, download.directory).apply { mkdirs() }
            val archive = File(directory, download.url.substringAfterLast('/'))
            onProgress("下载 ${download.url}")
            download(download.url, archive, onProgress)

            val executable = when {
                archive.name.endsWith(".zip", ignoreCase = true) -> {
                    onProgress("解压 ${archive.name}")
                    unzip(archive, directory)
                    File(directory, download.executableInArchive)
                }
                else -> archive
            }
            if (!executable.isFile) {
                throw ManagerException("下载完成但没有找到 ${download.executableInArchive}")
            }
            executable.setExecutable(true)
            onProgress("已就绪：${executable.absolutePath}")
            executable.absolutePath
        }

    private fun download(url: String, target: File, onProgress: (String) -> Unit) {
        if (target.isFile && target.length() > 0) {
            onProgress("已有 ${target.name}，跳过下载")
            return
        }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                    if (total % (4L * 1024 * 1024) < buffer.size) {
                        onProgress("已下载 ${total / (1024 * 1024)} MB")
                    }
                }
            }
        }
    }

    private fun unzip(archive: File, target: File) {
        java.util.zip.ZipFile(archive).use { zip ->
            val canonicalTarget = target.canonicalPath + File.separator
            zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                val out = File(target, entry.name)
                if (!out.canonicalPath.startsWith(canonicalTarget)) {
                    throw ManagerException("压缩包内含越界路径：${entry.name}")
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
