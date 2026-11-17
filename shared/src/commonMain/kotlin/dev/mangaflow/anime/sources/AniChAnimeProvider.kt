package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Original native implementation of anonymous AniCh wire contracts observed 2026-11-16.
 * No external implementation, embedded credentials, guest registration or remote JS is bundled.
 * Automatic station selection stays inside the selected actual episode; explicit stations stay exact.
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
            // The episode feed is the catalogue. Resolving every episode's media stations here
            // made opening a long show wait on hundreds of requests and hid unfinished episodes.
            // Keep exact feed identities; obtain stations only for the episode the user selects.
            val episodes = available.map { (sort, caption) ->
                val label = "第 $sort 集" + caption.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                AnimeSourceEpisode(id = "${title.id}:$sort:auto", title = label,
                    url = vodUrl(title.id, sort), number = sort.toDouble(), group = "自动线路",
                    playbackData = JSONObject().put("work", title.id).put("sort", sort).put("automatic", true).toString())
            }
            if (episodes.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
                "AniCh 未返回实际可选剧集，未按总集数补造。")
            checkRoute(http, AnimeSourceStage.CATALOG)
            AnimeSourceCatalog(title, episodes)
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        require(catalog.title.sourceId == id && catalog.title.url == detailUrl(catalog.title.id) && catalog.episodes.any { it == episode })
        val selected = JSONObject(requireNotNull(episode.playbackData))
        val work = selected.getString("work")
        val sort = selected.getLong("sort")
        val automatic = selected.optBoolean("automatic", false)
        val slug = if (automatic) null else selected.getString("slug")
        require(work == catalog.title.id && episode.url == vodUrl(work, sort))
        require(if (automatic) episode.id == "$work:$sort:auto" && episode.group == "自动线路"
            else episode.group == slug && episode.id == "$work:$sort:${animeHash(requireNotNull(slug))}")
        val http = AnimeHttp(id)
        return try {
            val available = lines(http, work, sort, AnimeSourceStage.RESOLVE)
            // Automatic is explicit in the catalogue; legacy user-selected stations stay exact.
            val line = if (automatic) available.firstOrNull() else available.singleOrNull { it.slug == slug }
            if (line == null) throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                "所选 AniCh 剧集暂未提供可用线路，请重试或选择其他来源。")
            val stream = if (!automatic) AnimeStreamResolver(http).resolve(line.url, homeUrl)
                else resolveAutomatic(http, available)
            checkRoute(http, AnimeSourceStage.RESOLVE)
            AnimePlaybackAsset(id = "$id:${animeHash(episode.id + "\n" + animeContentIdentity(stream.url))}",
                sourceId = id, sourceEpisodeId = episode.id, url = stream.url, route = http.route,
                title = episode.title, edition = episode.edition, mimeType = stream.mimeType,
                headers = mapOf("User-Agent" to AnimeHttp.USER_AGENT, "Referer" to stream.referer),
                subtitles = stream.subtitles, sourceRevision = revision)
        } finally { http.close() }
    }

    private suspend fun resolveAutomatic(http: AnimeHttp, available: List<Line>): ResolvedAnimeStream {
        var lastFailure: Exception? = null
        for (line in available.take(3)) {
            checkRoute(http, AnimeSourceStage.RESOLVE)
            try {
                withTimeoutOrNull(5_000L) { AnimeStreamResolver(http).resolve(line.url, homeUrl) }
                    ?.let { return it }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { lastFailure = failure }
        }
        throw lastFailure ?: AnimeSourceException(id, AnimeSourceStage.RESOLVE,
            "所选剧集的线路解析超时，请重试或选择其他来源。")
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
