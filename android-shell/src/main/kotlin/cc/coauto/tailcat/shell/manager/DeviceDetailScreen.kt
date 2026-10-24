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
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(text = "‹ 设备", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                (profile?.name ?: "设备") + if (temporary) "  ·  临时" else "",
                style = styles.title2,
                color = colors.onBackground,
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            StateDot(session.state)
            Spacer(Modifier.width(10.dp))
            Text(
                session.state.label + (session.serial?.let { "  ·  $it" } ?: ""),
                style = styles.body1,
                color = if (online) colors.primary else colors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
        }

        session.error?.let { Text(it, style = styles.footnote1, color = colors.error) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (session.serial == null) {
                Button(
                    enabled = !session.state.isBusy,
                    onClick = { manager.connect(deviceId) },
                ) { Text(if (session.state == ConnectionState.Connecting) "连接中…" else "连接") }
            } else {
                Button(
                    enabled = !session.state.isBusy,
                    onClick = { manager.disconnect(deviceId) },
                ) { Text("断开") }
            }
            TextButton(
                text = "重新读取",
                enabled = online,
                onClick = { manager.refreshDeviceInformation(deviceId) },
            )
            TextButton(
                text = "探测链路",
                enabled = !session.state.isBusy,
                onClick = { manager.diagnose(deviceId) },
            )
        }

        // 本地 ADB 入口在接线、写脚本时都要用，单独一行免得和上面几个挤成一团
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = "复制 ADB 入口",
                enabled = session.serial != null,
                onClick = {
                    session.serial?.let {
                        clipboard.setText(AnnotatedString(it))
                        fileError = null
                    }
                },
            )
            // 改配置必须先断开：core 的 saveDevice 会拒绝在连着的时候改，界面上直接就不给点
            TextButton(
                text = if (temporary) "编辑临时设备" else "编辑设备",
                enabled = session.serial == null && !session.state.isBusy,
                onClick = { editing = !editing },
            )
        }

        // 屏幕控制：手机端不是调 scrcpy.exe（那是桌面程序），而是自带一份服务端推过去、
        // 自己在进程里说 scrcpy 协议 + MediaCodec 硬解。要连着才能用。
        TextButton(
            text = "屏幕控制",
            enabled = online,
            onClick = onOpenScreen,
        )

        AnimatedVisibility(
            visible = editing,
            enter = fadeIn(tween(TRANSITION_MS)) + expandVertically(tween(TRANSITION_MS)),
            exit = fadeOut(tween(TRANSITION_MS)) + shrinkVertically(tween(TRANSITION_MS)),
        ) {
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

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                SmallTitle("概览")
                InfoRow("型号", session.model)
                InfoRow("产品", session.product)
                InfoRow("Android", session.android)
                InfoRow("序列号", session.deviceSerial)
                InfoRow("身份", session.identity)
                InfoRow("链路", session.route)
                address?.let { InfoRow("Tailcat 地址", maskedAddress(it)) }
                profile?.preferredPort?.let { InfoRow("本地入口", "127.0.0.1:$it") }
            }
        }

        // Shell 窗口：命令输入与**所有**本机任务（装包 / 上传 / 下载 / shell）的输出
        // 合并成一份连续的终端式日志，不再分散在「任务」列表里逐条展开
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallTitle("ADB Shell")
                Text(
                    "打开 Shell 窗口：敲的命令，以及装包 / 上传 / 下载的输出，" +
                        "都按先后顺序流进同一份日志。",
                    style = styles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
                Button(enabled = online, onClick = onOpenConsole) { Text("打开 Shell 窗口") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallTitle("安装 APK")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = online && !busyInstalling,
                        onClick = {
                            // 用 */* 而不是 APK 的 MIME：不少文件管理器把 apk 标成
                            // application/octet-stream，按 APK MIME 过滤会让它们整个灰掉。
                            pickApk.launch(arrayOf("*/*"))
                        },
                    ) { Text(if (busyInstalling) "准备中…" else "选择 APK") }
                }
                fileError?.let { Text(it, style = styles.footnote1, color = colors.error) }
                if (!online) {
                    Text(
                        "先连上设备才能装包。",
                        style = styles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SmallTitle("文件传输")
                TextField(
                    value = remotePath,
                    onValueChange = { remotePath = it },
                    label = "远端路径",
                    enabled = online,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = online && !busyTransfer,
                        onClick = { pickUpload.launch(arrayOf("*/*")) },
                    ) { Text(if (busyTransfer) "传输中…" else "上传文件") }
                    Button(
                        enabled = online && !busyTransfer,
                        onClick = { pickUploadTree.launch(null) },
                    ) { Text("上传目录") }
                    Button(
                        enabled = online && !busyTransfer && remotePath.trim().isNotEmpty(),
                        onClick = {
                            // 远端路径对下载来说必须指到一个文件，用最后一段当默认文件名
                            pickDownload.launch(remotePath.trim().substringAfterLast('/').ifEmpty { "download.bin" })
                        },
                    ) { Text("下载") }
                }
                // 这是 Compose 的 Text，不解析 markdown —— 别写 **加粗**，会原样显示出来
                Text(
                    "上传：把文件或整个目录放进这个目录，例如 /sdcard/Download/。\n" +
                        "上传目录会先在本地镜像一份再推走，大目录会多占一份缓存空间。\n" +
                        "下载：这里要填到具体文件，例如 /sdcard/Download/a.txt。",
                    style = styles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
                fileError?.let { Text(it, style = styles.footnote1, color = colors.error) }
            }
        }

        // 「本机任务」区块已去掉：任务的输出现在全部流进 Shell 窗口，摆在详情页这份是重复的。
        // 要取消运行中的任务，去设备列表页的「任务」区块（那是跨设备的全局视图）。
        if (session.logs.isNotEmpty()) {
            HorizontalDivider()
            SmallTitle("日志")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
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

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (temporary) {
                TextButton(
                    text = "保存到我的设备",
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
            TextButton(
                text = "删除设备",
                onClick = {
                    scope.launch {
                        runCatching { manager.removeDevice(deviceId) }.onSuccess { onBack() }
                            .onFailure { fileError = it.message }
                    }
                },
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = MiuixTheme.colorScheme
    val styles = MiuixTheme.textStyles
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = styles.footnote1, color = colors.onSurfaceVariantSummary)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = styles.footnote1,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
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
