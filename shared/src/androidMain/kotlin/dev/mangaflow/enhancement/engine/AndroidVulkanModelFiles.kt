package dev.mangaflow.enhancement.engine

import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

internal data class AndroidVulkanModelPart(val name: String, val bytes: Long, val sha256: String) {
    fun matches(file: File) = file.isFile && file.length() == bytes && engineSha256(file) == sha256
}

internal suspend fun installAndroidVulkanModel(
    runtimes: PanelRuntimeStore,
    installed: File,
    url: String,
    parts: List<AndroidVulkanModelPart>,
    progress: (EngineProgress) -> Unit,
) {
    val runtimeId = PanelRuntimeId.CUGAN
    if (!runtimes.states.value.getValue(runtimeId).installed) {
        runtimes.download(runtimeId)
        val status = runtimes.states.first { options ->
            val current = options.getValue(runtimeId)
            if (current.downloading) progress(EngineProgress(EngineStage.DOWNLOAD, current.bytes,
                current.totalBytes.takeIf { it > 0L }, detail = "正在下载签名 ncnn Vulkan 组件", bytes = current.bytes))
            !current.downloading
        }.getValue(runtimeId)
        check(status.installed) { status.error ?: "ncnn Vulkan 组件未能安装" }
    }
    check(!runtimes.states.value.getValue(runtimeId).pendingRemoval) { "ncnn Vulkan 组件将在重启后移除" }
    if (parts.all { it.matches(File(installed, it.name)) }) return
    val folder = checkNotNull(installed.parentFile)
    check(folder.isDirectory || folder.mkdirs()) { "无法创建 Vulkan 模型目录" }
    val temporary = File(folder, "download-${UUID.randomUUID()}").also { check(it.mkdir()) }
    val total = parts.sumOf { it.bytes }
    try {
        var count = 0L
        for (part in parts) {
            val output = File(temporary, part.name)
            val connection = URL("$url/${part.name}").openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 40_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                check(connection.responseCode == 200) { "Vulkan 模型下载 HTTP ${connection.responseCode}" }
                var partBytes = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(output).use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            partBytes += read
                            check(partBytes <= part.bytes) { "Vulkan 模型文件长度异常" }
                            stream.write(buffer, 0, read)
                            progress(EngineProgress(EngineStage.DOWNLOAD, count + partBytes, total, bytes = count + partBytes))
                        }
                        stream.fd.sync()
                    }
                }
                progress(EngineProgress(EngineStage.VERIFY, detail = "核对官方文件大小与 SHA-256"))
                check(part.matches(output)) { "Vulkan 模型摘要不符，已拒绝安装" }
                count += partBytes
            } finally { connection.disconnect() }
        }
        currentCoroutineContext().ensureActive()
        // Both members are checked before their version directory becomes visible.
        if (installed.exists()) check(installed.deleteRecursively()) { "无法移除损坏的旧模型" }
        Files.move(temporary.toPath(), installed.toPath(), ATOMIC_MOVE)
        check(parts.all { it.matches(File(installed, it.name)) }) { "Vulkan 模型安装尚未确认" }
    } finally { temporary.deleteRecursively() }
}
