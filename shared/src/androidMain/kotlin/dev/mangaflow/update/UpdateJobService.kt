package dev.mangaflow.update

import android.app.job.JobParameters
import android.app.job.JobService

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        UpdateManager.get(applicationContext).check(manual = false) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
