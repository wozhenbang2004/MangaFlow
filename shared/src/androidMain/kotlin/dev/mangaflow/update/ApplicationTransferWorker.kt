package dev.mangaflow.update

import dev.mangaflow.net.RelayRouteBinding

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mangaflow.net.DownloadProxyEndpoint
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.checked
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Only the two existing user-requested binary transfers use this host. */
internal enum class ApplicationTransferKind { UPDATE, RUNTIME }
internal enum class ApplicationTransferStatus { QUEUED, RUNNING, WAITING_SYSTEM, PAUSED, FAILED, COMPLETED }
internal enum class ApplicationTransferResult { COMPLETED, RETRY, FAILED, OBSOLETE }

internal data class ApplicationTransferIntent(
    val workId: String,
    val kind: ApplicationTransferKind,
    val owner: String,
    val route: DownloadRouteSnapshot,
    val payload: JSONObject,
    val status: ApplicationTransferStatus = ApplicationTransferStatus.QUEUED,
    val message: String = "",
) {
    val resumable: Boolean get() = status in setOf(ApplicationTransferStatus.QUEUED,
        ApplicationTransferStatus.RUNNING, ApplicationTransferStatus.WAITING_SYSTEM)
}

/** Durable intent is written before the OS is asked to execute; stale runners cannot update it. */
internal object AndroidTransferIntents {
    private val lock = Any()
    private val scheduleLock = Any()
    private fun file(context: Context, kind: ApplicationTransferKind, owner: String): AtomicFile {
        require(owner.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        return AtomicFile(File(File(context.noBackupFilesDir, "application-transfers"), "${kind.name}-$owner.json"))
    }

    fun read(context: Context, kind: ApplicationTransferKind, owner: String): ApplicationTransferIntent? = synchronized(lock) {
        val saved = file(context, kind, owner)
        val backup = File(saved.baseFile.path + ".bak")
        if (!saved.baseFile.exists() && !backup.exists()) return@synchronized null
        require(maxOf(saved.baseFile.length(), backup.length()) in 1..1_048_576L) { "后台任务记录大小无效" }
        val bytes = saved.readFully()
        require(bytes.size in 1..1_048_576) { "后台任务记录大小无效" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("schema") == 1 && json.getString("kind") == kind.name && json.getString("owner") == owner)
        val id = json.getString("workId").also { UUID.fromString(it) }
        val route = json.getJSONObject("route")
        val proxy = route.optJSONObject("proxy")?.let {
            DownloadProxyEndpoint(it.getString("host"), it.getInt("port"), DownloadProxyType.valueOf(it.getString("type")))
        }
        ApplicationTransferIntent(id, kind, owner,
            DownloadRouteSnapshot(DownloadRouteMode.valueOf(route.getString("mode")), proxy,
                if (route.has("relay")) route.getJSONObject("relay").let {
                    RelayRouteBinding(it.getString("package"), it.getString("certificate"), it.getString("grantId"))
                } else null).checked(),
            json.getJSONObject("payload"), ApplicationTransferStatus.valueOf(json.getString("status")), json.optString("message"))
    }

    fun create(context: Context, kind: ApplicationTransferKind, owner: String, route: DownloadRouteSnapshot,
        payload: JSONObject): ApplicationTransferIntent = synchronized(lock) {
        val next = ApplicationTransferIntent(UUID.randomUUID().toString(), kind, owner, route.checked(),
            JSONObject(payload.toString()))
        write(context, next)
        next
    }

    fun update(context: Context, expected: ApplicationTransferIntent,
        change: (ApplicationTransferIntent) -> ApplicationTransferIntent): ApplicationTransferIntent? = synchronized(lock) {
        val current = read(context, expected.kind, expected.owner) ?: return@synchronized null
        if (current.workId != expected.workId) return@synchronized null
        change(current).also { write(context, it) }
    }

    /** Pause and claim serialize on the same durable record; only an eligible generation may start. */
    fun claim(context: Context, expected: ApplicationTransferIntent): ApplicationTransferIntent? = synchronized(lock) {
        val current = read(context, expected.kind, expected.owner) ?: return@synchronized null
        if (current.workId != expected.workId || !current.resumable) return@synchronized null
        current.copy(status = ApplicationTransferStatus.RUNNING, message = "").also { write(context, it) }
    }

    /** Publish UI state while pause/replacement cannot change the accepted generation. */
    fun withCurrent(context: Context, expected: ApplicationTransferIntent,
        action: (ApplicationTransferIntent) -> Unit) = synchronized(lock) {
        read(context, expected.kind, expected.owner)?.takeIf { it.workId == expected.workId }?.let(action)
    }

    private fun write(context: Context, task: ApplicationTransferIntent) {
        val route = JSONObject().put("mode", task.route.mode.name)
        task.route.relayBinding?.let { route.put("relay", JSONObject().put("package", it.providerPackage)
            .put("certificate", it.certificate).put("grantId", it.grantId)) }
        task.route.proxy?.let { route.put("proxy", JSONObject().put("host", it.host).put("port", it.port).put("type", it.type.name)) }
        val json = JSONObject().put("schema", 1).put("workId", task.workId).put("kind", task.kind.name)
            .put("owner", task.owner).put("status", task.status.name).put("message", task.message.take(1000))
            .put("route", route).put("payload", task.payload)
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1_048_576) { "后台任务记录过大" }
        val target = file(context, task.kind, task.owner)
        check(target.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (failure: Exception) { target.failWrite(output); throw failure }
    }

    /** Restoring a paused/failed task never turns it into an automatic network request. */
    fun schedule(context: Context, expected: ApplicationTransferIntent): ApplicationTransferIntent? = synchronized(scheduleLock) {
        val current = read(context, expected.kind, expected.owner) ?: return@synchronized null
        if (current.workId != expected.workId || !current.resumable) return@synchronized null
        val manager = WorkManager.getInstance(context)
        val existing = manager.getWorkInfoById(UUID.fromString(current.workId)).get(20, TimeUnit.SECONDS)
        if (existing != null && !existing.state.isFinished) return@synchronized current
        val task = if (existing == null) current else update(context, current) {
            if (it.resumable) it.copy(workId = UUID.randomUUID().toString(), status = ApplicationTransferStatus.QUEUED) else it
        } ?: return@synchronized null
        if (!task.resumable) return@synchronized null
        val request = OneTimeWorkRequestBuilder<ApplicationTransferWorker>().setId(UUID.fromString(task.workId))
            .setInputData(workDataOf("kind" to task.kind.name, "owner" to task.owner))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniqueWork("mangaflow-application-${task.kind.name}-${task.owner}", ExistingWorkPolicy.REPLACE, request)
            .result.get(20, TimeUnit.SECONDS)
        task
    }

    fun pause(context: Context, task: ApplicationTransferIntent) {
        val paused = try { update(context, task) {
            if (it.resumable) it.copy(status = ApplicationTransferStatus.PAUSED, message = "下载已暂停，可继续下载") else it
        } } catch (failure: Exception) {
            // A full disk must not turn an explicit pause back into an automatic restart.
            synchronized(lock) {
                runCatching { read(context, task.kind, task.owner) }.getOrNull()
                    ?.takeIf { it.workId == task.workId }?.let { file(context, task.kind, task.owner).delete() }
            }
            stopExecution(context, task)
            WorkManager.getInstance(context).cancelWorkById(UUID.fromString(task.workId))
            throw failure
        } ?: return
        if (paused.status != ApplicationTransferStatus.PAUSED) return
        stopExecution(context, task)
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(task.workId))
    }

    fun stopExecution(context: Context, task: ApplicationTransferIntent) {
        when (task.kind) {
            ApplicationTransferKind.UPDATE -> UpdateManager.get(context).stopScheduledDownload(task.workId)
            ApplicationTransferKind.RUNTIME -> PanelRuntimeStore.get(context)
                .stopScheduledDownload(PanelRuntimeId.valueOf(task.owner), task.workId)
        }
    }
}

/** OS-owned execution and notification wrap the same verified managers used by the UI. */
class ApplicationTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val kind = inputData.getString("kind")?.let { ApplicationTransferKind.valueOf(it) } ?: return@coroutineScope Result.failure()
        val owner = inputData.getString("owner") ?: return@coroutineScope Result.failure()
        val task = AndroidTransferIntents.read(applicationContext, kind, owner)
            ?.takeIf { it.workId == id.toString() && it.resumable } ?: return@coroutineScope Result.success()
        try { setForeground(foreground(task)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val retry = runAttemptCount < 2
            var reason = if (retry) "系统暂未允许后台传输，等待重新调度" else "系统未允许后台传输，请回到应用检查后台限制后重试"
            val next = try { AndroidTransferIntents.update(applicationContext, task) {
                if (it.resumable) it.copy(status = if (retry) ApplicationTransferStatus.WAITING_SYSTEM else ApplicationTransferStatus.FAILED,
                    message = reason) else it
            } } catch (_: Exception) {
                reason += "；任务状态未能保存"
                runCatching { AndroidTransferIntents.read(applicationContext, kind, owner) }.getOrNull()
            }
            if (next == null || next.workId != task.workId || next.status in setOf(ApplicationTransferStatus.PAUSED, ApplicationTransferStatus.COMPLETED))
                return@coroutineScope Result.success()
            when (kind) {
                ApplicationTransferKind.UPDATE -> UpdateManager.get(applicationContext).publishScheduledWait(task, reason, terminal = !retry)
                ApplicationTransferKind.RUNTIME -> PanelRuntimeStore.get(applicationContext)
                    .publishScheduledWait(PanelRuntimeId.valueOf(owner), task, reason, terminal = !retry)
            }
            return@coroutineScope if (retry) Result.retry() else Result.failure()
        }
        val progress = launch {
            while (isActive) {
                delay(1_000L)
                try {
                    if (AndroidTransferIntents.read(applicationContext, kind, owner)?.workId != task.workId) break
                    setForeground(foreground(task))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { break } // Keep the already-promoted engine alive if a refresh is denied.
            }
        }
        val execution = async(Dispatchers.IO) {
            when (kind) {
                ApplicationTransferKind.UPDATE -> UpdateManager.get(applicationContext).runScheduledDownload(task)
                ApplicationTransferKind.RUNTIME -> PanelRuntimeStore.get(applicationContext)
                    .runScheduledDownload(PanelRuntimeId.valueOf(owner), task)
            }
        }
        try {
            when (execution.await()) {
                ApplicationTransferResult.COMPLETED, ApplicationTransferResult.OBSOLETE -> Result.success()
                ApplicationTransferResult.RETRY -> Result.retry()
                ApplicationTransferResult.FAILED -> Result.failure()
            }
        } catch (cancelled: CancellationException) {
            AndroidTransferIntents.stopExecution(applicationContext, task)
            AndroidTransferIntents.update(applicationContext, task) {
                if (it.resumable) it.copy(status = ApplicationTransferStatus.WAITING_SYSTEM, message = "等待系统允许继续后台传输") else it
            }
            throw cancelled
        } finally { progress.cancel() }
    }

    private fun foreground(task: ApplicationTransferIntent): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台传输", NotificationManager.IMPORTANCE_LOW))
        val pause = Intent(applicationContext, ApplicationTransferActionReceiver::class.java)
            .setData(Uri.Builder().scheme("mangaflow").authority("application-transfer").appendPath(task.workId).build())
            .putExtra("kind", task.kind.name).putExtra("owner", task.owner).putExtra("workId", task.workId)
        val description = when (task.kind) {
            ApplicationTransferKind.UPDATE -> UpdateManager.get(applicationContext).state.value.message
            ApplicationTransferKind.RUNTIME -> PanelRuntimeStore.get(applicationContext).states.value[PanelRuntimeId.valueOf(task.owner)]
                ?.let { if (it.error != null) it.error else "已收到 ${it.bytes / 1024} KB" } ?: "正在准备运行库"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(if (task.kind == ApplicationTransferKind.UPDATE) "应用更新" else "可选运行库")
            .setContentText(description).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "暂停", PendingIntent.getBroadcast(applicationContext, 0, pause,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
        val notificationId = ("application-${task.kind}-${task.owner}".hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(notificationId, notification)
    }

    companion object { private const val CHANNEL = "mangaflow-application-transfers" }
}

class ApplicationTransferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind")?.let { runCatching { ApplicationTransferKind.valueOf(it) }.getOrNull() } ?: return
        val owner = intent.getStringExtra("owner") ?: return
        val expected = intent.getStringExtra("workId") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { AndroidTransferIntents.read(context, kind, owner)?.takeIf { it.workId == expected }
                ?.let { AndroidTransferIntents.pause(context, it) } }
            catch (_: Exception) { /* The durable pause path already stopped the exact active writer. */ }
            finally { pending.finish() }
        }
    }
}
