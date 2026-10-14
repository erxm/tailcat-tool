package cc.coauto.tailcat.desktop.win

import java.io.File

/**
 * `cmd.exe` 的引号规则与 POSIX 完全不同（`"` 与 `^`/`&` 的转义方式都不一样），
 * 所以 macOS 版的 `shellQuote` **不能照搬**，必须单独写。
 */
public object CmdQuoter {

    private const val SPECIAL = " \t\"&|<>^()%!"

    public fun quote(value: String): String {
        if (value.isNotEmpty() && value.none { it in SPECIAL }) return value
        // cmd 里双引号内的 " 用两个 " 转义；% 需要变成 %% 以免被当作变量展开。
        val escaped = value.replace("\"", "\"\"").replace("%", "%%")
        return "\"$escaped\""
    }
}

public object WindowsTerminal {

    /**
     * 打开一个**独立的**控制台窗口跑 `adb -s <serial> shell`。
     *
     * 注意这是唯一一个我们**希望**有新控制台窗口的地方——与应用其余部分（靠隐藏控制台
     * 抑制黑框）的目标相反，所以这里显式用 `start` 开新窗口。
     * 本机没有 Windows Terminal（无 wt.exe），因此用 cmd.exe。
     */
    public fun openAdbShell(adbPath: String, serial: String) {
        val command = "${CmdQuoter.quote(adbPath)} -s ${CmdQuoter.quote(serial)} shell"
        ProcessBuilder(
            "cmd.exe", "/c", "start", "", "cmd.exe", "/k", command,
        ).apply {
            directory(File(System.getProperty("user.home")))
        }.start()
    }
}
