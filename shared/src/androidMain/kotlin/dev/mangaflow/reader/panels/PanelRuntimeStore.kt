package dev.mangaflow.reader.panels

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.javaProxy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.tensorflow.lite.TensorFlowLite

/**
 * Optional, same-signer native libraries. Neither construction nor ordinary reading downloads or
 * loads code. A signed receipt is rechecked against the installed APK and every library on startup.
 * Native libraries cannot be unloaded: a process which attempted a load must restart before removal.
 */
actual class PanelRuntimeStore private constructor(context: Context) {
    private val context = context.applicationContext
    private val appPackageInfo get() = this.context.packageManager.getPackageInfo(this.context.packageName, 0)
    private val appVersionName: String get() = appPackageInfo.versionName.orEmpty()
    private val appVersionCode: Long get() = if (Build.VERSION.SDK_INT >= 28) appPackageInfo.longVersionCode else {
        @Suppress("DEPRECATION")
        appPackageInfo.versionCode.toLong()
    }
    private val directory = File(this.context.noBackupFilesDir, "panel-runtimes")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Any()
    private val loadGate = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val downloads = mutableMapOf<PanelRuntimeId, Job>()
    private val calls = mutableMapOf<PanelRuntimeId, Call>()
    private val removing = mutableSetOf<PanelRuntimeId>()
    private val installed = mutableMapOf<PanelRuntimeId, Installation>()
    private val touched = mutableSetOf<PanelRuntimeId>()
    private val loadFailures = mutableMapOf<PanelRuntimeId, String>()

    actual val abi: String? = Build.SUPPORTED_ABIS.firstOrNull {
        it in ABIS && (it in ABIS_64) == Process.is64Bit()
    }
    private val mutableStates = MutableStateFlow(PanelRuntimeId.entries.associateWith {
        PanelRuntimeState(error = if (abi == null) "当前应用进程的架构暂不支持可选运行库" else null)
    })
    actual val states: StateFlow<Map<PanelRuntimeId, PanelRuntimeState>> = mutableStates.asStateFlow()

    init {
        scope.launch {
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("runtime directory")
                directory.listFiles()?.filter { it.name.startsWith(".staging-") }?.forEach { deleteTree(it) }
                for (id in PanelRuntimeId.entries) {
                    // An explicit removal outlives the process which still owns dlopen handles.
                    val marker = removalMarker(id)
                    if (marker.exists()) {
                        val removed = runtimeDirectories(id).map { deleteTree(it) }.all { it }
                        val finished = removed && marker.delete()
                        mutableStates.update { old -> old + (id to PanelRuntimeState(
                            pendingRemoval = !finished,
                            error = if (finished) null else "运行库暂时无法删除，请完全退出应用后重试",
                        )) }
                        continue
                    }
                    var invalid = false
                    for (candidate in runtimeDirectories(id)) {
                        try {
                            val envelope = readLocalReceipt(candidate)
                            val entry = verifyCatalog(envelope, currentRelease = false).single {
                                it.id == id && it.abi == abi
                            }
                            require(candidate.name == entry.directoryName) { "运行库目录身份不匹配" }
                            verifyLibraries(candidate, entry, seal = true)
                            synchronized(control) { installed[id] = Installation(candidate, entry) }
                            mutableStates.update { old -> old + (id to PanelRuntimeState(
                                installed = true, bytes = entry.archiveBytes, totalBytes = entry.archiveBytes,
                            )) }
                            break
                        } catch (_: Exception) {
                            invalid = true
                            deleteTree(candidate)
                        }
                    }
                    if (invalid && states.value.getValue(id).installed.not() && abi != null) updateError(id, "运行库校验失败，请重新下载")
                }
            } catch (_: Exception) {
                PanelRuntimeId.entries.forEach { updateError(it, "运行库存储暂不可用，请检查可用空间") }
            } finally { ready.complete(Unit) }
        }
    }

    actual fun spec(id: PanelRuntimeId): PanelRuntimeSpec = SPECS.getValue(id)

    /** The store owns the transfer so closing a sheet does not cancel an explicit download. */
    actual suspend fun download(id: PanelRuntimeId) {
        synchronized(control) {
            if (id in removing || downloads.containsKey(id)) return
            if (states.value.getValue(id).pendingRemoval) { updateError(id, "运行库已安排在重启后删除"); return }
            if (id in touched) { updateError(id, "运行库已在本次启动中使用，请完全退出应用后再管理"); return }
            if (installed.containsKey(id)) return
            if (abi == null) { updateError(id, "当前应用进程的架构暂不支持可选运行库"); return }
            val job = scope.launch(start = CoroutineStart.LAZY) { downloadRuntime(id) }
            downloads[id] = job
            job.invokeOnCompletion {
                synchronized(control) {
                    // Cancellation may win before the coroutine body starts.
                    if (downloads[id] === job) {
                        downloads.remove(id)
                        calls.remove(id)?.cancel()
                        deleteTree(staging(id))
                        mutableStates.update { old -> old + (id to old.getValue(id).copy(
                            downloading = false, progress = null,
                            bytes = if (old.getValue(id).installed) old.getValue(id).totalBytes else 0,
                        )) }
                    }
                }
            }
            mutableStates.update { old -> old + (id to old.getValue(id).copy(downloading = true, progress = 0f, error = null)) }
            job.start()
        }
    }

    actual fun cancel(id: PanelRuntimeId) {
        synchronized(control) {
            val call = calls[id]
            downloads[id]?.cancel()
            call?.cancel()
        }
    }

    actual suspend fun remove(id: PanelRuntimeId) = withContext(NonCancellable + Dispatchers.IO) {
        val job = synchronized(control) {
            if (id in touched) { markPendingRemoval(id); return@withContext }
            if (!removing.add(id)) return@withContext
            calls[id]?.cancel()
            downloads[id]?.also { it.cancel() }
        }
        try {
            ready.await()
            job?.cancelAndJoin()
            loadGate.withLock {
                if (synchronized(control) { id in touched }) {
                    markPendingRemoval(id)
                    return@withLock
                }
                deleteTree(staging(id))
                val filesRemoved = runtimeDirectories(id).map { deleteTree(it) }.all { it }
                val marker = removalMarker(id)
                val removed = filesRemoved && (!marker.exists() || marker.delete())
                if (removed) synchronized(control) { installed.remove(id) }
                mutableStates.update { old -> old + (id to if (removed) PanelRuntimeState()
                    else old.getValue(id).copy(pendingRemoval = marker.exists(),
                        error = "运行库暂时无法删除，请完全退出应用后重试")) }
            }
        } finally { synchronized(control) { removing.remove(id) } }
    }

    /** Must finish before the caller initializes the selected native component. */
    suspend fun ensureLoaded(id: PanelRuntimeId): Unit = withContext(Dispatchers.IO) {
        ready.await()
        loadGate.withLock {
            if (states.value.getValue(id).loaded) return@withLock
            val installation = synchronized(control) {
                loadFailures[id]?.let { throw RuntimeFailure(it) }
                if (states.value.getValue(id).pendingRemoval) throw RuntimeFailure("运行库已安排在重启后删除")
                if (id in removing || downloads.containsKey(id)) throw RuntimeFailure("运行库正在管理，请稍后重试")
                installed[id] ?: throw RuntimeFailure("请先下载${spec(id).title}")
            }
            try {
                verifyLibraries(installation.directory, installation.entry, seal = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                synchronized(control) { installed.remove(id) }
                mutableStates.update { old -> old + (id to old.getValue(id).copy(installed = false,
                    error = "运行库校验失败，请删除后重新下载")) }
                throw RuntimeFailure("运行库校验失败，请删除后重新下载")
            }
            currentCoroutineContext().ensureActive()
            // Once the first dlopen starts, cancellation cannot undo it. Finish or mark restart required.
            withContext(NonCancellable) {
                synchronized(control) { touched.add(id) }
                try {
                    val root = installation.directory.canonicalFile
                    when (id) {
                        PanelRuntimeId.LITERT -> {
                            System.load(File(root, "libtensorflowlite_jni.so").absolutePath)
                            TensorFlowLite.init()
                        }
                        PanelRuntimeId.ONNX -> {
                            // JNI_OnLoad may resolve Java bridge classes, so their trusted path exists first.
                            System.setProperty("mangaflow.onnx.native.dir", root.absolutePath)
                            System.load(File(root, "libonnxruntime.so").absolutePath)
                            System.load(File(root, "libonnxruntime4j_jni.so").absolutePath)
                        }
                        PanelRuntimeId.STEAM -> System.load(File(root, "libmangaflow_steam.so").absolutePath)
                        PanelRuntimeId.QUICKJS -> {
                            // The upstream Android loader reads this trusted path before create().
                            System.setProperty("com.dokar.quickjs.library.path", root.absolutePath)
                            System.setProperty("com.dokar.quickjs.library.name", "libquickjs.so")
                            System.load(File(root, "libquickjs.so").absolutePath)
                        }
                    }
                    mutableStates.update { old -> old + (id to old.getValue(id).copy(loaded = true, error = null)) }
                } catch (_: LinkageError) {
                    nativeLoadFailed(id)
                } catch (_: OutOfMemoryError) {
                    nativeLoadFailed(id)
                } catch (_: Exception) {
                    nativeLoadFailed(id)
                }
            }
        }
    }

    private fun nativeLoadFailed(id: PanelRuntimeId): Nothing {
        val message = "运行库加载失败，请完全退出应用后重试；仍失败可重新下载"
        synchronized(control) { loadFailures[id] = message }
        updateError(id, message)
        throw RuntimeFailure(message)
    }

    private suspend fun downloadRuntime(id: PanelRuntimeId) {
        val ownJob = currentCoroutineContext()[Job]
        val transferContext = currentCoroutineContext()
        val stage = staging(id)
        var success: Installation? = null
        var error: String? = null
        var taskClient: OkHttpClient? = null
        try {
            ready.await()
            transferContext.ensureActive()
            if (states.value.getValue(id).pendingRemoval) throw RuntimeFailure("运行库正在等待删除，请完全退出应用后重试")
            synchronized(control) { installed[id] }?.let { success = it; return }
            val category = if (id == PanelRuntimeId.STEAM) DownloadCategory.STEAM_COMPONENT else DownloadCategory.COMPONENTS
            val route = DownloadRoutingStore.get(context).snapshot(category)
            val client = OkHttpClient.Builder().proxy(route.javaProxy())
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES).retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build()
            taskClient = client
            if (!directory.isDirectory && !directory.mkdirs()) throw RuntimeFailure("无法创建运行库目录")
            val envelope = readRemoteCatalog(id, client)
            val entry = verifyCatalog(envelope, currentRelease = true).single { it.id == id && it.abi == abi }
            mutableStates.update { old -> old + (id to old.getValue(id).copy(totalBytes = entry.archiveBytes)) }
            val unpacked = entry.libraries.sumOf { it.bytes }
            if (directory.usableSpace < entry.archiveBytes + unpacked + 16L * 1024 * 1024) throw RuntimeFailure("存储空间不足，无法下载并展开运行库")
            if (!deleteTree(stage) || !stage.mkdir()) throw RuntimeFailure("无法准备运行库下载目录")
            val archive = File(stage, "download.zip.part")
            downloadArchive(id, entry, archive, client)
            unpack(archive, stage, entry)
            archive.delete()
            writeSynced(File(stage, RECEIPT), envelope)
            verifyLibraries(stage, entry, seal = true)
            val destination = File(directory, entry.directoryName)
            transferContext.ensureActive()
            synchronized(control) {
                transferContext.ensureActive()
                if (id in touched || id in removing) throw RuntimeFailure("运行库正在使用或删除，请完全退出应用后重试")
                if (destination.exists()) throw RuntimeFailure("已有运行库目录，请先删除后重新下载")
                if (!stage.renameTo(destination)) throw RuntimeFailure("运行库保存失败，请重试")
                success = Installation(destination, entry)
                installed[id] = requireNotNull(success)
            }
        } catch (_: CancellationException) {
            // Cancel leaves neither an installed flag nor an unsigned partial runtime.
        } catch (failure: Exception) {
            error = if (!currentCoroutineContext().isActive) null else when (failure) {
                is DownloadRouteException -> failure.message
                is RuntimeFailure -> failure.message
                is SocketTimeoutException -> "运行库下载超时，可稍后重试"
                is UnknownHostException -> "无法连接运行库下载服务，请检查网络后重试"
                is SSLException -> "运行库下载的安全连接失败，请稍后重试"
                is SecurityException -> "运行库签名校验失败，已停止下载"
                else -> "运行库下载或校验失败，请检查网络和存储空间后重试"
            }
        } finally {
            taskClient?.dispatcher?.executorService?.shutdown()
            taskClient?.connectionPool?.evictAll()
            deleteTree(stage)
            synchronized(control) {
                if (downloads[id] === ownJob) {
                    downloads.remove(id)
                    calls.remove(id)?.cancel()
                    val item = success
                    mutableStates.update { old -> old + (id to PanelRuntimeState(
                        installed = item != null,
                        bytes = item?.entry?.archiveBytes ?: 0,
                        totalBytes = item?.entry?.archiveBytes ?: old.getValue(id).totalBytes,
                        error = error,
                    )) }
                }
            }
        }
    }

    private suspend fun readRemoteCatalog(id: PanelRuntimeId, client: OkHttpClient): ByteArray {
        val url = "https://github.com/$REPOSITORY/releases/download/v${appVersionName}/runtime-catalog.json"
        return open(id, url, client).use { response ->
            val input = response.body?.byteStream() ?: throw RuntimeFailure("运行库目录为空")
            input.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_CATALOG_BYTES) throw RuntimeFailure("运行库目录过大")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private suspend fun downloadArchive(id: PanelRuntimeId, entry: Entry, destination: File, client: OkHttpClient) {
        open(id, entry.archiveUrl, client).use { response ->
            val body = response.body ?: throw RuntimeFailure("运行库下载内容为空")
            if (body.contentLength() > entry.archiveBytes) throw RuntimeFailure("运行库归档大小不符")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var emitted = 0L
            body.byteStream().use { input -> FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    if (received > entry.archiveBytes) throw RuntimeFailure("运行库归档大小不符")
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    val now = SystemClock.elapsedRealtime()
                    if (now - emitted >= 100L) {
                        emitted = now
                        mutableStates.update { old -> old + (id to old.getValue(id).copy(
                            bytes = received, progress = received.toFloat() / entry.archiveBytes,
                        )) }
                    }
                }
                output.fd.sync()
            } }
            if (received != entry.archiveBytes || digest.digest().hex() != entry.archiveHash) throw RuntimeFailure("运行库归档校验失败，请重新下载")
        }
    }

    /** Redirects retain HTTPS, a closed GitHub CDN host set and the original repository asset path. */
    private suspend fun open(id: PanelRuntimeId, initial: String, client: OkHttpClient): Response {
        val initialUrl = initial.toHttpUrl()
        var url = initialUrl
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.host in HOSTS) { "运行库下载地址无效" }
            if (url.host == "github.com") require(url.encodedPath == initialUrl.encodedPath && url.query == null) { "运行库下载仓库不匹配" }
            val call = client.newCall(Request.Builder().url(url).header("Accept-Encoding", "identity").build())
            synchronized(control) { calls[id] = call }
            currentCoroutineContext().ensureActive()
            val response = call.execute()
            if (response.code in REDIRECTS) {
                val location = response.header("Location")
                response.close()
                url = location?.let { url.resolve(it) } ?: throw RuntimeFailure("运行库下载返回无效跳转")
            } else {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw RuntimeFailure(if (code == 404) "当前版本运行库尚未发布，请稍后重试" else "运行库下载失败（$code），可稍后重试")
                }
                return response
            }
        }
        throw RuntimeFailure("运行库下载跳转次数过多")
    }

    private fun verifyCatalog(envelope: ByteArray, currentRelease: Boolean): List<Entry> {
        require(envelope.size in 1..MAX_CATALOG_BYTES) { "运行库目录大小无效" }
        val wrapper = JSONObject(envelope.toString(Charsets.UTF_8))
        require(wrapper.getString("algorithm") == "SHA256withRSA") { "运行库签名算法无效" }
        val payload = Base64.decode(wrapper.getString("payload"), Base64.DEFAULT)
        val signature = Base64.decode(wrapper.getString("signature"), Base64.DEFAULT)
        require(payload.size in 1..MAX_PAYLOAD_BYTES && signature.size in 256..1024) { "运行库签名大小无效" }
        val verified = installedSignatures().any { bytes -> runCatching {
            val key = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)).publicKey
            key is RSAPublicKey && key.modulus.bitLength() >= 2048 && Signature.getInstance("SHA256withRSA").run {
                initVerify(key); update(payload); verify(signature)
            }
        }.getOrDefault(false) }
        if (!verified) throw SecurityException("运行库签名与当前应用不匹配")
        val root = JSONObject(payload.toString(Charsets.UTF_8))
        require(root.getInt("schema") == 1 && root.getString("packageName") == context.packageName && context.packageName == "dev.mangaflow") { "运行库应用身份无效" }
        val code = root.getLong("versionCode")
        val name = root.getString("versionName")
        require(code in 1..2_100_000_000L && name.matches(SIMPLE_NAME) && root.getString("sourceCommit").matches(COMMIT)) { "运行库发布身份无效" }
        if (currentRelease) require(code == appVersionCode.toLong() && name == appVersionName) { "运行库版本与当前应用不匹配" }
        val values = root.getJSONArray("runtimes")
        require(values.length() in ABIS.size..PanelRuntimeId.entries.size * ABIS.size) { "运行库目录条目不完整" }
        val entries = (0 until values.length()).map { index ->
            val value = values.getJSONObject(index)
            val id = PanelRuntimeId.valueOf(value.getString("id"))
            val runtimeAbi = value.getString("abi")
            val version = value.getString("version")
            // An older signed receipt can contain another engine's previous SDK version.
            // The selected installed engine is still checked strictly by verifyLibraries.
            require(runtimeAbi in ABIS && version.matches(SIMPLE_NAME) &&
                (!currentRelease || version == spec(id).version)) { "运行库版本或架构不匹配" }
            val archive = value.getJSONObject("archive")
            val archiveName = archive.getString("name")
            val archiveBytes = archive.getLong("bytes")
            val archiveHash = archive.getString("sha256")
            require(archiveName.matches(SIMPLE_NAME) && archiveName.endsWith(".zip") && archiveBytes in 1..MAX_ARCHIVE_BYTES && archiveHash.matches(HASH)) { "运行库归档信息无效" }
            val archiveUrl = archive.getString("url")
            val parsed = archiveUrl.toHttpUrl()
            require(parsed.scheme == "https" && parsed.host == "github.com" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null &&
                parsed.encodedPath == "/$REPOSITORY/releases/download/v$name/$archiveName") { "运行库归档来源无效" }
            val libraries = value.getJSONArray("libraries")
            val expected = libraryNames(id)
            require(libraries.length() == expected.size) { "运行库组件数量无效" }
            val files = (0 until libraries.length()).map { at ->
                val item = libraries.getJSONObject(at)
                val fileName = item.getString("name")
                val bytes = item.getLong("bytes")
                val hash = item.getString("sha256")
                require(fileName in expected && bytes in 1..MAX_UNPACKED_BYTES && hash.matches(HASH)) { "运行库组件信息无效" }
                Library(fileName, bytes, hash)
            }
            require(files.map { it.name }.toSet() == expected && files.sumOf { it.bytes } <= MAX_UNPACKED_BYTES) { "运行库组件重复或过大" }
            Entry(id, version, runtimeAbi, archiveUrl, archiveBytes, archiveHash, files)
        }
        require(entries.map { it.id to it.abi }.toSet().size == entries.size) { "运行库目录包含重复架构" }
        val runtimeIds = entries.map { it.id }.toSet()
        // Same-signer installed receipts may predate the optional script or Steam component.
        // New release catalogs include every engine; selected installed libraries stay strict.
        val legacyBase = setOf(PanelRuntimeId.LITERT, PanelRuntimeId.ONNX)
        require(if (currentRelease) runtimeIds == PanelRuntimeId.entries.toSet()
            else runtimeIds.containsAll(legacyBase)) { "运行库目录缺少必要引擎" }
        require(entries.map { it.id to it.abi }.toSet() == runtimeIds.flatMap { id -> ABIS.map { id to it } }.toSet()) {
            "运行库目录条目不完整"
        }
        return entries
    }

    private suspend fun unpack(archive: File, stage: File, entry: Entry) {
        val found = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val expected = entry.libraries.associateBy { it.name }
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val member = zip.nextEntry ?: break
                val name = member.name
                require(seen.add(name) && seen.size <= 64) { "运行库归档包含重复或过多条目" }
                require(name.isNotEmpty() && !name.contains('\\') && !name.contains('\u0000')) { "运行库归档路径无效" }
                if (member.isDirectory) {
                    require(name == "LICENSES/") { "运行库归档包含额外目录" }
                    zip.closeEntry()
                    continue
                }
                val library = expected[name]
                val license = name.startsWith("LICENSES/") && name.removePrefix("LICENSES/").let {
                    it.matches(SIMPLE_NAME) && it != "." && it != ".."
                }
                require(library != null || license) { "运行库归档包含未声明文件" }
                // ZIP attributes are never restored: only these signed names become new regular
                // files in a fresh private directory. No link entry is created or followed;
                // license entries are bounded and consumed without being written to disk.
                val output = library?.let { FileOutputStream(File(stage, it.name)) }
                var bytes = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                try {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = zip.read(buffer)
                        if (count < 0) break
                        bytes += count
                        total += count
                        require(total <= MAX_UNPACKED_BYTES && bytes <= (library?.bytes ?: MAX_LICENSE_BYTES)) { "运行库解包大小无效" }
                        output?.write(buffer, 0, count)
                        if (library != null) digest.update(buffer, 0, count)
                    }
                    output?.fd?.sync()
                } finally { output?.close() }
                if (library != null) {
                    require(bytes == library.bytes && digest.digest().hex() == library.hash) { "运行库组件校验失败" }
                    found.add(name)
                }
                zip.closeEntry()
            }
        }
        require(found == expected.keys) { "运行库归档缺少必要组件" }
    }

    private suspend fun verifyLibraries(root: File, entry: Entry, seal: Boolean) {
        require(entry.abi == abi && entry.version == spec(entry.id).version && isDirectChild(root, directory)) { "运行库目录或架构无效" }
        val expected = entry.libraries.map { it.name }.toSet() + RECEIPT
        require(root.isDirectory && root.listFiles()?.map { it.name }?.toSet() == expected) { "运行库目录文件不完整" }
        for (library in entry.libraries) {
            currentCoroutineContext().ensureActive()
            val file = File(root, library.name)
            require(isDirectChild(file, root) && file.isFile && file.length() == library.bytes && checksum(file) == library.hash) { "运行库组件校验失败" }
            requireElf(file, entry.abi)
            if (seal) require(file.setReadOnly()) { "无法保护运行库文件" }
            require(file.canRead() && !file.canWrite()) { "运行库文件权限无效" }
        }
        val receipt = File(root, RECEIPT)
        require(isDirectChild(receipt, root) && receipt.isFile) { "运行库安装凭据无效" }
        if (seal) require(receipt.setReadOnly()) { "无法保护运行库安装凭据" }
    }

    private fun requireElf(file: File, targetAbi: String) {
        val header = ByteArray(20)
        file.inputStream().use { input -> require(input.read(header) == header.size) { "运行库 ELF 头不完整" } }
        val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
        val expectedMachine = when (targetAbi) { "arm64-v8a" -> 183; "armeabi-v7a" -> 40; "x86_64" -> 62; else -> 3 }
        require(header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte() &&
            header[4].toInt() == (if (targetAbi in ABIS_64) 2 else 1) && header[5].toInt() == 1 && machine == expectedMachine) { "运行库 ELF 架构与当前进程不匹配" }
    }

    private fun readLocalReceipt(root: File): ByteArray {
        require(isDirectChild(root, directory)) { "运行库目录不可信" }
        val receipt = File(root, RECEIPT)
        require(isDirectChild(receipt, root) && receipt.isFile && receipt.length() in 1..MAX_CATALOG_BYTES.toLong()) { "运行库安装凭据无效" }
        return receipt.readBytes()
    }

    @Suppress("DEPRECATION")
    private fun installedSignatures(): List<ByteArray> {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toByteArray() }.orEmpty()
    }

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

    private fun isDirectChild(file: File, parent: File): Boolean =
        file.parentFile?.canonicalFile == parent.canonicalFile && file.canonicalFile.parentFile == parent.canonicalFile && file.canonicalFile.name == file.name

    private fun runtimeDirectories(id: PanelRuntimeId): List<File> = directory.listFiles()?.filter {
        it.name.startsWith("${id.name.lowercase()}-")
    }?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun staging(id: PanelRuntimeId) = File(directory, ".staging-${id.name.lowercase()}")

    private fun removalMarker(id: PanelRuntimeId) = File(directory, ".remove-${id.name.lowercase()}")

    private fun markPendingRemoval(id: PanelRuntimeId) {
        val marker = removalMarker(id)
        val temporary = File(directory, marker.name + ".tmp")
        try {
            writeSynced(temporary, byteArrayOf(1))
            if (!temporary.renameTo(marker)) throw IOException("removal marker")
            mutableStates.update { old -> old + (id to old.getValue(id).copy(pendingRemoval = true, error = null)) }
        } catch (_: IOException) {
            updateError(id, "无法保存删除计划，请完全退出应用后再试")
        } finally { temporary.delete() }
    }

    private fun deleteTree(file: File): Boolean {
        if (!file.exists()) return true
        if (file.parentFile != null && !isDirectChild(file, requireNotNull(file.parentFile))) return file.delete()
        if (file.isDirectory && file.listFiles()?.map { deleteTree(it) }?.all { it } != true) return false
        return file.delete()
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun updateError(id: PanelRuntimeId, message: String) {
        mutableStates.update { old -> old + (id to old.getValue(id).copy(error = message)) }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Library(val name: String, val bytes: Long, val hash: String)
    private data class Entry(val id: PanelRuntimeId, val version: String, val abi: String,
        val archiveUrl: String, val archiveBytes: Long, val archiveHash: String, val libraries: List<Library>) {
        val directoryName: String get() = "${id.name.lowercase()}-$version-$abi-$archiveHash"
    }
    private data class Installation(val directory: File, val entry: Entry)
    private class RuntimeFailure(message: String) : IOException(message)

    actual companion object {
        private const val REPOSITORY = "wozhenbang2004/MangaFlow"
        private const val RECEIPT = "catalog.json"
        private const val MAX_CATALOG_BYTES = 384 * 1024
        private const val MAX_PAYLOAD_BYTES = 256 * 1024
        private const val MAX_ARCHIVE_BYTES = 100L * 1024 * 1024
        private const val MAX_UNPACKED_BYTES = 250L * 1024 * 1024
        private const val MAX_LICENSE_BYTES = 2L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val COMMIT = Regex("[a-f0-9]{40}")
        private val SIMPLE_NAME = Regex("[A-Za-z0-9._-]{1,180}")
        private val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        private val ABIS_64 = setOf("arm64-v8a", "x86_64")
        private val HOSTS = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val SPECS = mapOf(
            PanelRuntimeId.STEAM to PanelRuntimeSpec(PanelRuntimeId.STEAM, "Steam 连接组件", "0.1.4-f541366",
                "登录 Steam 并下载可直接使用的创意工坊壁纸，仅在启用时下载", "MPL-2.0"),
            PanelRuntimeId.LITERT to PanelRuntimeSpec(PanelRuntimeId.LITERT, "轻量 AI 运行库", "1.4.2", "运行 YOLO26 Nano，仅下载当前应用架构所需组件", "Apache-2.0"),
            PanelRuntimeId.ONNX to PanelRuntimeSpec(PanelRuntimeId.ONNX, "高精度与排序运行库", "1.31.1", "运行 Koharu 和 Inkwell，两个模型共用此运行库", "MIT"),
            PanelRuntimeId.QUICKJS to PanelRuntimeSpec(PanelRuntimeId.QUICKJS, "漫画源脚本引擎", "1.0.18",
                "供你导入的第三方漫画源脚本使用，仅下载当前应用架构所需组件", "Apache-2.0 / MIT"),
        )
        private fun libraryNames(id: PanelRuntimeId): Set<String> = when (id) {
            PanelRuntimeId.STEAM -> setOf("libmangaflow_steam.so")
            PanelRuntimeId.LITERT -> setOf("libtensorflowlite_jni.so")
            PanelRuntimeId.ONNX -> setOf("libonnxruntime.so", "libonnxruntime4j_jni.so")
            PanelRuntimeId.QUICKJS -> setOf("libquickjs.so")
        }
        @Volatile private var instance: PanelRuntimeStore? = null
        actual fun get(context: Context): PanelRuntimeStore = instance ?: synchronized(this) {
            instance ?: PanelRuntimeStore(context).also { instance = it }
        }
    }
}
