package dev.mangaflow.enhancement.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class DesktopEnginePackage(
    val id: String, val version: String, val executable: String, val url: String, val sha256: String,
    val bytes: Long,
)

/** Archive identities are pinned to the official release receipts, not mutable latest URLs. */
internal object DesktopEnginePackages {
    val manga = DesktopEnginePackage(EnhancementEngineIds.MANGAJANAI, "0.9.3", "mangajanai.exe",
        "https://github.com/the-database/MangaJaNaiCLI/releases/download/v0.9.3/mangajanai-windows-x64.zip",
        "c1c5396945557fdea0263510a693ed3e976602a7c93ea66a33491463dc747ae5", 33_610_970)
    val video = DesktopEnginePackage(EnhancementEngineIds.VIDEOJANAI, "2.14.1", "videojanai.exe",
        "https://github.com/the-database/VideoJaNai/releases/download/2.14.1/videojanai-windows-x64.zip",
        "a362daaaaf380cd70b65cfcbd86449f03fde8e606bc251513eaae699747389ec", 170_908_359)
    val ffmpeg = DesktopEnginePackage("ffmpeg", "9.0.2", "ffprobe.exe",
        "https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-full_build-shared.zip",
        "8d31e162f1616e37aab3fa2db991b97e1b8dbeb1c8465fd81a81be16ff91b328", 99_899_672)
}

internal class DesktopEngineRuntime(private val directory: File) {
    fun folder(pack: DesktopEnginePackage) = File(directory, "${pack.id}-${pack.version}")

    fun executable(pack: DesktopEnginePackage): File? {
        val folder = folder(pack)
        val receipt = File(folder, "mangaflow-install.properties")
        if (!receipt.isFile) return null
        return runCatching {
            val data = Properties().apply { receipt.inputStream().use { load(it) } }
            require(data.getProperty("archive.sha256") == pack.sha256)
            val relative = data.getProperty("executable.path") ?: return null
            val executable = File(folder, relative).canonicalFile
            require(executable.toPath().startsWith(folder.canonicalFile.toPath()) && executable.isFile)
            require(engineSha256(executable) == data.getProperty("executable.sha256"))
            executable
        }.getOrNull()
    }

    suspend fun install(pack: DesktopEnginePackage, progress: (EngineProgress) -> Unit): File = withContext(Dispatchers.IO) {
        executable(pack)?.let { return@withContext it }
        check(directory.isDirectory || directory.mkdirs())
        val target = folder(pack)
        require(!target.exists()) { "组件目录存在但校验未通过；已保留文件，请从画质组件目录移出后重新安装" }
        val archive = File(directory, "${pack.id}-${UUID.randomUUID()}.zip.partial")
        val staging = File(directory, "${pack.id}-${UUID.randomUUID()}.staging")
        try {
            download(pack, archive, progress)
            progress(EngineProgress(EngineStage.VERIFY, detail = "校验官方组件 SHA-256"))
            check(engineSha256(archive) == pack.sha256) { "组件 SHA-256 与固定版本不符" }
            check(staging.mkdir())
            unpack(archive, staging, progress)
            val executable = staging.walkTopDown().maxDepth(8).singleOrNull { it.isFile && it.name == pack.executable }
                ?: error("组件中未找到唯一的 ${pack.executable}")
            val properties = Properties().apply {
                setProperty("archive.sha256", pack.sha256)
                setProperty("version", pack.version)
                setProperty("executable.path", executable.relativeTo(staging).path)
                setProperty("executable.sha256", engineSha256(executable))
            }
            File(staging, "mangaflow-install.properties").outputStream().use { properties.store(it, "Pinned official engine archive") }
            currentCoroutineContext().ensureActive()
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            executable(pack) ?: error("安装后的组件校验未通过")
        } finally { archive.delete(); if (staging.exists()) staging.deleteRecursively() }
    }

    suspend fun download(pack: DesktopEnginePackage, file: File, progress: (EngineProgress) -> Unit) {
        var url = URI(pack.url).toURL()
        var connection: HttpURLConnection? = null
        try {
            for (redirect in 0..6) {
                require(url.protocol == "https" && url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                    "组件下载跳转到不支持的地址"
                }
                val opened = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 30_000
                    setRequestProperty("User-Agent", "MangaFlow-Enhancement/1")
                }
                connection = opened
                val status = opened.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val next = opened.getHeaderField("Location") ?: error("组件下载缺少跳转地址")
                    url = url.toURI().resolve(next).toURL(); opened.disconnect()
                    if (redirect == 6) error("组件下载跳转过多")
                    continue
                }
                check(status == 200) { "组件下载失败（HTTP $status）" }
                var received = 0L
                opened.inputStream.use { input ->
                    Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            check(received <= pack.bytes) { "组件大小超过固定版本" }
                            output.write(buffer, 0, count)
                            progress(EngineProgress(EngineStage.DOWNLOAD, received, pack.bytes, bytes = received))
                        }
                    }
                }
                check(received == pack.bytes) { "组件下载不完整" }
                return
            }
        } finally { connection?.disconnect() }
    }

    private suspend fun unpack(archive: File, destination: File, progress: (EngineProgress) -> Unit) {
        val base = destination.canonicalFile.toPath()
        var count = 0L
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                check(++count <= 50_000) { "组件归档文件过多" }
                val target = File(destination, entry.name).canonicalFile
                require(target.toPath().startsWith(base) && target.toPath() != base) { "组件归档含无效路径" }
                if (entry.isDirectory) check(target.isDirectory || target.mkdirs()) else {
                    check(target.parentFile.isDirectory || target.parentFile.mkdirs())
                    Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = zip.read(bytes)
                            if (read < 0) break
                            total += read
                            check(total <= 8L * 1024 * 1024 * 1024) { "组件解压大小超出安全预算" }
                            output.write(bytes, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                progress(EngineProgress(EngineStage.INSTALL, count, detail = "展开已校验组件", bytes = total))
            }
        }
    }
}

/** Argument arrays only. Returning requires confirmed exit of the process and every observed descendant. */
internal suspend fun runEngineProcess(
    executable: File,
    arguments: List<String>,
    workingDirectory: File = executable.parentFile,
    timeoutMillis: Long = 24L * 60 * 60 * 1000,
    line: (String) -> Unit = {},
): String = withContext(Dispatchers.IO) {
    require(executable.isFile && executable.isAbsolute)
    require(arguments.none { '\u0000' in it })
    val process = ProcessBuilder(listOf(executable.canonicalPath) + arguments)
        .directory(workingDirectory).redirectErrorStream(true).start()
    val parent = process.toHandle()
    val observed = ConcurrentHashMap<Long, ProcessHandle>().apply { put(parent.pid(), parent) }
    val complete = AtomicBoolean(false)
    fun exited(handle: ProcessHandle): Boolean = runCatching { !handle.isAlive }.getOrDefault(false)
    fun observeDescendants() {
        // Retain the handles before a parent exits or is killed. Once reparented, a child can no
        // longer be found through process.descendants(), but it still owns this task's resources.
        observed.values.toList().forEach { owner ->
            if (!exited(owner)) runCatching {
                owner.descendants().use { descendants ->
                    descendants.forEach { child -> observed[child.pid()] = child }
                }
            }
        }
    }
    suspend fun awaitOwnedExit() = withContext(NonCancellable + Dispatchers.IO) {
        while (true) {
            observeDescendants()
            val alive = observed.values.filterNot(::exited)
            if (alive.isEmpty()) break
            // destroyForcibly is a request, not an exit receipt. Keep the retained handles and
            // the caller's GPU permit until every process is confirmed dead. If the OS cannot
            // reap one yet, cancellation remains pending instead of admitting competing work.
            alive.forEach { handle -> runCatching { handle.destroyForcibly() } }
            delay(100L)
        }
    }
    try {
        coroutineScope {
            // The reader below may be in a native blocking read. Retire the process as soon as the
            // coroutine is cancelled, before coroutineScope waits for its reader child to finish.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally {
                    if (!complete.get()) awaitOwnedExit()
                }
            }
            try {
                val output = async(Dispatchers.IO) {
                    val captured = StringBuilder()
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val value = reader.readLine() ?: break
                            if (captured.length < 4 * 1024 * 1024) captured.append(value.take(16_384)).append('\n')
                            line(value.take(16_384))
                        }
                    }
                    captured.toString()
                }
                val started = System.nanoTime()
                while (true) {
                    observeDescendants()
                    val parentExited = process.waitFor(100, TimeUnit.MILLISECONDS)
                    if (parentExited && output.isCompleted) break
                    currentCoroutineContext().ensureActive()
                    check((System.nanoTime() - started) / 1_000_000 < timeoutMillis) { "增强任务超过运行时限" }
                    // An exited parent may leave a live descendant holding its stdout pipe.
                    if (parentExited) delay(100L)
                }
                val log = output.await()
                check(process.exitValue() == 0) { "增强引擎退出（${process.exitValue()}）：${log.lineSequence().filter { it.isNotBlank() }.takeLastLines(4)}" }
                complete.set(true)
                log
            } finally {
                withContext(NonCancellable) { cancellation.cancelAndJoin() }
            }
        }
    } finally {
        // This also catches observed children that outlive a successful parent without owning
        // its stdout pipe. No timeout or cancellation is allowed to release their resource lease.
        awaitOwnedExit()
    }
}

private fun Sequence<String>.takeLastLines(count: Int): String = toList().takeLast(count).joinToString(" · ")
