package cc.coauto.tailcat.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.creds.MemoryCredentialStore
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.session.SessionManagerDependencies
import cc.coauto.tailcat.desktop.ui.ManagerCommands
import cc.coauto.tailcat.desktop.ui.ManagerScreen
import cc.coauto.tailcat.desktop.win.ConsoleHost
import cc.coauto.tailcat.desktop.win.JsonConfigurationStore
import cc.coauto.tailcat.desktop.win.NativeDialogs
import cc.coauto.tailcat.desktop.win.SingleInstance
import cc.coauto.tailcat.desktop.win.WindowsCredentialStore
import cc.coauto.tailcat.desktop.win.WindowsPlatformServices
import cc.coauto.tailcat.desktop.win.WindowsTray
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private const val WINDOW_TITLE = "Tailcat Manager"

/** 唤醒请求的轮询间隔。一次 `isFile` 而已，够便宜，用户按下去半秒内窗口就回来了。 */
private const val ACTIVATION_POLL_MILLIS = 500L

public fun main(args: Array<String>) {
    // 必须在任何子进程启动之前：给本进程一个隐藏控制台，否则每调用一次 adb 都会闪一次黑框。
    ConsoleHost.attachHiddenConsole()
    NativeDialogs.applySystemLookAndFeel()

    val demo = args.contains("--demo")
    val model = ApplicationModel(demo)
    if (!demo && !SingleInstance.acquire(model.appDirectory)) {
        // 唤起已有实例的窗口，而不是毫无反应地退出。
        // 不在这里直接 SetForegroundWindow：那是绕过 Compose 去显示别人的原生窗口，
        // 对方状态机里的 visible 还是 false，下次关闭就再也收不回去了。
        SingleInstance.requestActivation()
        System.err.println("Tailcat Manager 已在运行，已唤出已有窗口。")
        return
    }

    tailcatApplication(model, demo)
}

private fun tailcatApplication(model: ApplicationModel, demo: Boolean) = application {
    var windowVisible by remember { mutableStateOf(true) }
    var exiting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val commands = remember { ManagerCommands() }
    val windowState = rememberWindowState(width = 1180.dp, height = 780.dp)

    fun showWindow() {
        windowVisible = true
        // 光置 visible 不够：窗口缩在任务栏里时，用户还是看不到它。
        windowState.isMinimized = false
    }

    fun requestExit() {
        if (exiting) return
        exiting = true
        scope.launch {
            // 退出前把 GUI 启动的通道、转发进程和正在执行的任务都收干净。
            runCatching { model.manager.shutdown() }
            model.services?.shutdown()
            exitApplication()
        }
    }

    LaunchedEffect(Unit) {
        model.startup()
        if (model.startupError != null) {
            windowVisible = true
        }
    }

    // 第二个实例双击快捷方式时，会往实例目录里放一个请求文件。
    LaunchedEffect(demo) {
        if (demo) return@LaunchedEffect
        while (true) {
            delay(ACTIVATION_POLL_MILLIS)
            if (SingleInstance.consumeActivationRequest()) showWindow()
        }
    }

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = WINDOW_TITLE,
        state = windowState,
        icon = AppIcons.windowIcon,
    ) {
        // 桌面端没有菜单栏会显得不像应用，快捷键也顺带由它承载。
        // 菜单项自己不动状态，统一发命令给 ManagerScreen（对话框状态都在那边）。
        // 注意：这里是 `FrameWindowScope.MenuBar`，不是 Window 的参数——CMP 1.12 里没有 menuBar 形参。
        MenuBar {
            Menu("文件", mnemonic = 'F') {
                Item("导入地址簿", shortcut = KeyShortcut(Key.I, ctrl = true)) {
                    commands.send(ManagerCommands.Command.ImportAddressBook)
                }
                Item("工具设置…", shortcut = KeyShortcut(Key.Comma, ctrl = true)) {
                    commands.send(ManagerCommands.Command.OpenToolSettings)
                }
                Separator()
                Item("退出", shortcut = KeyShortcut(Key.Q, ctrl = true)) { requestExit() }
            }
            Menu("设备", mnemonic = 'D') {
                Item("添加设备…", shortcut = KeyShortcut(Key.N, ctrl = true)) {
                    commands.send(ManagerCommands.Command.AddDevice)
                }
                Item("临时连接…", shortcut = KeyShortcut(Key.N, ctrl = true, shift = true)) {
                    commands.send(ManagerCommands.Command.NewTemporary)
                }
                Separator()
                Item("测试全部设备", shortcut = KeyShortcut(Key.T, ctrl = true)) {
                    commands.send(ManagerCommands.Command.TestAll)
                }
            }
            Menu("窗口", mnemonic = 'W') {
                Item(
                    "显示主窗口",
                    shortcut = KeyShortcut(Key.M, ctrl = true, shift = true),
                ) { showWindow() }
            }
        }
        MaterialTheme {
            ManagerScreen(manager = model.manager, demo = demo, commands = commands)
        }
    }

    // 用 AWT 的 SystemTray 而不是 Compose 自带的 Tray：后者在 Windows 上仍是实验性的。
    LaunchedEffect(model.manager) {
        WindowsTray.install(
            manager = model.manager,
            onOpen = { showWindow() },
            onExit = { requestExit() },
        )
    }

    LaunchedEffect(Unit) {
        // 托盘菜单里的设备状态需要随会话变化重建。
        model.manager.profiles.collect { WindowsTray.refresh() }
    }

    if (model.startupError != null) {
        Window(onCloseRequest = { requestExit() }, title = "配置读取失败") {
            MaterialTheme {
                StartupErrorScreen(
                    message = model.startupError.orEmpty(),
                    directory = model.appDirectory,
                )
            }
        }
    }
}
