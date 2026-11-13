package dev.mangaflow.data.sources

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.SourceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/**
 * MangaDex public API adapter. The feed/At-home sequence was checked against
 * MangaDex's API contract and Venera's a04e0559 implementation; no scripts are bundled.
 * Anonymous source refusals, unavailable/linked chapters and limits remain explicit.
 */
class MangaDexBodySource : BodySource {
    override val info = BodySourceInfo("mangadex", "MangaDex", "多语言", HOME, "$HOME/favicon.ico")
    private val http = SourceHttp()
    // Feed language codes differ from free-form metadata/alternative-title locale keys.
    private val languages = listOf("zh", "zh-hk", "zh-ro", "en")

    override suspend fun discover(page: Int): MangaPage = searchPage("", page)
    override suspend fun search(query: String, page: Int): MangaPage {
        require(query.trim().length in 1..120)
        return searchPage(query.trim(), page)
    }

    private suspend fun searchPage(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..499) { "MangaDex 分页超出范围。" }
        val url = "$API/manga".toHttpUrl().newBuilder().addQueryParameter("limit", "20")
            .addQueryParameter("offset", (page * 20).toString())
            .addQueryParameter("includes[]", "cover_art").addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist").addQueryParameter("hasAvailableChapters", "true")
            .addQueryParameter("contentRating[]", "safe").addQueryParameter("contentRating[]", "suggestive")
        if (query.isNotBlank()) url.addQueryParameter("title", query)
            .addQueryParameter("order[relevance]", "desc")
        else url.addQueryParameter("order[followedCount]", "desc")
        val data = get(url.build())
        val items = data.getJSONArray("data").bodyObjects().map(::manga)
        val total = data.optInt("total", -1)
        val more = items.size == 20 && (total < 0 || page * 20 + items.size < total)
        MangaPage(items, more, if (more) page + 1 else null)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val id = uuid(SourceKeys.decodeManga(mangaId).mangaId)
        val url = "$API/manga/$id".toHttpUrl().newBuilder().addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author").addQueryParameter("includes[]", "artist").build()
        val current = manga(get(url).getJSONObject("data"))
        val chapters = ArrayList<Chapter>()
        val seen = HashSet<String>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val feed = "$API/manga/$id/feed".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "500").addQueryParameter("offset", offset.toString())
                .addQueryParameter("order[chapter]", "asc").addQueryParameter("order[updatedAt]", "asc")
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("includeExternalUrl", "0")
            languages.forEach { feed.addQueryParameter("translatedLanguage[]", it) }
            val data = get(feed.build())
            val rows = data.getJSONArray("data").bodyObjects()
            rows.forEach { row ->
                val attributes = row.getJSONObject("attributes")
                if (attributes.bodyText("externalUrl").isNotBlank() || attributes.optInt("pages") <= 0 ||
                    attributes.optBoolean("isUnavailable", false)) return@forEach
                val nativeChapterId = uuid(row.bodyId("id"))
                if (!seen.add(nativeChapterId)) return@forEach
                val number = attributes.bodyText("chapter")
                val volume = attributes.bodyText("volume")
                val rawTitle = attributes.bodyText("title")
                val title = if (number.isNotBlank()) "第${number}话" + rawTitle.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                    else rawTitle.ifBlank { "短篇" }
                val language = languageName(attributes.bodyText("translatedLanguage"))
                val scanlator = row.optJSONArray("relationships")?.bodyObjects().orEmpty()
                    .filter { it.bodyText("type") == "scanlation_group" }
                    .mapNotNull { it.optJSONObject("attributes")?.bodyText("name")?.takeIf(String::isNotBlank) }.joinToString(" / ")
                val edition = if (volume.isBlank()) "连载版" else "连载版 · 第${volume}卷"
                val variant = ChapterVariant(info.id, info.name, id, nativeChapterId, title, language,
                    edition, scanlator, "$HOME/chapter/$nativeChapterId", attributes.optInt("pages").takeIf { it > 0 })
                chapters += Chapter(variant.storageId, title, chapters.size,
                    if (volume.isBlank()) "连载 · $language" else "连载 · 第${volume}卷 · $language",
                    variants = listOf(variant), sourceId = info.id, sourceMangaId = id, sourceChapterId = nativeChapterId)
            }
            offset += rows.size
            val total = data.optInt("total", offset)
            if (rows.isEmpty() || offset >= total) break
            if (offset >= 10_000) throw SourceException("MangaDex 目录超过单次可读范围，未保存不完整的目录。", "$HOME/title/$id")
        }
        if (chapters.isEmpty()) throw SourceException("MangaDex 暂无可直接读取的中文或英语章节；外链、下架章节不计入可读目录。", "$HOME/title/$id")
        MangaDetail(current, chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id)
        val nativeMangaId = uuid(ref.mangaId)
        val nativeChapterId = uuid(ref.chapterId)
        val chapter = get("$API/chapter/$nativeChapterId".toHttpUrl()).getJSONObject("data")
        val attributes = chapter.getJSONObject("attributes")
        val owner = chapter.getJSONArray("relationships").bodyObjects().firstOrNull { it.bodyText("type") == "manga" }?.bodyText("id")
        if (owner != nativeMangaId || attributes.bodyText("externalUrl").isNotBlank() ||
            attributes.optInt("pages") <= 0 || attributes.optBoolean("isUnavailable", false)) {
            throw SourceException("MangaDex 本章不属于当前作品或暂不可直接读取。", "$HOME/chapter/$nativeChapterId")
        }
        val data = get("$API/at-home/server/$nativeChapterId".toHttpUrl())
        val base = checkedBodyUrl(data.bodyText("baseUrl"))
        val imageSet = data.getJSONObject("chapter")
        val hash = imageSet.bodyText("hash").also { require(it.matches(Regex("[a-fA-F0-9]{16,128}"))) { "MangaDex 图片清单无效。" } }
        val files = imageSet.getJSONArray("data")
        (0 until files.length()).map { index ->
            val filename = files.getString(index)
            require(filename.isNotBlank() && !filename.contains('/') && !filename.contains('\\')) { "MangaDex 图片文件名无效。" }
            Page(index, base.newBuilder().addPathSegment("data").addPathSegment(hash).addPathSegment(filename).build().toString())
        }.also { if (it.isEmpty()) throw SourceException("MangaDex 本章没有返回原图。", "$HOME/chapter/$nativeChapterId") }
    }

    override fun imageHeaders(mangaId: String, chapterId: String) = mapOf(
        "User-Agent" to BODY_USER_AGENT, "Referer" to "$HOME/", "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8",
    )

    private fun manga(row: JSONObject): Manga {
        val id = uuid(row.bodyId("id"))
        val attributes = row.getJSONObject("attributes")
        val titles = linkedMapOf<String, String>()
        fun addTitles(values: JSONObject?) {
            values?.keys()?.asSequence()?.forEach { language -> values.bodyText(language).takeIf(String::isNotBlank)?.let { titles.putIfAbsent(language, it) } }
        }
        addTitles(attributes.optJSONObject("title"))
        attributes.optJSONArray("altTitles")?.bodyObjects()?.forEach(::addTitles)
        val title = listOf("zh", "zh-hk", "zh-tw", "en", "ja").firstNotNullOfOrNull { titles[it] }
            ?: titles.values.firstOrNull() ?: throw SourceException("MangaDex 没有返回作品标题。", HOME)
        val relationships = row.optJSONArray("relationships")?.bodyObjects().orEmpty()
        val cover = relationships.firstOrNull { it.bodyText("type") == "cover_art" }?.optJSONObject("attributes")?.bodyText("fileName").orEmpty()
        val description = attributes.optJSONObject("description")
        return Manga(
            SourceKeys.manga(info.id, id), title,
            if (cover.isBlank()) "" else "https://uploads.mangadex.org/covers/$id/".toHttpUrl().newBuilder().addPathSegment("$cover.256.jpg").build().toString(),
            authors = relationships.filter { it.bodyText("type") in setOf("author", "artist") }
                .mapNotNull { it.optJSONObject("attributes")?.bodyText("name")?.takeIf(String::isNotBlank) }.distinct(),
            description = description?.bodyText("zh")?.takeIf(String::isNotBlank)
                ?: description?.bodyText("en").orEmpty(),
            status = attributes.bodyText("status"),
            tags = attributes.optJSONArray("tags")?.bodyObjects()?.mapNotNull {
                it.optJSONObject("attributes")?.optJSONObject("name")?.let { name -> name.bodyText("zh").ifBlank { name.bodyText("en") } }?.takeIf(String::isNotBlank)
            }.orEmpty(),
            sourceUrl = "$HOME/title/$id", sourceId = info.id, sourceMangaId = id,
        )
    }

    private suspend fun get(url: HttpUrl): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", BODY_USER_AGENT).header("Accept", "application/json")
            .header("Referer", "$HOME/").build()
        val data = http.bodyJson(request, "MangaDex", HOME)
        if (data.bodyText("result") == "error") throw SourceException("MangaDex 暂时拒绝此请求，请稍后重试或检查来源准入要求。", HOME)
        return data
    }

    private fun uuid(value: String): String = value.also {
        require(it.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))) { "MangaDex 标识无效。" }
    }
    private fun languageName(value: String) = when (value) {
        "zh", "zh-ro" -> "简中"; "zh-hk", "zh-tw" -> "繁中"; "en" -> "英语"; else -> value.ifBlank { "未知语言" }
    }

    companion object {
        private const val HOME = "https://mangadex.org"
        private const val API = "https://api.mangadex.org"
    }
}
