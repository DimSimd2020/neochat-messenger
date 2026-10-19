package app.neochat

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.Bundle
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
        CoreClient.addListener { ev -> if (ev.optString("type") == "notify") Notifier.onNotify(this, ev) }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (started++ == 0) CoreClient.call("set_foreground", JSONObject().put("active", true))
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

    /** Push needs FCM credentials that this build does not have; a periodic job fetches instead. */
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
