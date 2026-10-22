package app.neochat

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.neochat.core.CoreClient
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import kotlin.concurrent.thread

/** FCM carries a wake-up hint only. The encrypted feed supplies messages and call signals. */
class PushService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) { PushRegistration.onRegistered(this, installationId) }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "sync") return
        scheduleSync(message.priority == RemoteMessage.PRIORITY_HIGH)
    }

    override fun onDeletedMessages() { scheduleSync(false) }

    private fun scheduleSync(urgent: Boolean) {
        // Sync includes network and crypto: give it a system-owned job, not the short FCM callback.
        val scheduler = getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(SyncJob.PUSH_ID) != null) return
        fun job(expedited: Boolean): JobInfo {
            val builder = JobInfo.Builder(SyncJob.PUSH_ID, ComponentName(this, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            if (Build.VERSION.SDK_INT >= 31 && expedited) builder.setExpedited(true)
            else builder.setOverrideDeadline(0)
            return builder.build()
        }
        if (scheduler.schedule(job(urgent)) == JobScheduler.RESULT_FAILURE && urgent) scheduler.schedule(job(false))
    }
}

/** Registers the current installation with the signed-in device, without logging identifiers. */
object PushRegistration {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var registered = ""

    fun sync(ctx: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { sync(ctx.applicationContext) }; return }
        if (busy || FirebaseApp.getApps(ctx).isEmpty()) return
        busy = true
        CoreClient.call("status") { value, _ ->
            val loggedIn = (value as? JSONObject)?.optBoolean("logged_in") == true
            if (!loggedIn) registered = ""
            // Enabling FID auto-init reads the installation synchronously inside the SDK.
            // Core callbacks run on the main thread, so both SDK calls need a worker.
            thread(name = "neochat-push-register") {
                val messaging = FirebaseMessaging.getInstance()
                messaging.isAutoInitEnabled = loggedIn
                if (loggedIn) messaging.register().addOnCompleteListener { busy = false }
                else main.post { busy = false }
            }
        }
    }

    fun onRegistered(ctx: Context, installationId: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { onRegistered(ctx.applicationContext, installationId) }; return }
        CoreClient.call("status") { value, _ ->
            val status = value as? JSONObject
            if (status?.optBoolean("logged_in") != true) { registered = ""; return@call }
            val key = status.optString("device_id") + ":" + installationId
            if (registered == key) return@call
            CoreClient.call("set_push_token", JSONObject().put("provider", "fcm").put("token", installationId)) { _, error ->
                if (error == null) {
                    registered = key
                    RealtimeService.pushRegistered(ctx)
                }
            }
        }
    }
}
