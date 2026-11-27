package dev.mangaflow.update

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import dev.mangaflow.APP_DISPLAY_NAME
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.openDownloadConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** An APK release is trusted by the certificate that signed the installed app. */
actual class UpdateManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val root = File(context.cacheDir, "updates")
    private val metadata = AtomicFile(File(context.noBackupFilesDir, "app-update.json"))
    private val operation = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val _state = MutableStateFlow(UpdateState(lastChecked = preferences.getLong("last_check", 0)))
    actual val state: StateFlow<UpdateState> = _state.asStateFlow()
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var scheduledWork: String? = null
    @Volatile private var queuedWork: String? = null
    @Volatile private var checkToken: String? = null
    @Volatile private var checkJob: Job? = null
    private val installLock = Any()
    private var installationNotice: Pair<Int, String>? = null
    private var heldInstallConfirmation: Triple<Int, String, PendingIntent>? = null
    internal val installationEvents = MutableStateFlow(0L)

    actual val automatic: Boolean get() = preferences.getBoolean("automatic", true)

    actual fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            schedule()
            recover()
            runCatching { AndroidTransferIntents.read(context, ApplicationTransferKind.UPDATE, "apk")
                ?.takeIf { it.resumable }?.let { queuedWork = AndroidTransferIntents.schedule(context, it)?.workId } }
            if (PanelRuntimeId.entries.any { id -> runCatching {
                    AndroidTransferIntents.read(context, ApplicationTransferKind.RUNTIME, id.name)?.resumable == true
                }.getOrDefault(false) }) PanelRuntimeStore.get(context)
            if (automatic && System.currentTimeMillis() - preferences.getLong("last_check", 0) >= CHECK_INTERVAL) {
                check(manual = false)
            }
        }
    }

    actual fun setAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean("automatic", enabled).apply()
        scope.launch { schedule() }
        if (enabled) check(manual = false)
    }

    actual fun check(manual: Boolean, onComplete: (() -> Unit)?) {
        startCheck(manual, onComplete)
    }

    internal fun startScheduledCheck(onComplete: () -> Unit): String? = startCheck(false, onComplete)

    internal fun stopScheduledCheck(token: String) {
        if (checkToken == token) { checkJob?.cancel(); connection?.disconnect() }
    }

    private fun startCheck(manual: Boolean, onComplete: (() -> Unit)?): String? {
        if (!manual && !automatic) {
            complete(onComplete)
            return null
        }
        if (!operation.compareAndSet(false, true)) {
            complete(onComplete)
            return null
        }
        val token = UUID.randomUUID().toString()
        checkToken = token
        val beforeCheck = _state.value
        _state.value = beforeCheck.copy(phase = UpdatePhase.CHECKING, message = "正在检查新版本…")
        checkJob = scope.launch {
            try {
                val route = DownloadRoutingStore.get(context).snapshot(DownloadCategory.UPDATES)
                val bytes = readBytes(MANIFEST_URL, MAX_MANIFEST_BYTES, route)
                val manifest = readVerifiedManifest(bytes)
                val release = manifest.release
                val current = installed()
                val now = System.currentTimeMillis()
                if (release.versionCode <= versionCode(current)) {
                    metadata.delete()
                    _state.value = UpdateState(UpdatePhase.CURRENT, message = installationMessage(versionCode(installed()), "已是最新版本"), lastChecked = now)
                } else {
                    if (release.minSdk > Build.VERSION.SDK_INT) throw IOException("新版本需要 Android API ${release.minSdk} 或更新系统")
                    val output = metadata.startWrite()
                    try {
                        output.write(bytes)
                        metadata.finishWrite(output)
                    } catch (failure: Exception) {
                        metadata.failWrite(output)
                        throw failure
                    }
                    val ready = readyFile(release)
                    val pending = pendingInstallRequest()?.takeIf { it.versionCode == release.versionCode }
                    val cached = pending == null && ready.isFile && runCatching { validateApk(ready, release) }.isSuccess
                    val delta = if (cached || pending != null) null else installedBase(manifest, current)?.first
                    _state.value = UpdateState(
                        phase = if (cached || pending != null) UpdatePhase.READY else UpdatePhase.AVAILABLE,
                        release = release,
                        downloaded = if (cached) release.bytes else 0,
                        total = delta?.bytes ?: release.bytes,
                        message = when {
                            pending != null -> installationMessage(release.versionCode, "还有一个系统安装请求，可继续处理")
                            cached -> installationMessage(release.versionCode, "新版本已下载，可继续安装")
                            delta != null -> "发现新版本 ${release.versionName} · 增量更新"
                            else -> "发现新版本 ${release.versionName}"
                        },
                        lastChecked = now,
                    )
                    if (!manual && automatic) notifyAvailable(release)
                }
                preferences.edit().putLong("last_check", now).apply()
            } catch (failure: Exception) {
                _state.value = beforeCheck.copy(
                    phase = when {
                        beforeCheck.phase == UpdatePhase.READY -> UpdatePhase.READY
                        beforeCheck.release != null -> UpdatePhase.AVAILABLE
                        else -> UpdatePhase.ERROR
                    },
                    message = updateFailureMessage(failure, "检查失败，请稍后重试"),
                )
            } finally {
                val deliver = currentCoroutineContext().isActive && checkToken == token
                connection = null
                operation.set(false)
                if (checkToken == token) { checkToken = null; checkJob = null }
                if (deliver) complete(onComplete)
            }
        }
        return token
    }

    actual fun download() {
        val release = _state.value.release ?: return
        if (!operation.compareAndSet(false, true)) return
        scope.launch {
            var handedOff = false
            try {
                val previous = AndroidTransferIntents.read(context, ApplicationTransferKind.UPDATE, "apk")
                if (previous?.resumable == true && previous.payload.optString("sha256") == release.sha256 &&
                    previous.payload.optLong("versionCode") == release.versionCode) {
                    queuedWork = previous.workId
                    handedOff = true
                    operation.set(false)
                    queuedWork = AndroidTransferIntents.schedule(context, previous)?.workId
                    return@launch
                }
                if (previous?.resumable == true) AndroidTransferIntents.pause(context, previous)
                val envelope = envelopeFor(release)
                val checked = verifyManifest(envelope)
                require(checked.sha256 == release.sha256 && checked.versionCode == release.versionCode)
                val matching = previous?.takeIf { it.payload.optString("sha256") == release.sha256 }
                val task = AndroidTransferIntents.create(context, ApplicationTransferKind.UPDATE, "apk",
                    matching?.route ?: DownloadRoutingStore.get(context).snapshot(DownloadCategory.UPDATES),
                    JSONObject().put("envelope", Base64.encodeToString(envelope, Base64.NO_WRAP))
                        .put("sha256", release.sha256).put("versionCode", release.versionCode))
                queuedWork = task.workId
                _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, message = "等待网络或系统调度")
                handedOff = true
                operation.set(false)
                queuedWork = AndroidTransferIntents.schedule(context, task)?.workId
            } catch (failure: Exception) {
                _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, message = failure.message ?: "后台下载未能排队，请重试")
            } finally { if (!handedOff) operation.set(false) }
        }
    }

    internal suspend fun runScheduledDownload(task: ApplicationTransferIntent): ApplicationTransferResult = withContext(Dispatchers.IO) {
        val current = AndroidTransferIntents.read(context, task.kind, task.owner)
        if (current?.workId != task.workId || !current.resumable) return@withContext ApplicationTransferResult.OBSOLETE
        if (!operation.compareAndSet(false, true)) return@withContext ApplicationTransferResult.RETRY
        cancelled.set(false)
        scheduledWork = task.workId
        var partial: File? = null
        try {
            if (AndroidTransferIntents.claim(context, task) == null) return@withContext ApplicationTransferResult.OBSOLETE
            requireNotCancelled()
            val route = task.route
            val manifest = readVerifiedManifest(Base64.decode(task.payload.getString("envelope"), Base64.DEFAULT))
            val release = manifest.release
            require(release.sha256 == task.payload.getString("sha256") && release.versionCode == task.payload.getLong("versionCode"))
            _state.value = UpdateState(UpdatePhase.DOWNLOADING, release, total = release.bytes, message = "正在后台下载安装包…")
            val currentPackage = installed()
            val installedVersion = versionCode(currentPackage)
            require(release.versionCode > installedVersion) {
                if (release.versionCode == installedVersion) "此版本已安装，请重新检查更新"
                else "下载任务版本低于当前版本，已阻止降级；请重新检查更新"
            }
            if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建更新目录")
            val target = readyFile(release)
            if (target.isFile && runCatching { validateApk(target, release) }.isSuccess) {
                _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = release.bytes, total = release.bytes, message = "新版本已下载，可继续安装")
                return@withContext ApplicationTransferResult.COMPLETED
            }
            val incoming = File(root, release.sha256 + ".part")
            val validator = File(root, release.sha256 + ".validator")
            partial = incoming
            // A previously verified APK may still be held by the package installer
            // through a FileProvider URI from an older installed build. Do not sweep
            // other versions here: the OS may open the granted file after handoff.
            // Finished APKs are discarded on confirmed upgrade recovery; the cache
            // directory also remains reclaimable under system storage pressure.
            if (incoming.length() > release.bytes) incoming.delete()
            if (incoming.length() == release.bytes) {
                if (runCatching { validateApk(incoming, release) }.isSuccess) {
                    if (target.exists() && !target.delete()) throw IOException("无法替换旧安装包")
                    if (!incoming.renameTo(target)) throw IOException("无法保存安装包")
                    _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = release.bytes, total = release.bytes, message = "新版本已下载，可继续安装")
                    return@withContext ApplicationTransferResult.COMPLETED
                }
                incoming.delete()
            }
            val base = installedBase(manifest, currentPackage)
            if (base != null && incoming.length() == 0L) {
                val (delta, installedApk) = base
                val patch = File(root, delta.sha256 + ".delta.part")
                val patchValidator = File(root, delta.sha256 + ".delta.validator")
                val reconstructed = File(root, release.sha256 + ".reconstructed.apk")
                partial = patch
                try {
                    val remaining = delta.bytes - patch.length().coerceIn(0, delta.bytes)
                    require(root.usableSpace >= release.bytes + remaining + 16L * 1024 * 1024) { "存储空间不足" }
                    _state.value = _state.value.copy(total = delta.bytes, message = "正在后台下载增量更新…")
                    downloadUpdateAsset(delta.url, delta.sha256, delta.bytes, patch, patchValidator, route)
                    requireNotCancelled()
                    require(sha256(patch) == delta.sha256) { "增量更新摘要不一致" }
                    _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在合成并校验安装包…")
                    reconstructApk(installedApk, patch, reconstructed, release.bytes, ::requireNotCancelled)
                    validateApk(reconstructed, release)
                    requireNotCancelled()
                    if (target.exists() && !target.delete()) throw IOException("无法替换旧安装包")
                    if (!reconstructed.renameTo(target)) throw IOException("无法保存安装包")
                    patch.delete()
                    patchValidator.delete()
                    _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = delta.bytes,
                        total = delta.bytes, message = "增量更新已就绪，安装后保留书架和阅读进度")
                    return@withContext ApplicationTransferResult.COMPLETED
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    requireNotCancelled()
                    patch.delete()
                    patchValidator.delete()
                    _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0,
                        total = release.bytes, message = "增量更新不可用，正在下载安装包…")
                } finally {
                    reconstructed.delete()
                    connection?.disconnect()
                    connection = null
                }
            }
            partial = incoming
            downloadUpdateAsset(release.url, release.sha256, release.bytes, incoming, validator, route)
            requireNotCancelled()
            _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在核对安装包…")
            try { validateApk(incoming, release) } catch (invalid: Exception) { incoming.delete(); throw invalid }
            requireNotCancelled()
            if (target.exists() && !target.delete()) throw IOException("无法替换旧安装包")
            if (!incoming.renameTo(target)) throw IOException("无法保存安装包")
            validator.delete()
            _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = release.bytes, total = release.bytes, message = "已准备好，安装后保留书架和阅读进度")
            ApplicationTransferResult.COMPLETED
        } catch (failure: Exception) {
            _state.value = _state.value.copy(
                phase = UpdatePhase.AVAILABLE,
                downloaded = partial?.length() ?: 0,
                message = if (cancelled.get() || failure is CancellationException) "下载已暂停，可继续下载"
                    else updateFailureMessage(failure, "下载失败，请重试"),
            )
            AndroidTransferIntents.update(context, task) {
                if (it.status == ApplicationTransferStatus.PAUSED) it else it.copy(status =
                    if (failure is CancellationException || cancelled.get()) ApplicationTransferStatus.WAITING_SYSTEM else ApplicationTransferStatus.FAILED)
            }
            if (failure is CancellationException) throw failure
            ApplicationTransferResult.FAILED
        } finally {
            try {
                if (_state.value.phase == UpdatePhase.READY) AndroidTransferIntents.update(context, task) {
                    if (it.status == ApplicationTransferStatus.PAUSED) it else it.copy(status = ApplicationTransferStatus.COMPLETED)
                }
            } finally {
                connection = null
                scheduledWork = null
                operation.set(false)
            }
        }
    }

    private fun downloadUpdateAsset(
        url: String, sha256: String, size: Long, incoming: File, validator: File, route: DownloadRouteSnapshot,
    ) {
        if (incoming.length() > size) incoming.delete()
        if (incoming.isFile && incoming.length() == size) return
        val saved = runCatching { JSONObject(validator.readText()) }.getOrNull()
        val tag = saved?.optString("etag")?.takeIf { it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
        val resumeMatches = tag != null && saved != null && saved.optString("sha256") == sha256 &&
            saved.optString("url") == url
        if (incoming.length() > 0L && !resumeMatches) { incoming.delete(); validator.delete() }
        val offset = incoming.length()
        if (root.usableSpace < size - offset + 16L * 1024 * 1024) throw IOException("存储空间不足，请清理后重试")
        requireNotCancelled()
        val active = open(url, offset, route, tag)
        connection = active
        val deadline = System.nanoTime() + 20L * 60 * 1_000_000_000
        try {
            val start = if (active.responseCode == 206) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(active.getHeaderField("Content-Range").orEmpty())
                    ?: throw IOException("更新服务返回了无效的续传信息")
                val end = range.groupValues[2].toLongOrNull()
                if (range.groupValues[1].toLongOrNull() != offset || end == null || end != size - 1 ||
                    range.groupValues[3].toLongOrNull() != size || offset > 0L &&
                    active.getHeaderField("ETag")?.let { it != tag } == true) {
                    throw IOException("更新服务返回了不匹配的续传信息")
                }
                offset
            } else 0L
            val currentTag = active.getHeaderField("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
                ?: tag?.takeIf { start > 0L }
            if (currentTag != null) {
                val atomic = AtomicFile(validator)
                val savedOutput = atomic.startWrite()
                try {
                    savedOutput.write(JSONObject().put("etag", currentTag).put("sha256", sha256)
                        .put("url", url).toString().toByteArray())
                    atomic.finishWrite(savedOutput)
                } catch (failure: Exception) { atomic.failWrite(savedOutput); throw failure }
            } else validator.delete()
            val advertised = active.getHeaderFieldLong("Content-Length", -1)
            if (advertised >= 0 && advertised != size - start) throw IOException("安装包大小与发布信息不一致")
            active.inputStream.use { input ->
                FileOutputStream(incoming, start > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = start
                    var lastEmission = 0L
                    while (true) {
                        requireNotCancelled()
                        if (System.nanoTime() > deadline) throw IOException("下载超时，请重试")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > size) throw IOException("下载内容超出声明大小")
                        output.write(buffer, 0, count)
                        val now = System.currentTimeMillis()
                        if (now - lastEmission >= 200 || received == size) {
                            _state.value = _state.value.copy(downloaded = received, total = size)
                            lastEmission = now
                        }
                    }
                    if (received != size) throw IOException("下载未完成，请重试")
                }
            }
        } finally {
            active.disconnect()
            connection = null
        }
    }

    internal suspend fun publishScheduledWait(task: ApplicationTransferIntent, reason: String, terminal: Boolean) = withContext(Dispatchers.IO) {
        if (!operation.compareAndSet(false, true)) return@withContext
        try {
            val release = verifyManifest(Base64.decode(task.payload.getString("envelope"), Base64.DEFAULT))
            AndroidTransferIntents.withCurrent(context, task) { owned ->
                if (owned.status !in setOf(ApplicationTransferStatus.PAUSED, ApplicationTransferStatus.COMPLETED)) {
                    queuedWork = task.workId
                    _state.value = UpdateState(if (terminal) UpdatePhase.AVAILABLE else UpdatePhase.DOWNLOADING,
                        release, total = release.bytes, message = reason)
                }
            }
        } catch (_: Exception) {
            AndroidTransferIntents.withCurrent(context, task) { owned ->
                if (owned.status !in setOf(ApplicationTransferStatus.PAUSED, ApplicationTransferStatus.COMPLETED))
                    _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = "后台传输状态未能恢复，请重新检查更新")
            }
        } finally { operation.set(false) }
    }

    internal fun stopScheduledDownload(workId: String) {
        if (scheduledWork == workId) { cancelled.set(true); connection?.disconnect() }
    }

    actual fun cancelDownload() {
        if (_state.value.phase != UpdatePhase.DOWNLOADING && _state.value.phase != UpdatePhase.VERIFYING) return
        val expected = scheduledWork ?: queuedWork ?: return
        cancelled.set(true)
        connection?.disconnect()
        scope.launch {
            try {
                AndroidTransferIntents.read(context, ApplicationTransferKind.UPDATE, "apk")?.takeIf { it.workId == expected }?.let {
                    AndroidTransferIntents.pause(context, it)
                }
                _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, message = "下载已暂停，可继续下载")
            } catch (_: Exception) {
                _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, message = "下载已停止，任务记录未能保存，请重新检查更新")
            }
        }
    }

    actual fun install(context: Context) {
        val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        if (activity == null) {
            _state.value = _state.value.copy(message = "请在应用窗口中继续安装")
            return
        }
        val expected = _state.value.release ?: return
        if (_state.value.phase != UpdatePhase.READY || !operation.compareAndSet(false, true)) return
        scope.launch {
            try {
                installRelease(activity, expected)
            } catch (failure: Exception) {
                _state.update { state ->
                    if (state.phase == UpdatePhase.READY && state.release == expected)
                        state.copy(message = failure.message ?: "无法打开安装程序，请重试") else state
                }
            } finally {
                operation.set(false)
            }
        }
    }

    private suspend fun installRelease(activity: Activity, expected: AppRelease) {
        try {
            val previous = pendingInstallRequest()
            if (previous != null && previous.versionCode != expected.versionCode) {
                withContext(Dispatchers.Main) {
                    showInstallRequest(activity, previous.id, previous.token, recoverOnly = true)
                }
                return
            }
            val release = verifyManifest(envelopeFor(expected))
            val current = installed()
            if (versionCode(current) >= release.versionCode) {
                replaceInstallReadyState(expected, current)
                return
            }
            if (previous?.sha256 != null && previous.sha256 != release.sha256) {
                withContext(Dispatchers.Main) {
                    showInstallRequest(activity, previous.id, previous.token, recoverOnly = true, cancelOnly = true)
                }
                return
            }
            val staged = previous?.takeIf { it.sha256 == release.sha256 }
            val apk = readyFile(release)
            if (staged == null) validateApk(apk, release)
            withContext(Dispatchers.Main) {
                if (activity.isFinishing || activity.isDestroyed) return@withContext
                if (!context.packageManager.canRequestPackageInstalls()) {
                    _state.value = _state.value.copy(message = "请允许 $APP_DISPLAY_NAME 安装应用，返回后再次点击安装")
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    // Session owns its copy; cache cleanup cannot revoke the installer's APK.
                    val sessionId = staged?.id ?: withContext(Dispatchers.IO) { prepareInstallSession(apk, release) }
                    if (activity.isFinishing || activity.isDestroyed) {
                        _state.value = _state.value.copy(message = "安装包已准备好，请返回应用后继续安装")
                        return@withContext
                    }
                    val token = staged?.token ?: preferences.getString("install_token", "").orEmpty()
                    if (showInstallRequest(activity, sessionId, token) && previous == null) {
                        _state.update { state ->
                            if (state.phase == UpdatePhase.READY && state.release == expected)
                                state.copy(message = "等待系统安装确认；安装结果将在此保留") else state
                        }
                    }
                }
            }
        } catch (unusable: UnusableApkBytes) {
            val previous = pendingInstallRequest()?.takeIf { it.versionCode == expected.versionCode }
            if (previous != null) {
                // A sealed session can only continue its original system confirmation.
                // An unverified legacy copy must not be committed from this entry.
                withContext(Dispatchers.Main) {
                    showInstallRequest(activity, previous.id, previous.token,
                        recoverOnly = true, cancelOnly = !previous.sealed)
                }
            } else replaceInstallReadyState(expected, installed(), unusable.message)
        }
    }

    private data class OwnedInstallRequest(
        val id: Int, val token: String, val versionCode: Long, val sha256: String?, val sealed: Boolean,
    )

    private fun pendingInstallRequest(): OwnedInstallRequest? {
        val request = synchronized(installLock) {
            val id = preferences.getInt("install_session", -1)
            val token = preferences.getString("install_token", "").orEmpty()
            val code = preferences.getLong("install_version", -1)
            if (!ownsInstallSession(id, token) || code <= 0 || preferences.getInt("install_status", -1) >= 0) return null
            OwnedInstallRequest(id, token, code, preferences.getString("install_sha256", null), sealed = false)
        }
        val session = context.packageManager.packageInstaller.getSessionInfo(request.id) ?: return null
        if (session.installerPackageName != context.packageName || session.appPackageName != context.packageName) return null
        return synchronized(installLock) {
            request.copy(sealed = session.isSealed).takeIf {
                ownsInstallSession(it.id, it.token) && preferences.getInt("install_status", -1) < 0
            }
        }
    }

    private fun showInstallRequest(activity: Activity, id: Int, token: String,
        recoverOnly: Boolean = false, cancelOnly: Boolean = false): Boolean {
        if (activity.isFinishing || activity.isDestroyed || installRequestTerminal(id, token)) return false
        activity.startActivity(Intent(activity, UpdateInstallActivity::class.java)
            .putExtra("session_id", id).putExtra("install_token", token)
            .putExtra("recover_only", recoverOnly).putExtra("cancel_only", cancelOnly))
        return true
    }

    private fun replaceInstallReadyState(expected: AppRelease, current: PackageInfo, unusable: String? = null) {
        _state.update { state ->
            if (state.phase != UpdatePhase.READY || state.release != expected) state
            else if (versionCode(current) >= expected.versionCode)
                UpdateState(UpdatePhase.CURRENT, message = "当前已安装 ${current.versionName}", lastChecked = state.lastChecked)
            else if (unusable != null) state.copy(phase = UpdatePhase.AVAILABLE, downloaded = 0,
                total = expected.bytes, message = unusable)
            else state
        }
    }

    private fun prepareInstallSession(apk: File, release: AppRelease): Int {
        val installer = context.packageManager.packageInstaller
        val previousIdentity = synchronized(installLock) {
            preferences.getInt("install_session", -1) to preferences.getString("install_token", "").orEmpty()
        }
        val previous = previousIdentity.first
        if (previous >= 0 && installer.getSessionInfo(previous) != null) {
            require(preferences.getLong("install_version", -1) == release.versionCode) {
                "另一个版本仍在系统安装器中，请先完成或取消它"
            }
            require(!installRequestTerminal(previous, previousIdentity.second)) {
                "系统正在处理上次安装请求的结果，请稍后重试"
            }
            return previous
        }
        if (previous >= 0) clearInstallConfirmation(previous, previousIdentity.second)
        // Session allocation may make Android reclaim our cache. Pin the source file
        // before that allocation and verify the exact bytes flowing to the session.
        return openUpdateApk(apk).use { input -> stageInstallSession(installer, input, release, previousIdentity) }
    }

    private fun stageInstallSession(installer: PackageInstaller, input: InputStream, release: AppRelease,
        previousIdentity: Pair<Int, String>): Int {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(release.bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, release.bytes).use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val copied = DigestInputStream(input, digest).copyTo(output)
                    if (copied != release.bytes || digest.digest().hex() != release.sha256)
                        throw UnusableApkBytes("安装包复制时校验不一致，请重新下载")
                    session.fsync(output)
                }
            }
            synchronized(installLock) {
                check(preferences.getInt("install_session", -1) == previousIdentity.first &&
                    preferences.getString("install_token", "").orEmpty() == previousIdentity.second) {
                    "安装请求已变化，请返回软件更新重试"
                }
                check(preferences.edit().putInt("install_session", id)
                    .putString("install_token", UUID.randomUUID().toString())
                    .putLong("install_version", release.versionCode).remove("install_status")
                    .putString("install_sha256", release.sha256)
                    .putString("install_result", "等待系统安装确认")
                    .putLong("install_result_version", release.versionCode).commit()) { "无法保存安装会话，请重试" }
                installationNotice = null
            }
            return id
        } catch (failure: Exception) {
            runCatching { installer.abandonSession(id) }
            throw failure
        }
    }

    internal fun ownsInstallSession(id: Int, token: String): Boolean = synchronized(installLock) {
        id >= 0 && token.isNotBlank() && preferences.getInt("install_session", -1) == id &&
            preferences.getString("install_token", null) == token
    }

    internal fun installationResult(id: Int, token: String, status: Int, detail: String?): String? = synchronized(installLock) {
        if (!ownsInstallSession(id, token)) return@synchronized null
        installedUpdateMessage(id, token)?.let { return@synchronized it }
        // Terminal receipts are idempotent. Delayed callbacks cannot reverse a completed request.
        if (preferences.getInt("install_status", -1) >= 0) return@synchronized installationDisplayMessage(id, token)
        if (status < 0 && status != PackageInstaller.STATUS_PENDING_USER_ACTION) return@synchronized null
        val code = preferences.getLong("install_version", -1)
        val label = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> "请在系统界面确认安装"
            PackageInstaller.STATUS_SUCCESS -> "系统已确认安装成功"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "安装已取消或被系统中止；安装包已保留，可重试"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足，安装包已保留；请腾出空间后重试"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装与已有应用冲突，旧应用和数据未删除"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "系统报告安装包与此设备不兼容"
            PackageInstaller.STATUS_FAILURE_INVALID -> "系统报告安装包无效，请重新下载"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统策略或安全检查阻止"
            else -> "系统安装失败，安装包和原有数据已保留"
        }
        val safeDetail = detail.orEmpty().filter { !it.isISOControl() }.take(500)
        val message = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION || status == PackageInstaller.STATUS_SUCCESS) label
            else "$label（状态 $status）" + safeDetail.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        val editor = preferences.edit().putString("install_result", message).putLong("install_result_version", code)
            .putInt("install_status", status)
        // Keep ownership even if disk persistence fails, so a later receipt/retry can recover.
        val saved = editor.commit()
        val visible = if (saved) message else "$message\n安装结果未能保存，关闭应用后可能无法恢复此提示"
        installationNotice = id to visible
        _state.value = if (status == PackageInstaller.STATUS_SUCCESS) UpdateState(UpdatePhase.CURRENT, message = visible,
            lastChecked = _state.value.lastChecked) else _state.value.copy(message = visible)
        installationEvents.value += 1
        return@synchronized visible
    }

    internal fun installationLaunchFailed(id: Int, token: String, detail: String): String? = synchronized(installLock) {
        if (!ownsInstallSession(id, token)) return@synchronized null
        installedUpdateMessage(id, token)?.let { return@synchronized it }
        if (preferences.getInt("install_status", -1) >= 0) return@synchronized installationDisplayMessage(id, token)
        // Never call an uncertain handoff a cancellation or installation failure.
        val message = "未能继续系统安装确认：${detail.take(300)}。安装结果未知，请返回软件更新重试"
        val saved = preferences.edit().putString("install_result", message)
            .putLong("install_result_version", preferences.getLong("install_version", -1)).commit()
        val visible = if (saved) message else "$message\n此提示未能保存；会话身份仍保留，可再次查看"
        installationNotice = id to visible
        _state.value = _state.value.copy(message = visible)
        installationEvents.value += 1
        return@synchronized visible
    }

    internal fun installedUpdateMessage(id: Int, token: String): String? = synchronized(installLock) {
        if (!ownsInstallSession(id, token)) return@synchronized null
        val requested = preferences.getLong("install_version", -1)
        val current = installed()
        if (requested <= 0 || versionCode(current) < requested) return@synchronized null
        clearInstallConfirmation(id, token)
        val message = "当前已安装 ${current.versionName}，无需重新安装"
        installationNotice = id to message
        _state.value = UpdateState(UpdatePhase.CURRENT, message = message, lastChecked = _state.value.lastChecked)
        return@synchronized message
    }

    internal fun installationDisplayMessage(id: Int, token: String): String? = synchronized(installLock) {
        if (!ownsInstallSession(id, token)) return@synchronized null
        installedUpdateMessage(id, token)?.let { return@synchronized it }
        return@synchronized installationNotice?.takeIf { it.first == id }?.second
            ?: preferences.getString("install_result", "等待系统安装结果")
    }

    internal fun installRequestTerminal(id: Int, token: String): Boolean = synchronized(installLock) {
        !ownsInstallSession(id, token) || installedUpdateMessage(id, token) != null ||
            preferences.getInt("install_status", -1) >= 0
    }

    internal fun receiveInstallationStatus(result: Intent): Unit = synchronized(installLock) {
        val id = result.getIntExtra("session_id", -1)
        val token = result.getStringExtra("install_token").orEmpty()
        if (!ownsInstallSession(id, token) || result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id ||
            !result.hasExtra(PackageInstaller.EXTRA_STATUS)) return@synchronized
        if (installRequestTerminal(id, token)) {
            clearInstallConfirmation(id, token)
            installationEvents.value += 1
            return@synchronized
        }
        val status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status < 0 && status != PackageInstaller.STATUS_PENDING_USER_ACTION) return@synchronized
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = if (Build.VERSION.SDK_INT >= 33)
                result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation == null) {
                installationLaunchFailed(id, token, "系统未返回安装确认界面")
                return@synchronized
            }
            // Held by the OS, not persisted as an unsafe parcel. Only a foreground user action
            // or the resumed installer activity can consume this one-shot capability.
            val pending = PendingIntent.getActivity(context, id,
                UpdateInstallActivity.confirmationIntent(context, id, token).putExtra(Intent.EXTRA_INTENT, confirmation),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            heldInstallConfirmation = Triple(id, token, pending)
            runCatching { notifyInstallConfirmation(pending) }
        } else {
            clearInstallConfirmation(id, token)
        }
        installationResult(id, token, status, result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    internal fun pendingInstallConfirmation(id: Int, token: String): PendingIntent? = synchronized(installLock) {
        if (installRequestTerminal(id, token)) return@synchronized null
        return@synchronized heldInstallConfirmation?.takeIf { it.first == id && it.second == token }?.third
            ?: PendingIntent.getActivity(context, id, UpdateInstallActivity.confirmationIntent(context, id, token),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
    }

    internal fun releaseInstallConfirmation(id: Int, token: String): Unit = synchronized(installLock) {
        if (heldInstallConfirmation?.let { it.first == id && it.second == token } == true) heldInstallConfirmation = null
    }

    private fun clearInstallConfirmation(id: Int, token: String): Unit = synchronized(installLock) {
        if (!ownsInstallSession(id, token)) return@synchronized
        heldInstallConfirmation?.takeIf { it.first == id && it.second == token }?.third?.cancel()
        releaseInstallConfirmation(id, token)
        PendingIntent.getActivity(context, id, UpdateInstallActivity.confirmationIntent(context, id, token),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)?.cancel()
        context.getSystemService(NotificationManager::class.java)?.cancel(JOB_ID + 1)
    }

    private fun notifyInstallConfirmation(pending: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(JOB_ID + 1, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("继续确认 $APP_DISPLAY_NAME 更新")
            .setContentText("轻触打开系统安装确认；不会自动安装")
            .setContentIntent(pending).setAutoCancel(true).build())
    }

    internal fun cancelInstallRequest(id: Int, token: String): String? {
        synchronized(installLock) {
            if (!ownsInstallSession(id, token)) return null
            installedUpdateMessage(id, token)?.let { return it }
            if (preferences.getInt("install_status", -1) >= 0) return installationDisplayMessage(id, token)
        }
        // This binder operation touches only the captured old session, never a replacement.
        context.packageManager.packageInstaller.abandonSession(id)
        synchronized(installLock) {
            if (!ownsInstallSession(id, token)) return null
            clearInstallConfirmation(id, token)
            return installationResult(id, token, PackageInstaller.STATUS_FAILURE_ABORTED,
                "用户取消此次安装请求，可返回软件更新重新安装")
        }
    }

    private fun installationMessage(code: Long, fallback: String): String = synchronized(installLock) {
        if (preferences.getLong("install_result_version", -1) != code) return@synchronized fallback
        val current = installed()
        // Self-update may kill the process before its callback. PackageManager is authoritative.
        if (versionCode(current) >= code) return@synchronized "当前已安装 ${current.versionName}"
        return@synchronized preferences.getString("install_result", null) ?: fallback
    }


    private suspend fun recover() {
        if (!operation.compareAndSet(false, true)) return
        try {
            installedUpdateMessage(preferences.getInt("install_session", -1),
                preferences.getString("install_token", "").orEmpty())
            val requested = preferences.getLong("install_result_version", -1)
            val currentVersion = versionCode(installed())
            if (requested > 0 && currentVersion >= requested) {
                _state.value = UpdateState(UpdatePhase.CURRENT,
                    message = "已确认当前安装版本 $currentVersion 达到此次更新版本",
                    lastChecked = _state.value.lastChecked)
            }
            val task = AndroidTransferIntents.read(context, ApplicationTransferKind.UPDATE, "apk")
            val accepted = task?.let { Base64.decode(it.payload.getString("envelope"), Base64.DEFAULT) }
            val acceptedRelease = accepted?.let(::verifyManifest)
            if (task != null && acceptedRelease != null && acceptedRelease.versionCode <= versionCode(installed())) {
                AndroidTransferIntents.update(context, task) { it.copy(status = ApplicationTransferStatus.COMPLETED) }
            }
            val ownedEnvelope = if (acceptedRelease != null && acceptedRelease.versionCode > versionCode(installed())) accepted else null
            val envelope = ownedEnvelope ?: if (metadata.baseFile.exists()) metadata.readFully() else return
            val release = verifyManifest(envelope)
            if (release.versionCode <= versionCode(installed())) {
                _state.value = UpdateState(UpdatePhase.CURRENT, message = installationMessage(versionCode(installed()), "已是最新版本"), lastChecked = _state.value.lastChecked)
                metadata.delete()
                root.listFiles()?.forEach { it.delete() }
                return
            }
            val ready = readyFile(release)
            val pending = pendingInstallRequest()?.takeIf { it.versionCode == release.versionCode }
            val valid = pending == null && ready.isFile && runCatching { validateApk(ready, release) }.isSuccess
            val ownedTask = task?.takeIf { it.payload.optString("sha256") == release.sha256 }
            _state.value = _state.value.copy(
                phase = if (valid || pending != null) UpdatePhase.READY else if (ownedTask?.resumable == true) UpdatePhase.DOWNLOADING else UpdatePhase.AVAILABLE,
                release = release,
                total = release.bytes,
                downloaded = if (valid) release.bytes else 0,
                message = if (pending != null) installationMessage(release.versionCode, "还有一个系统安装请求，可继续处理")
                    else if (valid) installationMessage(release.versionCode, "新版本已下载，可继续安装")
                    else ownedTask?.message?.takeIf { it.isNotBlank() }
                    ?: if (ownedTask?.resumable == true) "等待系统恢复后台下载" else "发现新版本 ${release.versionName}",
            )
        } catch (_: Exception) {
            runCatching { AndroidTransferIntents.read(context, ApplicationTransferKind.UPDATE, "apk")?.let { task ->
                AndroidTransferIntents.update(context, task) { if (it.resumable) it.copy(status = ApplicationTransferStatus.FAILED) else it }
            } }
            _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = "更新下载记录未能恢复，请重新检查更新")
        } finally {
            operation.set(false)
        }
    }

    private fun envelopeFor(expected: AppRelease): ByteArray {
        val task = AndroidTransferIntents.read(context, ApplicationTransferKind.UPDATE, "apk")
        val bytes = if (task != null && task.payload.optString("sha256") == expected.sha256 &&
            task.payload.optLong("versionCode") == expected.versionCode)
            Base64.decode(task.payload.getString("envelope"), Base64.DEFAULT) else metadata.readFully()
        val release = verifyManifest(bytes)
        require(release.sha256 == expected.sha256 && release.versionCode == expected.versionCode) {
            "更新任务已变化，请重新检查更新后再安装"
        }
        return bytes
    }

    private data class VerifiedManifest(val release: AppRelease, val deltas: List<ApkDelta>)

    private fun verifyManifest(envelope: ByteArray): AppRelease = readVerifiedManifest(envelope).release

    private fun installedBase(manifest: VerifiedManifest, current: PackageInfo): Pair<ApkDelta, File>? {
        val version = versionCode(current)
        val candidates = manifest.deltas.filter { it.baseVersionCode == version }
        if (candidates.isEmpty()) return null
        val source = current.applicationInfo?.sourceDir?.let(::File) ?: return null
        if (source.length() !in 1L..MAX_DELTA_APK_BYTES) return null
        val digest = try { sha256(source) } catch (_: IOException) { return null }
        val delta = candidates.firstOrNull { it.baseSha256 == digest } ?: return null
        return delta to source
    }

    private fun readVerifiedManifest(envelope: ByteArray): VerifiedManifest {
        require(envelope.size in 1..MAX_MANIFEST_BYTES) { "更新信息大小无效" }
        val wrapper = JSONObject(envelope.toString(Charsets.UTF_8))
        require(wrapper.getString("algorithm") == "SHA256withRSA") { "更新签名格式不支持" }
        val payload = Base64.decode(wrapper.getString("payload"), Base64.DEFAULT)
        val signature = Base64.decode(wrapper.getString("signature"), Base64.DEFAULT)
        require(payload.size <= 48 * 1024 && signature.size in 256..1024) { "更新签名大小无效" }
        val authenticated = signatures(installed()).any { certificateBytes ->
            val key = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificateBytes)).publicKey
            key is RSAPublicKey && key.modulus.bitLength() >= 2048 && Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        }
        if (!authenticated) throw SecurityException("更新签名不匹配，已停止更新")
        val data = JSONObject(payload.toString(Charsets.UTF_8))
        require(data.getInt("schema") == 1 && data.getString("packageName") == context.packageName) { "更新应用身份不匹配" }
        val apk = data.getJSONObject("apk")
        val code = data.getLong("versionCode")
        val name = data.getString("versionName")
        val notes = data.optString("notes")
        val bytes = apk.getLong("bytes")
        val hash = apk.getString("sha256")
        val url = apk.getString("url")
        val minSdk = data.getInt("minSdk")
        require(code in 1..2_100_000_000L && name.length in 1..80 && notes.length <= 6000) { "更新版本信息无效" }
        require(bytes in 1..MAX_APK_BYTES && hash.matches(Regex("[a-f0-9]{64}"))) { "更新安装包信息无效" }
        require(minSdk in 26..100) { "更新系统要求无效" }
        val parsed = Uri.parse(url)
        require(parsed.scheme == "https" && parsed.host == "github.com" && parsed.userInfo == null && parsed.fragment == null && parsed.query == null && parsed.path?.startsWith("/$REPOSITORY/releases/download/") == true && parsed.path?.endsWith(".apk") == true) { "更新下载地址无效" }
        val release = AppRelease(code, name, notes, url, hash, bytes, minSdk, data.optString("sourceCommit"))
        val entries = data.optJSONArray("apkDeltas")
        val deltas = if (entries == null || entries.length() !in 1..8 || bytes > MAX_DELTA_APK_BYTES ||
            !name.matches(Regex("0[.]1[.][0-9]+"))) emptyList() else (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val baseCode = entry.optLong("baseVersionCode")
            val baseSha = entry.optString("baseSha256")
            val deltaSha = entry.optString("sha256")
            val deltaBytes = entry.optLong("bytes")
            val base = baseCode - 10_000
            val deltaUrl = "https://github.com/$REPOSITORY/releases/download/v$name/MangaFlow-0.1.$base-to-$name.apk.delta"
            if (entry.optString("format") != "mfd1" || baseCode !in 10_001L until code ||
                !baseSha.matches(Regex("[a-f0-9]{64}")) || !deltaSha.matches(Regex("[a-f0-9]{64}")) ||
                deltaBytes !in 1L until bytes || entry.optString("url") != deltaUrl) return@mapNotNull null
            ApkDelta(baseCode, baseSha, deltaUrl, deltaBytes, deltaSha)
        }
        return VerifiedManifest(release, deltas)
    }

    private class UnusableApkBytes(message: String) : IOException(message)

    private fun openUpdateApk(file: File) = try {
        file.inputStream()
    } catch (failure: FileNotFoundException) {
        if (!file.isFile) throw UnusableApkBytes("已下载的安装包不再可用，请重新下载")
        throw failure
    }

    private fun validateApk(file: File, release: AppRelease) {
        if (!file.isFile) throw UnusableApkBytes("已下载的安装包不再可用，请重新下载")
        if (file.length() != release.bytes) throw UnusableApkBytes("安装包不完整，请重新下载")
        if (openUpdateApk(file).use { sha256(it) } != release.sha256)
            throw UnusableApkBytes("安装包摘要不匹配，请重新下载")
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags()) ?: run {
            if (!file.isFile) throw UnusableApkBytes("已下载的安装包不再可用，请重新下载")
            throw IOException("无法读取安装包")
        }
        val current = installed()
        require(candidate.packageName == context.packageName && versionCode(candidate) == release.versionCode && candidate.versionName == release.versionName) { "安装包版本或应用身份不匹配" }
        val candidateVersion = versionCode(candidate)
        val installedVersion = versionCode(current)
        require(candidateVersion > installedVersion) {
            if (candidateVersion == installedVersion) "此更新版本已安装"
            else "安装包版本低于当前版本，已阻止降级；请重新检查更新"
        }
        val before = signatures(current).map { it.toList() }.toSet()
        val after = signatures(candidate).map { it.toList() }.toSet()
        require(before.isNotEmpty() && before == after) { "安装包签名与当前应用不一致" }
    }

    @Suppress("DEPRECATION")
    private fun installed(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<ByteArray> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toByteArray() }.orEmpty()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun readyFile(release: AppRelease): File = File(root, release.sha256 + ".apk")

    private fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun readBytes(url: String, limit: Int, route: DownloadRouteSnapshot): ByteArray {
        val active = open(url, route = route)
        connection = active
        return try {
            active.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) throw IOException("更新信息过大")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            active.disconnect()
        }
    }

    private fun open(initial: String, offset: Long = 0, route: DownloadRouteSnapshot, validator: String? = null): HttpURLConnection {
        var url = URL(initial)
        repeat(6) {
            require(url.protocol == "https" && url.userInfo == null && url.port in setOf(-1, 443) && url.host in ALLOWED_HOSTS) { "更新服务重定向地址无效" }
            val active = route.openDownloadConnection(url).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "MangaFlow-Android")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0 && validator != null) {
                    setRequestProperty("Range", "bytes=$offset-")
                    setRequestProperty("If-Range", validator)
                }
                useCaches = false
            }
            connection = active
            if (scheduledWork != null) requireNotCancelled()
            val status = try { active.responseCode } catch (failure: Exception) { active.disconnect(); throw failure }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = active.getHeaderField("Location")
                active.disconnect()
                if (location.isNullOrBlank()) throw IOException("更新服务返回了无效跳转")
                url = URL(url, location)
            } else {
                if (status !in 200..299) {
                    active.disconnect()
                    throw IOException(when (status) {
                        404 -> "还没有可下载的新版本"
                        403, 429 -> "更新服务暂时繁忙，请稍后重试"
                        else -> "更新服务暂不可用（$status）"
                    })
                }
                return active
            }
        }
        throw IOException("更新服务跳转次数过多")
    }

    private fun requireNotCancelled() {
        if (cancelled.get()) throw CancellationException("下载已取消")
    }

    private fun updateFailureMessage(failure: Exception, fallback: String): String {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        return when {
            causes.any { it is UnknownHostException } -> "无法连接更新服务，请切换网络或检查代理后重试"
            causes.any { it is SocketTimeoutException } -> "连接更新服务超时，请稍后重试"
            causes.any { it is ConnectException || it is NoRouteToHostException } -> "更新服务暂时连接不上，请切换网络后重试"
            causes.any { it is SSLException } -> "更新服务安全连接失败，请稍后重试"
            else -> failure.message ?: fallback
        }
    }

    @Synchronized
    private fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!automatic) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CHECK_INTERVAL)
                .setPersisted(true)
                .setRequiresBatteryNotLow(true)
                .build(),
        )
    }

    private fun notifyAvailable(release: AppRelease) {
        if (preferences.getLong("last_notified", 0) == release.versionCode) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.putExtra("open_updates", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, JOB_ID, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            JOB_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("$APP_DISPLAY_NAME ${release.versionName} 已发布")
                .setContentText("轻触查看更新内容并下载安装")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
        preferences.edit().putLong("last_notified", release.versionCode).apply()
    }

    private fun complete(callback: (() -> Unit)?) {
        if (callback != null) Handler(Looper.getMainLooper()).post { callback() }
    }

    actual companion object {
        actual const val REPOSITORY = "wozhenbang2004/MangaFlow"
        actual const val MANIFEST_URL = "https://github.com/$REPOSITORY/releases/latest/download/latest.json"
        private const val CHECK_INTERVAL = 6L * 60 * 60 * 1000
        private const val MAX_MANIFEST_BYTES = 96 * 1024
        private const val MAX_DELTA_APK_BYTES = 32L * 1024 * 1024
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val JOB_ID = 84217
        private const val CHANNEL = "app_updates"
        private val ALLOWED_HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        @Volatile private var instance: UpdateManager? = null

        actual fun get(context: Context): UpdateManager = instance ?: synchronized(this) {
            instance ?: UpdateManager(context.applicationContext).also { instance = it }
        }
    }
}
