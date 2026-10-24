package cc.coauto.tailcat.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import cc.coauto.tailcat.shell.manager.ManagerModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 前台服务：只要有设备连着，就让进程一直活着。
 *
 * **为什么必须有它。** `SessionManager` 的心跳与自动重连都是协程，而应用退到后台后，
 * 进程会被系统冻结、协程跟着停。实测表现：在详情页点「上传」拉起系统文件选择器，
 * 挑几分钟文件回来 —— 隧道早就断了，界面还停在「ADB 已连接」，于是点什么都是
 * 「任务目标已离线」。core 里本来就有自动恢复（探测到离线会用 `adb connect` 退避重试），
 * 但它同样是被冻住的那部分。
 *
 * 桌面端窗口永远在前台，不存在这个问题；**这是移植到手机端必须自己补的一块**。
 * 服务端角色早有同样的服务（[TailcatService]），这里是管理器角色的对应物。
 *
 * 用 specialUse 而不是 dataSync，理由同 [TailcatService]：这不是周期性同步，
 * 而是用户自己发起、需要一直开着的连接。
 */
class ManagerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: NotificationManager

    /** 连续多少次轮询没看到活动连接。用来跨过重连时的短暂空窗，别一掉线就把服务关了。 */
    private var idleRounds = 0

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0))

        scope.launch {
            while (true) {
                delay(POLL_MILLIS)
                val model = ManagerModel.get(this@ManagerService)
                val active = model.manager.ownedActiveCount
                notifications.notify(NOTIFICATION_ID, buildNotification(active))

                // 重连时会有一小段连接数为 0，给足宽限再收摊
                if (active > 0) idleRounds = 0 else idleRounds++
                if (idleRounds * POLL_MILLIS >= IDLE_GRACE_MILLIS) {
                    stopSelf()
                    return@launch
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                runCatching { ManagerModel.get(this@ManagerService).manager.disconnectAll() }
            }
            stopSelf()
            return START_NOT_STICKY
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
            "Tailcat 管理器",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "设备连着的时候保持连接存活"
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun buildNotification(active: Int): Notification {
        val text = if (active > 0) "$active 台设备已连接" else "暂无连接的设备"
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ManagerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Tailcat 管理器")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "全部断开", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "cc.coauto.tailcat.shell.MANAGER_STOP"

        private const val CHANNEL_ID = "tailcat-manager"
        private const val NOTIFICATION_ID = 1002
        private const val POLL_MILLIS = 2_000L

        /** 连接数归零后还继续保活多久。要够长，跨得过一次重连。 */
        private const val IDLE_GRACE_MILLIS = 20_000L
    }
}
