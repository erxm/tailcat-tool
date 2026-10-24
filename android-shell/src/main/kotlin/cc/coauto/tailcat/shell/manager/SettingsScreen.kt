package cc.coauto.tailcat.shell.manager

import android.content.Context
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
import cc.coauto.tailcat.shell.ui.BigTitleBar
import cc.coauto.tailcat.shell.ui.Dimens
import cc.coauto.tailcat.shell.ui.GroupCard
import cc.coauto.tailcat.shell.ui.RowTrailing
import cc.coauto.tailcat.shell.ui.SectionHeader
import cc.coauto.tailcat.shell.ui.SettingRow
import cc.coauto.tailcat.shell.ui.TailcatIcons

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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = Dimens.PagePadding, end = Dimens.PagePadding, bottom = Dimens.CardGap * 2),
    ) {
        BigTitleBar("设置")

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
            SettingRow(
                title = "重新检测",
                summary = "在磁盘上重新查找两个二进制（升级应用后路径会变）",
                icon = ImageVector.vectorResource(TailcatIcons.Refresh),
                trailing = RowTrailing.Chevron,
                onClick = onRelocateTools,
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
                title = "macOS 原版",
                summary = "已归档在仓库 legacy-macos/，不再发布",
            )
        }
    }
}
