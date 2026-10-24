package cc.coauto.tailcat.shell

import android.content.Context

/** 用户设置。都是本地偏好，不涉及凭据，用 SharedPreferences 足够。 */
object Settings {

    private const val FILE = "tailcat-shell"
    private const val KEY_PORTS = "ports"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_PERSISTENT = "persistentKey"

    const val DEFAULT_PORTS = "5555"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun portsText(context: Context): String =
        prefs(context).getString(KEY_PORTS, DEFAULT_PORTS).orEmpty()

    fun setPortsText(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PORTS, value.trim()).apply()
    }

    /**
     * 解析逗号分隔的端口列表。tailcat 的 `serve` 接受的参数就是逗号分隔的纯端口号，
     * 每个端口会被代理到本机 localhost 的同号端口。
     */
    fun ports(text: String): List<Int> = text.split(',', ' ', '\n')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..65535 }
        .distinct()

    fun effectivePorts(context: Context): List<Int> =
        ports(portsText(context)).ifEmpty { ports(DEFAULT_PORTS) }

    fun autoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSTART, false)

    fun setAutoStart(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART, value).apply()
    }

    /**
     * 是否使用持久密钥（固定地址）。
     * 关掉时服务端会显式带 `--key=new` 走临时密钥，但**不会删除**已落盘的密钥，
     * 所以再打开能拿回同一个地址。
     */
    fun persistentKey(context: Context): Boolean = prefs(context).getBoolean(KEY_PERSISTENT, false)

    fun setPersistentKey(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PERSISTENT, value).apply()
    }
}
