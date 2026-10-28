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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.shell.manager.DeviceConsoleScreen
import cc.coauto.tailcat.shell.manager.DeviceDetailScreen
import cc.coauto.tailcat.shell.manager.DeviceListScreen
import cc.coauto.tailcat.shell.manager.HomeScreen
import cc.coauto.tailcat.shell.manager.ManagerModel
import cc.coauto.tailcat.shell.manager.SettingsScreen
import cc.coauto.tailcat.shell.screen.ScreenControlScreen
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.TailcatIcons
import cc.coauto.tailcat.shell.ui.focusRing
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TRANSITION_MS = 320

/** 底部导航的四档，顺序即下标。 */
private val TABS = listOf("首页", "设备", "服务端", "设置")

private const val TAB_HOME = 0
private const val TAB_DEVICES = 1
private const val TAB_SERVER = 2
private const val TAB_SETTINGS = 3

/**
 * 应用外壳：底部四档导航，每一档挂一个界面。
 *
 * 改动要点（原先是顶部一个两档 TabRow）：
 *  - 顶部 tab 改成**底部导航**，与参照应用一致 —— 手上够得着，且不再跟页面标题抢视线；
 *  - **每张全屏页面都提到这一层**：投屏、Shell 窗口有自己的 `BackHandler`，不再嵌在某档里
 *    （Shell 窗口原先嵌在设备档内，底部导航一加就会把终端区顶掉，输入法弹起时更明显）；
 *  - 返回键的语义按层来：全屏页 → 退出全屏；设备详情 → 回列表；非首页 → 回首页；首页 → 交给系统退出。
 *
 * `ManagerModel` 是进程级单例，这里只是取一下 —— 转屏、分屏导致 Activity 重建时，
 * 已经连上的会话、正在跑的任务都不会被推倒重来。
 */
@Composable
fun TailcatApp(context: Context) {
    val colors = MiuixTheme.colorScheme
    val model = remember(context) { ManagerModel.get(context) }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(TAB_HOME) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var consoleId by rememberSaveable { mutableStateOf<String?>(null) }
    var screenDeviceId by rememberSaveable { mutableStateOf<String?>(null) }

    // 首启时导入既有地址簿；没有地址簿就什么都不做。
    // startup() 内部已经吞了导入失败，这里再兜一层，避免地址簿异常把整个界面拖崩。
    LaunchedEffect(Unit) { runCatching { model.startup() } }

    // 屏幕控制要**独占整屏**（自己还会隐藏系统栏），所以放在导航之外、直接 return。
    // 它以前没有 BackHandler，按返回会一路退出 Activity —— 这里补上。
    screenDeviceId?.let { deviceId ->
        BackHandler { screenDeviceId = null }
        ScreenControlScreen(
            model = model,
            deviceId = deviceId,
            onExit = { screenDeviceId = null },
        )
        return
    }

    // Shell 窗口同样独占整屏：底部导航 + 输入法会把终端区挤没。
    consoleId?.takeIf { model.manager.profile(it) != null }?.let { id ->
        BackHandler { consoleId = null }
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            DeviceConsoleScreen(
                manager = model.manager,
                deviceId = id,
                deviceName = model.manager.profile(id)?.name ?: "设备",
                onBack = { consoleId = null },
            )
        }
        return
    }

    BackHandler(enabled = openId != null || tab != TAB_HOME) {
        when {
            openId != null -> openId = null
            else -> tab = TAB_HOME
        }
    }

    val isTv = cc.coauto.tailcat.shell.ui.LocalTvMode.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.weight(1f)) {
            // 电视：左侧竖排导航（遥控器方向键好瞄准），手机仍是底部四档
            if (isTv) TvNavRail(current = tab, onSelect = { tab = it })
            Box(Modifier.weight(1f)) {
            when (tab) {
                TAB_HOME -> HomeScreen(
                    model = model,
                    onOpenDevices = { tab = TAB_DEVICES },
                    onOpenServer = { tab = TAB_SERVER },
                )
                TAB_DEVICES -> DevicesScreen(
                    model = model,
                    openId = openId,
                    onOpen = { openId = it },
                    onOpenScreen = { screenDeviceId = it },
                    onOpenConsole = { consoleId = it },
                )
                TAB_SERVER -> ShellScreen(context)
                else -> SettingsScreen(
                    context = context,
                    model = model,
                    onRelocateTools = { scope.launch { model.relocateTools() } },
                )
            }
            }
        }
        if (!isTv) NavigationBar(Modifier.fillMaxWidth()) {
            NavigationBarItem(
                selected = tab == TAB_HOME,
                onClick = { tab = TAB_HOME },
                icon = ImageVector.vectorResource(TailcatIcons.Home),
                label = TABS[TAB_HOME],
                // 电视遥控器导航时给一档可见的焦点
                modifier = Modifier.focusRing(shape = RoundedCornerShape(10.dp)),
            )
            NavigationBarItem(
                selected = tab == TAB_DEVICES,
                onClick = { tab = TAB_DEVICES },
                icon = ImageVector.vectorResource(TailcatIcons.Devices),
                label = TABS[TAB_DEVICES],
                // 电视遥控器导航时给一档可见的焦点
                modifier = Modifier.focusRing(shape = RoundedCornerShape(10.dp)),
            )
            NavigationBarItem(
                selected = tab == TAB_SERVER,
                onClick = { tab = TAB_SERVER },
                icon = ImageVector.vectorResource(TailcatIcons.Server),
                label = TABS[TAB_SERVER],
                // 电视遥控器导航时给一档可见的焦点
                modifier = Modifier.focusRing(shape = RoundedCornerShape(10.dp)),
            )
            NavigationBarItem(
                selected = tab == TAB_SETTINGS,
                onClick = { tab = TAB_SETTINGS },
                icon = ImageVector.vectorResource(TailcatIcons.Settings),
                label = TABS[TAB_SETTINGS],
                // 电视遥控器导航时给一档可见的焦点
                modifier = Modifier.focusRing(shape = RoundedCornerShape(10.dp)),
            )
        }
    }
}

/** 设备档：列表 ⇄ 详情。Shell 窗口与投屏都由上层接管，不在这里。 */
@Composable
private fun DevicesScreen(
    model: ManagerModel,
    openId: String?,
    onOpen: (String?) -> Unit,
    onOpenScreen: (String) -> Unit,
    onOpenConsole: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val manager = model.manager

    // 配置读不出来时**不写盘**，界面得把原因说出来，否则用户只会看到「一台设备都没有」
    model.startupError?.let { message ->
        Column(Modifier.fillMaxSize().padding(Dimens.PagePadding)) {
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
                onOpen = { onOpen(it) },
            )
        } else {
            DeviceDetailScreen(
                manager = manager,
                deviceId = id,
                onOpenScreen = { onOpenScreen(id) },
                onOpenConsole = { onOpenConsole(id) },
                onBack = { onOpen(null) },
            )
        }
    }
}


/**
 * 电视用的左侧导航。
 *
 * 为什么不用底部 `NavigationBar`：它在手机上靠"点"，而电视只有方向键 —— 一排贴边的小图标
 * 用遥控器很难瞄准，也没有焦点反馈。竖排在左侧、每行 56dp、带图标与文字，方向键上下就能走。
 */
@Composable
private fun TvNavRail(current: Int, onSelect: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val items = listOf(
        Triple("首页", TailcatIcons.Home, TAB_HOME),
        Triple("设备", TailcatIcons.Devices, TAB_DEVICES),
        Triple("服务端", TailcatIcons.Server, TAB_SERVER),
        Triple("设置", TailcatIcons.Settings, TAB_SETTINGS),
    )
    Column(
        Modifier
            .width(190.dp)
            .fillMaxHeight()
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Tailcat",
            style = styles.title3,
            color = colors.onBackground,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        items.forEach { (label, icon, index) ->
            val selected = current == index
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) colors.surfaceContainerHighest else colors.surface)
                    .clickable { onSelect(index) }
                    .focusRing(shape = RoundedCornerShape(10.dp))
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(icon),
                    contentDescription = null,
                    tint = if (selected) colors.onSurfaceContainerHighest else colors.onSurface,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    label,
                    style = styles.body1,
                    color = if (selected) colors.onSurfaceContainerHighest else colors.onSurface,
                )
            }
        }
    }
}
