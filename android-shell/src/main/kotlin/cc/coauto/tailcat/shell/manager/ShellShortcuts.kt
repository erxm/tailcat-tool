package cc.coauto.tailcat.shell.manager

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Shell 窗口输入框上方的一条快捷命令。 */
@Serializable
internal data class ShellShortcut(val label: String, val command: String)

/**
 * 快捷命令的落盘位置：`filesDir/manager/shell-shortcuts.json`。
 *
 * 和 `configuration.json` 放在同一层目录，但**单独一个文件**：快捷命令是界面偏好，
 * 不希望它牵连设备配置的读写（配置那边有符号链接防护、原子落盘那一套严格语义）。
 * 这里同样写临时文件再 rename，避免掉电时留半个 JSON。
 *
 * 文件坏了/读不出来就回到默认那几条 —— 快捷命令丢了不算事故，不该把 Shell 窗口拦在门外。
 */
internal class ShellShortcutStore(context: Context) {

    private val directory = File(context.filesDir, "manager")
    private val file = File(directory, FILE_NAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<ShellShortcut> = runCatching {
        if (!file.isFile) return@runCatching DEFAULTS
        json.decodeFromString<List<ShellShortcut>>(file.readText(Charsets.UTF_8))
    }.getOrElse {
        // 别静默回默认值：读到坏文件要能在 logcat 里看见
        Log.w(TAG, "快捷命令读取失败，改用默认值", it)
        DEFAULTS
    }

    fun save(items: List<ShellShortcut>) {
        runCatching {
            directory.mkdirs()
            val temporary = File(directory, "$FILE_NAME.tmp")
            temporary.writeText(json.encodeToString(items), Charsets.UTF_8)
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            // 落盘失败必须留痕，否则界面是「改了」、重启发现没保存
            Log.w(TAG, "快捷命令保存失败：${file.absolutePath}", it)
        }
    }

    private companion object {
        const val TAG = "TailcatShortcuts"
        const val FILE_NAME = "shell-shortcuts.json"

        /** 首次使用的默认清单：都是「按键成本高、又常用」的查询。 */
        val DEFAULTS = listOf(
            ShellShortcut("版本", "getprop ro.build.version.release"),
            ShellShortcut("型号", "getprop ro.product.model"),
            ShellShortcut("包列表", "pm list packages -3"),
            ShellShortcut("当前界面", "dumpsys window | grep mCurrentFocus"),
            ShellShortcut("屏幕", "wm size"),
            ShellShortcut("温度", "cat /sys/class/thermal/thermal_zone0/temp"),
        )
    }
}
