package cc.coauto.tailcat.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.reach.ReachabilityState
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.desktop.win.NativeDialogs
import kotlinx.coroutines.launch

@Composable
public fun DeviceEditorDialog(
    manager: SessionManager,
    profile: DeviceProfile?,
    temporary: Boolean,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onSaved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile?.name.orEmpty()) }
    var address by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf(profile?.preferredPort?.toString().orEmpty()) }
    var remotePort by remember { mutableStateOf((profile?.remotePort ?: 5555).toString()) }
    var localError by remember { mutableStateOf<String?>(null) }

    // 编辑既有设备时读回凭据（DPAPI 免提示，不会弹授权框）。
    androidx.compose.runtime.LaunchedEffect(profile?.id) {
        if (profile != null) {
            runCatching { manager.addressForEditing(profile.id) }
                .onSuccess { address = it }
                .onFailure { localError = it.message }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(Modifier.width(520.dp)) {
            Text(
                when {
                    temporary && profile == null -> "临时连接"
                    temporary -> "编辑临时设备"
                    profile == null -> "添加设备"
                    else -> "编辑设备"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (temporary) "设备名称（可选）" else "设备名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Tailcat 地址") },
                singleLine = !reveal,
                visualTransformation = if (reveal) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = reveal, onCheckedChange = { reveal = it })
                Text("显示完整连接凭据", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("本地端口（留空自动）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = remotePort,
                    onValueChange = { remotePort = it },
                    label = { Text("远端 ADB 端口") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                if (temporary) {
                    "临时设备不写入地址簿或凭据库，连接后可使用全部 ADB 操作；退出应用时清除。"
                } else {
                    "连接凭据用 Windows DPAPI 加密后保存在本机（绑定当前用户账户）。自动端口从 5556 起分配。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = {
                    val portText = port.trim()
                    val local = portText.ifEmpty { null }?.toIntOrNull()
                    if (portText.isNotEmpty() && local == null) {
                        localError = "本地端口需要填写整数"
                        return@Button
                    }
                    val remote = remotePort.trim().toIntOrNull()
                    if (remote == null) {
                        localError = "远端端口需要填写整数"
                        return@Button
                    }
                    scope.launch {
                        runCatching {
                            if (temporary) {
                                val id = manager.saveTemporaryDevice(
                                    profile?.id, name, address, local, remote,
                                )
                                if (profile == null) manager.connect(id)
                                id
                            } else {
                                manager.saveDevice(profile?.id, name, address, local, remote)
                            }
                        }.onSuccess { onSaved(it) }
                            .onFailure { localError = it.message }
                    }
                }) {
                    Text(if (temporary && profile == null) "连接" else "保存")
                }
            }
        }
    }
}

@Composable
public fun PromoteDialog(
    manager: SessionManager,
    profile: DeviceProfile,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(profile.name) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存到我的设备") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("设备名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "保存连接配置与凭据；当前会话、通道和正在执行的任务保持不变。",
                    style = MaterialTheme.typography.labelSmall,
                )
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    runCatching { manager.promoteTemporaryDevice(profile.id, name) }
                        .onSuccess { onDismiss() }
                        .onFailure { localError = it.message }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
public fun TransferDialog(
    manager: SessionManager,
    request: TransferRequest,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val uploading = request.localPath != null
    var remotePath by remember { mutableStateOf(request.remoteDefault) }
    var downloadFolder by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(Modifier.width(560.dp)) {
            Text(if (uploading) "上传文件或目录" else "下载文件", style = MaterialTheme.typography.titleLarge)
            Text("目标设备：${request.deviceName}", style = MaterialTheme.typography.titleSmall)
            request.localPath?.let {
                Text("本地：$it", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedTextField(
                value = remotePath,
                onValueChange = { remotePath = it },
                label = {
                    Text(
                        if (uploading) {
                            "远端保存目录或路径（以 / 结尾则放进该目录）"
                        } else {
                            "远端文件或目录路径"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!uploading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        downloadFolder ?: "请选择本地保存目录",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        downloadFolder = NativeDialogs.chooseDirectory("选择下载保存目录")
                    }) { Text("选择目录") }
                }
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    enabled = uploading || downloadFolder != null,
                    onClick = {
                        val remote = remotePath.trim()
                        if (!remote.startsWith("/") || remote.contains('\u0000') || remote.contains('\n')) {
                            localError = "请填写绝对远端路径，例如 /sdcard/Download/example.txt"
                            return@Button
                        }
                        runCatching {
                            if (uploading) {
                                manager.beginPush(request.deviceId, request.localPath!!, remote)
                            } else {
                                manager.beginPull(request.deviceId, remote, downloadFolder!!)
                            }
                        }.onSuccess { onDismiss() }
                            .onFailure { localError = it.message }
                    },
                ) { Text(if (uploading) "上传" else "下载") }
            }
        }
    }
}

@Composable
public fun ToolSettingsDialog(
    manager: SessionManager,
    demo: Boolean,
    onDismiss: () -> Unit,
) {
    val tools by manager.tools.collectAsState()
    val installing by manager.componentInstaller.installing.collectAsState()
    val output by manager.componentInstaller.output.collectAsState()
    val message by manager.componentInstaller.message.collectAsState()
    val failed by manager.componentInstaller.failed.collectAsState()
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(tools) }
    var localError by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(tools) { draft = tools }

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(Modifier.width(760.dp).heightIn(max = 720.dp), spacing = 10.dp) {
            Text("工具设置", style = MaterialTheme.typography.titleLarge)

            ToolPathRow("tailcat", draft.tailcat) { draft = draft.copy(tailcat = it) }
            ToolPathRow("adb", draft.adb) { draft = draft.copy(adb = it) }
            ToolPathRow("scrcpy（可选）", draft.scrcpy) { draft = draft.copy(scrcpy = it) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.addressBookDirectory,
                    onValueChange = { draft = draft.copy(addressBookDirectory = it) },
                    label = { Text("地址簿目录（含 users 子目录）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    NativeDialogs.chooseDirectory("选择地址簿目录")?.let {
                        draft = draft.copy(addressBookDirectory = it)
                    }
                }) { Text("选择") }
            }

            if (manager.componentInstaller.hasInstaller) {
                Text(
                    "缺失组件优先通过 winget 用户级静默安装（不需要管理员权限）；" +
                        "winget 不可用或安装失败时，会自动改为从官方地址直接下载。" +
                        "安装完成后会重新扫描目录并保存路径。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "未检测到${manager.componentInstaller.installerLabel}。" +
                        "缺失组件将直接从官方地址下载，无需先装包管理器；" +
                        "若想改用包管理器，可从 ${manager.componentInstaller.installerUrl ?: "官网"} 安装它。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (output.isNotEmpty()) {
                Text(
                    output,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = !installing.let { it != null } && !demo,
                    onClick = {
                        manager.installMissingComponents { component, path ->
                            draft = draft.with(component, path)
                        }
                    },
                ) { Text(if (installing != null) "安装中… ${installing?.label}" else "安装缺失组件") }
                OutlinedButton(
                    enabled = installing != null,
                    onClick = { manager.componentInstaller.cancel() },
                ) { Text("取消安装") }
                OutlinedButton(onClick = {
                    // 重新检测：安装后新工具不在本进程 PATH 中，必须重新扫描目录。
                    draft = ToolSettings(
                        tailcat = manager.findTool("tailcat") ?: draft.tailcat,
                        adb = manager.findTool("adb") ?: draft.adb,
                        scrcpy = manager.findTool("scrcpy") ?: draft.scrcpy,
                        addressBookDirectory = draft.addressBookDirectory,
                    )
                }) { Text("重新检测") }

                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("关闭") }
                Button(onClick = {
                    scope.launch {
                        runCatching { manager.updateTools(draft) }
                            .onSuccess { onDismiss() }
                            .onFailure { localError = it.message }
                    }
                }) { Text("保存") }
            }
        }
    }
}

@Composable
private fun ToolPathRow(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = {
            NativeDialogs.chooseFile("选择 $label 可执行文件")?.let(onChange)
        }) { Text("选择") }
    }
}

@Composable
public fun ReachabilityDialog(manager: SessionManager, onDismiss: () -> Unit) {
    val results by manager.reachability.results.collectAsState()
    val running by manager.reachability.isRunning.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        DialogPanel(Modifier.width(780.dp).heightIn(max = 660.dp), spacing = 10.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tailcat 可达性测试", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                val checker = manager.reachability
                Text(
                    when {
                        results.isEmpty() -> ""
                        running -> "测试中… 已完成 ${checker.completedCount} / ${results.size}"
                        else -> "${checker.reachableCount} / ${results.size} 响应 · 已有 ADB：${checker.adbOnlineCount} 台在线"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "每台 ping 5 秒超时、并发最多 3 台。不创建转发、不执行 adb connect/disconnect。" +
                    "未收到 pong 不必然代表已有 ADB 离线——也可能是服务端版本不支持或新链路建连超时。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("设备", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium)
                Text("结果", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("延迟", Modifier.weight(0.8f), style = MaterialTheme.typography.labelMedium)
                Text("路径", Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium)
                Text("ADB", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                results.forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            (if (item.temporary) "⚡ " else "") + item.name,
                            Modifier.weight(1.4f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            item.state.label,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.state == ReachabilityState.Reachable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(item.latency, Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                        Text(item.route, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall)
                        Text(item.adbState.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !running,
                    onClick = { manager.testAllDevices() },
                ) { Text("重新测试全部") }
                OutlinedButton(enabled = running, onClick = { manager.reachability.cancel() }) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}
