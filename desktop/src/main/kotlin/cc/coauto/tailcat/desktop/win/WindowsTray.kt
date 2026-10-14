package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.desktop.AppIcons
import kotlinx.coroutines.launch
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.util.Timer
import java.util.TimerTask

/** 自带 CJK 字形的候选字体，按优先级排列。最后一个兜底是 AWT 的 logical font。 */
private val MENU_FONT_CANDIDATES = listOf("Microsoft YaHei UI", "Microsoft YaHei", "SimSun")

private const val MENU_FONT_SIZE = 12

/**
 * 系统托盘。关闭主窗口后连接继续运行，从这里重新打开或退出。
 *
 * 用 AWT 的 [SystemTray] 而不是 Compose 自带的 `Tray`：后者在 Windows 上仍是实验性的。
 * 菜单在设备/状态变化时**整体重建**——Windows 上原地修改已安装的菜单不可靠。
 */
public object WindowsTray {

    /**
     * 托盘菜单的字体**必须显式指定**，否则中文会显示成方块。
     *
     * AWT `PopupMenu` 背后是原生 Win32 菜单，菜单文字是 **Windows 自己画的**，用的是把
     * [java.awt.MenuComponent] 的字体转出来的 HFONT。不指定时它会落到 logical font「Dialog」，
     * 而 logical font 显示中文靠的是 **Java2D 的复合字体**兜底——那个兜底只对 Java 自己画字生效，
     * 转成 HFONT 交给 Windows 之后就只剩主字体了（这台机器上 Arial / Segoe UI 都不含 CJK 字形），
     * 于是菜单里的中文全变方块。应用其它文字由 Skia 绘制，不走这条路，所以看起来只有托盘是坏的。
     */
    private val menuFont: Font by lazy {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
        val family = MENU_FONT_CANDIDATES.firstOrNull { it in available } ?: Font.DIALOG
        Font(family, Font.PLAIN, MENU_FONT_SIZE)
    }

    private fun menuItem(label: String, onClick: () -> Unit): MenuItem =
        MenuItem(label).apply {
            font = menuFont
            addActionListener { onClick() }
        }

    private var manager: SessionManager? = null
    private var openAction: (() -> Unit)? = null
    private var exitAction: (() -> Unit)? = null
    private var icon: TrayIcon? = null
    private var timer: Timer? = null
    private var fingerprint: String = ""

    public fun install(manager: SessionManager, onOpen: () -> Unit, onExit: () -> Unit) {
        this.manager = manager
        this.openAction = onOpen
        this.exitAction = onExit
        if (!SystemTray.isSupported() || icon != null) return

        val trayIcon = TrayIcon(AppIcons.awtImage, "Tailcat Manager").apply {
            isImageAutoSize = true
            addActionListener { openAction?.invoke() }
            popupMenu = buildMenu()
        }
        runCatching { SystemTray.getSystemTray().add(trayIcon) }.onSuccess {
            icon = trayIcon
            fingerprint = currentFingerprint()
        }

        // 会话状态由后台协程驱动，托盘菜单需要一个低频刷新来跟上；
        // 只有内容真的变了才重建，避免菜单开着的时候被重置。
        timer = Timer("tailcat-tray", true).apply {
            scheduleAtFixedRate(
                object : TimerTask() {
                    override fun run() = refresh()
                },
                3_000,
                3_000,
            )
        }
    }

    public fun refresh() {
        val trayIcon = icon ?: return
        val next = currentFingerprint()
        if (next == fingerprint) return
        fingerprint = next
        runCatching { trayIcon.popupMenu = buildMenu() }
    }

    public fun dispose() {
        timer?.cancel()
        timer = null
        icon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        icon = null
    }

    private fun currentFingerprint(): String {
        val model = manager ?: return ""
        // 末尾带上活动通道数：它决定「断开全部」是否可点，变了就得重建菜单。
        return model.allProfiles().joinToString("|") { "${it.id}:${it.name}:${model.session(it.id).state}" } +
            "|active:${model.ownedActiveCount}"
    }

    private fun buildMenu(): PopupMenu {
        val menu = PopupMenu()
        menu.add(menuItem("Open Manager") { openAction?.invoke() })

        val model = manager
        if (model != null) {
            val profiles = model.allProfiles()
            if (profiles.isNotEmpty()) {
                menu.addSeparator()
                profiles.forEach { profile ->
                    val session = model.session(profile.id)
                    val label = buildString {
                        append(profile.name)
                        if (model.isTemporary(profile.id)) append(" · temp")
                        append(" · ")
                        append(session.state.englishLabel())
                    }
                    menu.add(
                        menuItem(label) {
                            if (session.serial == null) {
                                model.connect(profile.id)
                            } else {
                                model.disconnect(profile.id)
                            }
                        },
                    )
                }
            }
            menu.addSeparator()
            menu.add(menuItem("Recheck Connections") { model.recheckConnections() })
            menu.add(
                menuItem("Disconnect All (app-managed)") {
                    model.scope.launch { runCatching { model.disconnectAll() } }
                }.apply {
                    // 一个自己建的活动通道都没有时，这一项点了也是空操作，直接置灰。
                    isEnabled = model.ownedActiveCount > 0
                },
            )

            // 临时设备不进地址簿，只能从界面里关掉；主窗口收进托盘后就没别的入口了。
            profiles.filter { model.isTemporary(it.id) }.forEach { profile ->
                menu.add(
                    menuItem("Close Temporary Device: ${profile.name}") {
                        model.scope.launch { runCatching { model.removeDevice(profile.id) } }
                    },
                )
            }
        }

        menu.addSeparator()
        menu.add(menuItem("Exit Tailcat Manager") { exitAction?.invoke() })
        return menu
    }
}

/**
 * 托盘菜单**只用英文**。
 *
 * 这条路径由 Windows 自己绘制菜单文字（AWT `PopupMenu` 就是原生 Win32 菜单），本机上中文一律
 * 渲染成方块；显式指定含 CJK 的字体也没能解决。界面其它部分由 Skia 绘制，中文完全正常，
 * 所以只把这一处退回英文，不再继续折腾。
 *
 * 注意设备名是用户自己起的，仍可能是中文——那条路暂时没辙。
 */
private fun ConnectionState.englishLabel(): String = when (this) {
    ConnectionState.Disconnected -> "disconnected"
    ConnectionState.Connecting -> "connecting"
    ConnectionState.Online -> "adb online"
    ConnectionState.Offline -> "adb offline"
    ConnectionState.Unauthorized -> "adb unauthorized"
    ConnectionState.Disconnecting -> "disconnecting"
    ConnectionState.Failed -> "failed"
}
