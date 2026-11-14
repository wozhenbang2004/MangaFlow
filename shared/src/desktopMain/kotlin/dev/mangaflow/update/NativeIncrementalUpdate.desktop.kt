package dev.mangaflow.update

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.javaProxy
import dev.mangaflow.platform.storage.cacheDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.update4j.Configuration
import org.update4j.FileMetadata
import org.update4j.UpdateOptions
import org.update4j.service.UpdateHandler
import java.io.File
import java.io.InputStream
import java.io.FilterInputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The application supplies its normal save-and-close action; updates never kill an unsaved JVM. */
object DesktopUpdateExit {
    @Volatile var requestExit: (() -> Unit)? = null
}

private data class UpdateFile(val path: String, val bytes: Long, val sha256: String, val checksum: String, val url: String)
private data class DesktopPlan(
    val root: File, val work: File, val version: String,
    val files: List<UpdateFile>, val changed: List<UpdateFile>,
)
private val plans = ConcurrentHashMap<String, DesktopPlan>()
private val hashPattern = Regex("[a-f0-9]{64}")
private const val RELEASE_PREFIX = "https://github.com/wozhenbang2004/MangaFlow/releases/download/"
private const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

internal actual suspend fun planNativeIncrementalUpdate(
    context: PlatformContext, release: AppRelease, releaseMetadata: String, route: DownloadRouteSnapshot,
): NativeIncrementalPlan? = withContext(Dispatchers.IO) {
    val executable = System.getProperty("jpackage.app-path")?.let(::File)
        ?: ProcessHandle.current().info().command().orElse(null)?.let(::File)
        ?: return@withContext null
    if (!executable.name.equals("MangaFlow.exe", true)) return@withContext null
    val root = executable.canonicalFile.parentFile
    if (!File(root, "app/MangaFlow.cfg").isFile || !File(root, "runtime").isDirectory) return@withContext null
    val metadata = JSONObject(releaseMetadata)
    val assets = metadata.optJSONArray("assets") ?: return@withContext null
    val asset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        .firstOrNull { it.optString("name") == "MangaFlow-" + release.versionName + "-windows-x64.update.json" }
        ?: return@withContext null
    val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
    require(hashPattern.matches(digest)) { "增量清单缺少发布摘要" }
    val bytes = asset.optLong("size")
    require(bytes in 1..(2L * 1024 * 1024)) { "增量清单大小无效" }
    val data = readOfficial(asset.optString("browser_download_url"), bytes, route.javaProxy())
    require(hash(data) == digest) { "增量清单校验失败" }
    val manifest = JSONObject(data.toString(Charsets.UTF_8))
    if (manifest.optInt("protocol") != 1 || manifest.optString("version") != release.versionName) return@withContext null
    if (manifest.optString("runtimeFingerprint") != runtimeFingerprint(File(root, "runtime"))) return@withContext null
    val entries = manifest.getJSONArray("files")
    require(entries.length() in 1..1000) { "增量文件清单无效" }
    val files = (0 until entries.length()).map { index ->
        val entry = entries.getJSONObject(index)
        val path = entry.getString("path")
        safeAppFile(root, path)
        val fileHash = entry.getString("sha256")
        val fileBytes = entry.getLong("size")
        val checksum = entry.getString("checksum")
        val url = entry.getString("url")
        require(hashPattern.matches(fileHash) && fileBytes in 0..MAX_FILE_BYTES &&
            Regex("[a-f0-9]{1,8}").matches(checksum)) { "增量文件信息无效" }
        require(url.startsWith(RELEASE_PREFIX)) { "增量文件不属于当前项目发布" }
        require(URI(url).host == "github.com" && URI(url).scheme == "https") { "增量文件地址无效" }
        UpdateFile(path, fileBytes, fileHash, checksum, url)
    }
    require(files.map { it.path.lowercase() }.toSet().size == files.size) { "增量文件路径重复" }
    require(files.any { it.path == "app/MangaFlow.cfg" }) { "增量清单缺少启动配置" }
    val changed = files.filter { entry ->
        currentCoroutineContext().ensureActive()
        val local = safeAppFile(root, entry.path)
        !local.isFile || local.length() != entry.bytes || hash(local) != entry.sha256
    }
    val total = changed.sumOf { it.bytes }
    if (changed.isEmpty() || total >= release.bytes) return@withContext null
    val work = File(cacheDirectory(context).path, "updates/incremental/" + digest)
    check(work.isDirectory || work.mkdirs()) { "无法创建增量更新目录" }
    val plan = DesktopPlan(root, work, release.versionName, files, changed)
    plans[digest] = plan
    NativeIncrementalPlan(digest, total)
}

internal actual suspend fun downloadNativeIncrementalUpdate(
    context: PlatformContext, plan: NativeIncrementalPlan, route: DownloadRouteSnapshot, onProgress: (Long, Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val state = plans[plan.token] ?: error("增量计划已失效，请重新检查更新")
    val proxy = route.javaProxy()
    val cancellation = currentCoroutineContext()
    val payload = File(state.work, "payload")
    check(payload.isDirectory || payload.mkdirs()) { "无法创建增量暂存目录" }
    val xml = buildString {
        append("<configuration><base path=\"").append(escapeXml(payload.canonicalPath)).append("\"/><files>")
        state.changed.forEach { entry ->
            append("<file path=\"").append(escapeXml(entry.path)).append("\" uri=\"")
                .append(escapeXml(entry.url)).append("\" size=\"").append(entry.bytes)
                .append("\" checksum=\"").append(entry.checksum).append("\"/>")
        }
        append("</files></configuration>")
    }
    val config = Configuration.read(StringReader(xml))
    val archive = File(state.work, "download.zip")
    val byPath = state.changed.associateBy { safeAppFile(payload, it.path).toPath().toAbsolutePath().normalize() }
    val result = config.update(UpdateOptions.archive(archive.toPath()).updateHandler(object : UpdateHandler {
        override fun openDownloadStream(file: FileMetadata): InputStream {
            cancellation.ensureActive()
            val connection = officialConnection(file.uri.toString(), proxy)
            val input = try { connection.inputStream }
                catch (failure: Exception) { connection.disconnect(); throw failure }
            return object : FilterInputStream(input) {
                override fun close() {
                    try { super.close() } finally { connection.disconnect() }
                }
            }
        }
        override fun updateDownloadProgress(frac: Float) {
            cancellation.ensureActive()
            onProgress((plan.bytes * frac.coerceIn(0f, 1f)).toLong(), plan.bytes)
        }
        override fun validatingFile(file: FileMetadata, path: Path) {
            cancellation.ensureActive()
            val entry = byPath[file.path.toAbsolutePath().normalize()] ?: error("未知增量文件")
            require(Files.size(path) == entry.bytes && hash(path) == entry.sha256) { "增量文件 SHA-256 校验失败" }
        }
        override fun doneDownloadFile(file: FileMetadata, path: Path) {
            cancellation.ensureActive()
            val destination = file.path
            Files.createDirectories(destination.parent)
            Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }))
    result.exception?.let { throw it }
    state.changed.forEach { entry ->
        cancellation.ensureActive()
        val file = safeAppFile(payload, entry.path)
        require(file.isFile && file.length() == entry.bytes && hash(file) == entry.sha256) { "增量暂存不完整，请重新下载" }
    }
    // Only verified files enter the install plan. The external helper checks SHA-256 again after shutdown.
    val install = JSONObject().put("Mode", "incremental").put("InstallRoot", state.root.canonicalPath)
        .put("TargetVersion", state.version).put("PayloadRoot", payload.canonicalPath)
        .put("Files", JSONArray(state.files.map { JSONObject().put("Path", it.path).put("Size", it.bytes).put("Sha256", it.sha256) }))
        .put("ChangedPaths", JSONArray(state.changed.map { it.path }))
        .put("ResultPath", File(cacheDirectory(context).path, "updates/last-result.json").canonicalPath)
    File(state.work, "install.json").writeText(install.toString(), Charsets.UTF_8)
    archive.delete()
    onProgress(plan.bytes, plan.bytes)
}

internal actual fun installNativeIncrementalUpdate(context: PlatformContext, plan: NativeIncrementalPlan): Boolean {
    val state = plans[plan.token] ?: return false
    return launchDesktopHandoff(File(state.work, "install.json"))
}

internal fun launchNativeInstaller(context: PlatformContext, path: String): Boolean {
    val installer = File(path).canonicalFile
    require(installer.isFile && installer.extension.lowercase() in setOf("msi", "exe"))
    val work = File(cacheDirectory(context).path, "updates/installer-handoff")
    check(work.isDirectory || work.mkdirs())
    val executable = System.getProperty("jpackage.app-path")?.let(::File)
        ?: ProcessHandle.current().info().command().orElse(null)?.let(::File)
    val plan = JSONObject().put("Mode", "installer").put("Installer", installer.path)
        .put("InstallerSize", installer.length()).put("InstallerSha256", hash(installer))
        .put("InstallRoot", executable?.canonicalFile?.parentFile?.path.orEmpty())
        .put("ResultPath", File(cacheDirectory(context).path, "updates/last-result.json").canonicalPath)
    val file = File(work, "install.json")
    file.writeText(plan.toString(), Charsets.UTF_8)
    return launchDesktopHandoff(file)
}

private fun launchDesktopHandoff(plan: File): Boolean {
    val close = DesktopUpdateExit.requestExit ?: return false
    require(plan.isFile)
    val script = File(plan.parentFile, "install-update.ps1")
    val source = DesktopUpdateExit::class.java.getResourceAsStream("/desktop-update.ps1")
        ?: error("安装辅助程序缺失，请重新下载完整安装包")
    source.use { input -> script.outputStream().use { input.copyTo(it) } }
    ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
        "-ExecutionPolicy", "Bypass", "-File", script.canonicalPath,
        "-PlanPath", plan.canonicalPath, "-ParentPid", ProcessHandle.current().pid().toString())
        .redirectErrorStream(true).redirectOutput(File(plan.parentFile, "handoff.log")).start()
    close()
    return true
}

internal actual fun previousNativeUpdateResult(context: PlatformContext): String? = runCatching {
    val file = File(cacheDirectory(context).path, "updates/last-result.json")
    if (!file.isFile || file.length() > 64 * 1024) return@runCatching null
    val result = JSONObject(file.readText(Charsets.UTF_8))
    file.delete()
    result.optString("Message").takeIf { it.isNotBlank() }
}.getOrNull()

private fun safeAppFile(root: File, path: String): File {
    require(path.startsWith("app/") && path.length <= 500 && '\\' !in path &&
        path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c.isISOControl() || c in ":*?\"<>|" } })
    val base = File(root, "app").canonicalFile
    val file = File(root, path).canonicalFile
    require(file.toPath().startsWith(base.toPath()) && file != base) { "增量文件越过应用目录" }
    return file
}
private fun runtimeFingerprint(root: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
        val line = file.relativeTo(root).invariantSeparatorsPath + "\u0000" + hash(file) + "\n"
        digest.update(line.toByteArray(Charsets.UTF_8))
    }
    return hex(digest.digest())
}
private fun hash(file: File): String = hash(file.toPath())
private fun hash(path: Path): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    hex(digest.digest())
}
private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun escapeXml(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
private fun readOfficial(url: String, expectedBytes: Long, proxy: Proxy?): ByteArray = officialConnection(url, proxy).let { connection ->
    try {
        connection.inputStream.use { input ->
            val bytes = input.readNBytes((expectedBytes + 1).toInt())
            require(bytes.size.toLong() == expectedBytes) { "发布清单下载不完整" }
            bytes
        }
    } finally { connection.disconnect() }
}
private fun officialConnection(initial: String, proxy: Proxy?): HttpURLConnection {
    var url = URI(initial)
    repeat(6) {
        require(url.scheme == "https" && url.host in setOf("github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "增量下载地址无效" }
        val connection = (if (proxy == null) url.toURL().openConnection() else url.toURL().openConnection(proxy)) as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "MangaFlow")
        if (connection.responseCode in setOf(301, 302, 303, 307, 308)) {
            val next = connection.getHeaderField("Location") ?: error("增量下载重定向无效")
            connection.disconnect()
            url = url.resolve(next)
        } else {
            require(connection.responseCode == 200) { "增量下载失败（HTTP " + connection.responseCode + "）" }
            return connection
        }
    }
    error("增量下载重定向过多")
}
