package dev.mangaflow.storage

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android 14+ user transfers use the same chapter writer, routes and checkpoint protocol. */
@TargetApi(34)
class ChapterDownloadJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executions = mutableMapOf<Int, Execution>()

    override fun onStartJob(params: JobParameters): Boolean {
        val chapter = params.extras.getString(ChapterUserInitiatedJobs.KEY_CHAPTER) ?: return false
        val workId = params.extras.getString(ChapterUserInitiatedJobs.KEY_WORK) ?: return false
        if (!chapter.matches(Regex("[a-f0-9]{64}")) || runCatching { java.util.UUID.fromString(workId) }.isFailure) return false
        ChapterDownloadNotifications.createChannel(applicationContext)
        setNotification(params, -params.jobId, ChapterDownloadNotifications.notification(applicationContext, chapter, workId),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        val updates = DownloadNotificationUpdates()
        val execution = Execution(params, chapter, workId)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                retry = runChapterDownload(applicationContext, chapter, workId, 0) { entry ->
                    if (updates.accept(entry)) withContext(Dispatchers.Main.immediate) {
                        if (!execution.stopped && executions[params.jobId] === execution) {
                            setNotification(params, -params.jobId,
                                ChapterDownloadNotifications.notification(applicationContext, chapter, workId, entry),
                                JOB_END_NOTIFICATION_POLICY_REMOVE)
                            updateTransferredNetworkBytes(params, entry.progress.transferredBytes.coerceAtLeast(0L), 0L)
                        }
                    }
                } == DownloadOutcome.RETRY
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (executions[params.jobId] === execution) executions.remove(params.jobId)
                    ChapterUserInitiatedJobs.release(workId, execution.job)
                    if (!execution.stopped) jobFinished(params, retry)
                }
            }
        }
        execution.job = job
        if (!ChapterUserInitiatedJobs.claim(workId, job)) {
            job.cancel()
            return false
        }
        executions[params.jobId] = execution
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val execution = executions[params.jobId]?.takeIf {
            it.workId == params.extras.getString(ChapterUserInitiatedJobs.KEY_WORK) &&
                it.chapter == params.extras.getString(ChapterUserInitiatedJobs.KEY_CHAPTER)
        } ?: return false
        execution.stopped = true
        val userStopped = params.stopReason == JobParameters.STOP_REASON_USER
        val appCancelled = params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP
        ChapterUserInitiatedJobs.holdStop(execution.workId)
        execution.job.cancel(CancellationException("系统暂停后台下载（${params.stopReason}）"))
        // Cancel and close this exact writer before persisting the OS stop. A later
        // pause, delete, retry or source refresh changes workId and wins in update().
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    execution.job.join()
                    if (!appCancelled) {
                        val store = DownloadStore.get(applicationContext)
                        store.awaitReady()
                        runCatching {
                            store.update(execution.chapter, execution.workId) { entry ->
                                if (entry.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.PAUSED, DownloadStatus.FAILED)) entry
                                else entry.copy(
                                    status = if (userStopped) DownloadStatus.PAUSED else DownloadStatus.WAITING,
                                    error = if (userStopped) "已由系统停止，点击继续下载" else stopLabel(params.stopReason),
                                    progress = entry.progress.copy(
                                        phase = if (userStopped) DownloadPhase.PAUSED else DownloadPhase.WAITING,
                                        waitingReason = if (userStopped) null else stopReason(params.stopReason),
                                        retryAt = null, bytesPerSecond = null, updatedAt = wallTimeMillis(),
                                    ),
                                )
                            }
                        }
                    }
                } finally { ChapterUserInitiatedJobs.finishStop(execution.workId) }
            }
        }
        // Android does not reschedule user-stopped UIDT jobs. Health/network stops
        // retain the persisted job and its exponential backoff.
        return !userStopped && !appCancelled
    }

    override fun onDestroy() {
        executions.values.forEach { it.stopped = true }
        scope.cancel()
        super.onDestroy()
    }

    private class Execution(val params: JobParameters, val chapter: String, val workId: String) {
        lateinit var job: Job
        var stopped = false
    }

    private fun stopReason(reason: Int): DownloadWaitReason = when (reason) {
        JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> DownloadWaitReason.NO_NETWORK
        JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> DownloadWaitReason.STORAGE_LOW
        JobParameters.STOP_REASON_BACKGROUND_RESTRICTION, JobParameters.STOP_REASON_APP_STANDBY,
        JobParameters.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> DownloadWaitReason.BACKGROUND_RESTRICTED
        else -> DownloadWaitReason.SYSTEM_SUSPENDED
    }

    private fun stopLabel(reason: Int): String = when (reason) {
        JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "网络连接中断，等待系统重新安排"
        JobParameters.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "存储空间不足，等待系统重新安排"
        JobParameters.STOP_REASON_QUOTA -> "系统执行配额受限，等待恢复"
        JobParameters.STOP_REASON_TIMEOUT -> "系统暂停长时间任务，等待恢复"
        else -> "系统暂停后台下载，等待恢复"
    }
}

/** A dedicated namespace and exact workId extras keep jobs separate from app updates and WorkManager. */
@TargetApi(34)
internal object ChapterUserInitiatedJobs {
    const val KEY_CHAPTER = "chapter"
    const val KEY_WORK = "work"
    private const val NAMESPACE = "mangaflow.chapter-downloads"
    private val lock = Any()
    private val running = mutableMapOf<String, Job>()
    private val stopping = mutableSetOf<String>()

    private fun scheduler(context: Context): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)

    fun schedule(context: Context, chapter: String, workId: String): Boolean = synchronized(lock) {
        val state = ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
        if (state.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) return@synchronized false
        val scheduler = scheduler(context)
        val scheduled = scheduler.allPendingJobs
        if (scheduled.any { it.extras.getString(KEY_WORK) == workId }) return@synchronized true
        val occupied = scheduled.mapTo(mutableSetOf()) { it.id }
        var jobId = workId.hashCode().and(Int.MAX_VALUE).coerceAtLeast(1)
        while (jobId in occupied) jobId = if (jobId == Int.MAX_VALUE) 1 else jobId + 1
        val job = JobInfo.Builder(jobId, ComponentName(context, ChapterDownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresStorageNotLow(true)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply { putString(KEY_CHAPTER, chapter); putString(KEY_WORK, workId) })
            .build()
        try {
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (rejected: SecurityException) {
            // Visibility can change after the check. Confirm absence before the
            // caller falls back to WorkManager; an unknown result never creates two runners.
            scheduler.allPendingJobs.any { it.extras.getString(KEY_WORK) == workId }
        } catch (rejected: IllegalStateException) {
            scheduler.allPendingJobs.any { it.extras.getString(KEY_WORK) == workId }
        }
    }

    suspend fun cancel(context: Context, workId: String) {
        synchronized(lock) {
            val scheduler = scheduler(context)
            scheduler.allPendingJobs.filter { it.extras.getString(KEY_WORK) == workId }.forEach { scheduler.cancel(it.id) }
        }
        synchronized(lock) { running[workId] }?.cancelAndJoin()
    }

    fun state(context: Context, workId: String): DownloadTaskState? = synchronized(lock) {
        if (workId in stopping) DownloadTaskState.BLOCKED
        else if (running[workId]?.isActive == true) DownloadTaskState.RUNNING
        else if (scheduler(context).allPendingJobs.any { it.extras.getString(KEY_WORK) == workId }) DownloadTaskState.ENQUEUED
        else null
    }

    fun claim(workId: String, job: Job): Boolean = synchronized(lock) {
        if (running[workId]?.isCompleted == false) false else { running[workId] = job; true }
    }

    fun release(workId: String, job: Job) = synchronized(lock) {
        if (running[workId] === job) running.remove(workId)
        Unit
    }

    fun holdStop(workId: String) { synchronized(lock) { stopping += workId } }
    fun finishStop(workId: String) { synchronized(lock) { stopping -= workId } }
}
