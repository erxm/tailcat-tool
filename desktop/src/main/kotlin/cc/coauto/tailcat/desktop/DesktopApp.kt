package cc.coauto.tailcat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.creds.MemoryCredentialStore
import cc.coauto.tailcat.core.model.newId
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.session.SessionManagerDependencies
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.desktop.win.JsonConfigurationStore
import cc.coauto.tailcat.desktop.win.WindowsCredentialStore
import cc.coauto.tailcat.desktop.win.WindowsPlatformServices
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** 应用装配：读取配置、解析工具路径、构造 [SessionManager]。 */
public class ApplicationModel(public val demo: Boolean) {

    public val appDirectory: String
    public val services: WindowsPlatformServices?
    public val manager: SessionManager
    public var startupError: String? = null
        private set

    init {
        if (demo) {
            appDirectory = "(演示模式未使用磁盘)"
            services = null
            manager = DemoEnvironment.makeManager()
        } else {
            val base = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
            appDirectory = File(base, "TailcatManager").absolutePath
            val platform = WindowsPlatformServices(appDirectory)
            services = platform
            val store = JsonConfigurationStore(appDirectory)
            var loaded: AppConfiguration? = null
            try {
                loaded = store.load()
            } catch (t: Throwable) {
                startupError = t.message
            }
            manager = if (loaded != null) {
                SessionManager(
                    configuration = loaded.copy(tools = resolveTools(loaded.tools, platform)),
                    deps = SessionManagerDependencies(
                        store,
                        WindowsCredentialStore(appDirectory),
                        platform,
                    ),
                )
            } else {
                SessionManager(
                    configuration = AppConfiguration(tools = resolveTools(ToolSettings(), platform)),
                    deps = SessionManagerDependencies(null, MemoryCredentialStore(), platform),
                )
            }
        }
    }

    /** 把配置里为空的工具路径落到磁盘上实际找到的位置。 */
    private fun resolveTools(tools: ToolSettings, platform: WindowsPlatformServices): ToolSettings =
        ToolSettings(
            tailcat = tools.tailcat.ifEmpty { platform.locator.find(ToolComponent.Tailcat.executableName).orEmpty() },
            adb = tools.adb.ifEmpty { platform.locator.find(ToolComponent.Adb.executableName).orEmpty() },
            scrcpy = tools.scrcpy.ifEmpty { platform.locator.find(ToolComponent.Scrcpy.executableName).orEmpty() },
            addressBookDirectory = tools.addressBookDirectory,
        )

    public suspend fun startup() {
        if (demo || startupError != null) return
        if (manager.profiles.value.isNotEmpty()) return

        // 只在地址簿**确实存在**时才尝试导入，与 macOS 版一致：否则没用过 tailcat-adb 的
        // 用户每次首启都会看到一条「未找到地址簿」的无意义提示。
        val root = manager.tools.value.addressBookDirectory
            .ifEmpty { services?.defaultAddressBookDirectory.orEmpty() }
        if (root.isEmpty() || !File(root, "users").isDirectory) return

        runCatching { manager.importLegacy() }
            .onFailure { manager.setNotice(it.message) }
    }
}

@Composable
public fun StartupErrorScreen(message: String, directory: String) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("配置读取失败", style = MaterialTheme.typography.headlineSmall)
        Text(message)
        Text("配置文件已保留，请修复后重新启动应用。", style = MaterialTheme.typography.bodySmall)
        Text(directory, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 运行时图标：窗口图标与托盘图标。
 *
 * 用上游仓库的 `resources/AppIcon.png`（官方 Tailcat 猫图衍生，BSD 3-Clause，
 * 见 THIRD_PARTY_NOTICES.md），原样放在 `src/main/resources/icons/app-icon.png`。
 * **同一份文件**也用来生成 exe 图标（见 `desktop/icons/GenerateIcon.java`），
 * 所以任务栏、窗口标题栏、托盘三处必然是同一个图形。
 *
 * 原图 1254x1254，带较宽的透明边距；这里逐级折半缩放到实际取用的尺寸，
 * 一次缩太狠会严重走样。
 */
public object AppIcons {

    private const val RESOURCE = "/icons/app-icon.png"

    private val source: BufferedImage by lazy {
        AppIcons.javaClass.getResourceAsStream(RESOURCE)?.use { ImageIO.read(it) }
            ?: error("图标资源缺失：$RESOURCE")
    }

    private fun scaled(target: Int): BufferedImage {
        var current = source
        var width = source.width
        var height = source.height
        while (width / 2 >= target && height / 2 >= target) {
            width /= 2
            height /= 2
            current = resized(current, width, height)
        }
        return if (width == target && height == target) current else resized(current, target, target)
    }

    private fun resized(src: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    public val image: BufferedImage by lazy { scaled(64) }

    public val awtImage: java.awt.Image by lazy { scaled(32) }

    public val windowIcon: Painter by lazy { BitmapPainter(image.toComposeImageBitmap()) }
}
