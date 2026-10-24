package cc.coauto.tailcat.shell.screen

import android.util.Log
import android.view.Surface
import java.util.ArrayDeque

/**
 * 管着 [ScreenDecoder] 的生死，以及**解码器重建后怎么立刻续上画面**。
 *
 * **本文件移植自 `Miuzarte/ScrcpyForAndroid`（Apache-2.0）的 `VideoDecoderController`。**
 * 详见 `THIRD_PARTY_NOTICES.md`。去掉了它那套 `PersistentVideoRenderer`（它还支持录制、
 * 画中画等多路输出），这边只需要往 SurfaceView 的 Surface 上画。
 *
 * 为什么要缓存+重放（这是移植它的**主要理由**）：
 *
 * 服务端在采集会话重启时会重发 session packet，解码器要跟着重建。而 H.264 的关键帧间隔
 * 可能有好几秒 —— **新建的解码器如果只能干等下一个关键帧，画面就会黑/冻住好几秒**。
 * 所以这里把「一组能独立解码的起始包」留着：config（SPS/PPS）+ 关键帧 + 后续若干帧，
 * 新解码器一建好就按顺序回放进去，立刻出画。
 *
 * ```
 *   config 到达   → 清空缓存，只留 config
 *   keyframe 到达 → 清空缓存，留 [config?, keyframe]
 *   普通帧到达    → 追加，超过 MAX_BOOTSTRAP_PACKETS 就丢最旧的
 * ```
 */
class DecoderController(
    /** 拿当前要用的输出 Surface。没了（比如 SurfaceView 被销毁）就返回 null。 */
    private val surfaceProvider: () -> Surface?,
    /** 解码器报告真实输出尺寸（crop 后）。 */
    private val onVideoSizeChanged: (Int, Int) -> Unit = { _, _ -> },
    /** 解码帧率，仅用于界面显示。 */
    private val onFps: (Float) -> Unit = {},
) {

    private val lock = Any()
    private var decoder: ScreenDecoder? = null

    private val bootstrapLock = Any()
    private val bootstrapPackets = ArrayDeque<CachedPacket>()
    private var latestConfigPacket: CachedPacket? = null

    /** 上次建解码器失败的时刻。0 表示还没失败过（第一次尝试不会被退避挡住）。 */
    private var lastCreateFailureNanos = 0L

    /** 当前视频尺寸。由 session packet 初始化，之后可能被解码器的输出尺寸修正。 */
    @Volatile
    var videoWidth: Int = 0
        private set

    @Volatile
    var videoHeight: Int = 0
        private set

    /** 累计解码输出帧数。 */
    val decodedFrames: Long
        get() = synchronized(lock) { decoder?.decodedFrames ?: 0L }

    /** 现有解码器是否还能用。没有解码器时返回 false（调用方应 [ensureDecoder]）。 */
    val isUsable: Boolean
        get() = synchronized(lock) { decoder?.isUsable() == true }

    /**
     * 确保有一个可用的解码器。已有且健康就直接返回，不做任何事。
     *
     * 尺寸未知（<=0）时**不建**解码器：拿 0 去 configure 会直接抛，重试几次才等到
     * session packet 是正常的。
     */
    fun ensureDecoder(width: Int, height: Int, mime: String): Boolean {
        synchronized(lock) {
            decoder?.let { if (it.isUsable()) return true }
            if (width <= 0 || height <= 0) {
                Log.i(TAG, "尺寸未知（${width}x$height），暂不建解码器")
                return false
            }
            // 建失败时退避：调用方是逐帧来的，不退避的话 Surface 一旦不可用
            // 就会变成每帧一次 configure 尝试（每秒几十次异常），日志瞬间被刷爆。
            if (System.nanoTime() - lastCreateFailureNanos < CREATE_RETRY_BACKOFF_NANOS) {
                return false
            }
            videoWidth = width
            videoHeight = height
            // 已经坏了/没建成的先释放，别让它在后台占着编码器资源
            decoder?.let {
                Log.i(TAG, "释放损坏的解码器，准备重建")
                runCatching { it.release() }
            }
            decoder = null
            return createLocked(width, height, mime)
        }
    }

    /**
     * 视频尺寸变了（旋转、或客户端请求改分辨率）→ 重建解码器。
     *
     * 尺寸没变就直接返回 false —— 服务端**在采集会话重启时也会重发 session packet**，
     * 那种情况下尺寸往往原样不变，后面跟的仍是普通的参数集+关键帧，交给现有解码器
     * 继续吃就行。**原先我无条件重建，遇到反复重启的设备就永远解不出第二帧**
     * （每建好一次马上被拆掉）。
     */
    fun rebuildForSize(width: Int, height: Int, mime: String): Boolean {
        synchronized(lock) {
            if (decoder == null) {
                // 首次创建：等到第一个 session packet 才知道尺寸
                return ensureDecoder(width, height, mime)
            }
            if (width == videoWidth && height == videoHeight) return false
            Log.i(TAG, "视频尺寸 ${videoWidth}x$videoHeight → ${width}x$height，重建解码器")
            videoWidth = width
            videoHeight = height
            runCatching { decoder?.release() }
            decoder = null
        }

        // ⚠️ 让 OMX/Codec2 把缓冲还给旧的 native window 之后再建新的。
        // 不等的话，某些芯片上会出现「新解码器 configure 到同一个 Surface 上失败」——
        // 表现是重建后一直黑屏，日志里什么都没有。
        Thread.sleep(REBUILD_SETTLE_MILLIS)

        synchronized(lock) {
            return createLocked(videoWidth, videoHeight, mime)
        }
    }

    /**
     * 收一个包：先登记进 bootstrap 缓存，再喂给现有解码器。
     *
     * 返回解码器是否仍然可用。false 表示上层应当重建解码器或重启会话。
     */
    fun feed(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean, isConfig: Boolean): Boolean {
        cacheBootstrapPacket(data, ptsUs, isKeyFrame, isConfig)
        val active = synchronized(lock) { decoder } ?: return true
        runCatching { active.feedAnnexB(data, ptsUs, isKeyFrame, isConfig) }
        return active.isUsable()
    }

    /**
     * 换输出 Surface（SurfaceView 重建时会走到）。失败就要重建解码器。
     * 返回 true 表示已可用。
     */
    fun switchSurface(): Boolean {
        val surface = surfaceProvider() ?: return false
        val active = synchronized(lock) { decoder }
        if (active == null) return false
        if (!active.isUsable()) return false
        return active.switchOutputSurface(surface)
    }

    /** 丢弃 bootstrap 缓存（新会话开始时用），但不动现有解码器。 */
    fun resetBootstrap() {
        synchronized(bootstrapLock) {
            bootstrapPackets.clear()
            latestConfigPacket = null
        }
    }

    fun releaseAll() {
        synchronized(lock) {
            runCatching { decoder?.release() }
            decoder = null
            videoWidth = 0
            videoHeight = 0
        }
        resetBootstrap()
    }

    /** 必须在持有 [lock] 时调用。 */
    private fun createLocked(width: Int, height: Int, mime: String): Boolean {
        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "没有可用的 Surface，无法建解码器")
            return false
        }
        val fresh = try {
            ScreenDecoder(
                width = width,
                height = height,
                outputSurface = surface,
                mimeType = mime,
                onOutputSizeChanged = { w, h ->
                    if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                        videoWidth = w
                        videoHeight = h
                        onVideoSizeChanged(w, h)
                    }
                },
                onFpsUpdated = onFps,
            )
        } catch (t: Throwable) {
            // 这里**不**往上抛：解码器起不来是可以恢复的（等下一轮重建），
            // 抛出去会把整条读流线程打死，连画面尺寸都再也等不到了。
            Log.e(TAG, "建解码器失败 ${width}x$height：${t.message}")
            lastCreateFailureNanos = System.nanoTime()
            return false
        }
        decoder = fresh
        replayLocked(fresh)
        return true
    }

    /**
     * 把缓存的起始包回放到新解码器里 —— 这一步决定了重建之后是「立刻出画」
     * 还是「黑好几秒等关键帧」。
     */
    private fun replayLocked(decoder: ScreenDecoder) {
        val snapshot = synchronized(bootstrapLock) { bootstrapPackets.toList() }
        if (snapshot.isEmpty()) return
        Log.i(TAG, "回放起始包 ${snapshot.size} 个")
        snapshot.forEachIndexed { index, packet ->
            if (!decoder.isUsable()) {
                Log.w(TAG, "解码器已不可用，回放到第 $index 个中止")
                return
            }
            runCatching {
                decoder.feedAnnexB(packet.data, packet.ptsUs, packet.isKeyFrame, packet.isConfig)
            }
        }
    }

    private fun cacheBootstrapPacket(
        data: ByteArray,
        ptsUs: Long,
        isKeyFrame: Boolean,
        isConfig: Boolean,
    ) {
        val cached = CachedPacket(data.copyOf(), ptsUs, isConfig, isKeyFrame)
        synchronized(bootstrapLock) {
            when {
                cached.isConfig -> {
                    // 参数集一变，之前攒的帧就全作废了（它们引用的是旧 SPS/PPS）
                    latestConfigPacket = cached
                    bootstrapPackets.clear()
                    bootstrapPackets.addLast(cached)
                }

                cached.isKeyFrame -> {
                    bootstrapPackets.clear()
                    latestConfigPacket?.let { bootstrapPackets.addLast(it) }
                    bootstrapPackets.addLast(cached)
                }

                else -> {
                    while (bootstrapPackets.size >= MAX_BOOTSTRAP_PACKETS) {
                        bootstrapPackets.removeFirst()
                    }
                    bootstrapPackets.addLast(cached)
                }
            }
        }
    }

    private class CachedPacket(
        val data: ByteArray,
        val ptsUs: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
    )

    private companion object {
        const val TAG = "ScrcpyDecoderCtl"

        /** 攒 90 帧：够覆盖到下一个关键帧，又不至于让内存无限涨。 */
        const val MAX_BOOTSTRAP_PACKETS = 90

        const val REBUILD_SETTLE_MILLIS = 50L

        const val CREATE_RETRY_BACKOFF_NANOS = 1_000_000_000L
    }
}
