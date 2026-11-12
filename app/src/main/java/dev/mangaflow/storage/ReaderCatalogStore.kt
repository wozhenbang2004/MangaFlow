package dev.mangaflow.storage

import android.content.Context
import android.util.AtomicFile
import dev.mangaflow.data.MangaDetail
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A saved directory lets local images participate in the ordinary reader after a restart. */
class ReaderCatalogStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "reader-catalogs")
    private val lock = Any()

    suspend fun read(mangaId: String): MangaDetail? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            runCatching {
                val file = fileFor(mangaId)
                val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                val metadata = json.getJSONObject("manga")
                val manga = readManga(metadata).copy(sourceUuid = metadata.optString("sourceUuid"))
                if (manga.id != mangaId) return@synchronized null
                val array = json.getJSONArray("chapters")
                val chapters = (0 until array.length()).map { readChapter(array.getJSONObject(it)) }
                    .distinctBy { it.id }.sortedBy { it.index }
                if (chapters.isEmpty()) null else MangaDetail(manga, chapters)
            }.getOrNull()
        }
    }

    suspend fun save(detail: MangaDetail) = withContext(Dispatchers.IO) {
        if (detail.chapters.isEmpty()) return@withContext
        synchronized(lock) {
            // A full disk may prevent caching, but must not stop a readable online chapter.
            runCatching {
                check(root.isDirectory || root.mkdirs())
                val bytes = JSONObject().apply {
                    put("manga", detail.manga.toJson().put("sourceUuid", detail.manga.sourceUuid))
                    put("chapters", JSONArray(detail.chapters.map { it.toJson() }))
                }.toString().toByteArray(Charsets.UTF_8)
                val file = AtomicFile(fileFor(detail.manga.id))
                val stream = file.startWrite()
                try {
                    stream.write(bytes)
                    file.finishWrite(stream)
                } catch (failure: Exception) {
                    file.failWrite(stream)
                    throw failure
                }
            }
        }
        Unit
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        synchronized(lock) { root.listFiles()?.sumOf { it.length() } ?: 0L }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            root.listFiles()?.forEach { file ->
                check(file.delete() || !file.exists()) { "目录缓存未完全清除，请重试" }
            }
        }
        Unit
    }

    private fun fileFor(mangaId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(mangaId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$key.json")
    }
}
