package dev.mangaflow.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A catalog identity is deliberately not a Copy Manga/readable chapter identity. */
internal data class MangaBakaBook(
    val id: String,
    val title: String,
    val aliases: List<String>,
    val authors: List<String>,
    val coverUrl: String,
    val format: String,
    val status: String,
    val description: String = "",
    val relation: String = "",
    val note: String = "",
    val origins: List<String> = emptyList(),
)

internal data class MangaBakaStack(
    val id: String,
    val title: String,
    val owner: String,
    val count: Int,
    val spoiler: Boolean,
    val description: String = "",
    val books: List<MangaBakaBook> = emptyList(),
)

internal data class MangaBakaPage(
    val books: List<MangaBakaBook> = emptyList(),
    val stacks: List<MangaBakaStack> = emptyList(),
    val nextPage: Int? = null,
)

internal enum class MangaBakaShelf(val label: String) { RISING("上升中"), GEMS("冷门佳作"), STACKS("主题书单") }
internal enum class MangaBakaRelation(val label: String) { SIMILAR("相似作品"), SERIES("系列关系"), AUTHOR("同作者") }

/** Official public REST contracts, read from https://mangabaka.org/api.json, 2026-11-12. */
internal class MangaBakaCatalog(context: Context) {
    private val cache = Cache(File(context.applicationContext.cacheDir, "mangabaka-catalog"), 4L * 1024 * 1024)
    private val client = sourceClient().newBuilder().cache(cache).build()
    private val http = ReviewProviderHttp(SourceHttp(client))

    suspend fun shelf(shelf: MangaBakaShelf, page: Int, force: Boolean): MangaBakaPage = withContext(Dispatchers.IO) {
        requirePage(page)
        if (shelf == MangaBakaShelf.STACKS) {
            val path = "/v2/stacks"
            val data = request(path, listOf("page" to "$page", "limit" to "$PAGE_SIZE"), force)
            MangaBakaPage(stacks = rows(data).mapNotNull(::parseStack), nextPage = nextPage(data, page, path))
        } else {
            val path = if (shelf == MangaBakaShelf.RISING) "/v2/series/discover/rising" else "/v2/series/discover/hidden-gems"
            val data = request(path, listOf("limit" to "20"), force)
            MangaBakaPage(books = rows(data).mapNotNull(::parseBook).distinctBy { it.id })
        }
    }

    suspend fun book(id: String, force: Boolean = false): MangaBakaBook = withContext(Dispatchers.IO) {
        val requested = requireId(id)
        val item = request("/v2/series/$requested", listOf("schema" to "full"), force).optJSONObject("data") ?: invalid()
        if (requireId(item.reviewId("id")) != requested) invalid()
        val active = if (item.reviewText("state") == "merged") {
            val replacement = requireId(item.reviewId("merged_with"))
            if (replacement == requested) invalid()
            request("/v2/series/$replacement", listOf("schema" to "full"), force).optJSONObject("data")
                ?.also { if (requireId(it.reviewId("id")) != replacement) invalid() } ?: invalid()
        } else item
        parseBook(active) ?: throw SourceException("这条资料已移除或不是漫画，请返回刷新。", HOME)
    }

    suspend fun related(book: MangaBakaBook, relation: MangaBakaRelation, page: Int, force: Boolean): MangaBakaPage = withContext(Dispatchers.IO) {
        requirePage(page)
        val id = requireId(book.id)
        when (relation) {
            MangaBakaRelation.SIMILAR -> {
                val data = request("/v2/series/$id/readers-also-like", listOf("limit" to "24"), force)
                MangaBakaPage(books = rows(data).mapNotNull { row ->
                    parseBook(row.optJSONObject("series") ?: invalid())
                }.filter { it.id != id }.distinctBy { it.id })
            }
            MangaBakaRelation.SERIES -> {
                val path = "/v1/series/$id/relationships"
                val data = request(path, listOf("page" to "$page", "limit" to "$PAGE_SIZE"), force)
                MangaBakaPage(books = rows(data).mapNotNull { row ->
                    parseBook(row.optJSONObject("series") ?: invalid())?.copy(relation = relationLabel(row.reviewText("relation_type")))
                }.filter { it.id != id }.distinctBy { it.id + ":" + it.relation }, nextPage = nextPage(data, page, path))
            }
            MangaBakaRelation.AUTHOR -> {
                val author = book.authors.firstOrNull() ?: return@withContext MangaBakaPage()
                val path = "/v2/series/search"
                val data = request(path, comicTypes + listOf("staff" to author, "page" to "$page", "limit" to "$PAGE_SIZE"), force, search = true)
                MangaBakaPage(books = rows(data).mapNotNull(::parseBook).filter {
                    it.id != id && it.authors.any { name -> normalizeReviewTitle(name) == normalizeReviewTitle(author) }
                }.distinctBy { it.id },
                    nextPage = nextPage(data, page, path))
            }
        }
    }

    suspend fun stack(id: String, force: Boolean): MangaBakaStack = withContext(Dispatchers.IO) {
        val requested = requireId(id)
        val data = request("/v2/stacks/$requested", force = force).optJSONObject("data") ?: invalid()
        if (requireId(data.reviewId("id")) != requested) invalid()
        val stack = parseStack(data) ?: invalid()
        val entries = data.optJSONArray("entries") ?: invalid()
        // Removed/merged placeholders retain their order; never turn them into fake books.
        val books = (0 until entries.length()).map { entries.optJSONObject(it) ?: invalid() }
            .sortedBy { it.optDouble("rank", Double.MAX_VALUE) }.mapNotNull { entry ->
            if (entry.reviewText("kind") != "series") null
            else parseBook(entry.optJSONObject("series") ?: invalid())?.copy(note = plain(entry.reviewText("note"), 1200))
        }.distinctBy { it.id }
        stack.copy(description = plain(data.reviewText("description"), 8000), books = books)
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { cache.size() }

    suspend fun clear() = withContext(Dispatchers.IO) {
        client.dispatcher.cancelAll()
        cache.evictAll()
    }

    fun close() { client.dispatcher.cancelAll(); runCatching { cache.close() } }

    private suspend fun request(path: String, params: List<Pair<String, String>> = emptyList(), force: Boolean = false, search: Boolean = false): JSONObject {
        val url = (API + path).toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build().toString()
        val result = http.json(url, "MangaBaka", HOME,
            headers = if (force) mapOf("Cache-Control" to "no-cache") else emptyMap(),
            minIntervalMillis = if (search) 2100L else 600L)
        if (result.optInt("status", -1) != 200) invalid()
        return result
    }

    private fun rows(data: JSONObject): List<JSONObject> {
        val list = data.optJSONArray("data") ?: invalid()
        if (list.length() > 50) invalid()
        return (0 until list.length()).map { list.optJSONObject(it) ?: invalid() }
    }

    private fun nextPage(data: JSONObject, page: Int, path: String): Int? {
        val pagination = data.optJSONObject("pagination") ?: invalid()
        if (pagination.optInt("page", -1) != page || pagination.optInt("limit", -1) != PAGE_SIZE) invalid()
        if (!pagination.has("next")) invalid()
        if (pagination.isNull("next")) return null
        val next = pagination.reviewText("next").toHttpUrlOrNull() ?: invalid()
        if (!next.isHttps || next.host != "api.mangabaka.org" || next.port != 443 || next.encodedPath != path ||
            next.username.isNotEmpty() || next.password.isNotEmpty() || next.queryParameter("page")?.toIntOrNull() != page + 1) invalid()
        return (page + 1).takeIf { it * PAGE_SIZE <= 12_000 }
    }

    private fun parseBook(item: JSONObject): MangaBakaBook? {
        val state = item.reviewText("state")
        if (state in setOf("merged", "deleted")) return null
        if (state != "active") invalid()
        val kind = item.reviewText("type")
        if (kind !in COMIC_TYPES) return null
        val titles = item.optJSONArray("titles")?.reviewObjects().orEmpty().sortedWith(
            compareBy<JSONObject> { row -> when {
                row.reviewText("language").startsWith("zh-Hans") -> 0
                row.reviewText("language").startsWith("zh") && row.reviewText("language") != "zh-Latn" -> 1
                row.optBoolean("is_primary") -> 2
                row.reviewText("language") == "ja" -> 3
                else -> 4
            } },
        ).map { it.reviewText("title").take(1000) }
        val aliases = (titles + listOf(item.reviewText("title"), item.reviewText("native_title"), item.reviewText("romanized_title")))
            .filter(String::isNotBlank).distinct().take(64)
        if (aliases.isEmpty()) invalid()
        val authors = strings(item.optJSONArray("authors")) + strings(item.optJSONArray("artists"))
        val cover = item.optJSONObject("cover")
        val image = cover?.reviewText("x250").orEmpty().ifBlank { cover?.optJSONObject("x250")?.reviewText("x1").orEmpty() }
        val source = item.optJSONObject("source")
        val origins = SOURCE_NAMES.mapNotNull { (key, name) ->
            source?.optJSONObject(key)?.reviewId("id")?.takeIf(String::isNotBlank)?.let { name }
        }
        return MangaBakaBook(requireId(item.reviewId("id")), aliases.first(), aliases, authors.distinct(),
            safeImage(image), when (kind) { "manhwa" -> "韩漫"; "manhua" -> "国漫"; "oel" -> "英语漫画"; else -> "漫画" },
            when (item.reviewText("status")) { "completed" -> "已完结"; "releasing" -> "连载中"; "hiatus" -> "休刊中"; "cancelled" -> "已中止"; "upcoming" -> "即将连载"; else -> "" },
            description = plain(item.reviewText("description"), 8000), origins = origins)
    }

    private fun parseStack(item: JSONObject): MangaBakaStack? {
        if (item.reviewText("visibility") != "public") return null
        val title = plain(item.reviewText("title"), 500)
        if (title.isBlank()) invalid()
        val owner = item.optJSONObject("owner") ?: invalid()
        val count = item.reviewCount("entry_count") ?: invalid()
        val spoiler = item.opt("is_spoiler") as? Boolean ?: invalid()
        return MangaBakaStack(requireId(item.reviewId("id")), title,
            owner.reviewText("name").ifBlank { owner.reviewText("username") }.take(200), count, spoiler)
    }

    private fun strings(array: JSONArray?): List<String> = array?.let { values ->
        (0 until values.length()).map { (values.opt(it) as? String)?.trim()?.take(300) ?: invalid() }.filter(String::isNotBlank)
    }.orEmpty()

    private fun safeImage(raw: String): String = raw.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
    }?.toString().orEmpty()

    private fun plain(raw: String, limit: Int) = reviewPlainText(raw.take(32_000)).take(limit)
    private fun requireId(raw: String): String = raw.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
        ?.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toString() ?: invalid()
    private fun requirePage(page: Int) { if (page < 1 || page.toLong() * PAGE_SIZE > 12_000) invalid() }
    private fun invalid(): Nothing = throw SourceException("MangaBaka 资料格式已变化或不完整，请稍后重试。", HOME)

    private fun relationLabel(type: String): String = when (type) {
        "prequel" -> "前传"; "sequel" -> "续作"; "spin_off" -> "衍生作"; "side_story" -> "外传"
        "source" -> "原作"; "adaptation" -> "改编作"; "alternative" -> "其他版本"; "main" -> "本篇"
        "parent" -> "母系列"; "series" -> "同系列"; "remake" -> "重制版"; "reboot" -> "重启版"
        "compilation" -> "合集"; "contains" -> "收录作品"; "crossover" -> "联动作"; "parody" -> "戏仿作"
        "character_focus" -> "角色衍生"; "summary" -> "总集篇"; "uncollected" -> "未收录作品"
        else -> "相关作品"
    }

    private companion object {
        const val API = "https://api.mangabaka.org"
        const val HOME = "https://mangabaka.org"
        const val PAGE_SIZE = 18
        val COMIC_TYPES = setOf("manga", "manhwa", "manhua", "oel")
        val comicTypes = COMIC_TYPES.map { "type" to it }
        val SOURCE_NAMES = mapOf("anilist" to "AniList", "my_anime_list" to "MyAnimeList", "kitsu" to "Kitsu",
            "manga_updates" to "MangaUpdates", "anime_planet" to "Anime-Planet", "anime_news_network" to "Anime News Network", "shikimori" to "Shikimori")
    }
}
