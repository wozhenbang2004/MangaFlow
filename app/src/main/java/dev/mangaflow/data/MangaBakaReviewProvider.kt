package dev.mangaflow.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * MangaBaka v1 metadata and cross-site IDs, from https://mangabaka.org/api.json.
 * Ratings, source response bodies, descriptions and covers are not imported.
 * The repository still validates each mapped ID with its original manga provider.
 */
internal class MangaBakaReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "mangabaka"
    override val name = "MangaBaka"
    override val homeUrl = "https://mangabaka.org"
    override val hasWorkReviews = false

    override suspend fun search(query: String): List<ProviderCandidate> {
        if (query.isBlank()) return emptyList()
        val url = "$API/series/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "20")
            .build().toString()
        val rows = request(url, 2_100L).optJSONArray("data") ?: invalidResponse()
        return parseRows(rows).distinctBy(Series::id).map { it.candidate() }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val subjectId = requireSeriesId(id)
        val item = request("$API/series/$subjectId").optJSONObject("data") ?: invalidResponse()
        if (decimalId(item.opt("id")) != subjectId) {
            throw SourceException("返回的条目与所选作品编号不一致。", homeUrl)
        }
        val series = parseSeries(item)
            ?: throw SourceException("所选条目已合并、已删除或不是漫画，请重新关联。", homeUrl)
        return series.toSource()
    }

    override suspend fun page(id: String, page: Int): ReviewPage =
        throw SourceException("这个来源仅用于作品关联，不提供评论。", homeUrl)

    /** A real 404 can fall back to title matching; transport/schema failures cannot. */
    suspend fun fromKnownIds(ids: Map<String, String>): ReviewSource? {
        val known = ids.filterKeys { it in SOURCE_FIELDS || it == id }.mapValues { (_, value) ->
            decimalId(value) ?: throw SourceException("已关联作品的来源编号无效。", homeUrl)
        }
        val seeds = SEED_PATHS.mapNotNull { (providerId, path) ->
            known[providerId]?.let { Seed(providerId, path, it) }
        }.take(2)
        if (seeds.isEmpty()) return null

        val candidates = linkedMapOf<String, Series>()
        val conflictingSeries = mutableSetOf<String>()
        var hasConflict = false
        for (seed in seeds) {
            val lookupId = if (seed.providerId == "mangaupdates") {
                val decimal = seed.subjectId.toLong()
                if (decimal > MAX_MU_ID) throw SourceException("已关联作品的来源编号无效。", homeUrl)
                decimal.toString(36).padStart(7, '0')
            } else seed.subjectId
            val url = "$API/source/${seed.path}/$lookupId".toHttpUrl().newBuilder()
                .addQueryParameter("with_series", "true")
                .addQueryParameter("with_internal", "false")
                .addQueryParameter("with_source_response", "false")
                .build().toString()
            val response = try {
                request(url)
            } catch (error: SourceException) {
                if (error.httpStatus == 404) continue
                throw error
            }
            val rows = response.optJSONObject("data")?.optJSONArray("series") ?: invalidResponse()
            for (series in parseRows(rows)) {
                // A reverse lookup must contain the same source ID, not merely a similar title.
                val wrongBacklink = series.externalIds[seed.providerId] != seed.subjectId
                val conflicts = known.any { (providerId, expected) ->
                    val actual = if (providerId == id) series.id else series.externalIds[providerId]
                    actual != null && actual != expected
                }
                if (wrongBacklink || conflicts) {
                    hasConflict = true
                    conflictingSeries.add(series.id)
                    candidates.remove(series.id)
                    continue
                }
                if (series.id in conflictingSeries) continue
                val previous = candidates[series.id]
                if (previous != null && previous.externalIds.any { (providerId, previousId) ->
                        series.externalIds[providerId]?.let { it != previousId } == true
                    }) {
                    throw SourceException("作品关联信息前后不一致，请稍后重试。", homeUrl)
                }
                candidates[series.id] = if (previous == null) series else previous.copy(
                    titles = (previous.titles + series.titles).distinct(),
                    authors = (previous.authors + series.authors).distinct(),
                    externalIds = previous.externalIds + series.externalIds,
                )
            }
        }
        return when {
            hasConflict -> unresolved("已有来源编号相互冲突，请确认作品关联。",
                candidates.values.map { it.candidate().match })
            candidates.isEmpty() -> null
            candidates.size == 1 -> candidates.values.single().toSource()
            else -> unresolved("作品关联对应多个条目，请选择。", candidates.values.map { it.candidate().match })
        }
    }

    private suspend fun request(url: String, interval: Long = 600L): JSONObject {
        val response = http.json(url, name, homeUrl, minIntervalMillis = interval)
        if ((response.opt("status") as? Number)?.toDouble() != 200.0) invalidResponse()
        return response
    }

    private fun parseRows(rows: JSONArray): List<Series> = buildList {
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: invalidResponse()
            parseSeries(item)?.let { add(it) }
        }
    }

    private fun parseSeries(item: JSONObject): Series? {
        when (item.reviewText("state")) {
            "active" -> Unit
            "merged", "deleted" -> return null
            else -> invalidResponse()
        }
        val type = item.reviewText("type")
        if (type.isBlank()) invalidResponse()
        if (type !in COMIC_TYPES) return null
        val subjectId = requireSeriesId(item.opt("id"))
        val titles = titles(item)
        if (titles.isEmpty()) invalidResponse()
        val source = item.optJSONObject("source") ?: invalidResponse()
        val externalIds = buildMap {
            for ((providerId, field) in SOURCE_FIELDS) {
                if (!source.has(field) || source.isNull(field)) continue
                val record = source.optJSONObject(field) ?: invalidResponse()
                if (!record.has("id") || record.isNull("id")) continue
                val externalId = if (providerId == "mangaupdates") {
                    val encoded = record.reviewText("id").lowercase(Locale.ROOT)
                    if (!MU_ID.matches(encoded)) invalidResponse()
                    encoded.toLongOrNull(36)?.takeIf { it > 0 }?.toString() ?: invalidResponse()
                } else decimalId(record.opt("id")) ?: invalidResponse()
                put(providerId, externalId)
            }
        }
        val url = item.reviewText("canonical_url").toHttpUrlOrNull()
            ?.takeIf { it.isHttps && it.host == "mangabaka.org" && it.port == 443 &&
                it.username.isEmpty() && it.password.isEmpty() }
            ?.toString() ?: invalidResponse()
        val authors = if (!item.has("authors") || item.isNull("authors")) emptyList() else {
            val values = item.optJSONArray("authors") ?: invalidResponse()
            (0 until values.length()).map { index ->
                (values.opt(index) as? String)?.trim() ?: invalidResponse()
            }.filter(String::isNotBlank).distinct()
        }
        return Series(subjectId, titles, authors, type, url, externalIds)
    }

    private fun titles(item: JSONObject): List<String> = buildList {
        if (item.has("titles") && !item.isNull("titles")) {
            val values = item.optJSONArray("titles") ?: invalidResponse()
            val rows = (0 until values.length()).map { values.optJSONObject(it) ?: invalidResponse() }
            rows.sortedByDescending { it.optBoolean("is_primary", false) }.forEach { row ->
                val title = row.reviewText("title")
                if (title.isBlank()) invalidResponse()
                add(title)
            }
        }
        // v1 retains these deprecated fields; they supplement the current title records.
        listOf("title", "native_title", "romanized_title").forEach { add(item.reviewText(it)) }
    }.filter(String::isNotBlank).distinct()

    private fun Series.candidate() = ProviderCandidate(
        match = ReviewMatch(id, titles.first(),
            (listOf(kindLabel(type)) + authors).joinToString(" · "), url),
        titles = titles,
        authors = authors,
    )

    private fun Series.toSource() = ReviewSource(
        id = this@MangaBakaReviewProvider.id, name = name, status = ReviewStatus.READY,
        url = url, subjectId = id, matchedTitle = titles.first(), aliases = titles,
        externalIds = externalIds, hasWorkReviews = false,
    )

    private fun unresolved(message: String, candidates: List<ReviewMatch> = emptyList()) = ReviewSource(
        id = id, name = name, status = ReviewStatus.NO_MATCH, url = homeUrl,
        message = message, candidates = candidates, hasWorkReviews = false,
        reviewsError = message.takeIf { candidates.isEmpty() },
    )

    private fun requireSeriesId(raw: Any?): String = decimalId(raw)
        ?.takeIf { it.toLong() <= Int.MAX_VALUE }
        ?: throw SourceException("作品关联编号无效。", homeUrl)

    private fun decimalId(value: Any?): String? {
        val number = when (value) {
            is String -> value.trim().takeIf { DECIMAL_ID.matches(it) }?.toLongOrNull()
            is Number -> try { value.toString().toBigDecimalOrNull()?.longValueExact() } catch (_: ArithmeticException) { null }
            else -> null
        }
        return number?.takeIf { it > 0 }?.toString()
    }

    private fun invalidResponse(): Nothing = throw SourceException("作品关联资料格式无效，请稍后重试。", homeUrl)

    private fun kindLabel(type: String): String = when (type) {
        "manhwa" -> "韩漫"
        "manhua" -> "国漫"
        "oel" -> "原创英语漫画"
        else -> "漫画"
    }

    private data class Seed(val providerId: String, val path: String, val subjectId: String)
    private data class Series(
        val id: String,
        val titles: List<String>,
        val authors: List<String>,
        val type: String,
        val url: String,
        val externalIds: Map<String, String>,
    )

    private companion object {
        const val API = "https://api.mangabaka.org/v1"
        const val MAX_MU_ID = 78_364_164_095L // 36^7 - 1
        val COMIC_TYPES = setOf("manga", "manhwa", "manhua", "oel")
        val DECIMAL_ID = Regex("[0-9]+")
        val MU_ID = Regex("[0-9a-z]{7}")
        val SOURCE_FIELDS = mapOf(
            "anilist" to "anilist", "myanimelist" to "my_anime_list", "kitsu" to "kitsu",
            "mangaupdates" to "manga_updates", "shikimori" to "shikimori",
        )
        val SEED_PATHS = listOf(
            "anilist" to "anilist", "myanimelist" to "my-anime-list",
            "kitsu" to "kitsu", "mangaupdates" to "manga-updates",
        )
    }
}
