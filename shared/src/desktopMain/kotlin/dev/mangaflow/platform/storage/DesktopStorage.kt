package dev.mangaflow.platform.storage

import dev.mangaflow.platform.PlatformContext
import java.util.Base64
import java.util.concurrent.TimeUnit

actual fun dataDirectory(context: PlatformContext): StorageFile = StorageFile(context.dataRoot).also { check(it.isDirectory || it.mkdirs()) }
actual fun cacheDirectory(context: PlatformContext): StorageFile = StorageFile(context.cacheRoot).also { check(it.isDirectory || it.mkdirs()) }
actual fun availableSpace(file: StorageFile): Long = java.io.File(file.path).usableSpace
actual fun syncStorageFile(file: StorageFile) {
    java.nio.channels.FileChannel.open(java.nio.file.Paths.get(file.path), java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
}
actual fun preferences(context: PlatformContext, name: String): Preferences {
    require(Regex("[a-zA-Z0-9_.-]{1,120}").matches(name))
    return FilePreferences.get(StorageFile(StorageFile(dataDirectory(context), "preferences"), "$name.json"))
}

private fun secretFile(context: PlatformContext, key: String): java.io.File {
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    return java.io.File(dataDirectory(context).path, "secrets/$hash.dpapi")
}
private fun runSecretCommand(arguments: List<String>, input: String? = null, allowMissing: Boolean = false): String? {
    val process = try { ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start() }
    catch (failure: Exception) { throw IllegalStateException("系统安全凭据存储不可用，请启用系统钥匙串后重试。", failure) }
    process.outputStream.use { output -> input?.let { output.write(it.toByteArray(Charsets.UTF_8)) } }
    val output = java.util.concurrent.CompletableFuture.supplyAsync {
        process.inputStream.bufferedReader().use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4096)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= 2 * 1024 * 1024) { "安全凭据超出大小限制。" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    }
    check(process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); "系统安全凭据存储没有响应。" }
    val text = output.get(5, TimeUnit.SECONDS)
    if (allowMissing && process.exitValue() in setOf(1, 44)) return null
    check(process.exitValue() == 0) { "系统安全凭据存储拒绝了此操作。" }
    require(text.length <= 1024 * 1024) { "安全凭据超出大小限制。" }
    return text.trimEnd('\r', '\n')
}
private val desktopOs get() = System.getProperty("os.name").lowercase()
private fun service(key: String) = "dev.mangaflow.$key".also { require(key.length in 1..120 && key.none { it.isISOControl() }) }

actual fun readSecret(context: PlatformContext, key: String): String? = when {
    desktopOs.contains("win") -> {
        val file = secretFile(context, key)
        if (!file.isFile) null else {
            require(file.length() <= 2L * 1024 * 1024) { "安全凭据文件超出大小限制。" }
            val script = "Add-Type -AssemblyName System.Security; \$v=[Convert]::FromBase64String([Console]::In.ReadToEnd()); \$r=[Security.Cryptography.ProtectedData]::Unprotect(\$v,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([Convert]::ToBase64String(\$r))"
            val result = runSecretCommand(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), file.readText())!!
            Base64.getDecoder().decode(result).toString(Charsets.UTF_8)
        }
    }
    desktopOs.contains("mac") -> runSecretCommand(listOf("security", "find-generic-password", "-a", "MangaFlow", "-s", service(key), "-w"), allowMissing = true)
        ?.let { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }
    else -> runSecretCommand(listOf("secret-tool", "lookup", "application", "MangaFlow", "key", key), allowMissing = true)
}

actual fun writeSecret(context: PlatformContext, key: String, value: String) {
    require(value.encodeToByteArray().size <= 1024 * 1024)
    when {
        desktopOs.contains("win") -> {
            val script = "Add-Type -AssemblyName System.Security; \$v=[Convert]::FromBase64String([Console]::In.ReadToEnd()); \$r=[Security.Cryptography.ProtectedData]::Protect(\$v,\$null,[Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([Convert]::ToBase64String(\$r))"
            val result = runSecretCommand(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)))!!
            val file = secretFile(context, key)
            check(file.parentFile.isDirectory || file.parentFile.mkdirs())
            val atomic = StorageAtomicFile(StorageFile(file.absolutePath))
            val stream = atomic.startWrite()
            try { stream.write(result.encodeToByteArray()); atomic.finishWrite(stream) }
            catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        }
        desktopOs.contains("mac") -> {
            val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
            val safeService = service(key).replace("\\", "\\\\").replace("\"", "\\\"")
            runSecretCommand(listOf("security", "-i"), "add-generic-password -U -a MangaFlow -s \"$safeService\" -w \"$encoded\"\n")
        }
        else -> runSecretCommand(listOf("secret-tool", "store", "--label=MangaFlow", "application", "MangaFlow", "key", key), value)
    }
}

actual fun deleteSecret(context: PlatformContext, key: String) {
    when {
        desktopOs.contains("win") -> secretFile(context, key).let { check(!it.exists() || it.delete()) }
        desktopOs.contains("mac") -> runSecretCommand(listOf("security", "delete-generic-password", "-a", "MangaFlow", "-s", service(key)), allowMissing = true)
        else -> runSecretCommand(listOf("secret-tool", "clear", "application", "MangaFlow", "key", key), allowMissing = true)
    }
}
