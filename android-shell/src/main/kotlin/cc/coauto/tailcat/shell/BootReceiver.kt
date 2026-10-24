package cc.coauto.tailcat.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 开机自启。只在用户显式打开开关时才动作。
 *
 * ⚠️ Android 12 起对「从后台启动前台服务」有全局限制，Android 15 又收紧了部分 FGS 类型
 * 能否从 BOOT_COMPLETED 启动。这里如实捕获失败并记日志，不假装成功。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        if (!Settings.autoStart(context)) return
        try {
            val service = Intent(context, TailcatService::class.java).setAction(TailcatService.ACTION_START)
            ContextCompat.startForegroundService(context, service)
            Log.i(TAG, "开机自启：已请求启动前台服务")
        } catch (t: Throwable) {
            // 系统限制时这里会抛 ForegroundServiceStartNotAllowedException
            Log.w(TAG, "开机自启失败（系统限制后台启动前台服务）", t)
        }
    }

    private companion object {
        const val TAG = "TailcatShell"
    }
}
