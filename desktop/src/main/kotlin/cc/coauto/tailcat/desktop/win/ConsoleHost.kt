package cc.coauto.tailcat.desktop.win

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef

private interface Kernel32Console : Library {
    fun AllocConsole(): Int
    fun FreeConsole(): Int
    fun GetConsoleWindow(): Pointer?
}

/**
 * 控制台宿主。
 *
 * 背景：jpackage 产出的 `TailcatManager.exe` 是 **GUI 子系统**进程，本身没有控制台。
 * 这种进程启动控制台子进程（`adb.exe` / `tailcat.exe` / `winget.exe`）时，若不传
 * `CREATE_NO_WINDOW`，Windows 会为**每个**子进程分配一个新的可见控制台窗口 —— 于是每调用一次
 * adb 就闪一次黑框。而 JDK 的 `ProcessImpl` 不会传这个标志，`ProcessBuilder` 也没有接口设置它。
 *
 * 解法：让父进程自己持有一个**隐藏的**控制台。子进程默认会继承父进程的控制台，
 * 于是不再新建窗口，黑框消失。这样就不必用 JNA 手写 `CreateProcessW`
 * （那需要自己管管道、环境块和句柄继承，约 250 行且易错）。
 */
public object ConsoleHost {

    private const val SW_HIDE = 0

    // 加载失败（典型：JNA 的 jnidispatch.dll 解压被拦）就整块降级：
    // 后果只是可能闪一下黑框，不该让整个应用起不来。
    private val kernel32: Kernel32Console? by lazy {
        runCatching { Native.load("kernel32", Kernel32Console::class.java) }
            .onFailure { JnaEnvironment.record("ConsoleHost", it) }
            .getOrNull()
    }

    /** 隐藏失败时的重试次数与间隔。最坏情况拖慢启动 0.4 秒，比留一个黑框在屏幕上强。 */
    private const val HIDE_ATTEMPTS = 20
    private const val HIDE_RETRY_MILLIS = 20L

    /**
     * 保证本进程持有一个**隐藏的**控制台。
     *
     * ⚠️ 关键：不能写成「本进程原本没有控制台才隐藏」。jpackage 的启动器是 GUI 子系统，但它拉起的是
     * **控制台子系统**的 `java.exe`，Windows 会因此自动给 JVM 分配一个全新的控制台窗口——它一出生
     * 就是可见的。早先这里判断「已有控制台 = 从终端启动，别动它」，那个自动分配的黑框就再也没人隐藏，
     * 打包版一直挂着个黑框在屏幕上。
     *
     * @return 是否本次真的分配了控制台。
     */
    public fun attachHiddenConsole(): Boolean {
        val api = kernel32 ?: return false
        return try {
            val allocated = api.GetConsoleWindow() == null && api.AllocConsole() != 0
            hideConsoleWindow()
            allocated
        } catch (t: Throwable) {
            // 拿不到控制台只是会闪黑框，不该让应用起不来。
            false
        }
    }

    /**
     * 隐藏控制台窗口。
     *
     * 要重试：`AllocConsole` 返回时窗口**不一定已经建好**，此刻 `GetConsoleWindow` 会返回 null，
     * 只查一次的话隐藏就被整个跳过了。每次藏完复查可见性，没藏住就再藏一次。
     */
    private fun hideConsoleWindow() {
        val api = kernel32 ?: return
        repeat(HIDE_ATTEMPTS) { attempt ->
            val window = api.GetConsoleWindow()
            if (window != null) {
                val hwnd = WinDef.HWND(window)
                User32.INSTANCE.ShowWindow(hwnd, SW_HIDE)
                if (!User32.INSTANCE.IsWindowVisible(hwnd)) return
            }
            if (attempt < HIDE_ATTEMPTS - 1) Thread.sleep(HIDE_RETRY_MILLIS)
        }
    }
}
