package app.neochat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.neochat.core.CoreClient
import app.neochat.ui.MainActivity
import app.neochat.ui.Prefs
import org.json.JSONObject

/**
 * Keeps the core's WebSocket open while NeoChat is in the background, so
 * messages and calls arrive at once. This build has no push service (FCM
 * needs Google credentials), so without it new messages wait for the
 * periodic job. A background socket does not mark the user as online.
 */
class RealtimeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (application as App).openCore()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Фоновое подключение", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            description = "Нужно, чтобы сообщения и звонки приходили сразу. Канал можно скрыть в настройках Android."
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("NeoChat на связи")
            .setContentText("Сообщения и звонки приходят сразу")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else startForeground(ID, n)
        }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        CoreClient.call("set_background_connection", JSONObject().put("enabled", true))
        return START_STICKY
    }

    override fun onDestroy() {
        CoreClient.call("set_background_connection", JSONObject().put("enabled", false))
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "connection"
        const val ID = 7

        fun enabled(ctx: Context) = Prefs.of(ctx).getBoolean("realtime", true)

        /** Starts or stops the service to match the setting and the sign-in state. */
        fun sync(ctx: Context, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            if (!enabled(app)) {
                stop(app)
                return done()
            }
            CoreClient.call("status") { v, _ ->
                if ((v as? JSONObject)?.optBoolean("logged_in") == true) {
                    // Starting from the background is refused on Android 12+; the next foreground start retries.
                    runCatching { app.startForegroundService(Intent(app, RealtimeService::class.java)) }
                } else stop(app)
                done()
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RealtimeService::class.java))
        }
    }
}

/** Restores the background connection after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        RealtimeService.sync(ctx) { pending.finish() }
    }
}
