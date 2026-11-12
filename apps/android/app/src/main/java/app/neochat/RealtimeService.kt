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
 * Keeps the core's WebSocket open while NeoChat is open — including with the screen off, when the
 * activity is still started — so calls and messages arrive without waiting for the next push.
 * Background delivery belongs to FCM, with [SyncJob] as the fallback when push is unavailable.
 * A background socket does not mark the user as online.
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
        // Nothing recreates it on its own: the next foreground start does that.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CoreClient.call("set_background_connection", JSONObject().put("enabled", false))
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "connection"
        const val ID = 7

        fun enabled(ctx: Context): Boolean {
            val prefs = Prefs.of(ctx)
            return prefs.getBoolean("realtime", !prefs.getBoolean("push_registered", false))
        }

        /** A confirmed push registration replaces the default socket; an explicit choice is kept. */
        fun pushRegistered(ctx: Context) {
            Prefs.of(ctx).edit().putBoolean("push_registered", true).apply()
            if (!enabled(ctx)) stop(ctx)
        }

        /** Runs the service only while the app is open; anything else stops it again. */
        fun sync(ctx: Context, done: () -> Unit = {}) {
            val app = ctx.applicationContext
            if (!enabled(app) || !(app as App).inForeground) {
                stop(app)
                return done()
            }
            CoreClient.call("list_accounts") { v, _ ->
                val accounts = v as? org.json.JSONArray
                if (accounts != null && (0 until accounts.length()).any { accounts.optJSONObject(it)?.optBoolean("logged_in") == true }) {
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

/**
 * Re-arms the periodic fallback after an update replaces the app. Nothing is started at boot:
 * while NeoChat is closed delivery comes from push, and the job wakes it on its own schedule.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        App.scheduleSync(ctx)
    }
}
