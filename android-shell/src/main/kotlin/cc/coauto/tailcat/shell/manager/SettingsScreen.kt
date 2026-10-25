package cc.coauto.tailcat.shell.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import cc.coauto.tailcat.shell.screen.ScrcpyServer
import cc.coauto.tailcat.shell.ui.BigTitleBar
import cc.coauto.tailcat.shell.platform.AppPreferences
import cc.coauto.tailcat.shell.platform.MirrorQuality
import cc.coauto.tailcat.shell.ui.ChoiceRow
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.GroupCard
import cc.coauto.tailcat.shell.ui.RowTrailing
import cc.coauto.tailcat.shell.ui.SectionHeader
import cc.coauto.tailcat.shell.ui.SettingRow
import cc.coauto.tailcat.shell.ui.TailcatIcons
import java.io.File
import cc.coauto.tailcat.shell.platform.AdbTcpMode

/** 项目主页：fork 自 suyunkai/tailcat-tool，Windows + Android 两端都在这个仓库里。 */
private const val PROJECT_URL = "github.com/erxm/tailcat-tool"

/**
 * 设置：工具位置、地址簿、关于。
 *
 * 之前「工具」这一块混在设备列表的底部（还是裸 `Row`，没有卡片、没有分组标题），
 * 这次单独成页 —— 和参照应用一样，设置是独立的一档，不跟业务列表挤在一起。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    model: ManagerModel,
    onRelocateTools: () -> Unit,
) {
    val manager = model.manager
    val tools by manager.tools.collectAsState()

    val tailcat = remember(tools.tailcat) { manager.findTool("tailcat") ?: tools.tailcat.ifEmpty { null } }
    val adb = remember(tools.adb) { manager.findTool("adb") ?: tools.adb.ifEmpty { null } }
    val version = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("未知")
    }
    val addressBook = tools.addressBookDirectory.ifEmpty { model.services.defaultAddressBookDirectory }
    // 屏幕控制要用的那份 jar：首次投屏时才会被解出来，所以可能还不存在
    val scrcpyJar = remember(context) { File(context.cacheDir, "scrcpy-server.jar") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Dimens.PagePadding, end = Dimens.PagePadding, bottom = Dimens.CardGap * 2),
    ) {
        BigTitleBar("设置")

        SectionHeader("外观")
        GroupCard {
            ChoiceRow(
                title = "主题",
                summary = "深色/浅色由这里决定，跟随系统时看手机设置",
                options = listOf("跟随系统", "浅色", "深色"),
                selectedIndex = AppPreferences.value.themeMode,
                onSelect = { mode -> AppPreferences.update { it.copy(themeMode = mode) } },
            )
            ChoiceRow(
                title = "终端字号",
                summary = "Shell 窗口的等宽字号；改完下一场会话按新字号算行列",
                options = listOf("小", "标准", "大"),
                selectedIndex = when (AppPreferences.value.terminalFontSize) {
                    11 -> 0
                    14 -> 2
                    else -> 1
                },
                onSelect = { index ->
                    val size = listOf(11, 12, 14)[index]
                    AppPreferences.update { it.copy(terminalFontSize = size) }
                },
            )
        }

        SectionHeader("屏幕控制")
        GroupCard {
            ChoiceRow(
                title = "画质",
                summary = MirrorQuality.of(AppPreferences.value.mirrorQuality).summary,
                options = MirrorQuality.entries.map { it.label },
                selectedIndex = AppPreferences.value.mirrorQuality,
                onSelect = { index -> AppPreferences.update { it.copy(mirrorQuality = index) } },
            )
        }

        SectionHeader("行为")
        GroupCard {
            SettingRow(
                title = "启动服务端时自动让 ADB 端口就绪",
                summary = "关掉后只跑隧道；需要时去服务端页手动点一次",
                icon = ImageVector.vectorResource(TailcatIcons.Terminal),
                trailing = RowTrailing.Toggle(AppPreferences.value.autoAdbPort) { on ->
                    AppPreferences.update { it.copy(autoAdbPort = on) }
                },
            )
        }

        SectionHeader("工具")
        GroupCard {
            SettingRow(
                title = "Tailcat",
                summary = tailcat ?: "未找到",
                icon = ImageVector.vectorResource(TailcatIcons.Link),
                trailing = RowTrailing.Value(if (tailcat != null) "可用" else "缺失"),
            )
            SettingRow(
                title = "ADB",
                summary = adb ?: "未找到",
                icon = ImageVector.vectorResource(TailcatIcons.Terminal),
                trailing = RowTrailing.Value(if (adb != null) "可用" else "缺失"),
            )
            // 手机上**没有** scrcpy 可执行文件：屏幕控制是把官方 scrcpy-server.jar 推到被控设备上
            // 由那边的 app_process 跑，本机只留一份 jar 供 `adb push`。所以这里显示的是那份 jar 的位置。
            SettingRow(
                title = "scrcpy-server",
                summary = if (scrcpyJar.isFile) {
                    scrcpyJar.absolutePath
                } else {
                    "随应用内置（v${ScrcpyServer.VERSION}），首次投屏时解出到应用缓存"
                },
                icon = ImageVector.vectorResource(TailcatIcons.Screen),
                trailing = RowTrailing.Value(if (scrcpyJar.isFile) "已就绪" else "内置"),
            )
            SettingRow(
                title = "重新检测",
                summary = "在磁盘上重新查找两个二进制（升级应用后路径会变）",
                icon = ImageVector.vectorResource(TailcatIcons.Refresh),
                trailing = RowTrailing.Chevron,
                onClick = onRelocateTools,
            )
            // 只读展示：说明这台设备「把 adbd 切到 TCP 模式」会走哪条路。
            // 用户不需要选 —— 启动服务时按 root → 无线调试 的顺序自动尝试。
            SettingRow(
                title = "ADB 端口切换方式",
                summary = "启动服务时自动使用；无 root 需在开发者选项开启一次「无线调试」",
                icon = ImageVector.vectorResource(TailcatIcons.Server),
                trailing = RowTrailing.Value(remember(context) { AdbTcpMode.capability(context) }),
            )
        }

        SectionHeader("地址簿")
        GroupCard {
            SettingRow(
                title = "地址簿目录",
                summary = addressBook.ifEmpty { "未设置" },
                icon = ImageVector.vectorResource(TailcatIcons.Devices),
            )
        }

        SectionHeader("关于")
        GroupCard {
            SettingRow(title = "版本", summary = version)
            SettingRow(
                title = "依赖",
                summary = "Tailcat 0.7.0 · scrcpy-server 5.0.1 · MIUIX 0.8.8 · CameraX + ZXing",
            )
            SettingRow(
                title = "项目主页",
                summary = PROJECT_URL,
                trailing = RowTrailing.Chevron,
                onClick = {
                    // 没有浏览器（车机之类）时不要崩，点不动就算了
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://$PROJECT_URL")))
                    }
                },
            )
        }
    }
}
