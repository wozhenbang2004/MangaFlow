package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.DesktopEnginePackages
import dev.mangaflow.enhancement.engine.DesktopEngineRuntime
import dev.mangaflow.enhancement.engine.DesktopVideoPreparation
import dev.mangaflow.enhancement.engine.EngineContent
import dev.mangaflow.enhancement.engine.EngineVideoRequest
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.enhancement.engine.runEngineProcess
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.openani.mediamp.mpv.MPVHandle

/** A video-only filter consumer. The running media, audio output, subtitle tracks and clock never move. */
internal class DesktopAnimeOnlinePreparation(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val rendering: StateFlow<Boolean>,
    private val current: (AnimePreparedVideoRequest, MPVHandle) -> Boolean,
    private val suspendRealtime: (Boolean) -> Unit,
    private val update: (AnimePreparedVideoWindow?, String?) -> Unit,
    private val updateRetainedBytes: (Long) -> Unit,
) {
    private data class Prepared(
        val request: AnimePreparedVideoRequest,
        val handle: MPVHandle,
        val directory: File,
        val original: File,
        val media: File,
        val sourceReceipt: File,
        val receipt: File,
        val fps: Double,
        val firstSeconds: Double,
        val endSeconds: Double,
        var state: AnimePreparedVideoWindow,
    )
    private val files by lazy { DesktopAnimeWindowFiles.get(context) }
    private val runtime = DesktopEngineRuntime(File(context.dataRoot, "enhancement/engines"))
    @Volatile private var pending: Any? = null
    private var preparation: Job? = null
    private var prepared: Prepared? = null
    private var reusable: Prepared? = null
    private var consumerGeneration = 0L
    private var observer: Job? = null
    private var installed: MPVHandle? = null
    private val installedLabels = mutableSetOf<String>()
    private val retired = mutableListOf<File>()

    suspend fun prepare(request: AnimePreparedVideoRequest, handle: MPVHandle): Boolean {
        val identity = Any()
        val owner = currentCoroutineContext()[Job]
        val admitted = withContext(Dispatchers.Main.immediate) {
            if (!clear(retainReusable = true)) false else { pending = identity; preparation = owner; true }
        }
        if (!admitted) return false
        var directory: File? = null
        var retained = false
        val started = monotonicTimeMillis()
        fun valid() = pending === identity && current(request, handle)
        try {
            val cached = withContext(Dispatchers.Main.immediate) {
                reusable.also { reusable = null; updateRetainedBytes(0L) }
            }
            if (cached != null) {
                directory = cached.directory
                val usable = withContext(Dispatchers.Main.immediate) {
                    valid() && cached.handle === handle && request.canReuse(cached.request, cached.state,
                        ((handle.getPropertyDoubleOrNull("time-pos") ?: Double.MAX_VALUE) * 1000.0).toLong()) &&
                        handle.getPropertyDoubleOrNull("time-pos")?.let { it < cached.firstSeconds - 0.3 } == true
                }
                if (usable) {
                    val intact = withContext(Dispatchers.IO) {
                        cached.original.isFile && cached.media.isFile && cached.media.length() == cached.state.bytes &&
                            // Old output receipts must never acknowledge a newly reattached consumer.
                            runCatching {
                                cached.sourceReceipt.writeText("")
                                cached.receipt.writeText("")
                            }.isSuccess
                    }
                    withContext(Dispatchers.Main.immediate) {
                        if (intact && valid() &&
                            handle.getPropertyDoubleOrNull("time-pos")?.let { it < cached.firstSeconds - 0.3 } == true) {
                            cached.state = cached.state.copy(framesUsed = 0L, lastUsedAt = 0L)
                            prepared = cached
                            retained = true
                            update(cached.state, null)
                            observe(cached)
                        }
                    }
                    if (retained) return true
                }
                // A different model, timeline, budget or elapsed window cannot occupy the next allocation.
                withContext(Dispatchers.IO) { files.release(cached.directory) }
                directory = null
            }
            val result = withContext(Dispatchers.IO) {
                require(System.getProperty("os.name", "").startsWith("Windows", true)) { "当前在线视频预处理组件仅支持 Windows" }
                require(request.model.engineId == EnhancementEngineIds.VIDEOJANAI && EngineContent.VIDEO in request.model.contents) {
                    "在线预处理需要已安装的 VideoJaNai 番剧模型"
                }
                require(request.endMs - request.startMs in 1_000L..30_000L) { "Windows 单次在线增强窗口支持 1～30 秒，当前目标已保留" }
                val width = request.width.toLong() * request.model.nativeScale
                val height = request.height.toLong() * request.model.nativeScale
                require(width > 0 && height > 0 && width * height <= 8_847_360L) { "当前 Windows 增强窗口最多支持约 884 万输出像素" }
                require(request.maximumSourceBytes > 0L && request.maximumResultBytes > 0L) { "在线预处理没有可用字节额度" }
                val sourceFps = handle.getPropertyDoubleOrNull("container-fps")?.takeIf { it.isFinite() && it in 1.0..60.0 }
                    ?: error("当前片源帧率不在可确认的 1～60 FPS 范围")
                val gamma = handle.getPropertyString("video-params/gamma")?.lowercase()
                require(gamma in SDR) { "当前在线增强只处理已确认的 SDR 画面" }
                val decoder = handle.getPropertyString("hwdec-current")
                require(decoder == "no" || decoder?.endsWith("-copy") == true) {
                    "当前 GPU 直连解码不能安全接入本地窗口；重新打开当前视频可应用预处理解码方式"
                }
                require(handle.getPropertyDoubleOrNull("video-params/rotate")?.let { abs(it) < 0.01 } != false) {
                    "带旋转的视频尚不能安全映射增强窗口"
                }
                val nativePts = handle.getPropertyDoubleOrNull("video-pts") ?: error("当前视频时间轴尚未可读")
                val playback = handle.getPropertyDoubleOrNull("time-pos") ?: error("当前播放时钟尚未可读")
                require(nativePts.isFinite() && playback.isFinite() && abs(nativePts - playback) <= 0.1) {
                    "当前视频 PTS 与播放时间轴尚未对齐"
                }
                val probe = runtime.executable(DesktopEnginePackages.ffmpeg) ?: error("媒体处理组件尚未准备，请先安装运行组件")
                require(runtime.executable(DesktopEnginePackages.video) != null) { "VideoJaNai 运行组件尚未准备" }
                val ffmpeg = File(probe.parentFile, "ffmpeg.exe").also { require(it.isFile) { "媒体处理组件不完整" } }
                check(valid()) { "播放窗口已变化" }
                val work = files.create(request.asset).also { directory = it }
                val input = File(work, "original.mkv")
                val output = File(work, "enhanced.mkv")
                val captured = mutableListOf<Double>()
                val byteLimit = request.maximumResultBytes.coerceAtMost(Long.MAX_VALUE / 3L)
                val workLimit = byteLimit * 3L
                coroutineScope {
                    val watch = launch(Dispatchers.IO) {
                        while (true) {
                            delay(200L)
                            check(valid()) { "播放窗口已变化" }
                            check(DesktopAnimeWindowFiles.bytes(work) <= workLimit) { "在线增强工作文件已达到字节额度" }
                            check(work.usableSpace > 256L * 1024 * 1024) { "磁盘剩余空间不足以继续增强，原画保留" }
                        }
                    }
                    try {
                        val bridge = AnimeStreamBridge.create(request.asset, scope,
                            startupByteLimit = request.maximumSourceBytes,
                            cache = DesktopAnimeStreamCache.get(context), preparationOnly = true)
                        try {
                            val description = runEngineProcess(probe,
                                listOf("-v", "error", "-probesize", request.maximumSourceBytes.coerceAtMost(4L * 1024 * 1024).toString(),
                                    "-analyzeduration", "1000000", "-show_streams", "-show_format", "-of", "json", bridge.mediaUrl),
                                timeoutMillis = 10_000L)
                            checkRemoteMedia(description, request, sourceFps)
                            check(valid()) { "播放窗口已变化" }
                            val start = seconds(request.startMs)
                            val end = seconds(request.endMs)
                            // Keep source timestamps through trimming. showinfo records the actual first frame;
                            // only the bounded local video is rebased to zero for the existing offline engine.
                            runEngineProcess(ffmpeg, listOf("-nostdin", "-hide_banner", "-v", "info", "-xerror",
                                "-copyts", "-start_at_zero", "-ss", start,
                                "-t", seconds(request.endMs - request.startMs + 1_000L),
                                "-protocol_whitelist", "http,tcp,crypto,data", "-i", bridge.mediaUrl,
                                "-map", "0:v:0", "-an", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1",
                                "-vf", "trim=start=$start:end=$end,showinfo,setpts=PTS-STARTPTS,format=yuv420p",
                                "-fps_mode", "passthrough", "-threads", "2", "-c:v", "ffv1",
                                "-fs", byteLimit.toString(), "-f", "matroska", input.canonicalPath), timeoutMillis = 30_000L) { line ->
                                if ("showinfo" in line) FRAME_TIME.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { pts ->
                                    require(captured.size < 1_801 && pts.isFinite()) { "预处理帧数超出窗口范围" }
                                    captured += pts
                                }
                            }
                            check(bridge.lastFailure == null) { bridge.lastFailure ?: "原片预读未完成" }
                        } finally { bridge.close() }
                        currentCoroutineContext().ensureActive()
                        require(captured.size >= 2 && input.isFile && input.length() in 1L..byteLimit) { "没有取得完整的原视频窗口" }
                        val first = captured.first()
                        val frameSeconds = 1.0 / sourceFps
                        require(first * 1000 >= request.startMs - 2.0 && first * 1000 <= request.startMs + frameSeconds * 1000 + 2.0 &&
                            captured.last() * 1000 <= request.endMs + 2.0 && captured.last() + frameSeconds >= request.endMs / 1000.0 - 0.004) {
                            "原视频窗口未覆盖目标时间轴"
                        }
                        require(captured.zipWithNext().all { (a, b) -> abs((b - a) - frameSeconds) <= 0.003 }) {
                            "当前窗口含变速、跳帧或不连续时间戳，保留原画"
                        }
                        check(valid()) { "播放窗口已变化" }
                        val encoded = DesktopVideoPreparation(runtime).process(EngineVideoRequest(
                            sourcePath = input.canonicalPath, destinationPath = output.canonicalPath,
                            workDirectory = File(work, "processing").canonicalPath, model = request.model,
                            scale = request.model.nativeScale, encoder = "ffv1",
                        )) { check(valid()) { "播放窗口已变化" } }
                        require(encoded.bytes <= request.maximumResultBytes) { "增强窗口结果超过可用字节额度" }
                        val timestamps = frameTimes(probe, output)
                        require(timestamps.size == captured.size && timestamps.indices.all {
                            abs(timestamps[it] - (captured[it] - first)) <= 0.003
                        }) { "增强帧与原始帧的时间戳未逐帧对齐" }
                        check(valid()) { "播放窗口已变化" }
                        val endSeconds = minOf(request.endMs / 1000.0, first + timestamps.last() + frameSeconds)
                        Prepared(request, handle, work, input, output, File(work, "source-confirmed.txt"),
                            File(work, "consumed.txt"), sourceFps, first, endSeconds,
                            AnimePreparedVideoWindow((first * 1000).roundToLong(), (endSeconds * 1000).roundToLong(),
                                timestamps.size, encoded.bytes, request.model.title, encoded.width, encoded.height,
                                monotonicTimeMillis() - started, usageIsSampled = true))
                    } finally { watch.cancelAndJoin() }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (!valid()) return@withContext
                val position = handle.getPropertyDoubleOrNull("time-pos")
                if (position == null || position >= result.firstSeconds) {
                    update(null, "本次增强未赶上窗口开头，继续原画；后续窗口将参考实际耗时提前准备")
                    return@withContext
                }
                prepared = result
                retained = true
                update(result.state, null)
                observe(result)
            }
            return retained
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            withContext(Dispatchers.Main.immediate) {
                if (valid()) update(null, readablePlayerError(failure))
            }
            return false
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (pending === identity) { pending = null; preparation = null }
            }
            if (!retained) directory?.let { withContext(NonCancellable + Dispatchers.IO) { files.release(it) } }
        }
    }

    /** Main-thread ownership: remove the old graph before seeking or changing source/model. */
    fun clear(retainReusable: Boolean = false): Boolean {
        consumerGeneration++
        pending = null
        preparation?.cancel()
        preparation = null
        observer?.cancel()
        observer = null
        val old = prepared
        prepared = null
        installed?.let { handle ->
            installedLabels.toList().forEach { label ->
                if (runCatching { handle.command("vf", "remove", "@$label") }.getOrDefault(false)) installedLabels.remove(label)
            }
        }
        val removed = installedLabels.isEmpty()
        if (removed && retainReusable) {
            if (old != null) {
                reusable?.takeIf { it !== old }?.let { retired += it.directory }
                reusable = old
            }
        } else {
            if (old != null) retired += old.directory
            reusable?.let { retired += it.directory }
            reusable = null
        }
        updateRetainedBytes(reusable?.state?.bytes ?: 0L)
        if (removed) {
            installed = null
            suspendRealtime(false)
            releaseRetired()
        }
        update(null, if (removed) null else "无法确认已移除增强窗口，请重新打开当前视频")
        return removed
    }

    /** Called only after the owning native backend has synchronously closed. */
    fun nativeClosed() {
        installed = null
        installedLabels.clear()
        clear()
    }

    /** Reuse cannot cross a seek, so a retained window behind playback has no future consumer. */
    fun expireRetained(positionMs: Long) {
        val expired = reusable?.takeIf { positionMs >= it.state.endMs } ?: return
        reusable = null
        retired += expired.directory
        updateRetainedBytes(0L)
        if (installedLabels.isEmpty()) releaseRetired()
    }

    private fun releaseRetired() {
        val directories = retired.toList()
        retired.clear()
        if (directories.isEmpty()) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + Dispatchers.IO) {
                directories.forEach { directory ->
                    // A failed removal stays in the registered owner as a reviewable inactive entry.
                    runCatching { files.release(directory) }
                }
            }
        }
    }

    private fun observe(window: Prepared) {
        val generation = ++consumerGeneration
        fun ownsWindow() = consumerGeneration == generation && prepared === window
        observer = scope.launch(Dispatchers.Main.immediate) {
            val sourceReader = ReceiptReader(window.sourceReceipt)
            val outputReader = ReceiptReader(window.receipt)
            val witnessedSource = mutableSetOf<Double>()
            val sourceFuture = sortedSetOf<Double>()
            val acknowledgements = mutableSetOf<Double>()
            val future = sortedSetOf<Double>()
            var misses = 0
            var dropCount: Long? = null
            var overloaded = 0
            try {
                while (ownsWindow() && current(window.request, window.handle)) {
                    rendering.first { it }
                    val position = withContext(Dispatchers.IO) { window.handle.getPropertyDoubleOrNull("video-pts") }
                        ?.takeIf(Double::isFinite)
                    if (!ownsWindow() || !current(window.request, window.handle)) return@launch
                    if (position == null) { delay(200L); continue }
                    if (position >= window.endSeconds) { clear(); return@launch }
                    if (installed == null && position >= window.firstSeconds - 0.3) {
                        suspendRealtime(true)
                        install(window.handle, SOURCE_LABEL, sourceGraph(window))
                    }
                    if (installed != null) {
                        val sample = withContext(Dispatchers.IO) {
                            Triple(sourceReader.read() to outputReader.read(), window.handle.getPropertyLongOrNull("frame-drop-count"),
                                window.handle.getPropertyLongOrNull("video-out-params/w") to
                                    window.handle.getPropertyLongOrNull("video-out-params/h"))
                        }
                        if (!ownsWindow() || !current(window.request, window.handle)) return@launch
                        sample.first.first.filter { it.first >= window.firstSeconds - 0.002 && it.first < window.endSeconds }.forEach { (time, identical) ->
                            check(identical) { "当前原画与准备时的原视频帧不一致，已请求退出增强窗口" }
                            sourceFuture += time
                        }
                        val sourceReady = sourceFuture.filter { it <= position + 0.002 }
                        sourceFuture.removeAll(sourceReady.toSet())
                        witnessedSource.addAll(sourceReady)
                        if (LABEL !in installedLabels && witnessedSource.size >= 2) {
                            // Verify actual decoded source frames before adding the prepared-video overlay.
                            // Keeping the first graph also detects an upstream content change during reuse.
                            install(window.handle, LABEL, graph(window))
                            // mpv may retain or recreate upstream filters when its graph changes.
                            // Re-read source receipts without assuming either lifecycle behavior.
                            sourceReader.reset()
                            misses = 0
                        }
                        sample.first.second.filter { it.first >= window.firstSeconds - 0.002 && it.first < window.endSeconds }.forEach { (time, identical) ->
                            check(identical) { "增强输出与已校验的准备帧不一致，已请求恢复原画" }
                            future += time
                        }
                        val ready = future.filter { it <= position + 0.002 }
                        future.removeAll(ready.toSet())
                        val dimensionsMatch = sample.third.first == window.state.outputWidth.toLong() &&
                            sample.third.second == window.state.outputHeight.toLong()
                        val added = if (dimensionsMatch) ready.count { acknowledgements.add(it) } else 0
                        if (added > 0) {
                            misses = 0
                            window.state = window.state.copy(framesUsed = acknowledgements.size.toLong(), lastUsedAt = monotonicTimeMillis())
                            update(window.state, null)
                        } else if (position > window.firstSeconds + 0.8) misses++
                        val drops = sample.second
                        if (drops != null && dropCount != null && drops - checkNotNull(dropCount) >= 4L) overloaded++ else overloaded = 0
                        dropCount = drops
                        check(misses < 10) { "未确认增强窗口的真实画面输出，已请求恢复原画" }
                        check(overloaded < 3) { "增强窗口连续丢帧，已请求恢复原画" }
                    }
                    delay(200L)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (ownsWindow()) {
                    val removed = clear()
                    update(null, if (removed) readablePlayerError(failure) else "无法确认已移除增强窗口，请重新打开当前视频")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { if (ownsWindow()) clear() }
            }
        }
    }

    private fun install(handle: MPVHandle, label: String, graph: String) {
        check(handle.command("vf", "add", "@$label:lavfi=graph=%${graph.toByteArray().size}%$graph")) {
            "当前播放内核不支持视频窗口滤镜，继续原画"
        }
        installed = handle
        installedLabels += label
    }

    private class ReceiptReader(private val file: File) {
        private var cursor = 0L
        private var tail = ""
        private var timestamp: Double? = null

        fun reset() { cursor = 0L; tail = ""; timestamp = null }

        fun read(): List<Pair<Double, Boolean>> {
            if (!file.isFile) return emptyList()
            check(file.length() <= 2L * 1024 * 1024) { "视频输出回执超过窗口范围" }
            val text = RandomAccessFile(file, "r").use { reader ->
                // Reconfiguring the same native vf chain can reopen its metadata output. Only
                // real records are reread; the per-window PTS sets above prevent double counting.
                if (reader.length() < cursor) reset()
                reader.seek(cursor)
                val bytes = ByteArray((reader.length() - cursor).coerceAtMost(64L * 1024).toInt())
                reader.readFully(bytes)
                cursor += bytes.size
                bytes.toString(Charsets.UTF_8)
            }
            val combined = tail + text
            val complete = combined.substringBeforeLast('\n', "")
            tail = combined.substringAfterLast('\n')
            return buildList {
                complete.lineSequence().forEach { line ->
                    if (line.startsWith("frame:")) timestamp = RECEIPT_TIME.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                    else if (line.startsWith("lavfi.psnr.psnr_avg=")) timestamp?.takeIf(Double::isFinite)?.let { time ->
                        add(time to (line.substringAfter('=').trim().lowercase() in setOf("inf", "+inf", "infinity")))
                    }
                }
            }
        }
    }

    private fun sourceGraph(window: Prepared): String {
        val step = ceil(window.fps / 4.0).toInt().coerceAtLeast(1)
        return "[in]format=yuv420p,split[out][audit];" +
            "movie=filename=${filterPath(window.original)}:stream_index=0,setpts=PTS-STARTPTS+${window.firstSeconds}/TB,format=yuv420p[reference];" +
            "[audit]select='not(mod(n,$step))'[selected];[selected][reference]psnr=eof_action=pass:repeatlast=0:shortest=0," +
            "metadata=mode=print:key=lavfi.psnr.psnr_avg:file=${filterPath(window.sourceReceipt)}:direct=1,nullsink"
    }

    private fun graph(window: Prepared): String {
        val width = window.state.outputWidth
        val height = window.state.outputHeight
        val start = window.firstSeconds.toString()
        val end = window.endSeconds.toString()
        // The audit branch samples at most about four actual frames per second. PSNR infinity
        // requires identical pixels, not a self-issued marker or a filter-installation receipt.
        val step = ceil(window.fps / 4.0).toInt().coerceAtLeast(1)
        return "[in]scale=$width:$height:flags=bilinear,format=yuv420p[base];" +
            "movie=filename=${filterPath(window.media)}:stream_index=0,setpts=PTS-STARTPTS+$start/TB,format=yuv420p,split[patch][reference];" +
            "[base][patch]overlay=eof_action=pass:repeatlast=0:shortest=0:format=yuv420:enable='gte(t,$start)*lt(t,$end)'[replaced];" +
            "[replaced]split[out][audit];[audit]select='not(mod(n,$step))'[selected];" +
            "[selected][reference]psnr=eof_action=pass:repeatlast=0:shortest=0," +
            "metadata=mode=print:key=lavfi.psnr.psnr_avg:file=${filterPath(window.receipt)}:direct=1,nullsink"
    }

    private fun checkRemoteMedia(json: String, request: AnimePreparedVideoRequest, fps: Double) {
        val root = Json.parseToJsonElement(json).jsonObject
        val videos = (root["streams"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it.text("codec_type") == "video" }
        require(videos.size == 1) { "多视频轨片源尚不能确认预处理与当前解码使用同一视频轨" }
        val video = videos.single()
        require(video.number("width") == request.width && video.number("height") == request.height) { "预处理片源尺寸与当前视频不一致" }
        require(video.text("sample_aspect_ratio") in setOf("1:1", "0:1", "")) { "当前片源不是方形像素，保留原画" }
        require(video.text("pix_fmt") in setOf("yuv420p", "yuvj420p", "nv12")) {
            "当前在线增强支持 8 位 4:2:0 视频；不会为准备窗口降低原片位深或色度"
        }
        require(video.text("color_transfer") !in setOf("smpte2084", "arib-std-b67")) { "在线增强暂不处理 HDR 视频" }
        require((video["side_data_list"] as? JsonArray).orEmpty().none { item ->
            val side = item.jsonObject
            side.decimal("rotation")?.let { abs(it) > 0.01 } == true || side.text("side_data_type").contains("Dolby", true)
        }) { "当前片源方向或动态 HDR 元数据不能安全保留" }
        val rate = video.text("avg_frame_rate").split('/')
        val measured = rate.firstOrNull()?.toDoubleOrNull()?.let { numerator -> numerator / (rate.getOrNull(1)?.toDoubleOrNull() ?: 1.0) }
        require(measured != null && measured.isFinite() && abs(measured - fps) <= 0.01) { "预处理片源帧率与当前解码不一致" }
        val format = root["format"] as? JsonObject ?: error("原始视频时间轴未知")
        require(format.decimal("start_time")?.isFinite() == true && format.decimal("duration")?.let {
            it.isFinite() && it * 1000 >= request.endMs
        } == true) { "片源缺少可对齐的起点或完整时长" }
    }

    private suspend fun frameTimes(probe: File, file: File): List<Double> {
        val output = runEngineProcess(probe, listOf("-v", "error", "-select_streams", "v:0", "-show_frames",
            "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", file.canonicalPath), timeoutMillis = 15_000L)
        return (Json.parseToJsonElement(output).jsonObject["frames"] as? JsonArray).orEmpty().map {
            it.jsonObject.decimal("best_effort_timestamp_time")?.takeIf(Double::isFinite) ?: error("增强帧缺少时间戳")
        }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.decimal(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun seconds(milliseconds: Long) = (milliseconds / 1000.0).toString()
    private fun filterPath(file: File) = "'" + file.canonicalPath.replace('\\', '/').replace(":", "\\:").replace("'", "'\\''") + "'"

    companion object {
        private const val LABEL = "mangaflow-prepared-window"
        private const val SOURCE_LABEL = "mangaflow-prepared-source"
        private val SDR = setOf("bt.1886", "srgb", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "gamma2.6", "gamma2.8")
        private val FRAME_TIME = Regex("\\bn:\\s*[0-9]+\\s+pts:.*?\\bpts_time:([0-9.eE+\\-]+)")
        private val RECEIPT_TIME = Regex("\\bpts_time:([0-9.eE+\\-]+)")
    }
}
