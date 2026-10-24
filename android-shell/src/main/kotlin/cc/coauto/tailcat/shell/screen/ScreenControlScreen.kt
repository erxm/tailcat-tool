package cc.coauto.tailcat.shell.screen

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cc.coauto.tailcat.core.screen.TouchAction
import cc.coauto.tailcat.shell.manager.ManagerModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * 屏幕控制的全屏界面。
 *
 * **它被渲染在 `TailcatApp` 的最外层，不在那两个 tab 的 Column 里面** —— 否则顶部会一直挂着
 * TabRow，就不是真的全屏了。进入时隐藏系统栏、退出时恢复。
 *
 * 触摸坐标在这里按控件尺寸换算成视频帧坐标（[ScreenSession.touch] 负责最后那步缩放）：
 * SurfaceView 被约束成视频的宽高比，所以控件本身就是画面，不需要 letterbox 补偿。
 *
 * ## 关于「宿主机手势」
 *
 * 宿主机的返回/桌面/多任务手势**没法完全屏蔽**，这是 Android 的硬限制：
 * `setSystemGestureExclusionRects` 的排除高度被系统**封顶在 200dp**，而底部上滑回桌面
 * 根本不在可排除范围内（官方给的唯一出路是沉浸模式，也就是下面 already 在做的事）。
 *
 * 所以这里的策略是两条腿：
 *  1. 沉浸模式 + 左右边缘各排除 200dp，把误触降到最低；
 *  2. 悬浮球里给「返回 / 桌面 / 多任务」三个键，**发到被控端去执行** ——
 *     手势挡不住，那就用按钮把同一件事做掉，操作手感才是对的。
 */
@Composable
fun ScreenControlScreen(model: ManagerModel, deviceId: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    val session = remember(deviceId) {
        ScreenSession(context, model.manager, model.services, deviceId)
    }
    val state by session.state.collectAsState()

    // 全屏：藏掉状态栏和导航栏。退出时必须显式恢复，否则回到管理界面就没有状态栏了。
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // 关掉界面时一定要把会话收干净：被控设备上那个服务端进程不属于我们，不杀会一直留着
    DisposableEffect(session) {
        onDispose { session.stop() }
    }
    // 边缘手势排除：把左右两侧各 200dp（系统上限）从「返回手势」里挖出来
    ExcludeEdgeGestures(view)

    val aspect = (state as? ScreenState.Running)
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width.toFloat() / it.height.toFloat() }
        ?: DEFAULT_ASPECT

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            // 只给 aspectRatio、不加 fillMaxSize：让它在 Box 的有界约束里自己找能放下的尺寸。
            // 加上 fillMaxSize 会把两边都撑满，aspectRatio 就没得调整了。
            modifier = Modifier
                .aspectRatio(aspect)
                // ⚠️ key 里**不能**放 state：Running 状态每解码一帧就变一次，
                // 放进去等于让手势识别器每帧重启 —— 按住拖动会被反复截断，
                // 表现是「拖着拖着就断了」。
                .pointerInput(session) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        session.touch(
                            TouchAction.Down, down.position.x, down.position.y,
                            size.width, size.height,
                        )
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                session.touch(
                                    TouchAction.Up, change.position.x, change.position.y,
                                    size.width, size.height,
                                )
                                change.consume()
                                break
                            }
                            if (change.positionChanged()) {
                                session.touch(
                                    TouchAction.Move, change.position.x, change.position.y,
                                    size.width, size.height,
                                )
                                change.consume()
                            }
                        }
                    }
                },
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    // 看画面的时候不该因为息屏中断
                    keepScreenOn = true
                    holder.addCallback(
                        object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                session.start(holder.surface)
                            }

                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                // Surface 没了解码器就没法输出。整段停掉，回来重新点一次
                                // —— 比留一个半死不活的会话、让用户对着黑屏猜要好。
                                session.stop()
                            }
                        },
                    )
                }
            },
        )

        // 悬浮球常驻：它是手势挡不住时的唯一出口
        FloatingBall(
            suspended = state is ScreenState.Suspended,
            onBack = session::sendBack,
            onHome = session::sendHome,
            onRecents = session::sendRecents,
            onToggleSuspend = {
                if (state is ScreenState.Suspended) session.resume() else session.pause()
            },
            onExit = onExit,
        )

        when (val current = state) {
            is ScreenState.Starting -> Overlay(text = current.step, showSpinner = true, onExit = onExit)
            is ScreenState.Failed -> Overlay(text = current.message, showSpinner = false, onExit = onExit)
            is ScreenState.Stopped -> Overlay(text = "已停止", showSpinner = false, onExit = onExit)
            is ScreenState.Suspended -> SuspendedOverlay(
                onResume = { session.resume() },
                onExit = onExit,
            )
            is ScreenState.Running -> RunningInfo(current)
            ScreenState.Idle -> Unit
        }
    }
}

/**
 * 把左右两侧各 [GESTURE_EXCLUSION_DP] 从系统的「返回手势」区域里排除掉。
 *
 * ⚠️ **200dp 是系统的硬上限**（官方文档原文："the system will put a limit of 200dp on the
 * vertical extent of the exclusions it takes into account"）。给更高的矩形不会更管用，
 * 系统只会取其中 200dp，而且取哪一段是它说了算 —— 所以这里显式给一条居中的 200dp 带，
 * 至少这块位置是确定的。
 *
 * ⚠️ 只对**返回**手势有效。**底部上滑回桌面是排除不掉的**，那是系统保留的，
 * 应用一律拦不住。
 */
@Composable
private fun ExcludeEdgeGestures(view: android.view.View) {
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@DisposableEffect onDispose { }
        }
        var applied = false
        // 布局还没跑完时 width/height 是 0，那时候设等于没设 —— 所以 post 到下一个消息里。
        view.post {
            if (view.width <= 0 || view.height <= 0) return@post
            val band = (GESTURE_EXCLUSION_DP * view.resources.displayMetrics.density).toInt()
            val top = (view.height - band) / 2
            view.systemGestureExclusionRects = listOf(
                Rect(0, top, band, top + band),
                Rect(view.width - band, top, view.width, top + band),
            )
            applied = true
        }
        onDispose {
            if (applied) view.systemGestureExclusionRects = emptyList()
        }
    }
}

/**
 * 悬浮球（可拖动，点一下展开操作面板）。
 *
 * 面板里那三个导航键是**发给被控端**的：宿主的返回/桌面手势挡不干净，
 * 那就把同一件事用按钮做掉。
 */
@Composable
private fun FloatingBall(
    suspended: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onToggleSuspend: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val density = LocalDensity.current

    val ballSize = 46.dp
    val ballPx = with(density) { ballSize.toPx() }
    val gapPx = with(density) { 10.dp.toPx() }

    val posState = remember { mutableStateOf<Offset?>(null) }
    val expandedState = remember { mutableStateOf(false) }
    var panelSize by remember { mutableStateOf(IntSize.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxX = with(density) { maxWidth.toPx() } - ballPx
        val maxY = with(density) { maxHeight.toPx() } - ballPx
        // 默认落在右侧偏上：既不挡中间的操作区，也不压系统栏那一带
        val fallback = Offset(maxX - gapPx, maxY * 0.38f)
        val fallbackState = rememberUpdatedState(fallback)
        val maxXState = rememberUpdatedState(maxX)
        val maxYState = rememberUpdatedState(maxY)

        val current = posState.value ?: fallback

        // 面板放在球的左边；左边放不下就翻到右边。高度按球心对齐。
        val panelW = panelSize.width.toFloat()
        val panelH = panelSize.height.toFloat()
        val panelLeft = (current.x - panelW - gapPx).takeIf { it >= 0f }
            ?: (current.x + ballPx + gapPx).coerceAtMost((maxX + ballPx - panelW).coerceAtLeast(0f))
        val panelTop = (current.y + ballPx / 2f - panelH / 2f)
            .coerceIn(0f, (maxY + ballPx - panelH).coerceAtLeast(0f))

        if (expandedState.value) {
            Card(
                modifier = Modifier
                    .offset { IntOffset(panelLeft.roundToInt(), panelTop.roundToInt()) }
                    .width(PANEL_WIDTH)
                    .onSizeChanged { panelSize = it },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        if (suspended) "已挂起" else "发给被控端",
                        style = styles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    PanelItem("返回", onBack)
                    PanelItem("桌面", onHome)
                    PanelItem("多任务", onRecents)
                    PanelItem(if (suspended) "继续投屏" else "挂起投屏", onToggleSuspend)
                    PanelItem("退出投屏", onExit)
                }
            }
        }

        Box(
            Modifier
                .offset { IntOffset(current.x.roundToInt(), current.y.roundToInt()) }
                .size(ballSize)
                .clip(CircleShape)
                .background(Color(0xCC1F1F1F))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var dragged = false
                        var travelled = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChanged()) {
                                val delta = change.positionChange()
                                travelled += delta.getDistance()
                                // 先要超过 touchSlop 才算拖动，否则手指抖一下就会把「点」判成「拖」
                                if (!dragged && travelled > viewConfiguration.touchSlop) dragged = true
                                if (dragged) {
                                    val base = posState.value ?: fallbackState.value
                                    posState.value = Offset(
                                        (base.x + delta.x).coerceIn(0f, maxXState.value),
                                        (base.y + delta.y).coerceIn(0f, maxYState.value),
                                    )
                                    change.consume()
                                }
                            }
                        }
                        if (!dragged) expandedState.value = !expandedState.value
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (suspended) "▶" else "≡", color = Color.White, style = styles.title3)
        }
    }
}

@Composable
private fun PanelItem(label: String, onClick: () -> Unit) {
    TextButton(
        text = label,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RunningInfo(state: ScreenState.Running) {
    // 保持画面干净：正常运行时不显示任何常驻控件，只在左上角给一条极淡的信息
    Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.TopStart) {
        Text(
            "${state.deviceName} · ${state.width}x${state.height} · " +
                "${state.frames} 帧 · ${state.fps.toInt()} fps",
            color = Color(0x66FFFFFF),
            style = MiuixTheme.textStyles.footnote2,
        )
    }
}

@Composable
private fun SuspendedOverlay(onResume: () -> Unit, onExit: () -> Unit) {
    // 挂起是**真停**（被控端那个服务端进程也收了，不白耗它的电），所以这里得说清楚
    Overlay(
        text = "投屏已挂起\n被控端的服务端已停止，不会继续耗电",
        showSpinner = false,
        onExit = onExit,
        primary = "继续投屏" to onResume,
    )
}

@Composable
private fun Overlay(
    text: String,
    showSpinner: Boolean,
    onExit: () -> Unit,
    primary: Pair<String, () -> Unit>? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showSpinner) CircularProgressIndicator()
        Text(text, color = Color.White)
        primary?.let { (label, action) -> Button(onClick = action) { Text(label) } }
        Button(onClick = onExit) { Text("返回") }
    }
}

/** 系统对边缘手势排除的硬上限，给更多也不会生效。 */
private const val GESTURE_EXCLUSION_DP = 200

private val PANEL_WIDTH = 132.dp

private const val DEFAULT_ASPECT = 9f / 16f
