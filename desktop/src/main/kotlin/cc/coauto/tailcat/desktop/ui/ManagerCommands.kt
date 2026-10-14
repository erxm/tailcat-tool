package cc.coauto.tailcat.desktop.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 窗口菜单栏 → [ManagerScreen] 的单向命令通道。
 *
 * 菜单栏挂在 `Window(menuBar = …)` 上，而所有对话框状态都 `remember` 在 [ManagerScreen] 内部，
 * 两者不在同一棵组合树里，直接互相调用够不着。用一个只在这两者之间传递的请求流打通，
 * 比为了几个快捷键把十来个 `remember` 状态整体上提到 `application {}` 里简单得多。
 *
 * 一次性语义：[send] 覆盖上一次未消费的请求（用户连按两次快捷键，执行最新的那次就够了）。
 */
public class ManagerCommands {

    private val _pending = MutableStateFlow<Command?>(null)
    public val pending: StateFlow<Command?> = _pending.asStateFlow()

    public fun send(command: Command) {
        _pending.value = command
    }

    /** 消费请求。用 CAS 而不是 `value = null`：避免把并发 `send` 进来的新请求一起抹掉。 */
    public fun consume(command: Command) {
        _pending.compareAndSet(command, null)
    }

    public enum class Command {
        AddDevice,
        NewTemporary,
        ImportAddressBook,
        TestAll,
        OpenToolSettings,
    }
}
