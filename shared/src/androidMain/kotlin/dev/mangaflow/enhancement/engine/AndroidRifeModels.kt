package dev.mangaflow.enhancement.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Exact FP32 v2 exports, individually identified from the official vs-mlrt release archives. */
internal class AndroidRifeModels(private val directory: File) {
    private data class Source(val version: String, val stem: String, val bytes: Long, val sha256: String) {
        val filename get() = "rife_v$stem.onnx"
    }

    private val sources = listOf(
        Source("4.25-lite", "4.25_lite", 22_538_831L, "610b5de57cdcfbcce9914c23e60a1cd357779a6f9582a1bcfcb035f8eb38509b"),
        Source("4.22", "4.22", 37_365_121L, "cf0c5e9f6bf0fad2a794addf534127ecc9896a65df14d51d6cf4526c9cc74738"),
        Source("4.26", "4.26", 22_748_049L, "af25762dfec02a4bbb949decea63988b01fa56c46c0ff9dc66ac8e2f12cbb661"),
    )

    fun installed(): List<EngineModel> = sources.mapNotNull { source ->
        File(directory, source.filename).takeIf { it.isFile && it.length() == source.bytes }?.let { file ->
            if (engineSha256(file) == source.sha256) descriptor(source, file) else null
        }
    }

    fun find(version: String, digest: String?): EngineModel? = installed()
        .firstOrNull { it.version == version && (digest == null || it.sha256 == digest) }

    suspend fun tryImport(file: File, name: String): EngineModel? {
        val source = sources.firstOrNull { it.filename.equals(name, ignoreCase = true) || it.bytes == file.length() }
        if (source == null) {
            require(!name.startsWith("rife_", ignoreCase = true)) { "请选择安装说明所列的 RIFE FP32 v2 文件" }
            return null
        }
        require(file.length() == source.bytes && engineSha256(file) == source.sha256) {
            "RIFE 模型摘要不匹配。请从官方包提取 rife_v2 目录中的 FP32 文件"
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法保存 RIFE 模型" }
        val target = File(directory, source.filename)
        if (target.isFile && target.length() == source.bytes && engineSha256(target) == source.sha256)
            return descriptor(source, target)
        val temporary = File(directory, "${UUID.randomUUID()}.partial")
        try {
            val coroutine = currentCoroutineContext()
            file.inputStream().use { input ->
                Files.newOutputStream(temporary.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.length() == source.bytes && engineSha256(temporary) == source.sha256) { "RIFE 模型写入校验失败" }
            coroutine.ensureActive()
            Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            return descriptor(source, target)
        } finally {
            temporary.delete()
        }
    }

    private fun descriptor(source: Source, file: File) = EngineModel(
        "rife:${source.sha256}", EnhancementEngineIds.ANDROID_RIFE, "RIFE ${source.version} · FP32 v2",
        file.canonicalPath, source.sha256, 1, setOf(EngineContent.VIDEO), "上游 RIFE 与导出模型各自许可",
        source.version, source.bytes, inputType = "NCHW_RGB_PAIR_TIME_FLOAT",
    )
}
