package cc.coauto.tailcat.shell.manager

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.creds.Credentials
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.session.SessionManager
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设备编辑器：新增与编辑共用同一张卡。
 *
 * 桌面端把这件事做成一个对话框，两个入口都能打开它（设备行的 ⋯ 菜单、详情页的编辑按钮）。
 * 手机端这里沿用「内联展开的卡片」，和壳那部分的设置卡一致，也省掉一个弹窗组件。
 *
 * [profile] 为 null 表示新增；否则是编辑既有设备 —— 后者的连接地址存在凭据库里，
 * 得异步读回来填进输入框（[SessionManager.addressForEditing]）。
 *
 * 行为对齐桌面端：
 * - **新增**保存后自动连接（按钮写「保存并连接」）
 * - **编辑**只保存不连接 —— core 本来就要求先断开才能改配置，这里再自动连回去反而突兀
 * - 临时设备的名称可以留空，core 会自动起一个「临时设备 N」
 */
/**
 * 详情页上只露头尾。
 *
 * 整串 150+ 字符，明文铺在操作页上既占地方、又是截图泄漏面；桌面端的详情页干脆不显示它。
 * 要看全或要改，去编辑器（那里有「显示完整连接凭据」）。
 */
internal fun maskedAddress(address: String): String =
    if (address.length <= 24) address else "${address.take(10)}…${address.takeLast(6)}"

@Composable
fun DeviceEditorCard(
    manager: SessionManager,
    profile: DeviceProfile?,
    temporary: Boolean,
    onDone: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val editing = profile != null
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var address by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var localPort by remember { mutableStateOf(profile?.preferredPort?.toString().orEmpty()) }
    var remotePort by remember { mutableStateOf((profile?.remotePort ?: 5555).toString()) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    // 车机之类的设备可能根本没有摄像头（manifest 里 camera 声明的是 required=false），
    // 那就别摆一个按下去只会报错的按钮。
    val hasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    // 编辑时地址要异步读，读完之前不让点保存，否则会把空地址写回去
    var loadingAddress by remember { mutableStateOf(editing) }

    LaunchedEffect(profile?.id) {
        if (profile == null) return@LaunchedEffect
        runCatching { manager.addressForEditing(profile.id) }
            .onSuccess { address = it }
            .onFailure { localError = it.message }
        loadingAddress = false
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallTitle(
                when {
                    temporary && !editing -> "临时连接"
                    temporary -> "编辑临时设备"
                    editing -> "编辑设备"
                    else -> "添加设备"
                },
            )
            when {
                temporary && !editing -> Text(
                    "临时设备不写进配置，凭据只留在内存里；关掉应用或重启手机就没了。" +
                        "想留着，事后在详情页点「保存到我的设备」。",
                    style = styles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
                editing -> Text(
                    "改连接配置前要先断开 —— 这不只是界面限制，正在跑的会话没有一个安全的换址方式。",
                    style = styles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }

            // 名称对普通设备是必填的（core 的 validated() 要求 1–80 字符）；
            // 临时设备可以留空，core 会自动起一个「临时设备 N」。
            TextField(
                value = name,
                onValueChange = { name = it },
                label = if (temporary) "设备名称（可留空）" else "设备名称",
            )
            // 地址本身是凭据（内含预共享密钥），默认打码 —— 和桌面端一致。
            // 手机上尤其要紧：编辑时旁边常有人，截图也更容易流出去。
            TextField(
                value = address,
                onValueChange = { address = it },
                label = "Tailcat 地址（tc 开头）",
                visualTransformation = if (reveal) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            )
            // 地址有 150+ 字符，手敲到另一台手机上基本不现实 —— 而对端「服务端」页上本来就有
            // 二维码，直接扫过来最省事。
            if (hasCamera) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = "扫码填入",
                        onClick = { scanning = true },
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "扫另一台手机「服务端」页上的二维码",
                        style = styles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = reveal, onCheckedChange = { reveal = it })
                Spacer(Modifier.width(10.dp))
                Text("显示完整连接凭据", style = styles.footnote1, color = colors.onSurface)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    TextField(
                        value = localPort,
                        onValueChange = { localPort = it },
                        label = "本地端口（留空自动）",
                    )
                }
                Box(Modifier.weight(1f)) {
                    TextField(
                        value = remotePort,
                        onValueChange = { remotePort = it },
                        label = "远端 ADB 端口",
                    )
                }
            }
            Text(
                "本地端口留空会从 5556 起自动挑一个没被占用的。",
                style = styles.footnote2,
                color = colors.onSurfaceVariantSummary,
            )
            localError?.let { Text(it, style = styles.footnote1, color = colors.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !busy && !loadingAddress,
                    onClick = {
                        val port = localPort.trim()
                        val local = port.ifEmpty { null }?.toIntOrNull()
                        if (port.isNotEmpty() && local == null) {
                            localError = "本地端口要填整数"
                            return@Button
                        }
                        val remote = remotePort.trim().toIntOrNull()
                        if (remote == null) {
                            localError = "远端端口要填整数"
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            runCatching {
                                val id = if (temporary) {
                                    manager.saveTemporaryDevice(profile?.id, name, address, local, remote)
                                } else {
                                    manager.saveDevice(profile?.id, name, address, local, remote)
                                }
                                // 编辑时不自动连回去：core 要求改配置前先断开，刚改完就连上很突兀
                                if (!editing) manager.connect(id)
                                id
                            }.onSuccess {
                                onDone()
                            }.onFailure {
                                localError = it.message ?: "保存失败"
                            }
                            busy = false
                        }
                    },
                ) {
                    Text(
                        when {
                            busy || loadingAddress -> "处理中…"
                            editing -> "保存"
                            else -> "保存并连接"
                        },
                    )
                }
                TextButton(text = "取消", enabled = !busy, onClick = onDone)
            }
        }
    }

    if (scanning) {
        ScanQrDialog(
            onDismiss = { scanning = false },
            onDecoded = { text ->
                scanning = false
                // 二维码里未必是我们的地址 —— 扫到别人家的码、或者扫到一段普通文本是常事，
                // 所以按 core 的同一套规则校验一遍再回填，别把垃圾写进输入框。
                val cleaned = text.trim()
                if (Credentials.isPlausible(cleaned)) {
                    address = cleaned
                    localError = null
                } else {
                    localError = "扫到的内容不是 tailcat 地址（应以 tc 开头、至少 23 个字符）。" +
                        "请确认扫的是另一台手机「服务端」页上的那个二维码。"
                }
            },
        )
    }
}
