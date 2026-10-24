package cc.coauto.tailcat.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台服务：给 tailcat 子进程续命，并在常驻通知里显示当前 tc 地址。
 *
 * 用 specialUse 类型而不是 dataSync：Android 15 起 dataSync 有 6 小时上限，
 * 而这类隧道本来就是「开着就一直开着」，不该被系统掐。
 */
class TailcatService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(TailcatServer.state.value))
        // 拉起服务端的动作放在 onStartCommand：START_STICKY 重启时那里也会被调到（intent 为 null），
        // 在 onCreate 里再做一次会导致重复启动。
        // 地址要等 tailcat 打印出来才知道，拿到后刷新通知
        scope.launch {
            TailcatServer.state.collectLatest { state ->
                notifications.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                TailcatServer.stop(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> TailcatServer.ensureRunning(
                this,
                Settings.effectivePorts(this),
                Settings.persistentKey(this),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tailcat 服务端",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 tailcat 服务端的运行状态与连接地址"
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ServerState): Notification {
        val text = when (state) {
            is ServerState.Running -> state.address
            ServerState.Starting -> "正在启动…"
            is ServerState.Failed -> state.message
            ServerState.Stopped -> "已停止"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, TailcatService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Tailcat 服务端")
            .setContentText(text)
            // 地址有一百多字符，必须展开显示才有用
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "cc.coauto.tailcat.shell.START"
        const val ACTION_STOP = "cc.coauto.tailcat.shell.STOP"
        private const val CHANNEL_ID = "tailcat-server"
        private const val NOTIFICATION_ID = 1001
    }
}
