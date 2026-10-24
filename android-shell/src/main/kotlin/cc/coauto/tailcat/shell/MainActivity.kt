package cc.coauto.tailcat.shell

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import cc.coauto.tailcat.shell.manager.ManagerModel
import cc.coauto.tailcat.shell.ui.TailcatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import cc.coauto.tailcat.shell.ui.BigTitleBar
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.GroupCard
import cc.coauto.tailcat.shell.ui.RowTrailing
import cc.coauto.tailcat.shell.ui.SectionHeader
import cc.coauto.tailcat.shell.ui.SettingRow
import cc.coauto.tailcat.shell.ui.TailcatIcons
import cc.coauto.tailcat.shell.ui.ExpandingBlock

/** 统一的过渡时长，避免各处动画节奏不一致。 */
private const val TRANSITION_MS = 320

class MainActivity : ComponentActivity() {

    /**
     * 回到前台时主动复检一次连接。
     *
     * 会话状态是「上次探测时」定的，而 `SessionManager` 的探测是个每 3 秒一轮的协程循环 ——
     * **应用退到后台后，进程会被系统冻结，那个循环跟着停**，状态就再也不更新了。
     * 表现是：链路早就断了，界面还一直挂着「ADB 已连接」，直到用户点什么都失败。
     * 桌面端不存在这个问题（窗口永远在前台），移植时必须自己补上这一步。
     */
    override fun onStart() {
        super.onStart()
        ManagerModel.get(this).manager.recheckConnections()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 显式声明（targetSdk 35+ 本来就是强制 edge-to-edge 的），
        // 配合下面根布局的 safeDrawing insets 才不会让内容钻到状态栏/导航栏底下。
        enableEdgeToEdge()
        // 背景交给界面自己画，所以状态栏图标颜色要跟着深浅色走，否则浅色下会看不清
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !night
        setContent {
            TailcatTheme {
                TailcatApp(this)
            }
        }
    }
}

/**
 * 服务端角色：把本机端口通过 Tailcat 隧道暴露出去。
 *
 * 根布局**不再自己加 safeDrawing insets** —— 那是 [TailcatApp] 的职责，
 * 这里再加一次会把内容往下顶一截。
 */
@Composable
internal fun ShellScreen(context: Context) {
    val state by TailcatServer.state.collectAsState()
    val liveLog by TailcatServer.log.collectAsState()
    val clipboard = LocalClipboardManager.current
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles

    var portsText by remember { mutableStateOf(Settings.portsText(context)) }
    var autoStart by remember { mutableStateOf(Settings.autoStart(context)) }
    var persistentKey by remember { mutableStateOf(Settings.persistentKey(context)) }
    var hasSavedKey by remember { mutableStateOf(TailcatServer.hasPersistentKey(context)) }
    var showLog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) notice = "未授予通知权限，前台服务的通知不会显示" }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val running = state is ServerState.Running || state is ServerState.Starting
    val address = (state as? ServerState.Running)?.address

    // 端口后面到底有没有服务。隧道建得起来不代表对端有东西——最常见的就是忘了开 adb over TCP，
    // 那种情况下电脑端会一直「ADB 尚未上线」，而手机上看起来一切正常。
    val runningPorts = (state as? ServerState.Running)?.ports
    val portStatus by produceState<Map<Int, Boolean>>(emptyMap(), runningPorts) {
        if (runningPorts == null) {
            value = emptyMap()
            return@produceState
        }
        while (true) {
            value = withContext(Dispatchers.IO) {
                runningPorts.associateWith { PortProbe.isListening(it) }
            }
            delay(3_000)
        }
    }

    // 运行时长要一个每秒跳一次的时钟，否则它只是组合时算一次就定住了
    val nowMillis by produceState(0L, state) {
        if (state is ServerState.Running) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Dimens.PagePadding,
                end = Dimens.PagePadding,
                bottom = Dimens.SectionGap,
            ),
    ) {
        BigTitleBar("服务端")
        Text(
            "把本机端口通过 Tailcat 隧道暴露出去。默认 5555，即 adbd —— 另一台机器就能通过 tc 地址连上这台手机的 ADB。",
            modifier = Modifier.padding(
                start = Dimens.CardInner,
                end = Dimens.CardInner,
                bottom = Dimens.TitleToContent,
            ),
            style = styles.footnote1,
            color = colors.onBackgroundVariant,
        )

        StatusCard(state, address, nowMillis, portStatus)

        SectionHeader("服务")
        GroupCard {
            // 地址一有，二维码就占这个分组的**第一行**并整块显示 ——
            // 启动之后最常见的一步就是拿另一台手机来扫，不该再点一次才出现。
            val shownAddress = address
            if (shownAddress != null) {
                val qr = remember(shownAddress) { QrCode.bitmap(shownAddress, 720) }
                Column(
                    Modifier.fillMaxWidth().padding(Dimens.CardInner),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.CardGap),
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "tailcat 地址二维码",
                            modifier = Modifier.size(260.dp),
                        )
                    } else {
                        Text("二维码生成失败", style = styles.footnote1, color = colors.error)
                    }
                    Text("地址本身是凭据，别外传", style = styles.footnote2, color = colors.onSurfaceVariantSummary)
                }
            }

            // 启动与停止是同一个开关的两面，合成一行：按钮文字跟着当前状态变。
            // 之前是两个并排按钮，一个亮一个灰，还得先看一眼哪个能用。
            SettingRow(
                title = "Tailcat 服务",
                summary = if (running) {
                    "运行中 · 端口 " + ((state as? ServerState.Running)?.ports?.joinToString(",") ?: "")
                } else {
                    "把本机端口通过隧道暴露出去"
                },
                icon = ImageVector.vectorResource(TailcatIcons.Server),
                trailing = RowTrailing.Action(if (running) "停止" else "启动") {
                    if (running) {
                        TailcatServer.stop(context)
                        context.stopService(Intent(context, TailcatService::class.java))
                    } else {
                        val parsed = Settings.ports(portsText)
                        if (parsed.isEmpty()) {
                            notice = "请先填写至少一个有效端口，例如 5555"
                        } else {
                            Settings.setPortsText(context, portsText)
                            notice = null
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, TailcatService::class.java).setAction(TailcatService.ACTION_START),
                            )
                        }
                    }
                },
            )
            SettingRow(
                title = "复制地址",
                summary = address?.let { "tc 地址已生成" } ?: "启动后才有地址",
                icon = ImageVector.vectorResource(TailcatIcons.Link),
                enabled = address != null,
                trailing = RowTrailing.Action("复制") {
                    address?.let {
                        clipboard.setText(AnnotatedString(it))
                        notice = "地址已复制到剪贴板"
                    }
                },
            )
        }

        SectionHeader("端口与密钥")
        GroupCard {
            TextField(
                value = portsText,
                onValueChange = { portsText = it },
                label = "要暴露的端口",
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.CardInner, vertical = Dimens.RowVertical),
            )
            SettingRow(
                title = "固定地址",
                summary = if (persistentKey) "用长期密钥，重启后地址不变" else "用临时密钥，重启后地址会变",
                icon = ImageVector.vectorResource(TailcatIcons.Link),
                trailing = RowTrailing.Toggle(persistentKey) {
                    persistentKey = it
                    Settings.setPersistentKey(context, it)
                },
            )
            if (hasSavedKey) {
                SettingRow(
                    title = "删除长期密钥",
                    summary = "下次开启固定地址会生成新的，地址随之改变",
                    titleColor = colors.error,
                    trailing = RowTrailing.Chevron,
                    onClick = {
                        TailcatServer.forgetPersistentKey(context)
                        hasSavedKey = false
                        notice = "已删除长期密钥；下次开启固定地址会生成一个新的（地址随之改变）"
                    },
                )
            }
            SettingRow(
                title = "开机自启",
                summary = "开机后自动把服务端拉起来",
                icon = ImageVector.vectorResource(TailcatIcons.Refresh),
                trailing = RowTrailing.Toggle(autoStart) {
                    autoStart = it
                    Settings.setAutoStart(context, it)
                },
            )
        }

        SectionHeader("日志")
        GroupCard {
            SettingRow(
                title = if (showLog) "收起日志" else "查看日志",
                trailing = RowTrailing.Chevron,
                onClick = { showLog = !showLog },
            )
            // 日志就在这一行下面展开，和「编辑设备」等展开项保持同一套规矩
            if (showLog) {
                val lines = if (liveLog.isNotEmpty()) liveLog else TailcatServer.readLog(context)
                ExpandingBlock {
                    Column(Modifier.fillMaxWidth().padding(Dimens.CardInner)) {
                    if (lines.isEmpty()) {
                        Text("暂无日志", style = styles.footnote1, color = colors.onSurfaceVariantSummary)
                    } else {
                            lines.takeLast(200).forEach { line ->
                                Text(
                                    line,
                                    style = styles.footnote2,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
            SettingRow(
                title = "清空日志",
                trailing = RowTrailing.Chevron,
                onClick = { TailcatServer.clearLog(context) },
            )
        }

        notice?.let { message ->
            SectionHeader("提示")
            GroupCard {
                SettingRow(title = message, trailing = RowTrailing.Action("知道了") { notice = null })
            }
        }

        SectionHeader("诊断")
        GroupCard {
            SettingRow(
                title = "二进制诊断",
                summary = TailcatServer.binaryDiagnostic(context),
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: ServerState,
    address: String?,
    nowMillis: Long,
    portStatus: Map<Int, Boolean>,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles

    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = Dimens.CardRadius) {
        Column(
            Modifier.fillMaxWidth().padding(Dimens.CardInner).animateContentSize(
                animationSpec = tween(TRANSITION_MS, easing = FastOutSlowInEasing),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state)
                Spacer(Modifier.width(10.dp))
                // 状态文字切换时做淡入淡出，而不是生硬替换
                androidx.compose.animation.Crossfade(
                    targetState = statusLabel(state),
                    animationSpec = tween(TRANSITION_MS),
                ) { label ->
                    Text(label, style = styles.title3, color = colors.onSurface)
                }
            }

            val failure = (state as? ServerState.Failed)?.message
            when {
                address != null -> Text(
                    address,
                    style = styles.footnote2,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                )
                failure != null -> Text(failure, style = styles.footnote1, color = colors.error)
                else -> Text("尚未取得地址", style = styles.footnote1, color = colors.onSurfaceVariantSummary)
            }

            (state as? ServerState.Running)?.let { runningState ->
                Text(
                    "已运行 ${elapsed(runningState.startedAtMillis, nowMillis)}" +
                        "  ·  端口 ${runningState.ports.joinToString(",")}",
                    style = styles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    if (runningState.persistent) {
                        "固定地址 · 服务端重启后不变"
                    } else {
                        "临时地址 · 服务端一重启就会换，电脑端需要重新填"
                    },
                    style = styles.footnote2,
                    color = if (runningState.persistent) colors.primary else colors.onSurfaceVariantSummary,
                )

                if (portStatus.isEmpty()) {
                    Text("正在检测端口…", style = styles.footnote2, color = colors.onSurfaceVariantSummary)
                } else {
                    portStatus.forEach { (port, listening) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (listening) "✓" else "⚠",
                                style = styles.body1,
                                color = if (listening) Color(0xFF34C759) else colors.error,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (listening) "端口 $port 有服务在监听" else "端口 $port 上没有服务在监听",
                                style = styles.footnote2,
                                color = if (listening) colors.onSurfaceVariantSummary else colors.error,
                            )
                        }
                    }
                    val dead = portStatus.filterValues { !it }.keys
                    when {
                        PortProbe.ADB_PORT in dead -> Text(
                            "5555 是 ADB 端口。隧道建得起来，但对端是空的——电脑端会一直停在" +
                                "「ADB 尚未上线」。先在电脑上执行一次 adb tcpip 5555 即可。",
                            style = styles.footnote2,
                            color = colors.error,
                        )
                        dead.isNotEmpty() -> Text(
                            "被暴露的端口后面如果没有服务在监听，隧道虽然通，对端也拿不到任何响应。",
                            style = styles.footnote2,
                            color = colors.error,
                        )
                    }
                }
            }
        }
    }
}

/** 状态圆点。「正在启动」时做呼吸动画，静态状态则短暂过渡到目标颜色。 */
@Composable
private fun StatusDot(state: ServerState) {
    val colors = MiuixTheme.colorScheme
    val target = when (state) {
        ServerState.Stopped -> colors.outline
        ServerState.Starting -> colors.primary
        is ServerState.Running -> Color(0xFF34C759)
        is ServerState.Failed -> colors.error
    }
    val color by androidx.compose.animation.animateColorAsState(
        targetValue = target,
        animationSpec = tween(TRANSITION_MS),
        label = "statusDotColor",
    )
    val scale = if (state is ServerState.Starting) {
        val transition = rememberInfiniteTransition(label = "statusDotPulse")
        val pulse by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        )
        pulse
    } else {
        1f
    }
    val settled by animateFloatAsState(
        targetValue = scale,
        animationSpec = tween(TRANSITION_MS),
        label = "statusDotScale",
    )
    Box(
        Modifier
            .size(11.dp)
            .scale(settled)
            .alpha(settled)
            .background(color, CircleShape),
    )
}

private fun statusLabel(state: ServerState): String = when (state) {
    ServerState.Stopped -> "已停止"
    ServerState.Starting -> "正在启动…"
    is ServerState.Running -> "运行中"
    is ServerState.Failed -> "启动失败"
}

private fun elapsed(sinceMillis: Long, nowMillis: Long): String {
    val seconds = (nowMillis - sinceMillis).coerceAtLeast(0) / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "${h}小时${m}分"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}
