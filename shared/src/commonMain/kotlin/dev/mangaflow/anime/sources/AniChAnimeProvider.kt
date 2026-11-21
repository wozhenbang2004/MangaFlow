package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.danmaku.AnimeDanmakuComment
import dev.mangaflow.anime.danmaku.AnimeDanmakuMode
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
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
    override val supportsLatest = true
    override val supportsLatestPagination = false

    override suspend fun latest(page: Int): List<AnimeSourceTitle> {
        require(page >= 1)
        if (page > 1) return emptyList()
        val stage = AnimeSourceStage.CATALOG
        val http = AnimeHttp(id)
        try {
            val url = animePublicUrl(homeUrl).newBuilder().addPathSegments("bangumi/latest")
                .addQueryParameter("nsfw", "false").addQueryParameter("res", "true").build()
            val response = http.request(url, stage, referer = homeUrl)
            val titles = withContext(Dispatchers.Default) {
                AniChWire(response.bytes).messages(1).mapNotNull { row ->
                    val remoteId = row.number(2).takeIf { it > 0 }?.toString() ?: return@mapNotNull null
                    val label = row.string(7).ifBlank { row.string(8) }.ifBlank { return@mapNotNull null }
                    AnimeSourceTitle(id, remoteId, label, detailUrl(remoteId), sourceCover(homeUrl, row.string(6)),
                        updateText = row.number(3).takeIf { it > 0 }?.let { "更新至第 $it 集" }.orEmpty(),
                        sourceRevision = revision)
                }.distinctBy { it.id }
            }
            checkRoute(http, stage)
            return titles
        } finally { http.close() }
    }

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

    suspend fun danmaku(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): List<AnimeDanmakuComment> {
        require(catalog.title.sourceId == id && catalog.title.url == detailUrl(catalog.title.id) && episode in catalog.episodes)
        val ticket = JSONObject(requireNotNull(episode.playbackData))
        val sort = ticket.getLong("sort")
        require(ticket.getString("work") == catalog.title.id && episode.url == vodUrl(catalog.title.id, sort))
        val stage = AnimeSourceStage.CATALOG
        val http = AnimeHttp(id)
        try {
            val comments = mutableListOf<AnimeDanmakuComment>()
            var skip = 0L
            repeat(20) {
                checkRoute(http, stage)
                val url = animePublicUrl(homeUrl).newBuilder().addPathSegment("danmaku")
                    .addQueryParameter("bangumi", catalog.title.id).addQueryParameter("episode", sort.toString())
                    .addQueryParameter("skip", skip.toString()).build()
                val response = http.request(url, stage, referer = homeUrl)
                val (next, rows) = withContext(Dispatchers.Default) {
                    val batch = AniChWire(response.bytes)
                    batch.number(2) to batch.messages(1).mapNotNull { row -> runCatching { aniChComment(row) }.getOrNull() }
                }
                checkRoute(http, stage)
                comments.addAll(rows.take(150_000 - comments.size))
                if (next <= skip || comments.size == 150_000) return comments.distinctBy { it.id }.sortedBy { it.timeMs }
                skip = next
            }
            return comments.distinctBy { it.id }.sortedBy { it.timeMs }
        } finally { http.close() }
    }

    private fun aniChComment(row: AniChWire): AnimeDanmakuComment? {
        val seconds = row.decimal(6)?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() / 1000 } ?: return null
        val text = row.string(4).trim().take(500).ifBlank { return null }
        val time = (seconds * 1000).toLong()
        val color = row.string(2).removePrefix("#").takeLast(6).toIntOrNull(16)?.takeIf { it in 0..0xffffff } ?: 0xffffff
        val mode = when (row.number(7)) {
            2L -> AnimeDanmakuMode.TOP
            3L -> AnimeDanmakuMode.BOTTOM
            else -> AnimeDanmakuMode.SCROLL
        }
        return AnimeDanmakuComment(animeHash("$time\n$color\n$mode\n$text"), time, text, color, mode)
    }

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
            // Identity evidence belongs to this exact work, never to the requested subject.
            // Search protobuf cards omit airdate; use the structured detail contract only.
            val identity = try {
                val detail = withTimeoutOrNull(2_000L) {
                    val expected = animePublicUrl(detailUrl(title.id))
                    val response = http.request(expected, AnimeSourceStage.CATALOG,
                        referer = homeUrl, sameOriginRedirects = true)
                    if (response.url.encodedPath != expected.encodedPath) JSONObject()
                    else withContext(Dispatchers.Default) { JSONObject(response.text) }
                } ?: JSONObject()
                val detailName = detail.optString("title", "").trim()
                val year = aniChAirdateYear(detail.optString("airdate", ""))
                if (detailName == title.title.trim() && year != null && (title.year == null || title.year == year))
                    title.copy(year = year) else title
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { title } // Missing metadata must not hide the actual episode feed.
            checkRoute(http, AnimeSourceStage.CATALOG)
            AnimeSourceCatalog(identity, episodes)
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
            // Automatic can try any resource from this exact episode. A saved explicit station
            // still selects its own slug and cannot silently choose between competing resources.
            val candidates = if (automatic) available.distinctBy { it.url }
                else available.filter { it.slug == slug }
            if (candidates.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                "所选 AniCh 剧集暂未提供可用线路，请重试或选择其他来源。")
            if (!automatic && candidates.size > 1) throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                "这条 AniCh 线路对应多个不同资源，请选择自动线路或更换来源。")
            val stream = if (automatic) resolveAutomatic(http, candidates)
                else AnimeStreamResolver(http).resolve(candidates.single().url, homeUrl)
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
            // Only coalesce identical station/URL pairs. Paths and query values can identify
            // different resources even when the station slug is the same.
            AniChWire(bytes).messages(1).map { row ->
                val slug = row.string(5).also { require(it.isNotBlank() && it.length <= 256) }
                val encoded = row.string(1).also { require(it.length in 5..32768) }
                // Public wire encoding, not a secret: URL-safe Base64 with one inserted character.
                val unpadded = encoded.removeRange(3, 4).replace('-', '+').replace('_', '/')
                val normalized = unpadded + "=".repeat((4 - unpadded.length % 4) % 4)
                val url = Base64.decode(normalized).decodeToString(throwOnInvalidSequence = true)
                Line(slug, animeMediaUrl(url).toString())
            }.distinct()
        }
    }

    private data class Line(val slug: String, val url: String)
}

/** Strict, bounded protobuf wire reader. Field meanings stay at the call sites above. */
private class AniChWire(private val bytes: ByteArray) {
    private data class Field(val number: Int, val integer: Long? = null, val data: ByteArray? = null, val fixed64: Long? = null)
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
                1 -> {
                    require(bytes.size - position >= 8)
                    var bits = 0L
                    for (shift in 0..56 step 8) bits = bits or ((bytes[position++].toLong() and 255L) shl shift)
                    add(Field(number, fixed64 = bits))
                }
                5 -> { require(bytes.size - position >= 4); position += 4; add(Field(number)) }
                else -> error("AniCh 返回不支持的数据格式。")
            }
        }
    }
    private fun field(number: Int): Field? = fields.filter { it.number == number }
        .also { require(it.size <= 1) { "AniCh 返回重复标量字段。" } }.firstOrNull()
    fun number(number: Int): Long = field(number)?.let { requireNotNull(it.integer) } ?: 0L
    fun decimal(number: Int): Double? = field(number)?.fixed64?.let { Double.fromBits(it) }
    fun string(number: Int): String = field(number)?.let { requireNotNull(it.data).decodeToString(throwOnInvalidSequence = true) }.orEmpty()
    fun messages(number: Int): List<AniChWire> = fields.filter { it.number == number }.map { AniChWire(requireNotNull(it.data)) }
}

/** AniCh's public detail airdate is epoch milliseconds; never infer seconds or device timezone. */
internal fun aniChAirdateYear(raw: String): Int? {
    val value = raw.trim()
    val year = runCatching {
        when {
            // Reject missing/zero/second-like values rather than inventing a 1970 release year.
            Regex("""-?\d{12,13}""").matches(value) -> {
                val millis = value.toLongOrNull() ?: return null
                if (millis !in -2_208_988_800_000L until 4_133_980_800_000L) return null
                Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).year
            }
            Regex("""\d{4}-\d{2}-\d{2}""").matches(value) -> LocalDate.parse(value).year
            else -> Instant.parse(value).toLocalDateTime(TimeZone.UTC).year
        }
    }.getOrNull()
    return year?.takeIf { it in 1900..2100 }
}
