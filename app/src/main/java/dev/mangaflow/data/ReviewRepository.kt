package dev.mangaflow.data

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.Normalizer
import java.util.Locale

/** Ratings keep their original scale. Public review excerpts always retain their author and URL. */
class ReviewRepository {
    private val http = SourceHttp()
    private val cache = LinkedHashMap<String, CachedReviews>()

    /** Source callbacks run on IO workers and can arrive concurrently. */
    suspend fun lookup(manga: Manga, onSource: (ReviewSource) -> Unit = {}): List<ReviewSource> = withContext(Dispatchers.IO) {
        val key = manga.id + "|" + manga.title
        synchronized(cache) { cache[key] }?.takeIf { System.currentTimeMillis() - it.time < 60 * 60_000 }
            ?.let { cached ->
                cached.sources.forEach(onSource)
                return@withContext cached.sources
            }

        val sources = coroutineScope {
            val bgm = async { bangumi(manga).also { onSource(it.source) } }
            val ani = async { aniList(manga, listOf(manga.title)).also(onSource) }
            val bangumi = bgm.await()
            var anilist = ani.await()
            // A matched Bangumi title can supply the native name for a second, bounded lookup.
            // An unrelated first search result is never used as an automatic identity bridge.
            if (anilist.status == ReviewStatus.NO_MATCH && bangumi.source.status == ReviewStatus.READY &&
                bangumi.names.any { normalize(it) != normalize(manga.title) }) {
                anilist = aniList(manga, bangumi.names + manga.title).also(onSource)
            }
            listOf(bangumi.source, anilist)
        }
        if (sources.none { it.status == ReviewStatus.UNAVAILABLE }) synchronized(cache) {
            cache.remove(key)
            cache[key] = CachedReviews(System.currentTimeMillis(), sources)
            while (cache.size > 48) cache.remove(cache.keys.first())
        }
        sources
    }

    private suspend fun bangumi(manga: Manga): BangumiMatch {
        val searchUrl = "https://bgm.tv".toHttpUrl().newBuilder().addPathSegment("subject_search")
            .addPathSegment(manga.title).addQueryParameter("cat", "1").build().toString()
        try {
            val body = JSONObject().put("keyword", manga.title).put("sort", "match")
                .put("filter", JSONObject().put("type", JSONArray().put(1)))
            val result = jsonRequest("https://api.bgm.tv/v0/search/subjects?limit=8&offset=0", "Bangumi 评分", searchUrl, body)
            val rows = result.optJSONArray("data") ?: throw SourceException("Bangumi 没有返回有效的搜索结果。")
            val target = normalize(manga.title)
            val matches = rows.objects().filter { item ->
                item.optInt("type") == 1 && listOf(item.text("name"), item.text("name_cn")).any { normalize(it) == target }
            }
            val candidate = matches.singleOrNull()
                ?: return BangumiMatch(ReviewSource("bangumi", "Bangumi", ReviewStatus.NO_MATCH, searchUrl,
                    message = if (matches.isEmpty()) "尚未找到同名漫画，去原站查找" else "存在多个同名条目，请到原站核对"))
            val id = candidate.optInt("id")
            if (id <= 0) throw SourceException("Bangumi 条目标识缺失。")
            val url = "https://bgm.tv/subject/$id"
            val subject = jsonRequest("https://api.bgm.tv/v0/subjects/$id", "Bangumi 评分", url)
            if (subject.optInt("id") != id || subject.optInt("type") != 1) throw SourceException("Bangumi 条目类型不一致。")
            val platform = normalize(subject.text("platform"))
            if (!platform.contains("漫画")) return BangumiMatch(ReviewSource("bangumi", "Bangumi", ReviewStatus.NO_MATCH, searchUrl,
                message = "同名条目尚未确认是漫画，去原站核对"))
            val rating = subject.optJSONObject("rating")
            val total = rating?.optInt("total", 0) ?: 0
            val score = rating?.nullableScore("score", 10)?.takeIf { total > 0 }
            val names = listOf(subject.text("name"), subject.text("name_cn")).filter(String::isNotBlank)
            return BangumiMatch(
                ReviewSource("bangumi", "Bangumi", ReviewStatus.READY, "$url/comments",
                    matchedTitle = subject.text("name_cn").ifBlank { subject.text("name") },
                    score = score, scoreScale = 10, ratingCount = total,
                    message = "评分来自 Bangumi · 读者评论在原站查看"),
                names,
            )
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            return BangumiMatch(ReviewSource("bangumi", "Bangumi", ReviewStatus.UNAVAILABLE, searchUrl,
                message = error.userMessage("Bangumi 暂时无法连接，可到原站查看")))
        }
    }

    private suspend fun aniList(manga: Manga, aliases: List<String>): ReviewSource {
        val searchUrl = "https://anilist.co/search/manga".toHttpUrl().newBuilder()
            .addQueryParameter("search", manga.title).build().toString()
        try {
            val knownNames = aliases.filter(String::isNotBlank).map(::normalize).filter(String::isNotBlank).toSet()
            val queryName = aliases.firstOrNull { it.isNotBlank() } ?: manga.title
            val search = """
                query FindManga(${'$'}search: String!) {
                  Page(page: 1, perPage: 8) {
                    media(search: ${'$'}search, type: MANGA) {
                      id type format title { native romaji english } synonyms
                    }
                  }
                }
            """.trimIndent()
            val result = graphql(search, JSONObject().put("search", queryName), searchUrl)
            val rows = result.optJSONObject("Page")?.optJSONArray("media") ?: throw SourceException("AniList 没有返回有效的搜索结果。")
            val matches = rows.objects().filter { item ->
                item.text("type") == "MANGA" && item.text("format") != "NOVEL" &&
                    mediaNames(item).any { normalize(it) in knownNames }
            }
            val candidate = matches.singleOrNull() ?: return ReviewSource("anilist", "AniList", ReviewStatus.NO_MATCH, searchUrl,
                message = if (matches.isEmpty()) "尚未找到同名漫画，去原站查找" else "存在多个同名条目，请到原站核对")
            val id = candidate.optInt("id")
            if (id <= 0) throw SourceException("AniList 条目标识缺失。")
            val query = """
                query MangaReviews(${'$'}id: Int!) {
                  Media(id: ${'$'}id, type: MANGA) {
                    id title { native romaji english } averageScore
                    stats { scoreDistribution { amount } }
                    reviews(page: 1, perPage: 3, sort: [RATING_DESC]) {
                      nodes { id summary score private siteUrl user { name } }
                    }
                  }
                }
            """.trimIndent()
            val media = graphql(query, JSONObject().put("id", id), "https://anilist.co/manga/$id").optJSONObject("Media")
                ?: throw SourceException("AniList 没有返回漫画评分。")
            if (media.optInt("id") != id) throw SourceException("AniList 漫画归属不一致。")
            val total = media.optJSONObject("stats")?.optJSONArray("scoreDistribution")?.objects()
                ?.sumOf { it.optInt("amount", 0).coerceAtLeast(0) }
            val reviews = media.optJSONObject("reviews")?.optJSONArray("nodes")?.objects().orEmpty().mapNotNull { review ->
                if (review.optBoolean("private", false)) return@mapNotNull null
                val reviewId = review.optInt("id")
                val summary = Jsoup.parse(review.text("summary")).text().trim().take(360)
                val author = review.optJSONObject("user")?.text("name").orEmpty()
                if (reviewId <= 0 || summary.isBlank() || author.isBlank()) return@mapNotNull null
                // A canonical provider link cannot inject an arbitrary navigation target.
                MangaReview(reviewId.toString(), author, summary, "https://anilist.co/review/$reviewId",
                    review.nullableScore("score", 100), 100)
            }
            return ReviewSource("anilist", "AniList", ReviewStatus.READY, "https://anilist.co/manga/$id",
                matchedTitle = mediaNames(media).firstOrNull().orEmpty(),
                score = media.nullableScore("averageScore", 100), scoreScale = 100,
                ratingCount = total, reviews = reviews,
                message = if (reviews.isEmpty()) "暂无公开短评，去原站查看" else "读者评价 · 点击查看原文")
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            return ReviewSource("anilist", "AniList", ReviewStatus.UNAVAILABLE, searchUrl,
                message = error.userMessage("AniList 暂时无法连接，可到原站查看"))
        }
    }

    private suspend fun graphql(query: String, variables: JSONObject, publicUrl: String): JSONObject {
        val response = jsonRequest("https://graphql.anilist.co", "AniList 读者评价", publicUrl,
            JSONObject().put("query", query).put("variables", variables))
        if (response.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            throw SourceException("AniList 暂时未能完成查询，请稍后重试。", publicUrl)
        }
        return response.optJSONObject("data") ?: throw SourceException("AniList 返回了空数据。", publicUrl)
    }

    private suspend fun jsonRequest(url: String, label: String, publicUrl: String, body: JSONObject? = null): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("User-Agent", "MangaFlow/0.1.0 (https://github.com/wozhenbang2004/MangaFlow)")
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return JSONObject(http.request(request.build(), label, publicUrl).text)
    }

    private fun mediaNames(media: JSONObject): List<String> {
        val title = media.optJSONObject("title")
        return (listOf(title?.text("native").orEmpty(), title?.text("romaji").orEmpty(), title?.text("english").orEmpty()) +
            media.optJSONArray("synonyms")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty())
            .filter(String::isNotBlank)
    }

    private fun normalize(text: String): String {
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (Build.VERSION.SDK_INT >= 29) {
            normalized = try { android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(normalized) }
            catch (_: IllegalArgumentException) { normalized }
        }
        return normalized.filter(Char::isLetterOrDigit)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun JSONObject.text(key: String) = (opt(key) as? String).orEmpty().trim()
    private fun JSONObject.nullableScore(key: String, scale: Int): Double? =
        (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 && it <= scale }
    private fun Exception.userMessage(fallback: String) = if (this is SourceException) message.orEmpty().ifBlank { fallback } else fallback
    private data class BangumiMatch(val source: ReviewSource, val names: List<String> = emptyList())
    private data class CachedReviews(val time: Long, val sources: List<ReviewSource>)
}
