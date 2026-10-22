package app.neochat

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Bundle
import app.neochat.calls.CallManager
import app.neochat.calls.CallNotifications
import app.neochat.core.CoreClient
import app.neochat.core.DbKey
import org.json.JSONObject
import java.io.File

class App : Application() {
    /** Number of started activities: the app is in the foreground while > 0. */
    private var started = 0
    val inForeground get() = started > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        openCore()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Notifier.CHANNEL, "Сообщения", NotificationManager.IMPORTANCE_HIGH)
        )
        CallNotifications.createChannels(this)
        CoreClient.addListener { ev ->
            when (ev.optString("type")) {
                "notify" -> Notifier.onNotify(this, ev)
                "call", "call_media" -> CallManager.onCoreEvent(ev)
                "connection" -> if (ev.optString("state") == "online") PushRegistration.sync(this)
            }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0) {
                    CoreClient.call("set_foreground", JSONObject().put("active", true))
                    // Android only lets a foreground app start the service; retry on every return.
                    RealtimeService.sync(this@App)
                    PushRegistration.sync(this@App)
                }
            }

            override fun onActivityStopped(a: Activity) {
                if (--started == 0) CoreClient.call("set_foreground", JSONObject().put("active", false))
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        scheduleSync()
    }

    fun openCore() {
        if (CoreClient.isOpen) return
        val dir = File(filesDir, "core").apply { mkdirs() }
        CoreClient.open(dir.absolutePath, DbKey.loadOrCreate(this))
    }

    /** Periodic fallback when neither a push nor the background socket has delivered a sync hint. */
    private fun scheduleSync() {
        val js = getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(SyncJob.ID) != null) return
        js.schedule(
            JobInfo.Builder(SyncJob.ID, ComponentName(this, SyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .build()
        )
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
