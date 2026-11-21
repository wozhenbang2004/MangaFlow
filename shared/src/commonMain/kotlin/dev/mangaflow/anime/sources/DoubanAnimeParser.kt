package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.AnimeDiscoveryPage
import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull

internal data class DoubanSearchDocument(
    val candidates: List<AnimeSubject>,
    val nextUrl: String?,
    val error: String? = null,
)

internal data class DoubanSubjectDocument(val subject: AnimeSubject, val animation: Boolean)

/**
 * Public page fields, independently mapped to our metadata model. Search/detail selectors were
 * checked against cxfksword/jellyfin-plugin-metashark DoubanApi.cs at
 * 6bae92c59428c59f4fbb34474be4c65df81131f6 and Rhilip/PT-help modules/infogen/gen.py.
 * No page scripts, verification challenges, account cookies or private API credentials are used.
 */
internal object DoubanAnimeParser {
    fun search(html: String, url: HttpUrl, query: String): DoubanSearchDocument {
        val document = publicDocument(html, url, AnimeSourceStage.SEARCH)
        val results = document.selectFirst("div.result-list")
            ?: fail(AnimeSourceStage.SEARCH, "豆瓣没有返回可读取的搜索结果，可能需要原站验证或页面已变化。")
        var unreadable = 0
        val candidates = results.select(".result").mapNotNull { row ->
            val category = row.selectFirst("div.title > h3 > span")?.text().orEmpty()
                .trim().removeSurrounding("[", "]").removeSurrounding("【", "】")
            if (category !in setOf("电影", "电视剧")) {
                if (category.isBlank()) unreadable++
                return@mapNotNull null
            }
            val link = row.selectFirst("div.title a")
            val href = link?.attr("href").orEmpty()
            val direct = url.resolve(href)
            val linkedId = direct?.let(::subjectIdFromUrl)
                ?: direct?.queryParameter("url")?.toHttpUrlOrNull()?.let(::subjectIdFromUrl)
            val clickId = Regex("\\bsid\\s*:\\s*([1-9][0-9]*)\\s*[,}]")
                .find(link?.attr("onclick").orEmpty())?.groupValues?.get(1)?.let(::positiveId)
            val id = linkedId ?: clickId
            val title = link?.text().orEmpty().trim()
            if (id == null || title.isBlank() || (linkedId != null && clickId != null && linkedId != clickId)) {
                unreadable++
                return@mapNotNull null
            }
            val cast = row.selectFirst(".subject-cast")?.text().orEmpty()
            val year = Regex("(?:^|/\\s*)([0-9]{4})\\s*$").find(cast)?.groupValues?.get(1)
                ?.toIntOrNull()?.takeIf { it in 1..9999 }
            AnimeSubject(id = "douban:$id", title = title,
                coverUrl = sourceCover(url.toString(), row.selectFirst("a.nbg > img")?.attr("src").orEmpty()),
                description = row.selectFirst("div.content > p")?.text().orEmpty(),
                year = year, externalIds = mapOf("douban" to id),
                format = if (category == "电视剧") AnimeDiscoveryFormat.SERIES else AnimeDiscoveryFormat.MOVIE)
        }.distinctBy { it.id }
        val nextLinks = document.select("link[rel=next][href], .paginator .next a[href]")
            .map { it.attr("href") }.filter(String::isNotBlank)
        val nextUrls = nextLinks.map { href ->
            val next = url.resolve(href)
                ?: fail(AnimeSourceStage.SEARCH, "豆瓣返回了无效的下一页链接。")
            val start = next.queryParameter("start")?.toLongOrNull()
            val current = url.queryParameter("start")?.toLongOrNull() ?: 0L
            if (next.scheme != "https" || next.host != "www.douban.com" || next.port != 443 ||
                next.encodedPath.trimEnd('/') != "/search" || next.username.isNotEmpty() ||
                next.password.isNotEmpty() || next.queryParameter("q") != query ||
                next.queryParameter("cat") != "1002" || start == null || start <= current) {
                fail(AnimeSourceStage.SEARCH, "豆瓣下一页的关键词或分页位置不一致，请重新搜索。")
            }
            // Keep only the public search parameters; never replay tracking or account parameters.
            searchUrl(query, start).toString()
        }.distinct()
        if (nextUrls.size > 1) fail(AnimeSourceStage.SEARCH, "豆瓣返回了不一致的下一页链接。")
        val error = if (unreadable > 0) "$unreadable 条搜索结果未能确认作品身份，请重试本页。" else null
        if (candidates.isEmpty() && error != null) fail(AnimeSourceStage.SEARCH, error)
        return DoubanSearchDocument(candidates, nextUrls.singleOrNull(), error)
    }

    fun detail(html: String, url: HttpUrl, id: String): DoubanSubjectDocument {
        if (subjectIdFromUrl(url) != id) fail(AnimeSourceStage.METADATA, "豆瓣详情跳转到了其他作品。")
        val document = publicDocument(html, url, AnimeSourceStage.METADATA)
        val content = document.selectFirst("#content")
            ?: fail(AnimeSourceStage.METADATA, "豆瓣详情没有返回作品资料，请稍后重试。")
        val reviewed = content.selectFirst("h1 [property=v:itemreviewed]")?.text().orEmpty().trim()
        if (reviewed.isBlank()) fail(AnimeSourceStage.METADATA, "豆瓣详情缺少作品名称，可能需要原站验证。")
        val linkedData = document.select("script[type=application/ld+json]").mapNotNull {
            runCatching { JSONObject(it.data()) }.getOrNull()
        }.firstOrNull { it.optString("@type") in setOf("Movie", "TVSeries") }
        val identityUrls = listOfNotNull(
            document.selectFirst("link[rel=canonical]")?.attr("href"),
            document.selectFirst("meta[property=og:url]")?.attr("content"),
            linkedData?.optString("url"),
        ).filter(String::isNotBlank)
        identityUrls.forEach { identity ->
            if (url.resolve(identity)?.let(::subjectIdFromUrl) != id)
                fail(AnimeSourceStage.METADATA, "豆瓣详情身份与所选作品不一致。")
        }
        val info = content.selectFirst("#info")
            ?: fail(AnimeSourceStage.METADATA, "豆瓣详情缺少类型资料，暂时无法确认是否为动画。")
        val fields = info.html().split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)).mapNotNull { fragment ->
            val line = Ksoup.parse(fragment).text().trim()
            val separator = line.indexOfFirst { it == ':' || it == '：' }
            if (separator < 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }.toMap()
        val genres = info.select("[property=v:genre]").map { it.text().trim() }.filter(String::isNotBlank)
            .ifEmpty { linkedData?.optJSONArray("genre")?.let { array ->
                (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank)
            }.orEmpty() }.distinct()
        if (genres.isEmpty()) fail(AnimeSourceStage.METADATA, "豆瓣详情未能确认动画类型，请重试；这不代表作品不是动画。")
        val title = document.selectFirst("meta[name=keywords]")?.attr("content")?.substringBefore(',')?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().replace(Regex("\\s*[（(]豆瓣[）)]\\s*$"), "").trim()
                .takeIf { it.isNotBlank() } ?: reviewed
        val original = if (reviewed == title) "" else if (reviewed.startsWith("$title "))
            reviewed.removePrefix(title).trim() else reviewed
        val aliases = fields["又名"].orEmpty().split(" / ").map(String::trim)
            .filter { it.isNotBlank() && it != title && it != original }.distinct()
        if (aliases.size > 64 || aliases.any { it.length > 1000 })
            fail(AnimeSourceStage.METADATA, "豆瓣返回的作品别名资料不完整，请稍后重试。")
        val year = Regex("[0-9]{4}").find(content.selectFirst("h1 .year")?.text().orEmpty())
            ?.value?.toIntOrNull()?.takeIf { it in 1..9999 }
        val episodes = fields["集数"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val format = when {
            "集数" in fields || content.selectFirst(".episode_list") != null || linkedData?.optString("@type") == "TVSeries" ->
                AnimeDiscoveryFormat.SERIES
            linkedData?.optString("@type") == "Movie" -> AnimeDiscoveryFormat.MOVIE
            else -> null
        }
        val summary = content.selectFirst("#link-report-intra > span.all, #link-report > span.all")
            ?: content.selectFirst("[property=v:summary]")
            ?: content.selectFirst("#link-report-intra > span")
        val aggregate = linkedData?.optJSONObject("aggregateRating")
        val rating = (content.selectFirst("[property=v:average]")?.text()?.trim()?.toDoubleOrNull()
            ?: aggregate?.optString("ratingValue")?.toDoubleOrNull())?.takeIf { it.isFinite() && it in 0.0..10.0 }
        val count = (content.selectFirst("[property=v:votes]")?.text()?.trim()?.toLongOrNull()
            ?: aggregate?.optString("ratingCount")?.toLongOrNull())?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        val cover = content.selectFirst("#mainpic img")?.attr("src").orEmpty()
            .ifBlank { linkedData?.optString("image").orEmpty() }
        return DoubanSubjectDocument(
            AnimeSubject(id = "douban:$id", title = title, originalTitle = original,
                coverUrl = sourceCover(url.toString(), cover), description = summary?.text().orEmpty().trim()
                    .ifBlank { linkedData?.optString("description").orEmpty().trim() },
                episodeCount = episodes, year = year, tags = genres, externalIds = mapOf("douban" to id),
                rating = rating, ratingCount = count, format = format, aliases = aliases),
            animation = genres.any { it == "动画" || it.equals("Animation", ignoreCase = true) },
        )
    }

    /** Lanerc douban.js 2.0.0: rexxar items, without its filter-dropping tag-search fallback.
     * SHA256 c9ab5e9fe51147ceae6d0811cef52c359b21df1908405ac262a0a6ddb263c7d7.
     */
    fun browse(payload: JSONObject, page: Int, fetchedAt: Long): AnimeDiscoveryPage {
        val rows = payload.optJSONArray("items")
            ?: fail(AnimeSourceStage.METADATA, "豆瓣没有返回动画目录，请稍后重试。")
        val items = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index)
                ?: fail(AnimeSourceStage.METADATA, "豆瓣目录条目格式已变化，请稍后重试。")
            val id = positiveId(row.optString("id")) ?: return@mapNotNull null
            val type = row.optString("type")
            if (type.isNotBlank() && type !in setOf("movie", "tv")) return@mapNotNull null
            val title = row.optString("title").trim()
            if (title.isBlank()) fail(AnimeSourceStage.METADATA, "豆瓣目录返回了缺少名称的作品。")
            val ratingData = row.optJSONObject("rating")
            val score = ratingData?.optDouble("value")?.takeIf { it.isFinite() && it in 0.0..10.0 }
            val votes = ratingData?.optLong("count", -1L)?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
            val picture = row.optJSONObject("pic")
            val cover = picture?.optString("large").orEmpty().ifBlank { picture?.optString("normal").orEmpty() }
            val format = when (type) {
                "movie" -> AnimeDiscoveryFormat.MOVIE
                "tv" -> AnimeDiscoveryFormat.SERIES
                else -> null
            }
            val subject = AnimeSubject(id = "douban:$id", title = title,
                coverUrl = sourceCover("https://movie.douban.com/", cover),
                year = row.optString("year").toIntOrNull()?.takeIf { it in 1..9999 },
                rating = score, ratingCount = votes, format = format, externalIds = mapOf("douban" to id))
            AnimeDiscoveryItem(key = subject.id, title = subject.title, coverUrl = subject.coverUrl,
                year = subject.year, rating = score, subject = subject, format = format,
                sourceScores = score?.let { listOf(AnimeSourceScore(AnimeDiscoveryProvider.DOUBAN, it, 10, votes)) }.orEmpty(),
                coverProvider = AnimeDiscoveryProvider.DOUBAN)
        }.distinctBy { it.key }
        if (rows.length() > 0 && items.isEmpty()) fail(AnimeSourceStage.METADATA, "豆瓣这一页未返回可确认的动画作品，请重试。")
        val offset = (page - 1L) * DOUBAN_BROWSE_PAGE_SIZE
        val total = payload.optLong("total", -1L).takeIf { it >= 0 }
        if (rows.length() == 0 && total != null && offset < total)
            fail(AnimeSourceStage.METADATA, "豆瓣返回的动画分页不完整，请重试。")
        return AnimeDiscoveryPage(items, page,
            if (total != null) offset + rows.length() < total else rows.length() >= DOUBAN_BROWSE_PAGE_SIZE,
            fetchedAt)
    }

    fun searchUrl(query: String, start: Long = 0): HttpUrl = animePublicUrl("https://www.douban.com/search")
        .newBuilder().addQueryParameter("cat", "1002").addQueryParameter("q", query)
        .addQueryParameter("start", start.toString()).build()

    private fun publicDocument(html: String, url: HttpUrl, stage: AnimeSourceStage): Document {
        val document = Ksoup.parse(html, url.toString())
        val title = document.title()
        val body = document.body().text()
        if (document.selectFirst("form[action*=sec.douban.com], iframe[src*=sec.douban.com], input[name=captcha-solution]") != null ||
            listOf("安全验证", "异常请求", "访问异常", "登录豆瓣").any { title.contains(it) } ||
            body.contains("检测到有异常请求") || body.contains("有异常请求从你的 IP 发出")) {
            fail(stage, "豆瓣要求原站验证或登录，暂时无法读取；请稍后重试或在原站完成验证。")
        }
        return document
    }

    private fun subjectIdFromUrl(url: HttpUrl): String? {
        if (url.scheme !in setOf("http", "https") || url.host != "movie.douban.com" ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443)) return null
        return Regex("^/subject/([1-9][0-9]*)/?$").matchEntire(url.encodedPath)?.groupValues?.get(1)?.let(::positiveId)
    }

    private fun positiveId(value: String): String? = value.toLongOrNull()
        ?.takeIf { it > 0 && it.toString() == value }?.toString()

    private fun fail(stage: AnimeSourceStage, message: String): Nothing =
        throw AnimeSourceException(DOUBAN_PROVIDER, stage, message)
}
