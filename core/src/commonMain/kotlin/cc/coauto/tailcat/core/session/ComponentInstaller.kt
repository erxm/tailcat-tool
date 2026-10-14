package cc.coauto.tailcat.core.session

import cc.coauto.tailcat.core.creds.Credentials
import cc.coauto.tailcat.core.creds.OutputRedactor
import cc.coauto.tailcat.core.model.managerError
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.tools.ToolComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 缺失组件的安装。
 *
 * 每个组件按**三级降级**处理，任何一级成功即止：
 *  1. 磁盘上已经能找到 → 直接配置（配置路径失效但工具其实装了的情况）；
 *  2. 包管理器（Windows 上是 winget）→ 用户级、静默、非交互；
 *  3. **官方直链下载** → 新电脑上可能既没有 winget 也没有 Android SDK，
 *     没有这一级，新手就没法自助把环境配起来。
 *
 * 第 2 级失败不抛出，而是降到第 3 级；两级都断了才算这个组件失败。
 */
public class ComponentInstaller(
    private val runner: CommandExecuting,
    private val services: PlatformServices,
) {
    private val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())

    private val _installing = MutableStateFlow<ToolComponent?>(null)
    public val installing: StateFlow<ToolComponent?> = _installing.asStateFlow()

    private val _output = MutableStateFlow("")
    public val output: StateFlow<String> = _output.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    public val message: StateFlow<String?> = _message.asStateFlow()

    private val _failed = MutableStateFlow(false)
    public val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var job: Job? = null

    public val isBusy: Boolean get() = _installing.value != null

    /** 包管理器是否可用。界面据此决定提示文案（可直装 vs 需要先装包管理器）。 */
    public val hasInstaller: Boolean get() = services.installer.isAvailable

    /** 包管理器的展示名与官网地址，供「没装包管理器」时引导用户。 */
    public val installerLabel: String get() = services.installer.bootstrapLabel
    public val installerUrl: String? get() = services.installer.bootstrapUrl

    private fun append(text: String) {
        _output.update { (it + text).takeLast(32768) }
    }

    public fun install(component: ToolComponent, configure: (String) -> Unit): Job? =
        install(listOf(component)) { _, path -> configure(path) }

    public fun install(components: List<ToolComponent>, configure: (ToolComponent, String) -> Unit): Job? {
        if (isBusy || components.isEmpty()) return null
        _installing.value = components.first()
        _output.value = ""
        _message.value = null
        _failed.value = false

        val job = scope.launch {
            val done = mutableListOf<String>()
            try {
                for (component in components) {
                    ensureActive()
                    _installing.value = component
                    val path = resolve(component)
                    configure(component, path)
                    done += component.label
                }
                _message.value = if (done.size == 1) {
                    "${done.first()} 已配置完成"
                } else {
                    "${done.size} 个组件已配置完成"
                }
            } catch (e: CancellationException) {
                _failed.value = true
                _message.value = "安装已取消，可重新检测或重试"
                throw e
            } catch (t: Throwable) {
                _failed.value = true
                _message.value = Credentials.redact(t.message.orEmpty())
            } finally {
                _installing.value = null
                this@ComponentInstaller.job = null
            }
        }
        this.job = job
        return job
    }

    /** 让某个组件变为可用，返回其可执行文件的绝对路径。 */
    private suspend fun resolve(component: ToolComponent): String {
        // 一、已经在磁盘上
        services.locator.find(component.executableName)?.let {
            append("${component.label} 已检测并配置\n")
            return it
        }

        // 二、包管理器
        if (services.installer.isAvailable) {
            if (installWithManager(component)) {
                services.locator.find(component.executableName)?.let { return it }
                append("包管理器报告成功，但没有找到 ${component.label} 的可执行文件，改用官方直链\n")
            }
        } else {
            append("未检测到 ${services.installer.bootstrapLabel}，改为从官方地址直接下载\n")
        }

        // 三、官方直链
        val download = services.installer.directDownload(component)
            ?: managerError(
                "${component.label} 既没有办法通过包管理器安装，也没有可用的官方直链。" +
                    "请手动下载后在「工具设置」里指定可执行文件的路径。",
            )
        append("下载 ${download.url}\n")
        return services.archiveFetcher.fetch(download) { line -> append(line + "\n") }
    }

    /** 走包管理器安装。失败**不抛出**——调用方还要降级到官方直链，两条路都断了才算失败。 */
    private suspend fun installWithManager(component: ToolComponent): Boolean {
        append(services.installer.description(component) + "\n")
        val redactor = OutputRedactor()
        return try {
            val result = runner.run(services.installer.installCommand(component)) { chunk ->
                val safe = redactor.append(chunk)
                if (safe.isNotEmpty()) append(safe)
            }
            val trailing = redactor.flush()
            if (trailing.isNotEmpty()) append(trailing)
            coroutineContext.ensureActive()
            result.requireSuccess()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            append("包管理器安装失败：${Credentials.redact(t.message.orEmpty())}\n")
            false
        }
    }

    public fun cancel() {
        job?.cancel()
    }

    public suspend fun shutdown() {
        cancel()
        job?.join()
    }
}
