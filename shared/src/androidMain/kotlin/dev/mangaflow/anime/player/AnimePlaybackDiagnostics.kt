@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.media3.common.PlaybackException
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Bounded private playback observations, exported only through the existing error report. */
object AnimePlaybackDiagnostics {
    private const val FILE_NAME = "anime-playback-errors.json"
    private const val MAX_RECORDS = 12
    private const val MAX_BYTES = 64 * 1024
    private val lock = Any()
    private val sourceIdPattern = Regex("[A-Za-z0-9._:-]{1,96}")

    /** The caller captures the actual session before dispatching this write off Main. */
    internal fun record(
        context: Context,
        observedAt: Long,
        sourceId: String?,
        sessionGeneration: Long,
        nativeGeneration: Long,
        playerPhase: String,
        error: PlaybackException,
    ) {
        runCatching {
            synchronized(lock) {
                val causes = JSONArray()
                var cause: Throwable? = error
                val seen = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<Throwable, Boolean>())
                while (causes.length() < 8) {
                    val current = cause ?: break
                    if (!seen.add(current)) break
                    // Class names are structural evidence. Messages, URLs and headers are never read.
                    causes.put(current.javaClass.name.take(160))
                    cause = current.cause
                }
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode
                    else packageInfo.versionCode.toLong()
                val row = JSONObject()
                    .put("observedAt", observedAt)
                    .put("versionCode", versionCode)
                    .put("sourceId", sourceId?.takeIf(sourceIdPattern::matches) ?: "unknown")
                    .put("sessionGeneration", sessionGeneration)
                    .put("nativeGeneration", nativeGeneration)
                    .put("playerPhase", playerPhase.takeIf { it in setOf("IDLE", "OPENING", "READY", "ENDED", "ERROR", "RELEASED") } ?: "UNKNOWN")
                    .put("stage", stage(error.errorCode))
                    .put("errorCode", error.errorCode)
                    .put("causeTypes", causes)
                val previous = readRows(context)
                val ordered = ((0 until previous.length()).mapNotNull(previous::optJSONObject) + row)
                    .sortedBy { it.optLong("observedAt") }.takeLast(MAX_RECORDS)
                val rows = JSONArray()
                ordered.forEach { rows.put(it) }
                val bytes = rows.toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) return@synchronized
                val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    file.finishWrite(stream)
                } catch (failure: Throwable) {
                    file.failWrite(stream)
                    throw failure
                }
            }
        }
        // Failure to save diagnostics must not replace the observed playback error.
    }

    /** Read fresh from disk; the report Activity runs in a separate diagnostics process. */
    fun readSavedReport(context: Context): String? = runCatching {
        synchronized(lock) {
            val rows = readRows(context)
            if (rows.length() == 0) return@synchronized null
            buildString {
                for (index in maxOf(0, rows.length() - MAX_RECORDS) until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    if (!row.has("observedAt") || !row.has("errorCode") || !row.has("versionCode") ||
                        !row.has("sessionGeneration") || !row.has("nativeGeneration")) continue
                    val source = row.optString("sourceId").takeIf(sourceIdPattern::matches) ?: "unknown"
                    val code = row.getInt("errorCode")
                    appendLine("UTC ${Instant.ofEpochMilli(row.getLong("observedAt"))} · build=${row.getLong("versionCode")}")
                    appendLine("source=$source · session=${row.getLong("sessionGeneration")} · native=${row.getLong("nativeGeneration")}")
                    val phase = row.optString("playerPhase").takeIf {
                        it in setOf("IDLE", "OPENING", "READY", "ENDED", "ERROR", "RELEASED", "UNKNOWN")
                    } ?: "UNKNOWN"
                    appendLine("Media3=$code · stage=${stage(code)} · state=$phase")
                    val causes = row.optJSONArray("causeTypes")
                    val names = if (causes == null) emptyList() else
                        (0 until minOf(causes.length(), 8)).mapNotNull {
                            causes.optString(it).takeIf { name ->
                                name.length in 1..160 && name.all { character ->
                                    character.isLetterOrDigit() || character in "._$"
                                }
                            }
                        }
                    if (names.isNotEmpty()) appendLine("causes=${names.joinToString(" → ")}")
                    appendLine()
                }
            }.trim().takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private fun readRows(context: Context): JSONArray = runCatching {
        AtomicFile(File(context.noBackupFilesDir, FILE_NAME)).openRead().use { input ->
            val bytes = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read <= 0) break
                count += read
            }
            if (count > MAX_BYTES) JSONArray()
            else JSONArray(bytes.decodeToString(0, count))
        }
    }.getOrElse { JSONArray() }

    private fun stage(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "manifest"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "container"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "network"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "decoder"
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> "video-processing"
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED -> "drm"
        else -> "player"
    }
}
