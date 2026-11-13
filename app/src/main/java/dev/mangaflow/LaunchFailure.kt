package dev.mangaflow

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.system.exitProcess

/** One private, bounded Java crash record; never uploaded automatically. */
internal object LaunchFailure {
    private const val MAX_BYTES = 64 * 1024
    private var installed = false
    private var record: AtomicFile? = null
    var recoveryReport: String? = null
        private set

    fun install(context: Context) {
        if (installed) return
        installed = true
        record = runCatching { AtomicFile(File(context.noBackupFilesDir, "last-failure.txt")) }.getOrNull()
        recoveryReport = readSavedReport(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { save(thread, failure) }
            if (previous != null) {
                previous.uncaughtException(thread, failure)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Read from the isolated diagnostics process without installing a second crash writer. */
    fun readSavedReport(context: Context): String? = runCatching {
        AtomicFile(File(context.noBackupFilesDir, "last-failure.txt")).openRead().use { input ->
            val bytes = ByteArray(MAX_BYTES)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            bytes.decodeToString(0, count).takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    fun retry() {
        record?.delete()
        recoveryReport = null
    }

    @Synchronized
    private fun save(thread: Thread, failure: Throwable) {
        val output = record ?: return
        val text = buildString {
            appendLine("MangaFlow ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${redact(thread.name)}")
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = failure
            var count = 0
            while (count < 12) {
                val current = cause ?: break
                if (!seen.add(current)) break
                appendLine("${if (count == 0) "" else "Caused by: "}${current.javaClass.name}: ${redact(current.message.orEmpty().take(2048))}")
                current.stackTrace.take(40).forEach { appendLine("  at $it") }
                cause = current.cause
                count++
            }
        }.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, MAX_BYTES)) }
        val stream = output.startWrite()
        try {
            stream.write(text)
            output.finishWrite(stream)
        } catch (failure: Throwable) {
            output.failWrite(stream)
            throw failure
        }
    }

    internal fun redact(message: String): String = message
        .replace(Regex("(?i)bearer\\s+[^\\s,;]+"), "Bearer [redacted]")
        .replace(Regex("(?i)(password|passwd|token|cookie|authorization)([\\\"'\\s:=]+)[^\\s,;\\\"']+"), "$1$2[redacted]")
        .replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"), "$1?[redacted]")
}
