package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.net.DownloadRouteSnapshot
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Original native implementation of anonymous AniCh wire contracts observed 2026-11-16.
 * No external implementation, embedded credentials, guest registration or remote JS is bundled.
 * Each returned station belongs to its actual episode. Missing stations fail, never switch.
 */
internal class AniChAnimeProvider(override val revision: String) : AnimeProvider {
    override val id = "anime-native-ani"
    override val name = "AniCh"
    override val homeUrl = "https://anich.sends.eu.org/"

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && page >= 1)
        val http = AnimeHttp(id)
        return try {
            val url = animePublicUrl(homeUrl).newBuilder().addPathSegments("bangumi/search")
                .addQueryParameter("keyword", query.trim()).addQueryParameter("skip", ((page.toLong() - 1) * 30).toString())
                .addQueryParameter("nsfw", "false").addQueryParameter("res", "true").build()
            val response = http.request(url, AnimeSourceStage.SEARCH, referer = homeUrl)
            withContext(Dispatchers.Default) {
                AniChWire(response.bytes).messages(1).map { row ->
                    val remoteId = row.number(1).also { require(it > 0) }.toString()
                    val label = row.string(2).also { require(it.isNotBlank()) }
                    AnimeSourceTitle(id, remoteId, label, detailUrl(remoteId), sourceCover(homeUrl, row.string(7)),
                        episodeCount = row.number(4).takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt(),
                        updateText = row.string(8).ifBlank { row.string(5) }, sourceRevision = revision)
                }.distinctBy { it.id }
            }
        } finally { http.close() }
    }

    private fun remoteId(value: String): String = value.also { require(it.matches(Regex("[1-9][0-9]{0,18}"))) }
    private fun detailUrl(value: String): String = homeUrl + "bangumi/detail/" + remoteId(value)
    private fun vodUrl(value: String, sort: Long): String = homeUrl + "vod/" + remoteId(value) + "/" + sort.also { require(it > 0) }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        require(title.sourceId == id && title.url == detailUrl(title.id))
        val http = AnimeHttp(id)
        return try {
            // No synthesized episodes from a metadata total when the actual episode feed is absent.
            val response = http.request(animePublicUrl(homeUrl + "bangumi/episodes/" + title.id), AnimeSourceStage.CATALOG, referer = homeUrl)
            val available = withContext(Dispatchers.Default) {
                AniChWire(response.bytes).messages(1).filter { it.number(1) == 1L }.map { row ->
                    row.number(2).also { require(it in 1L..Int.MAX_VALUE.toLong()) } to row.string(8)
                }.also { rows -> require(rows.map { it.first }.distinct().size == rows.size) }.sortedBy { it.first }
            }
            val cacheKey = CatalogueKey(title.id, revision, http.route)
            val entry = synchronized(cacheLock) {
                val now = wallTimeMillis()
                catalogueCache.entries.removeAll { now - it.value.touchedAt > 120_000L }
                if (cacheKey !in catalogueCache && catalogueCache.size >= 16)
                    catalogueCache.minByOrNull { it.value.touchedAt }?.key?.let(catalogueCache::remove)
                catalogueCache.getOrPut(cacheKey) { CatalogueEntry(now) }.also { entry ->
                    entry.touchedAt = now
                    entry.stations.entries.removeAll { now - it.value.observedAt > 120_000L }
                }
            }
            val missing = synchronized(cacheLock) {
                available.filter { it.first !in entry.stations }.sortedBy { entry.attempts[it.first] ?: 0 }
            }
            // Return already-known real rows promptly. A slow episode cannot hold the entire work;
            // another refresh continues missing episodes using the short-lived route/revision cache.
            withTimeoutOrNull(8_000L) {
                for (batch in missing.chunked(4)) coroutineScope {
                    batch.map { (sort, _) -> async {
                        try {
                            synchronized(cacheLock) { entry.attempts[sort] = (entry.attempts[sort] ?: 0) + 1 }
                            val stations = withTimeoutOrNull(2_500L) {
                                lines(http, title.id, sort, AnimeSourceStage.CATALOG).map { it.slug }
                            }
                            if (!stations.isNullOrEmpty()) synchronized(cacheLock) {
                                entry.stations[sort] = StationObservation(stations, wallTimeMillis())
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { /* This episode remains explicitly incomplete and retryable. */ }
                    } }.awaitAll()
                }
            }
            val stations = synchronized(cacheLock) {
                val now = wallTimeMillis()
                entry.stations.filterValues { now - it.observedAt <= 120_000L }.mapValues { it.value.slugs }
            }
            val episodes = available.flatMap { (sort, caption) ->
                stations[sort].orEmpty().map { slug ->
                    val label = "第 $sort 集" + caption.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                    AnimeSourceEpisode(id = "${title.id}:$sort:${animeHash(slug)}", title = label,
                        url = vodUrl(title.id, sort), number = sort.toDouble(), group = slug,
                        playbackData = JSONObject().put("work", title.id).put("sort", sort).put("slug", slug).toString())
                }
            }
            val pending = available.count { it.first !in stations }
            if (episodes.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
                if (pending > 0) "AniCh 剧集目录已读取，线路尚未加载成功，请刷新重试。"
                else "AniCh 未返回实际可选线路，未按总集数补造。")
            checkRoute(http, AnimeSourceStage.CATALOG)
            AnimeSourceCatalog(title, episodes, if (pending > 0)
                "已加载 ${available.size - pending}/${available.size} 集的线路；其余 $pending 集尚未完成，刷新可继续。" else null)
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        require(catalog.title.sourceId == id && catalog.title.url == detailUrl(catalog.title.id) && catalog.episodes.any { it == episode })
        val selected = JSONObject(requireNotNull(episode.playbackData))
        val work = selected.getString("work")
        val sort = selected.getLong("sort")
        val slug = selected.getString("slug")
        require(work == catalog.title.id && episode.url == vodUrl(work, sort) && episode.group == slug &&
            episode.id == "$work:$sort:${animeHash(slug)}")
        val http = AnimeHttp(id)
        return try {
            val line = lines(http, work, sort, AnimeSourceStage.RESOLVE).singleOrNull { it.slug == slug }
                ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE, "所选 AniCh 线路已不可用，请刷新目录并重新选择；未自动替换线路。")
            val stream = AnimeStreamResolver(http).resolve(line.url, homeUrl)
            checkRoute(http, AnimeSourceStage.RESOLVE)
            AnimePlaybackAsset(id = "$id:${animeHash(episode.id + "\n" + animeContentIdentity(stream.url))}",
                sourceId = id, sourceEpisodeId = episode.id, url = stream.url, route = http.route,
                title = episode.title, edition = episode.edition, mimeType = stream.mimeType,
                headers = mapOf("User-Agent" to AnimeHttp.USER_AGENT, "Referer" to stream.referer),
                subtitles = stream.subtitles, sourceRevision = revision)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            synchronized(cacheLock) { catalogueCache[CatalogueKey(work, revision, http.route)]?.stations?.remove(sort) }
            throw failure
        } finally { http.close() }
    }

    private fun checkRoute(http: AnimeHttp, stage: AnimeSourceStage) {
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(id, DownloadCategory.ANIME))
            throw AnimeSourceException(id, stage, "动漫来源路由已变化，请重新读取。")
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun lines(http: AnimeHttp, work: String, sort: Long, stage: AnimeSourceStage): List<Line> {
        val response = http.request(animePublicUrl(vodUrl(work, sort)), stage, referer = homeUrl)
        return withContext(Dispatchers.Default) {
            val array = JSONArray(response.text)
            require(array.length() <= 8 * 1024 * 1024)
            val bytes = ByteArray(array.length()) { index ->
                val number = array.getInt(index)
                require(number in 0..255)
                number.toByte()
            }
            val rows = AniChWire(bytes).messages(1).map { row ->
                val slug = row.string(5).also { require(it.isNotBlank() && it.length <= 256) }
                val encoded = row.string(1).also { require(it.length in 5..32768) }
                // Public wire encoding, not a secret: URL-safe Base64 with one inserted character.
                val unpadded = encoded.removeRange(3, 4).replace('-', '+').replace('_', '/')
                val normalized = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)
                val url = Base64.decode(normalized).decodeToString(throwOnInvalidSequence = true)
                Line(slug, animePublicUrl(url).toString())
            }
            require(rows.map { it.slug }.distinct().size == rows.size) { "AniCh 返回重复线路标识，无法确定所选资源。" }
            rows
        }
    }

    private data class Line(val slug: String, val url: String)
    private data class CatalogueKey(val work: String, val revision: String, val route: DownloadRouteSnapshot)
    private data class StationObservation(val slugs: List<String>, val observedAt: Long)
    private class CatalogueEntry(var touchedAt: Long) {
        val stations = mutableMapOf<Long, StationObservation>()
        val attempts = mutableMapOf<Long, Int>()
    }
    companion object {
        private val cacheLock = SynchronizedObject()
        // Only observed station identities, no media URLs, account state or persisted credentials.
        private val catalogueCache = mutableMapOf<CatalogueKey, CatalogueEntry>()
    }
}

/** Strict, bounded protobuf wire reader. Field meanings stay at the call sites above. */
private class AniChWire(private val bytes: ByteArray) {
    private data class Field(val number: Int, val integer: Long? = null, val data: ByteArray? = null)
    private val fields: List<Field> = buildList {
        var position = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..56 step 7) {
                require(position < bytes.size)
                val byte = bytes[position++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            error("AniCh 整数字段越界。")
        }
        while (position < bytes.size) {
            require(size < 100_000)
            val tag = varint()
            val number = (tag ushr 3).also { require(it in 1..536870911) }.toInt()
            when ((tag and 7).toInt()) {
                0 -> add(Field(number, integer = varint()))
                2 -> {
                    val count = varint().also { require(it <= bytes.size - position) }.toInt()
                    add(Field(number, data = bytes.copyOfRange(position, position + count)))
                    position += count
                }
                1, 5 -> { val count = if ((tag and 7) == 1L) 8 else 4; require(count <= bytes.size - position); position += count; add(Field(number)) }
                else -> error("AniCh 返回不支持的数据格式。")
            }
        }
    }
    private fun field(number: Int): Field? = fields.filter { it.number == number }
        .also { require(it.size <= 1) { "AniCh 返回重复标量字段。" } }.firstOrNull()
    fun number(number: Int): Long = field(number)?.let { requireNotNull(it.integer) } ?: 0L
    fun string(number: Int): String = field(number)?.let { requireNotNull(it.data).decodeToString(throwOnInvalidSequence = true) }.orEmpty()
    fun messages(number: Int): List<AniChWire> = fields.filter { it.number == number }.map { AniChWire(requireNotNull(it.data)) }
}
