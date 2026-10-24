package cc.coauto.tailcat.shell.manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.shell.ui.StateDot
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.model.managerError
import cc.coauto.tailcat.core.session.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.GroupCard
import cc.coauto.tailcat.shell.ui.RowTrailing
import cc.coauto.tailcat.shell.ui.SectionHeader
import cc.coauto.tailcat.shell.ui.SettingRow
import cc.coauto.tailcat.shell.ui.TailcatIcons

/** 与 ShellScreen 统一的过渡时长。 */
private const val TRANSITION_MS = 320

/** 详情页：概览 + 操作 + Shell 窗口入口 + 装包 + 传输 + 日志。 */
@Composable
fun DeviceDetailScreen(
    manager: SessionManager,
    deviceId: String,
    onOpenScreen: () -> Unit,
    onOpenConsole: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by manager.sessionFlow(deviceId).collectAsState()
    val profile = manager.profile(deviceId)
    val temporary = manager.isTemporary(deviceId)
    // addressForEditing 是 suspend 的，取一次就够
    var address by remember(deviceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(deviceId) {
        address = runCatching { manager.addressForEditing(deviceId) }.getOrNull()
    }

    var editing by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var busyInstalling by remember { mutableStateOf(false) }
    // 传输的远端路径。默认和桌面端一致，落在对方的下载目录。
    var remotePath by remember { mutableStateOf("/sdcard/Download/") }
    var busyTransfer by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val online = session.state == ConnectionState.Online

    // 上传：SAF 选一个本地文件 → 落进应用缓存 → adb push 到「远端路径」下
    val pickUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyTransfer = true
        fileError = null
        scope.launch {
            runCatching {
                val local = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                manager.beginPush(deviceId, local.absolutePath, remoteTarget(remotePath, local.name))
            }.onFailure { fileError = it.message }
            busyTransfer = false
        }
    }

    // 上传目录：SAF 选的是**一棵目录树**（不是单个文件），所以要 OpenDocumentTree
    val pickUploadTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyTransfer = true
        fileError = null
        scope.launch {
            runCatching {
                val (local, name) = withContext(Dispatchers.IO) { copyTreeToCache(context, uri) }
                manager.beginPush(deviceId, local.absolutePath, remoteTarget(remotePath, name))
            }.onFailure { fileError = it.message ?: "准备目录失败" }
            busyTransfer = false
        }
    }

    // 下载：远端路径必须是个文件，所以先弹 SAF 让用户定「存到哪、叫什么」
    val pickDownload = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyTransfer = true
        fileError = null
        scope.launch {
            runCatching {
                val remote = remotePath.trim()
                // adb pull 收的也是本地文件路径，所以先拉进缓存再写进 SAF 目标
                val staging = withContext(Dispatchers.IO) {
                    newCacheFile(context, remote.substringAfterLast('/').ifEmpty { "download.bin" })
                }
                val taskId = manager.beginPull(deviceId, remote, staging.absolutePath)
                manager.waitForTask(taskId)
                val state = manager.tasks.value.firstOrNull { it.id == taskId }?.state
                if (state != TaskState.Succeeded) {
                    managerError(state?.label ?: "下载失败")
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        staging.inputStream().use { it.copyTo(output) }
                    } ?: managerError("无法写入所选位置")
                    staging.delete()
                }
            }.onFailure { fileError = it.message }
            busyTransfer = false
        }
    }

    // SAF 选 APK：桌面的 NativeDialogs 在 Android 上换成系统文件选择器。
    val pickApk = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busyInstalling = true
        fileError = null
        scope.launch {
            runCatching {
                // adb install 收的是文件路径，得先落到应用缓存里
                val local = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                manager.beginInstall(deviceId, local.absolutePath)
            }.onFailure { fileError = it.message }
            busyInstalling = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Dimens.PagePadding, end = Dimens.PagePadding, bottom = Dimens.SectionGap),
    ) {
        // 子页面用紧凑标题（返回 + 设备名）：层级上它比 tab 页低一层，不该再撑一个大标题。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Dimens.PagePadding, bottom = Dimens.TitleToContent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(text = "‹ 设备", onClick = onBack)
            Text(
                (profile?.name ?: "设备") + if (temporary) "  ·  临时" else "",
                style = styles.title2,
                color = colors.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        DetailStatusCard(
            state = session.state,
            serial = session.serial,
            online = online,
            onToggle = {
                if (session.serial == null) manager.connect(deviceId) else manager.disconnect(deviceId)
            },
        )

        (session.error ?: fileError)?.let { message ->
            GroupCard(Modifier.padding(top = Dimens.CardGap)) {
                SettingRow(
                    title = message,
                    titleColor = colors.error,
                    trailing = RowTrailing.Action("知道了") { fileError = null },
                )
            }
        }

        SectionHeader("连接")
        GroupCard {
            SettingRow(
                title = "重新读取",
                summary = "拉取型号 / 系统 / 执行身份",
                icon = ImageVector.vectorResource(TailcatIcons.Refresh),
                trailing = RowTrailing.Chevron,
                enabled = online,
                onClick = { manager.refreshDeviceInformation(deviceId) },
            )
            SettingRow(
                title = "探测链路",
                summary = session.route,
                icon = ImageVector.vectorResource(TailcatIcons.Link),
                trailing = RowTrailing.Chevron,
                enabled = !session.state.isBusy,
                onClick = { manager.diagnose(deviceId) },
            )
            SettingRow(
                title = "复制 ADB 入口",
                summary = session.serial ?: "尚未连接",
                icon = ImageVector.vectorResource(TailcatIcons.Terminal),
                enabled = session.serial != null,
                trailing = RowTrailing.Action("复制") {
                    session.serial?.let {
                        clipboard.setText(AnnotatedString(it))
                        fileError = null
                    }
                },
            )
            SettingRow(
                title = "屏幕控制",
                summary = "全屏投屏（应用内 scrcpy + 硬解）",
                icon = ImageVector.vectorResource(TailcatIcons.Screen),
                trailing = RowTrailing.Chevron,
                enabled = online,
                onClick = onOpenScreen,
            )
        }

        SectionHeader("设备信息")
        GroupCard {
            SettingRow("型号", trailing = RowTrailing.Value(session.model))
            SettingRow("产品", trailing = RowTrailing.Value(session.product))
            SettingRow("Android", trailing = RowTrailing.Value(session.android))
            SettingRow("序列号", trailing = RowTrailing.Value(session.deviceSerial))
            SettingRow("身份", trailing = RowTrailing.Value(session.identity))
            SettingRow("链路", trailing = RowTrailing.Value(session.route))
            address?.let { SettingRow("Tailcat 地址", trailing = RowTrailing.Value(maskedAddress(it))) }
            profile?.preferredPort?.let { SettingRow("本地入口", trailing = RowTrailing.Value("127.0.0.1:$it")) }
        }

        SectionHeader("Shell")
        GroupCard {
            // Shell 窗口把命令输入与**所有**本机任务（装包 / 上传 / 下载 / shell）的输出
            // 合成一份连续的终端式日志，不再分散在任务列表里逐条展开。
            SettingRow(
                title = "打开 Shell 窗口",
                summary = "命令与装包 / 上传 / 下载的输出按先后顺序流进同一份日志",
                icon = ImageVector.vectorResource(TailcatIcons.Terminal),
                trailing = RowTrailing.Chevron,
                enabled = online,
                onClick = onOpenConsole,
            )
        }

        SectionHeader("安装")
        GroupCard {
            SettingRow(
                title = "选择 APK 安装",
                summary = if (online) "用系统文件选择器挑一个 APK" else "先连上设备才能装包",
                icon = ImageVector.vectorResource(TailcatIcons.Install),
                enabled = online && !busyInstalling,
                // 用 */* 而不是 APK 的 MIME：不少文件管理器把 apk 标成
                // application/octet-stream，按 APK MIME 过滤会让它们整个灰掉。
                trailing = RowTrailing.Action(if (busyInstalling) "准备中…" else "选择") {
                    pickApk.launch(arrayOf("*/*"))
                },
            )
        }

        SectionHeader("文件传输")
        GroupCard {
            TextField(
                value = remotePath,
                onValueChange = { remotePath = it },
                label = "远端路径",
                enabled = online,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.CardInner, vertical = Dimens.RowVertical),
            )
            SettingRow(
                title = "上传文件",
                summary = "放进上面的远端目录",
                icon = ImageVector.vectorResource(TailcatIcons.Upload),
                enabled = online && !busyTransfer,
                trailing = RowTrailing.Action(if (busyTransfer) "传输中…" else "选择") {
                    pickUpload.launch(arrayOf("*/*"))
                },
            )
            SettingRow(
                title = "上传目录",
                summary = "整个目录先镜像再推走（上限 2000 个文件）",
                icon = ImageVector.vectorResource(TailcatIcons.Upload),
                trailing = RowTrailing.Chevron,
                enabled = online && !busyTransfer,
                onClick = { pickUploadTree.launch(null) },
            )
            SettingRow(
                title = "下载",
                summary = "远端路径要指到具体文件",
                icon = ImageVector.vectorResource(TailcatIcons.Download),
                trailing = RowTrailing.Chevron,
                enabled = online && !busyTransfer && remotePath.trim().isNotEmpty(),
                onClick = {
                    // 远端路径对下载来说必须指到一个文件，用最后一段当默认文件名
                    pickDownload.launch(remotePath.trim().substringAfterLast('/').ifEmpty { "download.bin" })
                },
            )
        }

        if (session.logs.isNotEmpty()) {
            SectionHeader("日志")
            GroupCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(Dimens.CardInner),
                ) {
                    session.logs.takeLast(200).forEach { line ->
                        Text(
                            line.text.trimEnd(),
                            style = styles.footnote2,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        SectionHeader("设备管理")
        GroupCard {
            SettingRow(
                title = if (temporary) "编辑临时设备" else "编辑设备",
                summary = "改配置前要先断开连接",
                trailing = RowTrailing.Chevron,
                enabled = session.serial == null && !session.state.isBusy,
                onClick = { editing = !editing },
            )
            if (temporary) {
                SettingRow(
                    title = "保存到我的设备",
                    summary = "把临时设备写进配置，名称留空时用型号兜底",
                    trailing = RowTrailing.Chevron,
                    onClick = {
                        // 临时设备允许没有名字，但转正后名称是必填的（core 的 validated()）。
                        // 详情页上没有改名的地方，所以这里用型号 / 序列号兜一个，别让用户撞上
                        // 一句没有字段可填的「设备名称需要 1–80 个字符」。
                        val fallback = session.model.takeIf { it != "—" }
                            ?: session.deviceSerial.takeIf { it != "—" }
                            ?: "设备"
                        val name = profile?.name?.takeIf { it.isNotBlank() } ?: fallback
                        scope.launch {
                            runCatching { manager.promoteTemporaryDevice(deviceId, name) }
                                .onFailure { fileError = it.message }
                        }
                    },
                )
            }
            SettingRow(
                title = "删除设备",
                summary = if (temporary) "同时结束这次临时会话" else "只从本机删除，不影响对方",
                titleColor = colors.error,
                trailing = RowTrailing.Chevron,
                onClick = {
                    scope.launch {
                        runCatching { manager.removeDevice(deviceId) }.onSuccess { onBack() }
                            .onFailure { fileError = it.message }
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = editing,
            enter = fadeIn(tween(TRANSITION_MS)) + expandVertically(tween(TRANSITION_MS)),
            exit = fadeOut(tween(TRANSITION_MS)) + shrinkVertically(tween(TRANSITION_MS)),
        ) {
            Column(Modifier.padding(top = Dimens.CardGap)) {
                DeviceEditorCard(
                    manager = manager,
                    profile = profile,
                    temporary = temporary,
                    onDone = {
                        editing = false
                        // 概览里的地址读的是快照，改完得重新取一次
                        scope.launch {
                            address = runCatching { manager.addressForEditing(deviceId) }.getOrNull()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 详情页顶部的状态卡。
 *
 * 在线时用强调容器色 —— 与首页的服务端卡同一套语义：**正在生效的东西才上色**。
 */
@Composable
private fun DetailStatusCard(
    state: ConnectionState,
    serial: String?,
    online: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    val background = if (online) colors.surfaceContainerHighest else colors.surface
    val strong = if (online) colors.onSurfaceContainerHighest else colors.onSurface
    val weak = if (online) colors.onSurfaceContainerHighest.copy(alpha = 0.72f) else colors.onSurfaceVariantSummary

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CardRadius))
            .background(background)
            .padding(Dimens.CardInner),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(state.label, style = styles.body1, color = strong)
            Text(serial ?: "尚未建立 ADB 连接", style = styles.footnote2, color = weak)
        }
        Button(onClick = onToggle, enabled = !state.isBusy) {
            Text(
                when {
                    state.isBusy -> "请稍候…"
                    serial == null -> "连接"
                    else -> "断开"
                },
            )
        }
    }
}

/**
 * 把 SAF 给的 Uri 复制进 cacheDir —— `adb install` / `adb push` 只认文件路径。
 * 用真名存，任务标题里才能显示出来是哪个文件。
 *
 * **每次选择放一个独立的时间戳子目录**：这些操作是异步的，
 * 传 A 的同时又选了 B，若两者共用一个文件名，删/覆盖会把正在传的文件抽走。
 * 顺手清掉一天前的旧目录 —— 正常传输不会跨天，缓存目录也不该无限长。
 */
private fun copyToCache(context: Context, uri: Uri): File {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: "upload.bin"

    val target = newCacheFile(context, name)
    // 必须真的把内容写进去：adb install / adb push 只会去 stat 这个路径，
    // 少这一步就会得到 `adb: error: cannot stat '...'`。
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { input.copyTo(it) }
    } ?: throw IllegalStateException("无法读取所选文件")
    return target
}

/**
 * 把 SAF 选中的**目录树**镜像到 cacheDir，返回 (镜像出来的本地根目录, 根目录名)。
 *
 * 为什么镜像而不是逐个文件 push：**`adb push` 本身就支持整目录递归**，镜像好之后只要一条命令
 * （一个任务、一份进度）；逐个推要 N 次 adb 往返，还得自己一层层把远端目录建出来。
 * 代价是占双份磁盘 —— 先在本地复制一份再推走 —— 所以设了文件数上限兜底：真正的大目录应该
 * 走别的路子，不该把手机缓存撑爆。
 *
 * ⚠️ 目录项的名字来自 SAF，它**可以**包含 `/` 或 `\`（能构造出来）。不挡的话拼出来的路径会
 * 跑到镜像目录外面去。
 *
 * ⚠️ 空目录会被保留（建出对应的空目录），而文件一个都没读到时会报错 —— 选错目录时给一句
 * 明确的话，比推一个空目录过去让人对着远端发呆强。
 */
private fun copyTreeToCache(context: Context, treeUri: Uri): Pair<File, String> {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalStateException("读不到所选目录")
    val rootName = root.name?.takeIf { it.isNotBlank() } ?: "upload"
    val target = newCacheFile(context, rootName).apply { mkdirs() }

    var files = 0
    fun walk(dir: DocumentFile, out: File) {
        for (child in dir.listFiles()) {
            val childName = child.name ?: continue
            if (childName.isBlank() || childName.contains('/') || childName.contains('\\')) continue
            if (child.isDirectory) {
                walk(child, File(out, childName).apply { mkdirs() })
                continue
            }
            if (!child.canRead()) continue
            files++
            if (files > MAX_TREE_FILES) {
                throw IllegalStateException("目录里的文件超过 $MAX_TREE_FILES 个，请分批上传")
            }
            val destination = File(out, childName)
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                destination.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("读不到 $childName")
        }
    }
    walk(root, target)

    if (files == 0) throw IllegalStateException("这个目录里没有可读的文件")
    return target to rootName
}

/** 镜像目录的文件数上限，见 [copyTreeToCache]。 */
private const val MAX_TREE_FILES = 2_000

/** 在 cacheDir 里开一个一次性的落盘位置。 */
private fun newCacheFile(context: Context, name: String): File {
    val root = File(context.cacheDir, "transfer").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }

    val dir = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
    return File(dir, name)
}

/**
 * 上传的落点：把文件名接到用户填的远端目录后面。
 * 已经以 `/` 结尾就直连，否则补一个 —— 少一个斜杠在 adb 那边就是「改名」而不是「放进目录」。
 */
private fun remoteTarget(directory: String, fileName: String): String {
    val base = directory.trim().ifEmpty { "/sdcard/Download" }
    return if (base.endsWith("/")) base + fileName else "$base/$fileName"
}
