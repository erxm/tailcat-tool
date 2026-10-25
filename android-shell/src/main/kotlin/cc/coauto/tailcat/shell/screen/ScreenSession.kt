package cc.coauto.tailcat.shell.screen

import cc.coauto.tailcat.shell.platform.AppPreferences

import android.content.Context
import android.util.Log
import android.view.Surface
import cc.coauto.tailcat.core.model.managerError
import cc.coauto.tailcat.core.proc.RunningProcess
import cc.coauto.tailcat.core.screen.AndroidKeyCode
import cc.coauto.tailcat.core.screen.KeyAction
import cc.coauto.tailcat.core.screen.ScrcpyPacket
import cc.coauto.tailcat.core.screen.TouchAction
import cc.coauto.tailcat.core.screen.encodeBackOrScreenOn
import cc.coauto.tailcat.core.screen.encodeKeyCode
import cc.coauto.tailcat.core.screen.encodeTouch
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.shell.platform.AndroidPlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 屏幕控制的对外状态。 */
sealed interface ScreenState {
    data object Idle : ScreenState

    /** 正在推 jar / 建隧道 / 起服务端。 */
    data class Starting(val step: String) : ScreenState

    data class Running(
        val deviceName: String,
        val width: Int,
        val height: Int,
        val frames: Long,
        val fps: Float,
    ) : ScreenState

    data class Failed(val message: String) : ScreenState

    /**
     * 挂起：投屏已经真的停了（被控端的服务端进程也杀了，不给它白耗电），
     * 但界面停在原地，点一下就能继续。
     */
    data object Suspended : ScreenState

    data object Stopped : ScreenState
}

/**
 * 一次屏幕控制会话：编排 adb 那几件事，然后读流喂给解码器，把触摸发回去。
 *
 * 整个链路（每一步失败都单独报出来，别让它糊成一句「连不上」）：
 *
 * ```
 *   terminalCommand 拿 (adb 路径, serial)
 *   → push  scrcpy-server.jar 到 /data/local/tmp
 *   → forward tcp:27183 localabstract:scrcpy_<scid>
 *   → shell 起 app_process（**长驻进程，不受任务超时约束**）
 *   → 连两条 socket（video / control）
 *   → 循环：读包 → DecoderController（缓存 + 重放 + 按需重建）→ 上屏
 * ```
 *
 * 收尾必须干净：服务端是**在被控设备上**跑的进程，不显式杀掉会一直留在那儿。
 */
class ScreenSession(
    private val context: Context,
    private val manager: SessionManager,
    private val services: AndroidPlatformServices,
    private val deviceId: String,
) {

    /**
     * 会话自己的协程作用域。
     *
     * ⚠️ 它是 `var` 而不是 `val`：挂起/继续要复用同一个 [ScreenSession] 实例，
     * 而 [cleanup] 会把作用域取消掉 —— 取消过的作用域上 `launch` 是**空操作**，
     * 继续投屏时就会毫无反应、且不报任何错。所以 [start] 每次都换一个新的。
     */
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 只用来收尾：清理时 [scope] 会被取消，不能拿它干活。 */
    private val teardown = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<ScreenState>(ScreenState.Idle)
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private var serverProcess: RunningProcess? = null
    private var connection: ScrcpyConnection? = null
    private var videoThread: Thread? = null

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var deviceName: String = ""

    /** 收尾时还要用到，所以在 [start] 里存下来。 */
    private var adbPath: String? = null
    private var serial: String? = null

    /** 收尾只会真的执行一次，见 [cleanup]。每次 [start] 复位。 */
    private val cleanedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 解码层。尺寸跟踪与重建策略都在它里面，见 [DecoderController]。
     *
     * 触摸要发的 `screen_size` 必须和视频帧尺寸**完全一致**，所以 [touch] 读的就是
     * [DecoderController.videoWidth] / [DecoderController.videoHeight]。
     */
    private val decoders = DecoderController(
        surfaceProvider = { surface },
        onVideoSizeChanged = { width, height -> republish(width, height) },
        onFps = { fps -> republishFps(fps) },
    )

    private val scid = ScrcpyServer.newScid()

    fun start(target: Surface) {
        surface = target
        val current = _state.value
        if (current is ScreenState.Starting || current is ScreenState.Running) {
            // 已经在跑了。重复调用（SurfaceView 重建、或从挂起里连点两下）不能起第二个服务端
            // —— 那会在被控设备上留下一个我们再也不认识的进程，既占编码器又杀不掉。
            Log.i(TAG, "会话已在运行，忽略重复的 start()")
            return
        }
        // 挂起后继续会再次走到这里：上一个作用域已经被 cleanup 取消过，
        // 必须换一个全新的，否则 launch 是空操作（不报错，只是没反应）。
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // 收尾去重的那把锁也要复位，否则这一轮结束时 cleanup 会被当成「已经收过了」而整个跳过
        cleanedUp.set(false)
        // 上一次会话的起始包缓存作废：分辨率/参数集可能都变了
        decoders.resetBootstrap()

        scope.launch {
            try {
                _state.value = ScreenState.Starting("准备连接")
                val (toolPath, deviceSerial) = manager.terminalCommand(deviceId)
                adbPath = toolPath
                serial = deviceSerial
                val adb = services.adbClient(toolPath)
                val runner = services.createRunner()

                _state.value = ScreenState.Starting("上传服务端")
                val jar = ScrcpyServer.stageJar(context)
                runner.run(adb.pushCommand(deviceSerial, jar.absolutePath, ScrcpyServer.REMOTE_JAR))
                    .requireSuccess()

                _state.value = ScreenState.Starting("建立端口转发")
                runner.run(
                    adb.forwardCommand(
                        deviceSerial,
                        "tcp:${ScrcpyServer.LOCAL_PORT}",
                        ScrcpyServer.forwardSpec(scid),
                    ),
                ).requireSuccess()

                _state.value = ScreenState.Starting("启动服务端")
                // 画质档位在设置页选，改完下一场投屏生效
                val quality = AppPreferences.mirrorQuality()
                // ⚠️ 用 start 而不是 run：服务端要一直活着，而 run 带超时会把它掐掉。
                // 超时给一个极大的值只是防御性的写法，start 本身不理会这个字段。
                serverProcess = runner.start(
                    adb.shellCommand(
                        deviceSerial,
                        ScrcpyServer.launchCommand(
                            scid,
                            quality.maxSize,
                            quality.bitRate,
                            quality.maxFps,
                        ),
                        timeoutMillis = Long.MAX_VALUE,
                    ),
                    onOutput = { Log.d(TAG, "server: ${it.trimEnd()}") },
                    onExit = { code ->
                        Log.i(TAG, "服务端退出，code=$code")
                        if (_state.value is ScreenState.Running) fail("被控端的服务端已退出（code=$code）")
                    },
                )

                _state.value = ScreenState.Starting("连接视频流")
                val opened = connectWithRetry()
                connection = opened
                deviceName = opened.deviceName

                _state.value = ScreenState.Starting("等待首帧")
                videoThread = Thread({ readLoop(opened) }, "scrcpy-video").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                fail(t.message ?: t::class.simpleName ?: "未知错误")
            }
        }
    }

    /**
     * 服务端起来要一点时间。这里连不上就重试 —— `adb forward` 建好之后本地端口立刻就开始监听，
     * 但设备端的 abstract socket 还没建起来，连接会被拒。
     */
    private fun connectWithRetry(): ScrcpyConnection {
        var last: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                return ScrcpyConnection(ScrcpyServer.LOCAL_PORT)
            } catch (t: Throwable) {
                last = t
                Thread.sleep(CONNECT_RETRY_DELAY_MILLIS * (attempt + 1))
            }
        }
        managerError("连接被控端视频流失败：${last?.message ?: "超时"}")
    }

    /**
     * 读视频流的主循环。
     *
     * 这里**不再自己管解码器的重建与参数集拼接** —— 那套逻辑（缓存 config/关键帧、
     * 新解码器建好就回放）全在 [DecoderController] 里。读流这层只负责把包按原样递过去。
     */
    private fun readLoop(active: ScrcpyConnection) {
        var packets = 0
        try {
            while (!Thread.currentThread().isInterrupted) {
                val video = active.nextVideoPacket() ?: break
                packets++
                when (val packet = video.packet) {
                    is ScrcpyPacket.Session -> {
                        Log.i(
                            TAG,
                            "会话尺寸 ${packet.width}x${packet.height}（resize=${packet.clientResized}）",
                        )
                        // 尺寸没变时它直接返回 false，不会白拆一次解码器 ——
                        // 服务端在采集会话重启时也会重发 session packet，而尺寸往往原样。
                        decoders.rebuildForSize(packet.width, packet.height, VIDEO_MIME)
                        republish(packet.width, packet.height)
                    }

                    is ScrcpyPacket.Media -> {
                        if (packets <= DIAGNOSTIC_PACKETS) {
                            Log.d(
                                TAG,
                                "包 #$packets media config=${packet.isConfig} " +
                                    "key=${packet.isKeyFrame} pts=${packet.ptsMicros} size=${packet.size}",
                            )
                        }
                        // 没有可用解码器就先建一个。此刻 bootstrap 缓存里通常已经有
                        // config + 关键帧，建好即回放 → 立刻出画，不用干等下一个关键帧。
                        if (!decoders.isUsable) {
                            decoders.ensureDecoder(
                                decoders.videoWidth,
                                decoders.videoHeight,
                                VIDEO_MIME,
                            )
                        }
                        val usable = decoders.feed(
                            video.payload,
                            packet.ptsMicros,
                            packet.isKeyFrame,
                            packet.isConfig,
                        )
                        if (!usable) {
                            // 解码器进了不可恢复的错误状态。这里**不能**调 releaseAll
                            // ——那会把 bootstrap 缓存一起清掉，重建后就只能干等关键帧了。
                            // 交给下一个包触发 ensureDecoder 去释放并重建。
                            Log.w(TAG, "解码器已不可用，等待重建")
                        }
                        val frames = decoders.decodedFrames
                        val current = _state.value
                        if (current is ScreenState.Running && current.frames != frames) {
                            _state.value = current.copy(frames = frames)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            if (!Thread.currentThread().isInterrupted) fail(t.message ?: "视频流中断")
            return
        }
        if (_state.value is ScreenState.Running) _state.value = ScreenState.Stopped
    }

    private fun republish(width: Int, height: Int) {
        val fps = (_state.value as? ScreenState.Running)?.fps ?: 0f
        _state.value = ScreenState.Running(deviceName, width, height, decoders.decodedFrames, fps)
    }

    private fun republishFps(fps: Float) {
        val current = _state.value
        if (current is ScreenState.Running) _state.value = current.copy(fps = fps)
    }

    /**
     * 发一次触摸。
     *
     * [viewX]/[viewY] 是控件内的像素坐标；界面把 SurfaceView 约束成视频的宽高比，
     * 所以这里只需按比例缩放到**视频帧坐标**。
     *
     * ⚠️ 发出去的 `screen_size` 必须是当前视频尺寸。服务端拿它和真实视频尺寸比对，
     * 不一致就**静默丢弃**这次事件 —— 表现出来是「点了没反应」，没有任何报错。
     */
    fun touch(action: TouchAction, viewX: Float, viewY: Float, viewWidth: Int, viewHeight: Int) {
        val active = connection ?: return
        val width = decoders.videoWidth
        val height = decoders.videoHeight
        if (width <= 0 || height <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        val x = (viewX / viewWidth * width).toInt().coerceIn(0, width - 1)
        val y = (viewY / viewHeight * height).toInt().coerceIn(0, height - 1)
        val pressure = if (action == TouchAction.Up) 0f else 1f

        // 编码和发送**分开** catch：混在一个 runCatching 里时，编码自身的 bug 会伪装成
        // 「发送失败」；而真正发送失败的异常 message 又可能是 null
        // （`NetworkOnMainThreadException` 就是用无参构造抛的），
        // 日志里只剩一句没头没尾的「发送触摸失败：null」，等于没有信息。
        val message = runCatching {
            encodeTouch(action, x, y, width, height, pressure)
        }.getOrElse {
            Log.w(TAG, "编码触摸消息失败", it)
            return
        }
        runCatching { active.sendControl(message) }
            .onFailure { Log.w(TAG, "发送触摸失败", it) }
    }

    private fun fail(message: String) {
        Log.w(TAG, "屏幕控制失败：$message")
        _state.value = ScreenState.Failed(message)
        cleanup()
    }

    fun stop() {
        _state.value = ScreenState.Stopped
        cleanup()
    }

    /**
     * 挂起投屏。
     *
     * 是**真的停**，不是「暂停收流」：scrcpy 服务端没有暂停这档事，放着不管它就会一直编码，
     * 白耗被控端的电和硬件编码器。所以这里连同被控端的服务端进程一起收掉，
     * 继续时重新走一遍启动流程（几秒）。
     */
    fun pause() {
        val current = _state.value
        if (current !is ScreenState.Running && current !is ScreenState.Starting) return
        Log.i(TAG, "挂起投屏")
        _state.value = ScreenState.Suspended
        cleanup()
    }

    /** 从挂起中继续。SurfaceView 一直活着，所以还是用同一个 Surface。 */
    fun resume() {
        val target = surface
        if (target == null || !target.isValid) {
            fail("画面已失效，请退出后重新进入")
            return
        }
        Log.i(TAG, "继续投屏")
        start(target)
    }

    /**
     * 给被控端发一个按键。自动成对发 DOWN + UP —— 只发 DOWN 等于一直按着不放。
     */
    fun sendKey(keyCode: Int) {
        val active = connection ?: return
        val down = runCatching { encodeKeyCode(KeyAction.DOWN, keyCode) }
            .getOrElse { Log.w(TAG, "编码按键失败 keyCode=$keyCode", it); return }
        val up = encodeKeyCode(KeyAction.UP, keyCode)
        sendControl(active, down)
        sendControl(active, up)
    }

    /**
     * 给被控端发「返回」。
     *
     * 走专门的 `BACK_OR_SCREEN_ON` 而不是 `sendKey(KEYCODE_BACK)`：**被控端屏幕熄灭时
     * 这条消息的语义是「点亮屏幕」**，那个判断只有服务端做得了（它才知道对端屏幕状态）。
     */
    fun sendBack() {
        val active = connection ?: return
        sendControl(active, encodeBackOrScreenOn(KeyAction.DOWN))
        sendControl(active, encodeBackOrScreenOn(KeyAction.UP))
    }

    /** 让被控端回到桌面。 */
    fun sendHome() = sendKey(AndroidKeyCode.HOME)

    /** 让被控端打开多任务。 */
    fun sendRecents() = sendKey(AndroidKeyCode.APP_SWITCH)

    private fun sendControl(active: ScrcpyConnection, message: ByteArray) {
        runCatching { active.sendControl(message) }
            .onFailure { Log.w(TAG, "发送控制消息失败", it) }
    }

    /**
     * 收尾：拆掉运行时，并把 [scope] 一并取消。
     *
     * 「挂起」用的是 [teardownSession]（不取消作用域），因为还要能继续；
     * 这里是真退出，作用域也要扔掉。
     *
     * ⚠️ **必须幂等**。退出投屏时至少会有两条路径同时打到收尾：界面的 `onDispose`、
     * 以及读流线程因为 socket 被关掉而走的 `fail()`。不去重的话每次退出都会向被控设备
     * 多发一遍 `pkill`（实测日志里能看到重复行）—— 不会出错，但白白多一次 adb 往返，
     * 而且让日志变得不可信。
     */
    private fun cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) return
        teardownSession()
        scope.cancel()
    }

    /**
     * 把一次会话的**运行时**全部拆掉：断流、释放解码器、连被控端那个服务端进程一起收掉。
     *
     * 不碰 [scope]，所以「挂起」也能走这条路 —— 挂起和退出的区别只在于要不要把作用域也取消。
     */
    private fun teardownSession() {
        videoThread?.interrupt()
        videoThread = null
        runCatching { connection?.close() }
        connection = null
        runCatching { decoders.releaseAll() }

        val process = serverProcess
        serverProcess = null
        teardown.launch {
            process?.let { runCatching { it.stop() } }
            killRemoteServer()
        }
    }

    /**
     * 去**被控设备**上把服务端进程收掉。
     *
     * 光停掉本机的 `adb shell` 是不够的 —— 实测 App 进程被系统杀掉（或直接覆盖安装）时，
     * 那条 adb 连接是硬断的，服务端不会收到任何信号，于是它就一直留在被控设备上占着
     * 硬件编码器。所以这里补一刀，按 scid 精确定位（不误伤别人的会话）。
     *
     * ⚠️ 必须在 [teardown] 上跑：本方法由 [cleanup] 触发，而那里已经把 `scope` 取消了，
     * 在已取消的 scope 上 `launch` 是空操作 —— 那种 bug 不报错，只是静静地不生效。
     */
    private suspend fun killRemoteServer() {
        val path = adbPath
        val target = serial
        if (path == null || target == null) {
            Log.w(TAG, "没有 adb 路径或序列号，跳过被控端清理（scid=$scid）")
            return
        }
        runCatching {
            val adb = services.adbClient(path)
            val result = services.createRunner().run(
                adb.shellCommand(target, ScrcpyServer.killCommand(scid), timeoutMillis = 10_000),
            )
            // 打出来：这条命令失败时**不会抛异常**（末尾有 `; true`），只有退出码和输出能说明问题
            Log.i(
                TAG,
                "被控端清理 scid=$scid 退出码=${result.exitCode} 超时=${result.timedOut} " +
                    "输出=${result.output.trim()}",
            )
        }.onFailure { Log.w(TAG, "清理被控端服务端失败", it) }
    }

    private companion object {
        const val TAG = "ScrcpySession"
        const val VIDEO_MIME = "video/avc"

        /** 排障时打前几十个包的详情就够了；常开会让日志被淹没。 */
        const val DIAGNOSTIC_PACKETS = 30
        const val CONNECT_ATTEMPTS = 10
        const val CONNECT_RETRY_DELAY_MILLIS = 300L
    }
}
