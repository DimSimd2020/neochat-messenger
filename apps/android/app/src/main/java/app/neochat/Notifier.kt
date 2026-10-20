package app.neochat

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.neochat.ui.MainActivity
import org.json.JSONObject

/** System notifications show the conversation title only, never message text (a missed call says so). */
object Notifier {
    const val CHANNEL = "messages"
    var visibleConversation: String? = null

    fun onNotify(ctx: Context, ev: JSONObject) {
        val app = ctx.applicationContext as App
        val cid = ev.optString("conversation_id")
        if (app.inForeground && cid == visibleConversation) return
        if (!ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("notifications", true)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, cid.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("conversation_id", cid)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(ev.optString("title", "NeoChat"))
            // A missed call names itself; message text is never shown.
            .setContentText(ev.optString("text").ifEmpty { "Новое сообщение" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(cid.hashCode(), n)
    }
}
