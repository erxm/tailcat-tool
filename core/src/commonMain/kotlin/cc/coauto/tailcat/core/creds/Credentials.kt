package cc.coauto.tailcat.core.creds

private val ADDRESS_PATTERN = Regex("^tc[A-Za-z0-9_-]{20,}$")
private val INLINE_ADDRESS = Regex("tc[A-Za-z0-9_-]{20,}")

public object Credentials {
    public const val REDACTED: String = "[连接凭据已隐藏]"

    public fun isPlausible(address: String): Boolean =
        address.length <= 8192 && ADDRESS_PATTERN.matches(address)

    public fun redact(text: String, address: String? = null): String {
        var result = text
        if (!address.isNullOrEmpty()) result = result.replace(address, REDACTED)
        return result.replace(INLINE_ADDRESS, REDACTED)
    }
}

/**
 * 缓冲不完整的行，确保被管道切分的凭据片段绝不会泄漏到界面。
 * 所有方法只在单一线程（进程回调线程）上调用。
 */
public class OutputRedactor(private val address: String? = null) {
    private val pending = StringBuilder()
    private var discarding = false
    private val out = StringBuilder()

    public fun append(chunk: String): String {
        pending.append(chunk)
        out.setLength(0)
        while (true) {
            val index = pending.indexOf("\n")
            if (index < 0) break
            val line = pending.substring(0, index)
            pending.delete(0, index + 1)
            if (!discarding) {
                out.append(Credentials.redact(line, address)).append('\n')
            }
            discarding = false
        }
        if (pending.length > 32768) {
            pending.setLength(0)
            discarding = true
            out.append("[输出行过长，已截断]\n")
        }
        return out.toString()
    }

    public fun flush(): String {
        val result = if (discarding) "" else Credentials.redact(pending.toString(), address)
        pending.setLength(0)
        discarding = false
        return result
    }
}
