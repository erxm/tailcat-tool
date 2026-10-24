package cc.coauto.tailcat.shell.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 用系统自带的 mDNS（`NsdManager`）找无线调试的服务端口。
 *
 * 为什么不用 `adb mdns services`：**我们打包的 adb 在编译时把 mdns 关掉了**，
 * 实测输出是 `mdns is not supported by this version of adb.`（退出码 1）。
 * 而 `NsdManager` 是 Android 的系统 API，行为和 `adb mdns` 一致，还不依赖 adb 的构建选项。
 *
 * 无线调试会公告两个服务：
 * - `_adb-tls-pairing._tcp` —— 只在你打开「使用配对码配对设备」时出现，端口每次不同
 * - `_adb-tls-connect._tcp` —— 常态存在，**端口每次 adbd 重启都会变**（实测 40319 → 37237）
 *
 * 所以两个端口都只能**现查现用**，不能缓存。
 */
internal object MdnsDiscovery {

    private const val TAG = "TailcatMdns"

    internal const val CONNECT_SERVICE = "_adb-tls-connect._tcp"
    internal const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"

    /** 找到 `_adb-tls-connect._tcp` 的端口；超时返回 null。 */
    internal suspend fun findConnectPort(
        context: Context,
        timeoutMillis: Long = 4_000,
    ): Int? = findPort(context, CONNECT_SERVICE, timeoutMillis)

    /** 找到 `_adb-tls-pairing._tcp` 的端口（只在配对对话框开着时存在）。 */
    internal suspend fun findPairingPort(
        context: Context,
        timeoutMillis: Long = 4_000,
    ): Int? = findPort(context, PAIRING_SERVICE, timeoutMillis)

    private suspend fun findPort(context: Context, serviceType: String, timeoutMillis: Long): Int? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                    ?: return@suspendCancellableCoroutine continuation.resume(null)

                var listener: NsdManager.DiscoveryListener? = null
                var finished = false

                fun finish(port: Int?) {
                    if (finished) return
                    finished = true
                    runCatching { listener?.let { nsd.stopServiceDiscovery(it) } }
                    if (continuation.isActive) continuation.resume(port)
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "发现 $serviceType 失败：$errorCode")
                        finish(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (!info.serviceType.contains(serviceType)) return
                        // 找到服务名还不够，端口要再解析一次
                        runCatching {
                            @Suppress("DEPRECATION")
                            nsd.resolveService(
                                info,
                                object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                        Log.w(TAG, "解析 ${serviceInfo.serviceName} 失败：$errorCode")
                                    }

                                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                        Log.i(TAG, "发现 ${serviceInfo.serviceName} → 端口 ${serviceInfo.port}")
                                        finish(serviceInfo.port)
                                    }
                                },
                            )
                        }.onFailure { finish(null) }
                    }

                    override fun onServiceLost(info: NsdServiceInfo) = Unit
                }

                continuation.invokeOnCancellation {
                    runCatching { listener?.let { nsd.stopServiceDiscovery(it) } }
                }
                runCatching {
                    nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure {
                    Log.w(TAG, "无法开始发现 $serviceType", it)
                    finish(null)
                }
            }
        }
}
