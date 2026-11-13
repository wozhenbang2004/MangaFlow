package dev.mangaflow.reader.panels

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

enum class PanelModelId { NANO, HIGH_QUALITY, ORDER }

data class PanelModelState(
    val installed: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
)

data class PanelModelSpec(
    val id: PanelModelId,
    val title: String,
    val description: String,
    val sizeLabel: String,
    val license: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val bytes: Long,
    val sha256: String,
    val revision: String,
    internal val fileName: String,
)

/** Models are optional private files. Constructing this store never starts a network request. */
class PanelModelStore private constructor(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, "panel-models")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Any()
    private val downloads = mutableMapOf<PanelModelId, Job>()
    private val calls = mutableMapOf<PanelModelId, Call>()
    private val removing = mutableSetOf<PanelModelId>()
    private val ready = CompletableDeferred<Unit>()
    private val mutableStates = MutableStateFlow(
        SPECS.mapValues { (_, spec) -> PanelModelState(totalBytes = spec.bytes) },
    )
    val states: StateFlow<Map<PanelModelId, PanelModelState>> = mutableStates.asStateFlow()
    val revisionIdentity: String = SPECS.values.joinToString("|") { "${it.id}:${it.sha256}" }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            val trusted = url.host == "huggingface.co" ||
                url.host.endsWith(".huggingface.co") || url.host.endsWith(".hf.co")
            if (url.scheme != "https" || !trusted) throw ModelDownloadFailure("模型下载地址不受信任")
            chain.proceed(chain.request())
        }
        .build()

    init {
        scope.launch {
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("model directory")
                for ((id, spec) in SPECS) {
                    partFile(spec).delete()
                    val file = File(directory, spec.fileName)
                    val valid = file.isFile && file.length() == spec.bytes && checksum(file) == spec.sha256
                    if (file.exists() && !valid) file.delete()
                    mutableStates.update { old ->
                        old + (id to old.getValue(id).copy(
                            installed = valid,
                            bytes = if (valid) spec.bytes else 0,
                        ))
                    }
                }
            } catch (_: IOException) {
                // Read-only or unavailable storage is reported if a download is requested.
            } finally {
                ready.complete(Unit)
            }
        }
    }

    fun spec(id: PanelModelId): PanelModelSpec = SPECS.getValue(id)

    fun modelFile(id: PanelModelId): File? {
        if (states.value[id]?.installed != true) return null
        val spec = spec(id)
        return File(directory, spec.fileName).takeIf { it.isFile && it.length() == spec.bytes }
    }

    internal suspend fun installedFile(id: PanelModelId): File {
        ready.await()
        return modelFile(id) ?: throw IllegalStateException("请先下载${spec(id).title}")
    }

    /** Explicit UI action only. The store owns the job so closing settings does not cancel it. */
    suspend fun download(id: PanelModelId) {
        synchronized(control) {
            if (id in removing || downloads.containsKey(id) || modelFile(id) != null) return
            val job = scope.launch(start = CoroutineStart.LAZY) { downloadModel(id) }
            downloads[id] = job
            job.invokeOnCompletion {
                // Cancellation can win before the coroutine body is entered, so its finally
                // block alone cannot own cleanup of the registered job and UI state.
                synchronized(control) {
                    if (downloads[id] === job) {
                        downloads.remove(id)
                        calls.remove(id)?.cancel()
                        partFile(spec(id)).delete()
                        mutableStates.update { old ->
                            old + (id to old.getValue(id).copy(
                                downloading = false, progress = null, bytes = 0, error = null,
                            ))
                        }
                    }
                }
            }
            mutableStates.update { old ->
                old + (id to old.getValue(id).copy(downloading = true, progress = 0f, error = null))
            }
            job.start()
        }
    }

    fun cancel(id: PanelModelId) {
        synchronized(control) {
            val call = calls[id]
            downloads[id]?.cancel()
            call?.cancel()
        }
    }

    suspend fun remove(id: PanelModelId) = withContext(NonCancellable + Dispatchers.IO) {
        val job = synchronized(control) {
            if (!removing.add(id)) return@withContext
            calls[id]?.cancel()
            downloads[id]?.also { it.cancel() }
        }
        try {
            ready.await()
            job?.cancelAndJoin()
            val spec = spec(id)
            partFile(spec).delete()
            val file = File(directory, spec.fileName)
            val removed = !file.exists() || file.delete()
            mutableStates.update { old ->
                old + (id to PanelModelState(
                    installed = !removed,
                    error = if (removed) null else "暂时无法删除模型，请稍后重试",
                    bytes = if (removed) 0 else spec.bytes,
                    totalBytes = spec.bytes,
                ))
            }
        } finally {
            synchronized(control) { removing.remove(id) }
        }
    }

    private suspend fun downloadModel(id: PanelModelId) {
        val spec = spec(id)
        val ownJob = currentCoroutineContext()[Job]
        val part = partFile(spec)
        var installed = false
        var error: String? = null
        try {
            ready.await()
            currentCoroutineContext().ensureActive()
            if (modelFile(id) != null) {
                installed = true
                return
            }
            if (!directory.isDirectory && !directory.mkdirs()) throw ModelDownloadFailure("无法创建模型目录")
            if (directory.usableSpace < spec.bytes + 8L * 1024 * 1024) {
                throw ModelDownloadFailure("存储空间不足，请先腾出 ${spec.sizeLabel}")
            }
            val call = client.newCall(Request.Builder().url(spec.downloadUrl).build())
            synchronized(control) { calls[id] = call }
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                if (!response.isSuccessful) throw ModelDownloadFailure("模型下载失败（${response.code}），可重试")
                val body = response.body ?: throw ModelDownloadFailure("模型下载内容为空")
                if (body.contentLength() > spec.bytes) throw ModelDownloadFailure("模型文件大小不符")
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var lastProgress = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > spec.bytes) throw ModelDownloadFailure("模型文件大小不符")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 100L) {
                                lastProgress = now
                                mutableStates.update { old ->
                                    old + (id to old.getValue(id).copy(
                                        progress = received.toFloat() / spec.bytes,
                                        bytes = received,
                                    ))
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (received != spec.bytes || digest.digest().hex() != spec.sha256) {
                    throw ModelDownloadFailure("模型校验失败，请重新下载")
                }
                currentCoroutineContext().ensureActive()
                // Same-directory POSIX rename installs the verified bytes atomically on Android.
                if (!part.renameTo(File(directory, spec.fileName))) throw ModelDownloadFailure("模型保存失败，请重试")
                installed = true
            }
        } catch (_: CancellationException) {
            // A cancelled download has no error badge and leaves no partial model behind.
        } catch (failure: Exception) {
            error = if (!currentCoroutineContext().isActive) null else when (failure) {
                is ModelDownloadFailure -> failure.message
                is SocketTimeoutException -> "模型下载超时，可重试"
                is UnknownHostException -> "无法连接模型站点，请检查网络后重试"
                is SSLException -> "模型站点安全连接失败，可稍后重试"
                else -> "模型下载失败，请检查网络或存储空间后重试"
            }
        } finally {
            part.delete()
            synchronized(control) {
                if (downloads[id] === ownJob) {
                    downloads.remove(id)
                    calls.remove(id)
                    mutableStates.update { old ->
                        old + (id to PanelModelState(
                            installed = installed,
                            progress = if (installed) 1f else null,
                            error = error,
                            bytes = if (installed) spec.bytes else 0,
                            totalBytes = spec.bytes,
                        ))
                    }
                }
            }
        }
    }

    private fun partFile(spec: PanelModelSpec) = File(directory, "${spec.fileName}.part")

    private suspend fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private class ModelDownloadFailure(message: String) : IOException(message)

    companion object {
        @Volatile private var instance: PanelModelStore? = null

        fun get(context: Context): PanelModelStore = instance ?: synchronized(this) {
            instance ?: PanelModelStore(context).also { instance = it }
        }

        private val SPECS = listOf(
            PanelModelSpec(
                id = PanelModelId.NANO,
                title = "轻量 · YOLO26 Nano",
                description = "本机识别，适合手机；需单独下载。模型为 AGPL-3.0，来源及条款见许可说明。",
                sizeLabel = "2.71 MiB",
                license = "AGPL-3.0 · Manga109-s 训练来源",
                sourceUrl = "https://huggingface.co/leoxs22/manga-panel-detector-yolo26n",
                downloadUrl = "https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/40a2854663d537563cfb95c370288a84c6505b9a/manga_panel_detector_int8.tflite",
                bytes = 2_837_823,
                sha256 = "b1a7d8d4492e04a777ae0d3efd9dc1fbd6e8f361971eadb813279ce3dfd1b464",
                revision = "40a2854663d537563cfb95c370288a84c6505b9a",
                fileName = "yolo26-nano-40a28546.tflite",
            ),
            PanelModelSpec(
                id = PanelModelId.HIGH_QUALITY,
                title = "高精度 · Koharu 1152",
                description = "本机 CPU 识别，内存与耗电较高。旧版 1152 模型采用自定义条款，保留 Manga109 训练数据声明；不是新版 HighRes 1920。",
                sizeLabel = "141.57 MiB",
                license = "自定义 / other · 请阅读模型与 Manga109 条款",
                sourceUrl = "https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx",
                downloadUrl = "https://huggingface.co/ShiniShiho/koharu-layout-rfdetr-seg-2xl-1152-onnx/resolve/bfbbd4e5ab34a50459865074fa044da496cebb57/rfdetr-seg-2xlarge.onnx",
                bytes = 148_442_003,
                sha256 = "7cc10d4316371946b8441da3512261a8e148b129abcdb0ea6235ed1d1d06d351",
                revision = "bfbbd4e5ab34a50459865074fa044da496cebb57",
                fileName = "koharu-1152-bfbbd4e5.onnx",
            ),
            PanelModelSpec(
                id = PanelModelId.ORDER,
                title = "智能顺序 · Inkwell",
                description = "仅根据分镜位置预测顺序，不识别画面内容；可配合任意识别模式，支持手动左右阅读方向。",
                sizeLabel = "101 KiB",
                license = "MIT · 仅顺序模型，不含其 AGPL 检测模型",
                sourceUrl = "https://huggingface.co/cedarrapidsboy/inkwell-panel-models",
                downloadUrl = "https://huggingface.co/cedarrapidsboy/inkwell-panel-models/resolve/fa7afba52ba7dc7b0840c7702b767972b5e17a9b/panel-order-model.onnx",
                bytes = 103_294,
                sha256 = "84eb78fc5ec31a3e986a7a16ce10f7baa657ef5c5b4a98877b40aa9354ff3464",
                revision = "fa7afba52ba7dc7b0840c7702b767972b5e17a9b",
                fileName = "inkwell-order-fa7afba5.onnx",
            ),
        ).associateBy { it.id }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
