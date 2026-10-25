package cc.coauto.tailcat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.desktop.win.NativeDialogs
import cc.coauto.tailcat.desktop.win.WindowsTerminal
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
public fun DeviceDetail(
    manager: SessionManager,
    profile: DeviceProfile,
    demo: Boolean,
    actions: ManagerActions,
    onEdit: () -> Unit,
    onPromote: () -> Unit,
    onCloseTemporary: () -> Unit,
    onTransfer: (TransferRequest) -> Unit,
    toolsConfigured: Boolean,
) {
    val session by manager.sessionFlow(profile.id).collectAsState()
    val temporary = manager.isTemporary(profile.id)
    var tab by remember { mutableIntStateOf(0) }
    var shellCommand by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(DesktopDimens.PagePadding)) {
        PageTitle(
            title = profile.name,
            subtitle = if (temporary) "临时设备 · 仅本次运行" else "独立连接会话",
        ) {
            if (temporary) {
                OutlinedButton(onClick = onPromote, enabled = session.state != ConnectionState.Disconnecting) {
                    Text("保存到我的设备")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCloseTemporary) { Text("关闭") }
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = { manager.connect(profile.id) },
                enabled = !session.state.isBusy,
            ) { Text(if (session.serial == null) "连接" else "重新连接") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { manager.disconnect(profile.id) },
                enabled = session.state != ConnectionState.Disconnected &&
                    session.state != ConnectionState.Disconnecting,
            ) { Text("断开") }
        }

        Spacer(Modifier.height(14.dp))
        TabRow(selectedTabIndex = tab, modifier = Modifier.width(240.dp)) {
            listOf("概览", "日志").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        Spacer(Modifier.height(14.dp))

        if (tab == 0) {
            OverviewTab(
                manager = manager,
                session = session,
                profile = profile,
                temporary = temporary,
                demo = demo,
                actions = actions,
                shellCommand = shellCommand,
                onShellCommandChange = { shellCommand = it },
                onEdit = onEdit,
                onTransfer = onTransfer,
                toolsConfigured = toolsConfigured,
                scope = scope,
            )
        } else {
            LogsTab(session)
        }
    }
}

@Composable
private fun OverviewTab(
    manager: SessionManager,
    session: cc.coauto.tailcat.core.model.DeviceSession,
    profile: DeviceProfile,
    temporary: Boolean,
    demo: Boolean,
    actions: ManagerActions,
    shellCommand: String,
    onShellCommandChange: (String) -> Unit,
    onEdit: () -> Unit,
    onTransfer: (TransferRequest) -> Unit,
    toolsConfigured: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val online = session.state == ConnectionState.Online
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(session.state, size = 11)
            Spacer(Modifier.width(8.dp))
            Text(session.state.label, style = MaterialTheme.typography.titleMedium)
        }
        if (temporary) {
            Spacer(Modifier.height(6.dp))
            Text(
                "临时连接仅保留在内存中，不写入地址簿或凭据库。断开后可重连；关闭或退出应用时清除配置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session.state == ConnectionState.Unauthorized) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tailcat 通道已建立。远端 ADB 尚未接受本机密钥，需要在设备侧完成授权。",
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        session.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                color = if (session.state == ConnectionState.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        // 这些值（serial、型号、序列号）是排查问题时最常要复制出去的东西，
        // 所以整块包在 SelectionContainer 里，可以像原版一样选中复制。
        SectionHeader("连接信息")
        GroupCard {
        SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailRow("本地入口", session.serial ?: "未分配")
            DetailRow("远端端口", profile.remotePort.toString())
            DetailRow("链路", session.route)
            DetailRow("设备型号", session.model)
            DetailRow("设备代号", session.product)
            DetailRow("Android", session.android)
            DetailRow("设备序列号", session.deviceSerial)
            DetailRow("Shell 权限", session.identity)
        }
        }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 原版有的一键复制：本地 ADB 入口（127.0.0.1:xxxx）在接线、写脚本时都要用
            OutlinedButton(
                onClick = { session.serial?.let { clipboard.setText(AnnotatedString(it)) } },
                enabled = session.serial != null,
            ) { Text("复制 ADB 入口") }
            OutlinedButton(
                onClick = { manager.diagnose(profile.id) },
                enabled = session.serial != null && !session.state.isBusy,
            ) { Text("探测链路") }
            OutlinedButton(
                onClick = { scope.launch { manager.refreshDeviceInformation(profile.id) } },
                enabled = online,
            ) { Text("刷新信息") }
            OutlinedButton(
                onClick = onEdit,
                enabled = session.serial == null && !session.state.isBusy,
            ) { Text(if (temporary) "编辑临时设备" else "编辑设备") }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider()
        Spacer(Modifier.height(14.dp))
        SectionTitle("设备操作")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    if (demo) {
                        actions.onInfo("演示模式：终端操作绑定所选设备，不启动真实 cmd")
                    } else {
                        val adb = manager.tools.value.adb
                        session.serial?.let { WindowsTerminal.openAdbShell(adb, it) }
                    }
                },
                enabled = online,
            ) { Text("终端") }
            OutlinedButton(
                onClick = {
                    if (demo) {
                        actions.onInfo("演示模式不选择真实 APK")
                    } else {
                        NativeDialogs.chooseFile("选择要安装的 APK", "apk")?.let { path ->
                            runCatching { manager.beginInstall(profile.id, path) }
                                .onFailure { actions.onError(it.message.orEmpty()) }
                        }
                    }
                },
                enabled = online,
            ) { Text("安装 APK") }
            OutlinedButton(
                onClick = {
                    if (demo) {
                        actions.onInfo("演示模式不选择真实文件")
                    } else {
                        // 目录也可以：adb push 对目录是递归的，整个目录会推到远端同名子目录下。
                        val path = NativeDialogs.chooseFileOrDirectory("选择要上传的文件或目录")
                            ?: return@OutlinedButton
                        onTransfer(TransferRequest(profile.id, profile.name, path, "/sdcard/Download/"))
                    }
                },
                enabled = online,
            ) { Text("上传") }
            OutlinedButton(
                onClick = { onTransfer(TransferRequest(profile.id, profile.name, null)) },
                enabled = online,
            ) { Text("下载") }
            OutlinedButton(
                onClick = {
                    runCatching { manager.beginScreen(profile.id) }
                        .onFailure { actions.onError(it.message.orEmpty()) }
                },
                enabled = online && toolsConfigured,
            ) { Text("屏幕控制 (scrcpy)") }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = shellCommand,
                onValueChange = onShellCommandChange,
                label = { Text("执行一条 ADB Shell 命令") },
                singleLine = true,
                enabled = online,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    runCatching { manager.beginShell(profile.id, shellCommand) }
                        .onFailure { actions.onError(it.message.orEmpty()) }
                    onShellCommandChange("")
                },
                enabled = online && shellCommand.isNotBlank(),
            ) { Text("执行") }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "任务在启动时固定设备、ADB 入口与 serial；切换侧栏不会改变任务目标。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogsTab(session: cc.coauto.tailcat.core.model.DeviceSession) {
    // 通道日志是排障时最多人要去复制的东西，整块可选中。
    SelectionContainer {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (session.logs.isEmpty()) {
            Text("暂无通道日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                session.logs.forEach { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            TIME_FORMAT.format(Date(entry.atMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.text,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun WrappedBox(content: @Composable () -> Unit) {
    Box(Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))) {
        content()
    }
}
