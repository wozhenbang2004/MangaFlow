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
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import androidx.core.content.FileProvider
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.openDownloadConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
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

    actual val automatic: Boolean get() = preferences.getBoolean("automatic", true)

    actual fun start() {
        schedule()
        if (!started.compareAndSet(false, true)) return
        scope.launch {
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
        schedule()
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
                val release = verifyManifest(bytes)
                val now = System.currentTimeMillis()
                if (release.versionCode <= versionCode(installed())) {
                    metadata.delete()
                    _state.value = UpdateState(UpdatePhase.CURRENT, message = "已是最新版本", lastChecked = now)
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
                    val cached = ready.isFile && runCatching { validateApk(ready, release) }.isSuccess
                    _state.value = UpdateState(
                        phase = if (cached) UpdatePhase.READY else UpdatePhase.AVAILABLE,
                        release = release,
                        downloaded = if (cached) release.bytes else 0,
                        total = release.bytes,
                        message = if (cached) "新版本已下载，可继续安装" else "发现新版本 ${release.versionName}",
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
            val release = verifyManifest(Base64.decode(task.payload.getString("envelope"), Base64.DEFAULT))
            require(release.sha256 == task.payload.getString("sha256") && release.versionCode == task.payload.getLong("versionCode"))
            _state.value = UpdateState(UpdatePhase.DOWNLOADING, release, total = release.bytes, message = "正在后台下载安装包…")
            require(release.versionCode > versionCode(installed())) { "此版本已安装，请重新检查更新" }
            if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建更新目录")
            val target = readyFile(release)
            if (target.isFile && runCatching { validateApk(target, release) }.isSuccess) {
                _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = release.bytes, total = release.bytes, message = "新版本已下载，可继续安装")
                return@withContext ApplicationTransferResult.COMPLETED
            }
            val incoming = File(root, release.sha256 + ".part")
            val validator = File(root, release.sha256 + ".validator")
            partial = incoming
            root.listFiles()?.filter { it.isFile && it != target && it != incoming && it != validator }?.forEach { it.delete() }
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
            val saved = runCatching { JSONObject(validator.readText()) }.getOrNull()
            val tag = saved?.optString("etag")?.takeIf { it.startsWith('"') && it.endsWith('"') && !it.startsWith("W/") }
            val resumeMatches = tag != null && saved != null && saved.optString("sha256") == release.sha256 &&
                saved.optString("url") == release.url
            if (incoming.length() > 0L && !resumeMatches) { incoming.delete(); validator.delete() }
            val offset = incoming.length()
            if (root.usableSpace < release.bytes - offset + 16L * 1024 * 1024) throw IOException("存储空间不足，请清理后重试")
            requireNotCancelled()
            val active = open(release.url, offset, route, tag)
            connection = active
            val deadline = System.nanoTime() + 20L * 60 * 1_000_000_000
            try {
                val start = if (active.responseCode == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(active.getHeaderField("Content-Range").orEmpty())
                        ?: throw IOException("更新服务返回了无效的续传信息")
                    val end = range.groupValues[2].toLongOrNull()
                    if (range.groupValues[1].toLongOrNull() != offset || end == null || end != release.bytes - 1 ||
                        range.groupValues[3].toLongOrNull() != release.bytes || offset > 0L &&
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
                        savedOutput.write(JSONObject().put("etag", currentTag).put("sha256", release.sha256)
                            .put("url", release.url).toString().toByteArray())
                        atomic.finishWrite(savedOutput)
                    } catch (failure: Exception) { atomic.failWrite(savedOutput); throw failure }
                } else validator.delete()
                val advertised = active.getHeaderFieldLong("Content-Length", -1)
                if (advertised >= 0 && advertised != release.bytes - start) throw IOException("安装包大小与发布信息不一致")
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
                            if (received > release.bytes) throw IOException("下载内容超出声明大小")
                            output.write(buffer, 0, count)
                            val now = System.currentTimeMillis()
                            if (now - lastEmission >= 200 || received == release.bytes) {
                                _state.value = _state.value.copy(downloaded = received, total = release.bytes)
                                lastEmission = now
                            }
                        }
                        if (received != release.bytes) throw IOException("下载未完成，请重试")
                    }
                }
            } finally {
                active.disconnect()
                connection = null
            }
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
                val release = verifyManifest(envelopeFor(expected))
                val apk = readyFile(release)
                validateApk(apk, release)
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        _state.value = _state.value.copy(message = "请允许漫流安装应用，返回后再次点击安装")
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        _state.value = _state.value.copy(message = "请在系统界面确认安装，取消后仍可继续")
                    }
                }
            } catch (failure: Exception) {
                _state.value = _state.value.copy(message = failure.message ?: "无法打开安装程序，请重试")
            } finally {
                operation.set(false)
            }
        }
    }

    private suspend fun recover() {
        if (!operation.compareAndSet(false, true)) return
        try {
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
                metadata.delete()
                root.listFiles()?.forEach { it.delete() }
                return
            }
            val ready = readyFile(release)
            val valid = ready.isFile && runCatching { validateApk(ready, release) }.isSuccess
            val ownedTask = task?.takeIf { it.payload.optString("sha256") == release.sha256 }
            _state.value = _state.value.copy(
                phase = if (valid) UpdatePhase.READY else if (ownedTask?.resumable == true) UpdatePhase.DOWNLOADING else UpdatePhase.AVAILABLE,
                release = release,
                total = release.bytes,
                downloaded = if (valid) release.bytes else 0,
                message = if (valid) "新版本已下载，可继续安装" else ownedTask?.message?.takeIf { it.isNotBlank() }
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

    private fun verifyManifest(envelope: ByteArray): AppRelease {
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
        return AppRelease(code, name, notes, url, hash, bytes, minSdk, data.optString("sourceCommit"))
    }

    private fun validateApk(file: File, release: AppRelease) {
        require(file.isFile && file.length() == release.bytes) { "安装包不完整，请重新下载" }
        require(sha256(file) == release.sha256) { "安装包摘要不匹配，请重新下载" }
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags()) ?: throw IOException("无法读取安装包")
        val current = installed()
        require(candidate.packageName == context.packageName && versionCode(candidate) == release.versionCode && candidate.versionName == release.versionName) { "安装包版本或应用身份不匹配" }
        require(versionCode(candidate) > versionCode(current)) { "此更新版本已安装" }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

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
                .setContentTitle("漫流 ${release.versionName} 已发布")
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
