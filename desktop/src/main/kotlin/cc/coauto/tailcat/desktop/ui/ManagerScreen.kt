package cc.coauto.tailcat.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.tools.ToolComponent
import kotlinx.coroutines.launch

public class ManagerActions(
    public val onError: (String) -> Unit,
    public val onInfo: (String) -> Unit,
)

@Composable
public fun ManagerScreen(
    manager: SessionManager,
    demo: Boolean,
    commands: ManagerCommands? = null,
) {
    val profiles by manager.profiles.collectAsState()
    val temporaryProfiles by manager.temporaryProfiles.collectAsState()
    val tasks by manager.tasks.collectAsState()
    val notice by manager.notice.collectAsState()
    val tools by manager.tools.collectAsState()

    var selection by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var promotion by remember { mutableStateOf<DeviceProfile?>(null) }
    var transfer by remember { mutableStateOf<TransferRequest?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showReachability by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<DeviceProfile?>(null) }

    val all = profiles + temporaryProfiles
    LaunchedEffect(all.map { it.id }) {
        if (selection == null || all.none { it.id == selection }) selection = all.firstOrNull()?.id
    }

    val actions = ManagerActions(
        onError = { error = it },
        onInfo = { manager.setNotice(it) },
    )

    // 工具栏、空状态页和菜单栏共用同一份动作，免得将来只改一处导致行为漂移。
    val runImport: () -> Unit = {
        if (demo) {
            manager.setNotice("演示模式不读取真实地址簿")
        } else {
            scope.launch {
                runCatching { manager.importLegacy() }.onFailure { e -> error = e.message }
            }
        }
    }
    val runTestAll: () -> Unit = {
        showReachability = true
        if (manager.reachability.results.value.isEmpty()) manager.testAllDevices()
    }

    // 菜单栏在 Window 上、对话框状态在这里，靠一条命令流打通（见 ManagerCommands）。
    val fallbackCommands = remember { ManagerCommands() }
    val commandBus = commands ?: fallbackCommands
    val pendingCommand by commandBus.pending.collectAsState()
    LaunchedEffect(pendingCommand) {
        val command = pendingCommand ?: return@LaunchedEffect
        when (command) {
            ManagerCommands.Command.AddDevice -> editor = EditorRequest(null, temporary = false)
            ManagerCommands.Command.NewTemporary -> editor = EditorRequest(null, temporary = true)
            ManagerCommands.Command.ImportAddressBook -> runImport()
            ManagerCommands.Command.TestAll -> runTestAll()
            ManagerCommands.Command.OpenToolSettings -> showSettings = true
        }
        commandBus.consume(command)
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            manager = manager,
            profiles = profiles,
            temporaryProfiles = temporaryProfiles,
            selection = selection,
            onSelect = { selection = it },
            demo = demo,
            onNewTemporary = { editor = EditorRequest(null, temporary = true) },
            onEdit = { profile, temp -> editor = EditorRequest(profile, temp) },
            onPromote = { promotion = it },
            onRemove = { removeTarget = it },
            onCloseTemporary = {
                scope.launch {
                    runCatching { manager.removeDevice(it) }.onFailure { e -> error = e.message }
                }
            },
            onActionError = { error = it },
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Toolbar(
                enabled = all.isNotEmpty(),
                demo = demo,
                onTestAll = runTestAll,
                onNewTemporary = { editor = EditorRequest(null, temporary = true) },
                onAdd = { editor = EditorRequest(null, temporary = false) },
                onImport = runImport,
                onSettings = { showSettings = true },
            )
            HorizontalDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val selected = all.firstOrNull { it.id == selection }
                if (selected == null) {
                    EmptyState(
                        hasDevices = all.isNotEmpty(),
                        onAdd = { editor = EditorRequest(null, temporary = false) },
                        onTemporary = { editor = EditorRequest(null, temporary = true) },
                        onImport = runImport,
                    )
                } else {
                    // 按设备 id 分组，切换设备时重建详情页状态（tab、输入框等）。
                    androidx.compose.runtime.key(selected.id) {
                        DeviceDetail(
                            manager = manager,
                            profile = selected,
                            demo = demo,
                            actions = actions,
                            onEdit = { editor = EditorRequest(selected, manager.isTemporary(selected.id)) },
                            onPromote = { promotion = selected },
                            onCloseTemporary = {
                                scope.launch {
                                    runCatching { manager.removeDevice(selected.id) }.onFailure { e -> error = e.message }
                                }
                            },
                            onTransfer = { request -> transfer = request },
                            toolsConfigured = tools.scrcpy.isNotEmpty(),
                        )
                    }
                }
            }

            if (notice != null) {
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(notice.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { manager.setNotice(null) }) { Text("关闭") }
                }
            }

            if (tasks.isNotEmpty()) {
                HorizontalDivider()
                TaskHistory(manager = manager, tasks = tasks)
            }
        }
    }

    editor?.let { request ->
        DeviceEditorDialog(
            manager = manager,
            profile = request.profile,
            temporary = request.temporary,
            onDismiss = { editor = null },
            onError = { error = it },
            onSaved = { id ->
                selection = id
                editor = null
            },
        )
    }
    promotion?.let { profile ->
        PromoteDialog(
            manager = manager,
            profile = profile,
            onDismiss = { promotion = null },
            onError = { error = it },
        )
    }
    transfer?.let { request ->
        TransferDialog(
            manager = manager,
            request = request,
            onDismiss = { transfer = null },
            onError = { error = it },
        )
    }
    if (showSettings) {
        ToolSettingsDialog(
            manager = manager,
            demo = demo,
            onDismiss = { showSettings = false },
        )
    }
    if (showReachability) {
        ReachabilityDialog(manager = manager, onDismiss = { showReachability = false })
    }
    removeTarget?.let { profile ->
        ConfirmDialog(
            title = "移除设备？",
            body = "会断开应用创建的通道并移除应用内配置。tailcat-adb 地址簿保留。",
            confirmLabel = "移除 ${profile.name}",
            onConfirm = {
                removeTarget = null
                scope.launch {
                    runCatching { manager.removeDevice(profile.id) }.onFailure { e -> error = e.message }
                }
            },
            onDismiss = { removeTarget = null },
        )
    }
    error?.let { message ->
        ConfirmDialog(
            title = "操作未完成",
            body = message,
            confirmLabel = "好",
            onConfirm = { error = null },
            onDismiss = { error = null },
        )
    }
}

public class EditorRequest(public val profile: DeviceProfile?, public val temporary: Boolean)

public class TransferRequest(
    public val deviceId: String,
    public val deviceName: String,
    public val localPath: String?,
    public val remoteDefault: String = "/sdcard/Download/",
)

@Composable
private fun Toolbar(
    enabled: Boolean,
    demo: Boolean,
    onTestAll: () -> Unit,
    onNewTemporary: () -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onTestAll, enabled = enabled) { Text("测试全部设备") }
        OutlinedButton(onClick = onNewTemporary) { Text("临时连接") }
        Button(onClick = onAdd) { Text("添加设备") }
        OutlinedButton(onClick = onImport) { Text("导入地址簿") }
        Spacer(Modifier.weight(1f))
        if (demo) {
            Text("界面演示 · 不执行真实命令", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(onClick = onSettings) { Text("工具设置") }
    }
}

@Composable
private fun Sidebar(
    manager: SessionManager,
    profiles: List<DeviceProfile>,
    temporaryProfiles: List<DeviceProfile>,
    selection: String?,
    onSelect: (String) -> Unit,
    demo: Boolean,
    onNewTemporary: () -> Unit,
    onEdit: (DeviceProfile, Boolean) -> Unit,
    onPromote: (DeviceProfile) -> Unit,
    onRemove: (DeviceProfile) -> Unit,
    onCloseTemporary: (String) -> Unit,
    onActionError: (String) -> Unit,
) {
    Column(
        Modifier.width(272.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            SidebarSection("我的设备")
            if (profiles.isEmpty()) {
                Text(
                    "还没有设备",
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            profiles.forEach { profile ->
                DeviceRow(
                    manager, profile, profile.id == selection, false,
                    { onSelect(profile.id) }, onEdit, onPromote, onRemove, onCloseTemporary, onActionError,
                )
            }
            Spacer(Modifier.height(12.dp))
            SidebarSection("临时设备")
            temporaryProfiles.forEach { profile ->
                DeviceRow(
                    manager, profile, profile.id == selection, true,
                    { onSelect(profile.id) }, onEdit, onPromote, onRemove, onCloseTemporary, onActionError,
                )
            }
            TextButton(onClick = onNewTemporary, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("+ 新建临时连接")
            }
            if (demo) {
                Text(
                    "演示模式不读取真实地址簿，也不写凭据。",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        ToolHealthPanel(manager)
    }
}

@Composable
private fun SidebarSection(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeviceRow(
    manager: SessionManager,
    profile: DeviceProfile,
    selected: Boolean,
    temporary: Boolean,
    onSelect: () -> Unit,
    onEdit: (DeviceProfile, Boolean) -> Unit,
    onPromote: (DeviceProfile) -> Unit,
    onRemove: (DeviceProfile) -> Unit,
    onCloseTemporary: (String) -> Unit,
    onActionError: (String) -> Unit,
) {
    // 每台设备订阅自己的 StateFlow：某台写一行日志不会导致其它设备行重组。
    val session by manager.sessionFlow(profile.id).collectAsState()
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 1.dp)
            .clickable { onSelect() },
        shape = RoundedCornerShape(6.dp),
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(session.state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (temporary) "⚡ " else "") + profile.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        session.state.label + (session.serial?.substringAfterLast(':')?.let { " · :$it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { menu = true }) { Text("⋯") }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (session.serial == null) "连接" else "重新连接") },
                    enabled = !session.state.isBusy,
                    onClick = {
                        menu = false
                        manager.connect(profile.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text("断开") },
                    enabled = session.serial != null || session.state.isBusy,
                    onClick = {
                        menu = false
                        manager.disconnect(profile.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (temporary) "编辑临时设备" else "编辑设备") },
                    enabled = session.serial == null && !session.state.isBusy,
                    onClick = {
                        menu = false
                        onEdit(profile, temporary)
                    },
                )
                if (temporary) {
                    DropdownMenuItem(
                        text = { Text("保存到我的设备") },
                        onClick = {
                            menu = false
                            onPromote(profile)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("关闭临时设备") },
                        onClick = {
                            menu = false
                            onCloseTemporary(profile.id)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("移除设备") },
                        onClick = {
                            menu = false
                            onRemove(profile)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("探测链路") },
                    enabled = session.serial != null && !session.state.isBusy,
                    onClick = {
                        menu = false
                        manager.diagnose(profile.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ToolHealthPanel(manager: SessionManager) {
    val tools by manager.tools.collectAsState()
    val installing by manager.componentInstaller.installing.collectAsState()
    val message by manager.componentInstaller.message.collectAsState()
    // 订阅 tools 让「可用」状态在安装完成后自动刷新
    val toolState = tools

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("工具", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToolComponent.entries.forEach { component ->
            ToolHealthRow(
                label = component.label,
                available = toolState[component].isNotEmpty() && manager.isToolAvailable(component),
                installing = installing == component,
                canInstall = manager.allowsComponentInstallation,
                onInstall = { manager.installComponent(component) },
            )
        }
        if (message != null) {
            Text(message.orEmpty(), style = MaterialTheme.typography.labelSmall)
        }

        // 新电脑上的自助入口：缺什么就一键补齐。
        // 包管理器不在时不必先装它——会直接从官方地址下载。
        val missing = ToolComponent.entries.filter { manager.findTool(it.executableName) == null }
        if (missing.isNotEmpty()) {
            Text(
                "缺少：" + missing.joinToString("、") { it.label } +
                    if (manager.componentInstaller.hasInstaller) "" else "（将直接从官方地址下载）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Button(
                onClick = { manager.installMissingComponents() },
                enabled = manager.allowsComponentInstallation && installing == null,
            ) { Text("一键安装缺失组件") }
        }
        Text(
            "GUI ${manager.ownedActiveCount} 个活动通道",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolHealthRow(
    label: String,
    available: Boolean,
    installing: Boolean,
    canInstall: Boolean,
    onInstall: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (available) "✓" else "✗", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        when {
            installing -> Text("安装中…", style = MaterialTheme.typography.labelSmall)
            !available -> TextButton(onClick = onInstall, enabled = canInstall) { Text("安装") }
        }
    }
}

@Composable
private fun EmptyState(
    hasDevices: Boolean,
    onAdd: () -> Unit,
    onTemporary: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (hasDevices) "选择一台设备" else "连接你的第一台设备",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "每台设备拥有独立的 Tailcat 转发通道与 ADB serial，支持同时连接。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd) { Text("添加设备") }
            OutlinedButton(onClick = onTemporary) { Text("临时连接") }
            OutlinedButton(onClick = onImport) { Text("导入地址簿") }
        }
    }
}

@Composable
private fun TaskHistory(manager: SessionManager, tasks: List<cc.coauto.tailcat.core.model.DeviceTask>) {
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text("设备任务", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            tasks.takeLast(20).reversed().forEach { task ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        expanded = if (expanded == task.id) null else task.id
                    }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(
                            "${task.deviceName} · ${task.serial}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        task.state.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.state == cc.coauto.tailcat.core.model.TaskState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (task.state == cc.coauto.tailcat.core.model.TaskState.Running) {
                        TextButton(onClick = { manager.cancelTask(task.id) }) { Text("取消") }
                    }
                }
                if (expanded == task.id) {
                    SelectionContainer {
                    Text(
                        task.output.ifEmpty { "等待命令输出…" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp),
                    )
                    }
                }
            }
        }
    }
}

@Composable
public fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
