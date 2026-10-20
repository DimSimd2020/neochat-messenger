package app.neochat.calls

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.neochat.R
import app.neochat.ui.str

/** Incoming-call and ongoing-call notifications. The peer's name only, as for messages. */
object CallNotifications {
    const val CHANNEL_INCOMING = "calls"
    const val CHANNEL_ONGOING = "call_ongoing"
    const val ID_INCOMING = 41
    const val ID_ONGOING = 42

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        // The ringtone is played by the app (looping, respects the ringer mode), not by the channel.
        nm.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ONGOING, "Идущий звонок", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun screen(ctx: Context, answer: Boolean = false) = PendingIntent.getActivity(
        ctx, if (answer) 2 else 1,
        Intent(ctx, CallActivity::class.java).putExtra(CallActivity.EXTRA_ANSWER, answer)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(ctx: Context, what: String) = PendingIntent.getBroadcast(
        ctx, what.hashCode(), Intent(ctx, CallActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE,
    )

    /** Full-screen ring on a locked or inactive phone; a heads-up card otherwise. */
    fun showIncoming(ctx: Context) {
        if (!canNotify(ctx)) return
        val call = CallManager.call
        val n = Notification.Builder(ctx, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(call.str("peer_name"))
            .setContentText(if (call.optBoolean("video")) "Входящий видеозвонок" else "Входящий звонок")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(screen(ctx))
            .setFullScreenIntent(screen(ctx), true)
            .addAction(Notification.Action.Builder(null, "Отклонить", action(ctx, CallActionReceiver.DECLINE)).build())
            .addAction(Notification.Action.Builder(null, "Ответить", screen(ctx, answer = true)).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_INCOMING, n)
    }

    fun cancelIncoming(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING)

    fun ongoing(ctx: Context): Notification {
        val call = CallManager.call
        val text = when (CallManager.phase) {
            CallManager.Phase.ACTIVE -> if (CallManager.reconnecting) "Восстанавливаем связь…" else "Идёт звонок"
            CallManager.Phase.OUTGOING -> "Вызов…"
            else -> "Соединение…"
        }
        return Notification.Builder(ctx, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(call.str("peer_name").ifEmpty { "Звонок" })
            .setContentText(text)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(CallManager.phase == CallManager.Phase.ACTIVE)
            .setWhen(if (CallManager.phase == CallManager.Phase.ACTIVE) CallManager.since else System.currentTimeMillis())
            .setContentIntent(screen(ctx))
            .addAction(Notification.Action.Builder(null, "Завершить", action(ctx, CallActionReceiver.HANGUP)).build())
            .build()
    }

    /** Keeps the ongoing notification in step with the call. */
    fun update(ctx: Context) {
        if (CallManager.live && canNotify(ctx) && CallService.running)
            ctx.getSystemService(NotificationManager::class.java).notify(ID_ONGOING, ongoing(ctx))
    }
}

/** Decline and hang up from notifications. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            DECLINE, HANGUP -> CallManager.hangup()
        }
    }

    companion object {
        const val DECLINE = "app.neochat.call.DECLINE"
        const val HANGUP = "app.neochat.call.HANGUP"
    }
}

/**
 * Foreground service for the duration of a call: the microphone (and camera)
 * keep working while NeoChat is in the background or the screen is off.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = CallNotifications.ongoing(this)
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            // Calls start only with the microphone permission granted (CallActivity / ChatScreen ask first).
            if (runCatching { startForeground(CallNotifications.ID_ONGOING, n, type) }.isFailure) {
                stopSelf()
                return START_NOT_STICKY
            }
        } else {
            startForeground(CallNotifications.ID_ONGOING, n)
        }
        running = true
        if (!CallManager.live) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false

        fun start(ctx: Context) {
            if (running) return
            runCatching { ctx.startForegroundService(Intent(ctx, CallService::class.java)) }
        }

        fun stop(ctx: Context) {
            if (!running) return
            ctx.stopService(Intent(ctx, CallService::class.java))
            running = false
        }
    }
}
