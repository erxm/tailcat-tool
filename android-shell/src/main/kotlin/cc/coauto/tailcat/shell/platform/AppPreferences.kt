package cc.coauto.tailcat.shell.platform

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 应用偏好：设置页里能改的那些。
 *
 * 和 `configuration.json`（设备清单）、`shell-shortcuts.json`（快捷命令）分开存 ——
 * 三者的生命周期与重要性不同，混在一个文件里改一处就要整份重写。
 */
@Serializable
internal data class Preferences(
    /** 0 = 跟随系统，1 = 浅色，2 = 深色。 */
    val themeMode: Int = THEME_SYSTEM,
    /** Shell 窗口的等宽字号（sp）。 */
    val terminalFontSize: Int = 12,
    /** 投屏画质档位，见 [MirrorQuality]。 */
    val mirrorQuality: Int = 1,
    /** 启动服务端时顺手把 ADB 端口弄成监听状态（有 root 切 adbd，没 root 借无线调试）。 */
    val autoAdbPort: Boolean = true,
) {
    internal companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}

/**
 * 投屏画质档。
 *
 * 三个数直接对应 scrcpy 服务端的 `max_size` / `video_bit_rate` / `max_fps`
 * （选项名见 [cc.coauto.tailcat.shell.screen.ScrcpyServer.launchCommand]，写错只 warn、不报错，
 * 所以这里只动**已经跑通**的那三个参数，不引入没验证过的开关）。
 */
internal enum class MirrorQuality(
    val label: String,
    val summary: String,
    val maxSize: Int,
    val bitRate: Int,
    val maxFps: Int,
) {
    Saver("省流", "1024p · 2 Mbps · 30fps", maxSize = 1024, bitRate = 2_000_000, maxFps = 30),
    Standard("标准", "1280p · 8 Mbps · 30fps", maxSize = 1280, bitRate = 8_000_000, maxFps = 30),
    Sharp("高清", "1920p · 16 Mbps · 60fps", maxSize = 1920, bitRate = 16_000_000, maxFps = 60),
    ;

    internal companion object {
        fun of(index: Int): MirrorQuality = entries.getOrElse(index) { Standard }
    }
}

/**
 * 进程内单例 + 落盘。
 *
 * 用 Compose 的 [mutableStateOf] 存当前值：设置页改一下就立刻生效（主题、字号、画质），
 * 不需要重启应用；持久化在 `filesDir/manager/preferences.json`（原子写入）。
 *
 * 注意 [attach] 必须在任何读取点之前调用一次（`MainActivity.onCreate` 里）。
 */
internal object AppPreferences {

    private const val TAG = "TailcatPrefs"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var file: File? = null

    var value by mutableStateOf(Preferences())
        private set

    fun attach(context: Context) {
        val directory = File(context.filesDir, "manager").apply { mkdirs() }
        val target = File(directory, "preferences.json")
        file = target
        value = runCatching {
            if (!target.isFile) Preferences() else json.decodeFromString<Preferences>(target.readText(Charsets.UTF_8))
        }.getOrElse {
            Log.w(TAG, "偏好读取失败，改用默认值", it)
            Preferences()
        }
    }

    fun update(transform: (Preferences) -> Preferences) {
        val next = transform(value)
        if (next == value) return
        value = next
        val target = file ?: return
        runCatching {
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(json.encodeToString(next), Charsets.UTF_8)
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure {
            Log.w(TAG, "偏好保存失败：${target.absolutePath}", it)
        }
    }

    /** 当前该用深色还是浅色（跟随系统时看 [systemDark]）。 */
    fun isDark(systemDark: Boolean): Boolean = when (value.themeMode) {
        Preferences.THEME_LIGHT -> false
        Preferences.THEME_DARK -> true
        else -> systemDark
    }

    fun mirrorQuality(): MirrorQuality = MirrorQuality.of(value.mirrorQuality)
}
