package cc.coauto.tailcat.shell.manager

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.creds.MemoryCredentialStore
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.session.SessionManagerDependencies
import cc.coauto.tailcat.core.tools.ToolComponent
import cc.coauto.tailcat.shell.ManagerService
import cc.coauto.tailcat.shell.platform.AndroidConfigurationStore
import cc.coauto.tailcat.shell.platform.AndroidCredentialStore
import cc.coauto.tailcat.shell.platform.AndroidPlatformServices
import cc.coauto.tailcat.shell.platform.StaleProcessReaper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 管理端的装配 —— 对应桌面端的 `ApplicationModel`。
 *
 * 做成进程级单例而不是放在 Activity 里：会话、转发通道、正在跑的任务都要跨界面存活，
 * 转屏或分屏重建 Activity 时不能把它们推倒重来（桌面端没有这个问题，手机上有）。
 */
class ManagerModel private constructor(context: Context) {

    /** 单例持有的是 applicationContext，留着它只是为了拉起前台服务。 */
    private val appContext: Context = context.applicationContext

    val services: AndroidPlatformServices = AndroidPlatformServices(context)

    private val store = AndroidConfigurationStore(context)

    /** 配置读失败时的原因；非 null 时界面应该提示而不是默默用空配置。 */
    var startupError: String? = null
        private set

    val manager: SessionManager

    private val keepAliveScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    /** 已经为当前这轮连接发过启动请求了，别每两秒重复发一次。 */
    private var keepAliveRequested = false

    init {
        var loaded: AppConfiguration? = null
        try {
            loaded = store.load()
        } catch (t: Throwable) {
            startupError = t.message
        }
        manager = if (loaded != null) {
            SessionManager(
                configuration = loaded.copy(tools = resolveTools(loaded.tools)),
                deps = SessionManagerDependencies(
                    store,
                    AndroidCredentialStore(context),
                    services,
                ),
            )
        } else {
            // 配置读不出来时**不写盘**：用内存凭据库起一个空会话，让用户还能进界面看错误。
            SessionManager(
                configuration = AppConfiguration(tools = resolveTools(ToolSettings())),
                deps = SessionManagerDependencies(null, MemoryCredentialStore(), services),
            )
        }

        // ⚠️ 必须放在 init 的**最后**。keepAliveScope 用的是 Dispatchers.Main.immediate，
        // 那个调度器是**立即执行**的：放在 manager 赋值之前的话，协程体会在构造过程中就跑到
        // syncKeepAlive()，那里读 manager.ownedActiveCount 直接 NPE。
        keepAliveScope.launch {
            while (true) {
                syncKeepAlive()
                delay(KEEP_ALIVE_POLL_MILLIS)
            }
        }
    }

    /**
     * 把工具路径落到实际找到的位置。
     *
     * ⚠️ **不能用配置里存的那份**（除了作为兜底）：两个二进制被提取到的目录是
     * `/data/app/~~<hash>/<包名>-<hash>/lib/arm64/`，那两个哈希**每次安装都会变**。
     * 首次连接成功时 SessionManager 会把当时解析出来的绝对路径写回配置，
     * 于是**每次应用升级这个路径就失效一次** —— 而 Android 端没有工具设置界面，
     * 用户会永久卡在「未找到 Tailcat」。一律以 locator 当场找到的为准，这个错就自愈了。
     */
    private fun resolveTools(tools: ToolSettings): ToolSettings = ToolSettings(
        tailcat = services.locator.find(ToolComponent.Tailcat.executableName) ?: tools.tailcat,
        adb = services.locator.find(ToolComponent.Adb.executableName) ?: tools.adb,
        // 手机端不做屏幕控制，scrcpy 保持为空
        scrcpy = tools.scrcpy,
        addressBookDirectory = tools.addressBookDirectory,
    )

    /**
     * 首次启动时导入既有地址簿。只在地址簿**确实存在**时才试，
     * 否则没用过 tailcat-adb 的用户每次首启都会看到一条无意义的「未找到地址簿」。
     */
    suspend fun startup() {
        // 先收拾上次运行遗留的子进程（被强杀 / 崩溃后留下的 tailcat、adb）。
        // ⚠️ 必须放在最前面、**无条件**执行 —— 挪到下面那两个 early return 之后的话，
        // 「配置读不出来」和「地址簿已导入过」这两种情况就永远清不掉了，而它们恰恰是常态。
        withContext(Dispatchers.IO) { runCatching { StaleProcessReaper.reap() } }

        if (startupError != null) return
        if (manager.profiles.value.isNotEmpty()) return

        val root = manager.tools.value.addressBookDirectory
            .ifEmpty { services.defaultAddressBookDirectory }
        if (root.isEmpty() || !File(root, "users").isDirectory) return

        runCatching { manager.importLegacy() }
            .onFailure { manager.setNotice(it.message) }
    }

    /**
     * 重新定位两个二进制并把结果写回工具配置。
     *
     * 正常路径下 [resolveTools] 每次读配置都会重找一遍，用不到这个；
     * 它是「界面上报找不到工具」时的手动兜底，也是 Android 端唯一有意义的「工具设置」动作 ——
     * 二进制随 APK 打包，没有可安装的目标，[services] 的 installer 在这是关掉的。
     */
    suspend fun relocateTools() {
        val current = manager.tools.value
        val tailcat = services.locator.find(ToolComponent.Tailcat.executableName)
        val adb = services.locator.find(ToolComponent.Adb.executableName)
        if (tailcat == null || adb == null) {
            manager.setNotice("没能定位到 ${if (tailcat == null) "tailcat " else ""}${if (adb == null) "adb" else ""}")
            return
        }
        manager.updateTools(
            ToolSettings(
                tailcat = tailcat,
                adb = adb,
                scrcpy = current.scrcpy,
                addressBookDirectory = current.addressBookDirectory,
            ),
        )
        manager.setNotice("工具已重新检测")
    }

    /**
     * 有设备连着就把前台服务拉起来，让进程不被冻结（见 [cc.coauto.tailcat.shell.ManagerService]）。
     *
     * 用轮询而不是监听会话流：会话是动态增删的，`combine` 一组会变的流要写不少样板，
     * 而这里每两秒只是读一个 Int。服务自己会在连接数归零一段时间后收摊，所以这里
     * 只在「从无到有」的那一刻发一次启动请求，不用管关闭。
     */
    private fun syncKeepAlive() {
        val active = manager.ownedActiveCount > 0
        if (active && !keepAliveRequested) {
            keepAliveRequested = true
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, ManagerService::class.java),
                )
            }.onFailure { keepAliveRequested = false }
        } else if (!active) {
            keepAliveRequested = false
        }
    }

    fun shutdown() {
        keepAliveScope.cancel()
        services.shutdown()
    }

    companion object {
        private const val KEEP_ALIVE_POLL_MILLIS = 2_000L

        @Volatile
        private var instance: ManagerModel? = null

        /** 用 applicationContext，避免把 Activity 泄进单例里。 */
        fun get(context: Context): ManagerModel = instance ?: synchronized(this) {
            instance ?: ManagerModel(context.applicationContext).also { instance = it }
        }
    }
}
