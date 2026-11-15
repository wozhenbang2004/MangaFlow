package dev.mangaflow.update

import android.app.job.JobParameters
import android.app.job.JobService

class UpdateJobService : JobService() {
    private var active: JobParameters? = null
    private var operation: String? = null

    override fun onStartJob(params: JobParameters): Boolean {
        active = params
        var started: String? = null
        started = UpdateManager.get(applicationContext).startScheduledCheck {
            if (active === params && operation == started) {
                active = null
                operation = null
                jobFinished(params, false)
            }
        }
        operation = started
        if (started == null) active = null
        return started != null
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) {
            val token = operation
            active = null
            operation = null
            token?.let { UpdateManager.get(applicationContext).stopScheduledCheck(it) }
        }
        return true
    }
}
