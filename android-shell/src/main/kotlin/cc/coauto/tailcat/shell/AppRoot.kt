package cc.coauto.tailcat.shell

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.shell.manager.DeviceConsoleScreen
import cc.coauto.tailcat.shell.manager.DeviceDetailScreen
import cc.coauto.tailcat.shell.manager.DeviceListScreen
import cc.coauto.tailcat.shell.manager.ManagerModel
import cc.coauto.tailcat.shell.screen.ScreenControlScreen
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 两个角色：把自己的 ADB 暴露出去，或者去连别的设备。 */
private val ROLE_TITLES = listOf("服务端", "设备")

private const val TRANSITION_MS = 320

/**
 * 应用外壳：顶部一个角色切换，下面挂两个界面。
 *
 * `ManagerModel` 是进程级单例，这里只是取一下 —— 转屏、分屏导致 Activity 重建时，
 * 已经连上的会话、正在跑的任务都不会被推倒重来。
 */
@Composable
fun TailcatApp(context: Context) {
    val colors = MiuixTheme.colorScheme
    val model = remember(context) { ManagerModel.get(context) }
    var roleIndex by rememberSaveable { mutableIntStateOf(0) }
    var screenDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    // 首启时导入既有地址簿；没有地址簿就什么都不做。
    // startup() 内部已经吞了导入失败，这里再兜一层，避免地址簿异常把整个界面拖崩。
    LaunchedEffect(Unit) { runCatching { model.startup() } }

    // 屏幕控制要**独占整屏**，所以它在 TabRow 那一层 Column 之外渲染 ——
    // 嵌在里面的话顶部会一直挂着两个 tab，而且根布局的 safeDrawing 内边距也会把画面挤小。
    screenDeviceId?.let { deviceId ->
        ScreenControlScreen(
            model = model,
            deviceId = deviceId,
            onExit = { screenDeviceId = null },
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TabRow(
            tabs = ROLE_TITLES,
            selectedTabIndex = roleIndex,
            onTabSelected = { roleIndex = it },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = roleIndex,
                transitionSpec = {
                    fadeIn(tween(TRANSITION_MS)) togetherWith fadeOut(tween(TRANSITION_MS))
                },
                label = "role",
            ) { index ->
                if (index == 0) {
                    ShellScreen(context)
                } else {
                    DevicesScreen(model, onOpenScreen = { screenDeviceId = it })
                }
            }
        }
    }
}

/** 设备页的返回栈：列表 ⇄ 详情 ⇄ Shell 窗口。 */
@Composable
private fun DevicesScreen(model: ManagerModel, onOpenScreen: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var consoleId by rememberSaveable { mutableStateOf<String?>(null) }
    val manager = model.manager

    // 系统返回键：逐层退，而不是直接退出应用。
    // 桌面端没有这个键，移植时最容易漏的一处。不在栈里时不拦，交回系统（退出应用）。
    BackHandler(enabled = consoleId != null || openId != null) {
        if (consoleId != null) consoleId = null else openId = null
    }

    // Shell 窗口自己占一页。设备被删掉时 profile 会消失，这里一并回落，
    // 免得停在一个查不到设备的空白控制台上。
    consoleId?.takeIf { manager.profile(it) != null }?.let { id ->
        DeviceConsoleScreen(
            manager = manager,
            deviceId = id,
            deviceName = manager.profile(id)?.name ?: "设备",
            onBack = { consoleId = null },
        )
        return
    }

    // 配置读不出来时**不写盘**，界面得把原因说出来，否则用户只会看到「一台设备都没有」
    model.startupError?.let { message ->
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("配置读取失败：$message", color = colors.error, style = MiuixTheme.textStyles.body1)
            Text(
                "这一轮不会写回配置，先把问题修好再重开。",
                color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote2,
            )
        }
        return
    }

    AnimatedContent(
        // 设备被删掉时 profile 会消失，这里一并回落成列表，避免停在空白详情页
        targetState = openId?.takeIf { manager.profile(it) != null },
        transitionSpec = {
            fadeIn(tween(TRANSITION_MS)) togetherWith fadeOut(tween(TRANSITION_MS))
        },
        label = "device",
    ) { id ->
        if (id == null) {
            DeviceListScreen(
                manager = manager,
                onRelocateTools = { model.relocateTools() },
                onOpen = { openId = it },
            )
        } else {
            DeviceDetailScreen(
                manager = manager,
                deviceId = id,
                onOpenScreen = { onOpenScreen(id) },
                onOpenConsole = { consoleId = id },
                onBack = { openId = null },
            )
        }
    }
}
