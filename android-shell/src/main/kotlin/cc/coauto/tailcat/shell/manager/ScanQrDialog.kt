package cc.coauto.tailcat.shell.manager

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import java.util.concurrent.Executors

/**
 * 扫码添加设备：读对端手机「服务端」页上那个二维码，把 tc 地址填进来。
 *
 * 「服务端」页展示的地址有 150+ 字符，手敲到另一台手机上基本不现实 —— 二维码本来就是为
 * 这个场景出的，只是之前只有「出码」没有「扫码」。
 *
 * 实现取向：用 **CameraX 取帧 + 已有的 zxing.core 解码**，而不是引入 ML Kit。
 * ML Kit 带 native 库，而本项目被 16KB 页对齐坑过一次（见 `THIRD_PARTY_NOTICES.md` 里
 * adb 那段）；CameraX 是纯 Java/AndroidX，没有原生库，且 zxing 本来就在依赖里，
 * 等于只多了一个取帧的库。
 */
@Composable
fun ScanQrDialog(
    onDismiss: () -> Unit,
    onDecoded: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    // 只接受第一次解码结果：一次已经扫到了，后面再触发只会把界面来回改
    var consumed by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(
        onDismissRequest = onDismiss,
        // 默认的对话框窗口是「内容自适应大小」的，相机预览会缩成一小块
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when {
                cameraError != null -> Center(
                    title = "打不开相机",
                    detail = cameraError,
                    onDismiss = onDismiss,
                )

                !granted -> Center(
                    title = "需要相机权限",
                    detail = if (asked) {
                        "没有相机权限扫不了码。可以到系统设置里给「Tailcat Manager」打开相机权限，" +
                            "或者直接手动粘贴对端显示的 tc 地址。"
                    } else {
                        "正在请求相机权限…"
                    },
                    onDismiss = onDismiss,
                )

                else -> {
                    CameraPreview(
                        onDecoded = { text ->
                            if (!consumed) {
                                consumed = true
                                onDecoded(text)
                            }
                        },
                        onError = { cameraError = it },
                        lifecycleOwner = lifecycleOwner,
                    )
                    Viewfinder(onDismiss = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val decoder = remember { QrFrameDecoder() }
    // 把最新的回调塞进 State，避免 analyzer 持有旧闭包
    val latestDecoded = rememberUpdatedState(onDecoded)

    DisposableEffect(Unit) {
        onDispose {
            runCatching { provider?.unbindAll() }
            decoder.close()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    runCatching {
                        val cameraProvider = future.get()
                        provider = cameraProvider

                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            // 只留最新一帧：解码跟不上时丢旧帧，而不是排队把延迟越堆越高
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { image ->
                            decoder.decode(image)?.let { text -> latestDecoded.value(text) }
                        }

                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.onFailure { onError(it.message ?: it.javaClass.simpleName) }
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}

/**
 * 从相机帧里解二维码。
 *
 * 相机给的是 YUV_420_888，而 QR 只关心亮度 —— 正好只用第 0 个平面（Y），
 * 不用做 YUV→RGB 的转换，省一步也省一次拷贝。
 */
private class QrFrameDecoder {
    private val reader = QRCodeReader()
    private var done = false

    fun decode(image: ImageProxy): String? {
        if (done) {
            image.close()
            return null
        }
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val buffer = plane.buffer
            if (rowStride < image.width || buffer.remaining() < rowStride * image.height) {
                return null
            }
            // ⚠️ 这里**必须**按 rowStride 而不是 width 来构造：相机的行宽通常是 2 的幂
            // 或对齐到 16/32 的倍数，直接按 width 切会把每一行的数据错位，表现是
            // 「图明明很清晰，就是扫不出来」。
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val source = PlanarYUVLuminanceSource(
                bytes, rowStride, image.height,
                0, 0, image.width, image.height,
                false,
            )
            val text = reader.decode(BinaryBitmap(HybridBinarizer(source))).text
            done = true
            text
        } catch (_: NotFoundException) {
            null // 这一帧里没有码，继续看下一帧
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }
    }

    fun close() {
        runCatching { reader.reset() }
    }
}

@Composable
private fun Viewfinder(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(240.dp)
                .border(2.dp, Color(0xCCFFFFFF), RoundedCornerShape(16.dp)),
        )
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            "对准另一台手机「服务端」页上的二维码",
            color = Color.White,
        )
        Button(onClick = onDismiss) { Text("取消") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Center(title: String, detail: String?, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White)
        detail?.let { Text(it, color = Color(0xB3FFFFFF)) }
        Button(onClick = onDismiss) { Text("返回") }
    }
}
