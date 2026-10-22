package app.neochat

import android.app.job.JobParameters
import android.app.job.JobService
import app.neochat.core.CoreClient
import kotlin.concurrent.thread

/** Background fetch while the app is not open; new messages raise notifications. */
class SyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as App
        if (app.inForeground && params.jobId != PUSH_ID) return false
        app.openCore()
        thread(name = "neochat-sync") {
            val (_, err) = CoreClient.callBlocking("sync_now")
            jobFinished(params, err?.code == "network")
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        const val ID = 1
        const val PUSH_ID = 2
    }
}
