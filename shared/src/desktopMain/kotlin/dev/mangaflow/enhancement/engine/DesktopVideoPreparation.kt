package dev.mangaflow.enhancement.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Offline media only. No URL resolving, HTTP streaming, player replacement or original-file writes. */
internal class DesktopVideoPreparation(private val runtime: DesktopEngineRuntime) {
    private data class Media(
        val width: Int, val height: Int, val duration: Double, val fpsNumerator: Long, val fpsDenominator: Long,
        val videoStart: Double, val formatStart: Double, val audio: List<String>, val subtitles: List<String>,
        val attachments: List<String>, val chapters: List<String>, val formatTags: Map<String, String>,
        val audioStarts: List<Double?>, val subtitleStarts: List<Double?>, val videoTags: Map<String, String>,
    ) { val fps: Double get() = fpsNumerator.toDouble() / fpsDenominator }

    suspend fun process(request: EngineVideoRequest, progress: (EngineProgress) -> Unit): EngineOutput = withContext(Dispatchers.IO) {
        val source = File(request.sourcePath).canonicalFile
        val destination = File(request.destinationPath).canonicalFile
        newEngineOutput(source, destination)
        require(request.scale in setOf(1, 2, 4)) { "视频输出支持 1×、2× 或 4×" }
        require(request.sceneThreshold.isFinite() && request.sceneThreshold in 0.01..0.5) { "场景检测阈值无效" }
        require(request.targetFps == null || request.targetFps.isFinite() && request.targetFps > 0 && request.targetFps <= 240) { "目标帧率无效" }
        require(request.frameMultiplier == null || request.frameMultiplier in 2..8) { "补帧倍率无效" }
        require(request.targetFps == null || request.frameMultiplier == null) { "目标帧率与补帧倍率只能选一个" }
        require(request.encoder in setOf("nvenc-hevc", "nvenc-av1", "amf-hevc", "qsv-hevc", "x265", "ffv1")) { "不支持的编码器" }
        require(request.rifeVersion.matches(Regex("4\\.[0-9]{1,2}(?:-lite)?"))) { "不支持的 RIFE 版本" }
        val executable = runtime.executable(DesktopEnginePackages.video) ?: error("请先安装番剧增强组件")
        val probe = runtime.executable(DesktopEnginePackages.ffmpeg) ?: error("媒体校验工具尚未准备好")
        val ffmpeg = File(probe.parentFile, "ffmpeg.exe").also { check(it.isFile) { "媒体校验工具不完整" } }
        val started = System.nanoTime()
        progress(EngineProgress(EngineStage.VALIDATE, detail = "读取原始视频、音轨、字幕和时间轴"))
        val before = readMedia(probe, source)
        require(before.videoStart >= -0.001 && before.formatStart >= -0.001) { "当前离线引擎尚不处理负起始时间轴；保留原视频" }
        require(before.width.toLong() * request.scale <= 16_384 && before.height.toLong() * request.scale <= 16_384) { "目标视频尺寸超出当前引擎范围" }
        if (request.targetFps != null) require(request.targetFps >= before.fps - 0.001) { "补帧目标不能低于原始帧率" }
        require(request.frameMultiplier == null || before.fps * request.frameMultiplier <= 240) { "倍频后超过当前 240 FPS 编码范围" }
        val weights = request.model?.let { model ->
            require(model.engineId == EnhancementEngineIds.VIDEOJANAI && EngineContent.VIDEO in model.contents) { "请选择番剧专用模型" }
            File(model.path).canonicalFile.also { check(engineSha256(it) == model.sha256) { "模型文件已变化" } }
        }
        require(request.scale == 1 || weights != null) { "放大需要已安装的真实番剧模型" }
        require(request.model == null || request.scale == 1 || request.scale == request.model.nativeScale ||
            request.scale == 4 && request.model.nativeScale == 2) { "当前模型没有这一放大路径" }
        require(request.model != null || request.targetFps != null || request.frameMultiplier != null) { "原画不需要创建相同的增强副本" }
        val interpolation = request.targetFps != null || request.frameMultiplier != null
        val rifeWeights = if (interpolation) {
            val name = "rife_v${request.rifeVersion.replace("-lite", "_lite")}${if (request.rifeEnsemble) "_ensemble" else ""}.onnx"
            runtime.folder(DesktopEnginePackages.video).walkTopDown().maxDepth(9).firstOrNull { it.isFile && it.name == name }
                ?: error("所选 RIFE 模型尚未安装，未开始处理")
        } else null
        val rifeHash = rifeWeights?.let(::engineSha256)
        require(request.expectedRifeSha256 == null || request.expectedRifeSha256 == rifeHash) { "RIFE 权重已变化，请重新选择" }
        val modelIdentity = "${request.model?.sha256.orEmpty()}|${rifeHash.orEmpty()}"
        val pipelineHash = MessageDigest.getInstance("SHA-256").digest(modelIdentity.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val sourceHash = engineSha256(source)
        val identity = listOf(sourceHash, request.model?.sha256.orEmpty(), request.scale, request.targetFps,
            request.frameMultiplier, request.rifeVersion, request.rifeEnsemble, rifeHash, request.sceneThreshold, request.encoder,
            request.segmentSeconds, DesktopEnginePackages.video.version).joinToString("|")
        val taskHash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val requestedWork = File(request.workDirectory).canonicalFile
        require(requestedWork != source && requestedWork != destination && !source.toPath().startsWith(requestedWork.toPath())) {
            "增强工作目录必须独立于原始文件"
        }
        val work = File(requestedWork, taskHash)
        check(work.isDirectory || work.mkdirs()) { "无法创建离线增强任务目录" }
        check(work.usableSpace > 256L * 1024 * 1024) { "增强工作空间不足" }
        val finalTemporary = File(work, "validated-${UUID.randomUUID()}.mkv")
        try {
            val encoded = if (request.segmentSeconds > 0) {
                require(request.segmentSeconds in 5..120) { "分段长度应为 5～120 秒" }
                require(abs(before.videoStart) <= 0.001 && abs(before.formatStart) <= 0.001) {
                    "这一片源的起始偏移不适合安全分段；请关闭分段或保留原画"
                }
                prepareSegments(source, work, executable, ffmpeg, probe, before, request, progress)
            } else {
                val result = File(work, "full.mkv")
                val receipt = File(work, "full.sha256")
                if (!validCheckpoint(result, receipt)) {
                    // Only files created by this exact task identity are replaced.
                    result.delete(); receipt.delete()
                    encode(executable, source, result, before, request, progress)
                    validateVideoOnly(before, readMedia(probe, result), request)
                    receipt.writeText(engineSha256(result))
                }
                result
            }
            currentCoroutineContext().ensureActive()
            progress(EngineProgress(EngineStage.VALIDATE, detail = "保留原片音轨、字幕、附件与章节；校验时间轴"))
            // Video is replaced explicitly. Other supported streams and all user metadata come from the untouched original.
            runEngineProcess(ffmpeg, listOf("-nostdin", "-hide_banner", "-v", "error", "-xerror", "-copyts",
                "-i", encoded.canonicalPath, "-i", source.canonicalPath, "-map", "0:v:0", "-map", "1:a?",
                "-map", "1:s?", "-map", "1:t?", "-map_metadata", "1", "-map_chapters", "1", "-c", "copy",
                "-map_metadata:s:v:0", "1:s:v:0", "-avoid_negative_ts", "disabled", "-f", "matroska", finalTemporary.canonicalPath))
            val after = readMedia(probe, finalTemporary)
            validateVideoOnly(before, after, request)
            check(before.audio == after.audio) { "增强结果音轨或音轨元数据不完整；未发布" }
            check(before.subtitles == after.subtitles) { "增强结果字幕或字幕元数据不完整；未发布" }
            check(before.attachments == after.attachments) { "增强结果附件不完整；未发布" }
            check(before.chapters == after.chapters) { "增强结果章节时间轴或标题不完整；未发布" }
            check(sameStarts(before.audioStarts, after.audioStarts) && sameStarts(before.subtitleStarts, after.subtitleStarts)) {
                "增强结果音轨或字幕时间轴偏移；未发布"
            }
            check(before.videoTags.all { (key, value) -> after.videoTags[key] == value }) { "增强结果未保留视频轨标题或语言；未发布" }
            check(before.formatTags.all { (key, value) -> after.formatTags[key] == value }) { "增强结果未保留受支持的容器元数据；未发布" }
            // A valid container header is insufficient; decoding every output frame catches truncated/corrupt output.
            runEngineProcess(ffmpeg, listOf("-nostdin", "-hide_banner", "-v", "error", "-xerror", "-err_detect", "explode",
                "-i", finalTemporary.canonicalPath, "-map", "0:v:0", "-f", "null", "-"))
            currentCoroutineContext().ensureActive()
            check(engineSha256(source) == sourceHash) { "处理期间原视频发生变化；增强结果未发布" }
            check(weights == null || engineSha256(weights) == request.model?.sha256) { "处理期间超分模型发生变化；增强结果未发布" }
            check(rifeWeights == null || engineSha256(rifeWeights) == rifeHash) { "处理期间补帧模型发生变化；增强结果未发布" }
            val hash = engineSha256(finalTemporary)
            val bytes = finalTemporary.length()
            require(bytes > 0)
            moveNewEngineFile(finalTemporary, destination)
            progress(EngineProgress(EngineStage.COMPLETE, 1, 1, detail = "增强视频及音轨、字幕、章节已校验"))
            EngineOutput(destination.canonicalPath, after.width, after.height, hash, bytes,
                EnhancementEngineIds.VIDEOJANAI, DesktopEnginePackages.video.version,
                pipelineHash, (System.nanoTime() - started) / 1_000_000,
                video = EngineVideoMetadata((after.duration * 1000).roundToLong(), after.fps,
                    after.audio.size, after.subtitles.size, after.chapters.size))
        } catch (failure: Throwable) {
            finalTemporary.delete()
            // Completed per-segment checkpoints remain resumable; original and other enhanced versions are untouched.
            throw failure
        }
    }

    private suspend fun prepareSegments(source: File, work: File, executable: File, ffmpeg: File, probe: File,
        original: Media, request: EngineVideoRequest, progress: (EngineProgress) -> Unit): File {
        val segments = File(work, "segments").also { check(it.isDirectory || it.mkdirs()) }
        val list = File(segments, "index.csv")
        val prepared = File(segments, "complete")
        if (!prepared.isFile) {
            // This folder only contains regenerable keyframe-aligned parts for this task identity.
            segments.listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
            progress(EngineProgress(EngineStage.PROCESS, detail = "按原始关键帧建立可恢复分段"))
            runEngineProcess(ffmpeg, listOf("-nostdin", "-hide_banner", "-v", "error", "-xerror", "-i", source.canonicalPath,
                "-map", "0:v:0", "-an", "-sn", "-dn", "-c", "copy", "-f", "segment",
                "-segment_time", request.segmentSeconds.toString(), "-reset_timestamps", "1",
                "-segment_list", "index.csv", "-segment_list_type", "csv", "part-%06d.mkv"), segments)
            check(list.isFile && list.length() > 0) { "没有可处理的视频分段" }
            prepared.writeText("keyframes-v1")
        }
        val files = list.readLines().filter { it.isNotBlank() }.map { line ->
            val name = line.substringBefore(',').removeSurrounding("\"")
            require(name.matches(Regex("part-[0-9]{6}\\.mkv"))) { "分段目录格式不符" }
            File(segments, name).also { check(it.isFile && it.length() > 0) }
        }
        require(files.isNotEmpty())
        val outputs = mutableListOf<File>()
        files.forEachIndexed { index, input ->
            currentCoroutineContext().ensureActive()
            check(work.usableSpace > 128L * 1024 * 1024) { "增强磁盘预算不足，已保留完成分段" }
            val output = File(work, "enhanced-${index.toString().padStart(6, '0')}.mkv")
            val receipt = File(work, "${output.name}.sha256")
            if (!validCheckpoint(output, receipt)) {
                output.delete(); receipt.delete()
                val segmentInfo = readMedia(probe, input)
                encode(executable, input, output, segmentInfo, request) { state ->
                    progress(state.copy(detail = "分段 ${index + 1}/${files.size} · ${state.detail.orEmpty()}"))
                }
                validateVideoOnly(segmentInfo, readMedia(probe, output), request)
                receipt.writeText(engineSha256(output))
            }
            outputs += output
            progress(EngineProgress(EngineStage.PROCESS, (index + 1).toLong(), files.size.toLong(), detail = "已校验分段，可暂停后续跑"))
        }
        val concat = File(work, "concat.txt")
        concat.writeText(outputs.joinToString("\n") { "file '${it.name}'" } + "\n")
        val joined = File(work, "joined.mkv")
        joined.delete()
        runEngineProcess(ffmpeg, listOf("-nostdin", "-hide_banner", "-v", "error", "-xerror", "-f", "concat",
            "-safe", "1", "-i", concat.canonicalPath, "-map", "0:v:0", "-c", "copy", joined.canonicalPath), work)
        validateVideoOnly(original, readMedia(probe, joined), request)
        return joined
    }

    private fun validCheckpoint(file: File, receipt: File): Boolean = file.isFile && receipt.isFile && runCatching {
        val hash = receipt.readText().trim()
        hash.matches(Regex("[a-f0-9]{64}")) && engineSha256(file) == hash
    }.getOrDefault(false)

    private suspend fun encode(executable: File, source: File, destination: File, media: Media,
        request: EngineVideoRequest, progress: (EngineProgress) -> Unit) {
        val args = mutableListOf("--no-config", source.canonicalPath, "-o", destination.canonicalPath,
            "--progress", "lines", "--encoder", request.encoder, "--stop-on-damage")
        val model = request.model
        if (model == null) args += "--no-upscale" else {
            args += listOf("--model", File(model.path).canonicalPath)
            if (request.scale == 4 && model.nativeScale == 2) args += listOf("--model", File(model.path).canonicalPath)
            if (request.scale == 1 && model.nativeScale != 1) args += listOf("--height", media.height.toString())
        }
        (request.targetFps ?: request.frameMultiplier?.let { media.fps * it })?.let { target ->
            val numerator = (target * 1_000_000).roundToLong() * media.fpsDenominator
            val denominator = 1_000_000L * media.fpsNumerator
            val divisor = gcd(numerator, denominator)
            val n = request.frameMultiplier?.toLong() ?: (numerator / divisor)
            val d = if (request.frameMultiplier != null) 1L else denominator / divisor
            require(n in 1..Int.MAX_VALUE && d in 1..Int.MAX_VALUE) { "目标帧率无法准确表示" }
            args += listOf("--rife-model", request.rifeVersion, "--rife-factor", "$n/$d",
                "--rife-scene-threshold", request.sceneThreshold.toString())
            if (model != null) args += "--rife-after-upscale"
            if (request.rifeEnsemble) args += "--rife-ensemble"
        }
        var previousFrame: Long? = null; var previousAt: Long? = null
        var videoDone = false
        runEngineProcess(executable, args) { line ->
            if (line.startsWith("VIDEO_DONE ")) videoDone = true
            if (line.startsWith("PROGRESS ")) {
                val values = line.substringAfter(' ').split(' ').mapNotNull { item ->
                    if ('=' in item) item.substringBefore('=') to item.substringAfter('=') else null
                }.toMap()
                val frame = values["frame"]?.toLongOrNull() ?: 0L
                val now = System.nanoTime()
                val delta = previousAt?.let { now - it }
                val rate = if (frame > 0 && previousFrame != null && frame > previousFrame!! && delta != null && delta > 0)
                    (frame - previousFrame!!) * 1_000_000_000.0 / delta else null
                if (frame > 0) { previousFrame = frame; previousAt = now }
                progress(EngineProgress(if (values["phase"] == "build_engine") EngineStage.MODEL_LOAD else EngineStage.ENCODE,
                    frame, values["total"]?.toLongOrNull()?.takeIf { it > 0 }, rate,
                    if (values["phase"] == "build_engine") "准备模型" else "已完成帧吞吐；不是显示 FPS"))
            } else if (line.startsWith("NATIVE_KERNELS ")) progress(EngineProgress(EngineStage.PROCESS,
                detail = if (line.contains("not used", true)) "当前模型使用兼容推理后端" else "引擎报告原生 GPU Kernel 已启用"))
        }
        check(videoDone && destination.isFile && destination.length() > 0) { "引擎没有返回完整视频完成回执" }
    }

    private suspend fun readMedia(executable: File, file: File): Media {
        val log = runEngineProcess(executable, listOf("-v", "error", "-show_streams", "-show_format", "-show_chapters", "-of", "json", file.canonicalPath),
            timeoutMillis = 120_000)
        val json = Json.parseToJsonElement(log).jsonObject
        val streams = (json["streams"] as? JsonArray)?.map { it.jsonObject } ?: error("视频流元数据不可读")
        require(streams.count { it.text("codec_type") == "video" && it.jsonObjectAt("disposition").number("attached_pic") != 1 } == 1) {
            "当前离线增强只接受单视频轨；原文件及多视角数据保持原样"
        }
        require(streams.all { it.text("codec_type") in setOf("video", "audio", "subtitle", "attachment") }) {
            "当前媒体含不支持的数据流；不会静默丢弃"
        }
        require(streams.none { it.jsonObjectAt("disposition").number("attached_pic") == 1 }) {
            "当前离线增强尚不能可靠迁移封面视频轨；原文件保持原样"
        }
        val video = streams.first { it.text("codec_type") == "video" && it.jsonObjectAt("disposition").number("attached_pic") != 1 }
        require(video.text("color_transfer") !in setOf("smpte2084", "arib-std-b67")) { "当前超分模型不处理 HDR；保留原视频" }
        require((video["side_data_list"] as? JsonArray).orEmpty().none { element ->
            val data = element.jsonObject
            (data["rotation"] as? JsonPrimitive)?.doubleOrNull?.let { abs(it) > 0.01 } == true ||
                data.text("side_data_type").contains("Dolby", ignoreCase = true)
        }) { "当前媒体方向或动态 HDR 元数据尚不能可靠保留" }
        val rate = (video.text("avg_frame_rate").takeUnless { it == "0/0" } ?: video.text("r_frame_rate")).split('/')
        val numerator = rate.firstOrNull()?.toLongOrNull() ?: error("原始帧率未知")
        val denominator = rate.getOrNull(1)?.toLongOrNull() ?: 1
        require(numerator > 0 && denominator > 0)
        val format = json.jsonObjectAt("format")
        val duration = format.decimal("duration") ?: video.decimal("duration") ?: error("视频时长未知")
        require(duration.isFinite() && duration > 0)
        fun stableTags(stream: JsonObject): Map<String, String> = stream.jsonObjectAt("tags")
            .filterKeys { it.lowercase() !in setOf("encoder", "duration", "bps", "number_of_frames", "number_of_bytes", "_statistics_writing_app", "_statistics_writing_date_utc", "_statistics_tags") }
            .map { (key, value) -> key.lowercase() to value.jsonPrimitive.content }.toMap()
        fun signature(stream: JsonObject): String {
            val tags = stableTags(stream)
            return listOf(stream.text("codec_name"), stream.text("codec_type"), stream.text("channels"), stream.text("sample_rate"),
                tags.toSortedMap().toString(), stream.jsonObjectAt("disposition").toSortedMap().toString()).joinToString("|")
        }
        val chapters = (json["chapters"] as? JsonArray).orEmpty().map { element ->
            val chapter = element.jsonObject
            listOf(((chapter.decimal("start_time") ?: 0.0) * 1000).roundToLong(),
                ((chapter.decimal("end_time") ?: 0.0) * 1000).roundToLong(), stableTags(chapter).toSortedMap()).joinToString("|")
        }
        val tags = format.jsonObjectAt("tags").filterKeys { it.lowercase() !in setOf("encoder", "creation_time", "major_brand", "minor_version", "compatible_brands") }
            .map { (key, value) -> key.lowercase() to value.jsonPrimitive.content }.toMap()
        return Media(video.number("width") ?: error("视频宽度未知"), video.number("height") ?: error("视频高度未知"), duration,
            numerator, denominator, video.decimal("start_time") ?: 0.0, format.decimal("start_time") ?: 0.0,
            streams.filter { it.text("codec_type") == "audio" }.map(::signature),
            streams.filter { it.text("codec_type") == "subtitle" }.map(::signature),
            streams.filter { it.text("codec_type") == "attachment" || it.jsonObjectAt("disposition").number("attached_pic") == 1 }.map(::signature),
            chapters, tags, streams.filter { it.text("codec_type") == "audio" }.map { it.decimal("start_time") },
            streams.filter { it.text("codec_type") == "subtitle" }.map { it.decimal("start_time") }, stableTags(video))
    }

    private fun validateVideoOnly(source: Media, output: Media, request: EngineVideoRequest) {
        check(output.width.toLong() == source.width.toLong() * request.scale && output.height.toLong() == source.height.toLong() * request.scale) {
            "增强输出尺寸与目标倍率不一致"
        }
        val target = request.targetFps ?: source.fps * (request.frameMultiplier ?: 1)
        check(abs(output.fps - target) <= maxOf(0.001, target * 0.0001)) { "增强输出帧率与目标不一致" }
        check(abs(output.videoStart - source.videoStart) <= 0.002) { "增强视频起始时间发生偏移，不能安全替换" }
        check(abs(output.duration - source.duration) <= maxOf(0.1, 2.0 / target)) { "增强输出时间轴偏移，不能安全替换" }
    }

    private fun sameStarts(source: List<Double?>, output: List<Double?>): Boolean = source.size == output.size &&
        source.zip(output).all { (before, after) -> if (before == null || after == null) before == after else abs(before - after) <= 0.002 }

    private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.decimal(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.jsonObjectAt(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())
    private fun gcd(first: Long, second: Long): Long { var a = first; var b = second; while (b != 0L) { val old = b; b = a % b; a = old }; return a }
}
