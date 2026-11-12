package app.neochat

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
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
                "notify", "account_notify" -> Notifier.onNotify(this, ev)
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
                if (--started == 0) {
                    CoreClient.call("set_foreground", JSONObject().put("active", false))
                    // Closed apps keep no socket open: push and the periodic job do the delivering.
                    RealtimeService.stop(this@App)
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // The last-resort delivery path: neither a push nor an open socket brought a hint.
        scheduleSync(this)
    }

    fun openCore() {
        if (CoreClient.isOpen) return
        val dir = File(filesDir, "core").apply { mkdirs() }
        CoreClient.open(dir.absolutePath, DbKey.loadOrCreate(this), android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty())
    }

    companion object {
        lateinit var instance: App
            private set

        /**
         * The periodic sync. Re-armed after an update by [BootReceiver]; scheduling it twice is a
         * no-op. It is persisted so a reboot does not leave push-less devices without delivery:
         * JobScheduler re-registers the job itself, without starting NeoChat at boot.
         */
        fun scheduleSync(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(SyncJob.ID) != null) return
            js.schedule(
                JobInfo.Builder(SyncJob.ID, ComponentName(ctx, SyncJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(15 * 60 * 1000L)
                    .build()
            )
        }
    }
}
