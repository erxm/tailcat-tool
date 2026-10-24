package cc.coauto.tailcat.shell.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * 解码器起不来时抛的异常 —— 带上 mime 和尺寸，否则 MediaCodec 抛出来的 message 经常是空的，
 * 界面上就只剩一句「无法启动解码器：」，什么线索都没有。
 */
class DecoderStartException(
    val mime: String,
    val width: Int,
    val height: Int,
    cause: Throwable,
) : Exception("无法启动解码器 $mime ${width}x$height：${cause.javaClass.simpleName} ${cause.message.orEmpty()}".trim(), cause)

/**
 * H.264/H.265 硬解，直接渲染到 [outputSurface]。
 *
 * **本文件移植自 `Miuzarte/ScrcpyForAndroid`（Apache-2.0）的 `MediaCodecVideoDecoder`。**
 * 详见 `THIRD_PARTY_NOTICES.md`。之所以换成它，是因为我原来那份实现在几个点上和
 * 这台设备上的真实行为对不上（见下面每条注释里的「原先」）。
 *
 * 用 Surface 输出而不是 ImageReader，是**故意**的：SurfaceFlinger 会按解码器给出的
 * crop rect 显示，省掉手工处理 `KEY_CROP_*` / slice-height 那一串 OEM 差异。
 *
 * 线程约定：[feedAnnexB] / [switchOutputSurface] / [release] 都是 `@Synchronized`，
 * 解码与取帧在**同一个调用线程**上完成（没有独立的 drain 线程）——
 * MediaCodec 的输入输出队列本来就是同一个上下文，拆成两个线程只会多出一类竞态。
 */
class ScreenDecoder(
    private val width: Int,
    private val height: Int,
    private val outputSurface: Surface,
    private val mimeType: String = MIME_AVC,
    /** 解码器报告真实输出尺寸时回调（crop 之后的值，可能和传入的 width/height 不同）。 */
    private val onOutputSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
    private val onFpsUpdated: ((fps: Float) -> Unit)? = null,
) {

    private val codec: MediaCodec
    private val bufferInfo = MediaCodec.BufferInfo()

    private var inCount = 0L
    private var outCount = 0L
    private var fpsWindowStartNs = System.nanoTime()
    private var fpsWindowFrameCount = 0

    @Volatile
    private var released = false

    /**
     * 解码器进入不可恢复的错误状态时被置上（configure/start 失败，或运行期
     * `CodecException` / `IllegalStateException`）。一旦非空，[isUsable] 变 false，
     * 后续 [feedAnnexB] 变成空操作，由上层决定是否重建。
     */
    @Volatile
    var codecError: Throwable? = null
        private set

    init {
        // ⚠️ MediaCodec 要求调用线程有 Looper。我们这边 feed 是从读流的普通 Thread 上调的，
        // 那个线程没有 Looper —— 在部分机型上会表现为解码器行为异常。
        // 上游 issue #63 就是这个问题。补一个最省事的 Looper，对正常机型无副作用。
        if (Looper.myLooper() == null) {
            Log.w(TAG, "当前线程没有 Looper，为 MediaCodec 补一个")
            Looper.prepare()
        }

        if (!outputSurface.isValid) {
            throw DecoderStartException(
                mimeType, width, height,
                IllegalStateException("输出 Surface 已失效"),
            )
        }

        val format = MediaFormat.createVideoFormat(mimeType, width, height)
        codec = try {
            MediaCodec.createDecoderByType(mimeType).also {
                it.configure(format, outputSurface, null, 0)
                it.start()
            }
        } catch (t: Throwable) {
            throw DecoderStartException(mimeType, width, height, t)
        }
        Log.i(TAG, "解码器已启动 $mimeType ${width}x$height")
    }

    /**
     * 喂一个 Annex-B 帧。
     *
     * [isConfig] 表示这是参数集包（SPS/PPS）：它会带上 `BUFFER_FLAG_CODEC_CONFIG` 标志。
     * **原先我没打这个标志**，只把参数集字节拼在下一帧前面 —— 解码器认不认这套是看实现的，
     * 打上标志才是它期望的正式通道。
     *
     * [isConfig] / [isKeyFrame] 这类关键包在拿不到输入 buffer 时会先抽一次输出再重试：
     * 丢关键包等于画面从此不动，宁可多等 50ms。
     */
    @Synchronized
    fun feedAnnexB(data: ByteArray, ptsUs: Long, isKeyFrame: Boolean, isConfig: Boolean = false) {
        if (released || codecError != null) return
        runCatching {
            var inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (inputIndex < 0 && (isConfig || isKeyFrame)) {
                drainOutput()
                inputIndex = codec.dequeueInputBuffer(CRITICAL_INPUT_TIMEOUT_US)
            }
            if (inputIndex >= 0) {
                val buffer = codec.getInputBuffer(inputIndex)
                if (buffer != null) {
                    buffer.clear()
                    if (buffer.remaining() < data.size) {
                        // 放不下就说出来。静默吞掉的表现是随机花屏，完全没法查。
                        Log.w(TAG, "输入缓冲不足：需要 ${data.size}，可用 ${buffer.remaining()}")
                    }
                    buffer.put(data)
                    var flags = 0
                    if (isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    codec.queueInputBuffer(inputIndex, 0, data.size, ptsUs, flags)
                    inCount++
                    if (inCount == 1L || inCount % 180L == 0L || isConfig) {
                        Log.d(TAG, "喂入 #$inCount size=${data.size} key=$isKeyFrame cfg=$isConfig pts=$ptsUs")
                    }
                } else {
                    Log.w(TAG, "拿到输入 buffer 索引但 getInputBuffer 返回 null")
                }
            } else if (isConfig || isKeyFrame) {
                Log.w(TAG, "丢弃关键包：size=${data.size} key=$isKeyFrame cfg=$isConfig")
            }
            drainOutput()
        }.onFailure {
            // CodecException / IllegalStateException 表示解码器已经进入不可恢复状态
            // （例如 Surface 冲突后 OMX 报致命错误）。记下来让上层重建。
            codecError = it
            Log.e(TAG, "喂帧失败，解码器标记为不可用：size=${data.size} key=$isKeyFrame cfg=$isConfig", it)
        }
    }

    /**
     * 换输出 Surface。
     *
     * 比「销毁解码器再建一个」便宜得多，而且**不会**留下一个和旧 Surface 还绑着的
     * 缓冲队列 —— 后者在某些芯片上会让新解码器直接 configure 失败。
     * 返回是否切换成功；失败时上层需要重建解码器。
     */
    @Synchronized
    fun switchOutputSurface(surface: Surface): Boolean {
        if (released) return false
        return runCatching {
            codec.setOutputSurface(surface)
            true
        }.getOrElse {
            Log.w(TAG, "切换输出 Surface 失败：${it.message}")
            false
        }
    }

    @Synchronized
    fun release() {
        released = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        Log.i(TAG, "解码器已释放，累计输出 $outCount 帧")
    }

    /** 解码器是否还能用。false 表示上层应当重建（或重启会话）。 */
    fun isUsable(): Boolean = !released && codecError == null

    /** 已解码输出的帧数。 */
    val decodedFrames: Long get() = outCount

    /**
     * 抽走所有已就绪的输出。
     *
     * ⚠️ **超时必须是 0**：这是「喂一帧、顺手抽干净」的调用点，不是独立线程的轮询循环。
     * 给非 0 超时会把读流的线程按帧卡住，反而拖慢整条链路。
     */
    private fun drainOutput() {
        while (true) {
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)
            } catch (_: IllegalStateException) {
                return // 已被 release
            }
            when {
                outIndex >= 0 -> {
                    // render = true：Surface 模式下不设它就不会真的上屏
                    codec.releaseOutputBuffer(outIndex, true)
                    outCount++
                    recordOutputFrame()
                }

                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    notifyOutputSize(codec.outputFormat)
                }

                else -> return
            }
        }
    }

    /**
     * 用解码器报告的输出格式回调真实尺寸。
     *
     * 有 crop 时不能直接用 `KEY_WIDTH`/`KEY_HEIGHT` —— 那是**对齐后**的缓冲尺寸，
     * 通常比画面大（比如 576x1288 的缓冲里放 576x1280 的画面）。
     * 触摸坐标是按视频帧坐标发的，这里报错就等于所有点击都偏。
     */
    private fun notifyOutputSize(format: MediaFormat) {
        val rawWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val rawHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        val width = if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        } else {
            rawWidth
        }
        val height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        } else {
            rawHeight
        }
        if (width > 0 && height > 0) {
            Log.i(TAG, "输出尺寸 ${rawWidth}x$rawHeight → crop 后 ${width}x$height")
            onOutputSizeChanged?.invoke(width, height)
        }
    }

    private fun recordOutputFrame() {
        fpsWindowFrameCount++
        val nowNs = System.nanoTime()
        val elapsedNs = nowNs - fpsWindowStartNs
        if (elapsedNs < FPS_WINDOW_NS) return
        onFpsUpdated?.invoke(fpsWindowFrameCount * 1_000_000_000f / elapsedNs.toFloat())
        fpsWindowStartNs = nowNs
        fpsWindowFrameCount = 0
    }

    private companion object {
        const val TAG = "ScrcpyDecoder"
        const val MIME_AVC = "video/avc"
        const val INPUT_TIMEOUT_US = 10_000L
        const val CRITICAL_INPUT_TIMEOUT_US = 50_000L
        const val OUTPUT_TIMEOUT_US = 0L
        const val FPS_WINDOW_NS = 1_000_000_000L
    }
}
