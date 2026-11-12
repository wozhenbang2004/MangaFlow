package dev.mangaflow.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Public, anonymous discovery and discussion. Account credentials never enter this client. */
class CopyCommunityRepository(
    private val repository: CopyMangaRepository,
    config: CopyMangaConfig = CopyMangaConfig(),
) {
    private val webOrigin = checkedOrigin(config.websiteOrigin)
    private val apiOrigin = checkedOrigin(config.apiOrigin)
    private val imageDomains = config.trustedImageDomains.map { it.lowercase().trim('.') }.toSet()
    private val http = SourceHttp()
    private val commentCache = LinkedHashMap<String, CachedComments>()
    private val commentRequests = List(16) { Mutex() }
    @Volatile private var commentGeneration = 0L

    suspend fun browse(
        mode: DiscoveryMode,
        period: RankPeriod,
        audience: RankAudience,
        page: Int = 0,
    ): MangaPage = withContext(Dispatchers.IO) {
        val offset = offset(page, BROWSE_SIZE)
        val builder = apiOrigin.newBuilder().addPathSegments("api/v3")
        val operation = when (mode) {
            DiscoveryMode.RECOMMENDED -> {
                builder.addPathSegment("recs").addQueryParameter("pos", "3200102")
                "漫画推荐"
            }
            DiscoveryMode.RANKING -> {
                builder.addPathSegment("ranks")
                    .addQueryParameter("type", "1")
                    .addQueryParameter("date_type", period.value)
                    .addQueryParameter("audience_type", audience.value)
                "漫画热榜"
            }
            DiscoveryMode.LATEST -> {
                builder.addPathSegment("comics")
                    .addQueryParameter("free_type", "1")
                    .addQueryParameter("ordering", "-datetime_updated")
                "漫画更新"
            }
        }
        val url = builder.addQueryParameter("limit", BROWSE_SIZE.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("platform", "3").build()
        val publicUrl = if (mode == DiscoveryMode.RANKING) webOrigin.newBuilder()
            .addPathSegment("rank").build().toString() else webOrigin.toString()
        val data = apiJson(url, operation, publicUrl)
        val list = list(data, operation, publicUrl)
        val pagination = pagination(data, page, BROWSE_SIZE, list.length(), publicUrl)
        val items = mutableListOf<Manga>()
        var malformed = 0
        for (index in 0 until list.length()) {
            currentCoroutineContext().ensureActive()
            val row = list.optJSONObject(index)
            val comic = if (mode == DiscoveryMode.LATEST) row else row?.optJSONObject("comic")
            if (row == null || comic == null) { malformed++; continue }
            if (restricted(row) || restricted(comic)) continue
            try {
                val id = segment(comic.text("path_word"))
                val title = comic.text("name").takeIf(String::isNotBlank)
                    ?: throw SourceException("来源漫画缺少名称。", publicUrl)
                items += Manga(
                    id = id,
                    title = title,
                    coverUrl = coverUrl(comic.text("cover")),
                    authors = names(comic.optJSONArray("author")),
                    tags = names(comic.optJSONArray("theme")),
                    latestChapter = comic.optJSONObject("last_chapter")?.text("name").orEmpty(),
                    sourceUrl = comicUrl(id),
                    sourceUuid = comic.text("uuid").takeIf { validUuid(it) }.orEmpty(),
                    // A rank row's period heat differs from the comic's overall heat.
                    popularity = if (mode == DiscoveryMode.RANKING) row.nonNegativeLong("popular")
                        ?: comic.nonNegativeLong("popular") else comic.nonNegativeLong("popular"),
                    totalPopularity = comic.nonNegativeLong("popular"),
                    rank = if (mode == DiscoveryMode.RANKING) row.nonNegativeInt("sort")
                        ?.takeIf { it > 0 } ?: (offset + index + 1) else null,
                )
            } catch (_: IllegalArgumentException) { malformed++ }
              catch (_: SourceException) { malformed++ }
        }
        if (list.length() > 0 && malformed == list.length()) {
            throw SourceException("$operation 返回了无法识别的漫画列表。", publicUrl)
        }
        // Keep the upstream ordering and rank numbers, including when unavailable rows are omitted.
        MangaPage(items.distinctBy(Manga::id), pagination.nextPage != null)
    }

    suspend fun overview(manga: Manga, forceRefresh: Boolean = false): ReviewSource = withContext(Dispatchers.IO) {
        val subjectId = comicUuid(manga)
        val source = ReviewSource(
            id = "copy", name = "读者", status = ReviewStatus.READY,
            url = comicUrl(manga.id), matchedTitle = manga.title,
            subjectId = subjectId, popularity = overallPopularity(manga),
        )
        try {
            val first = comments(manga, source, 0, forceRefresh)
            source.copy(reviews = first.items, reviewCount = first.total, nextPage = first.nextPage)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            source.copy(reviewsError = failure.message ?: "评论暂时无法读取，请重试。", nextPage = 0)
        }
    }

    /** Browse responses already include overall heat; never fetch a chapter catalog for a badge. */
    fun metric(manga: Manga): ReviewSource = ReviewSource(
        id = "copy", name = "读者", status = ReviewStatus.READY,
        url = comicUrl(manga.id), matchedTitle = manga.title,
        popularity = overallPopularity(manga), subjectId = manga.sourceUuid,
    )

    private fun overallPopularity(manga: Manga): Long? = manga.totalPopularity
        ?: repository.cachedDetail(manga.id)?.manga?.let { it.totalPopularity ?: it.popularity }
        ?: manga.popularity.takeIf { manga.rank == null }

    suspend fun comments(manga: Manga, source: ReviewSource, page: Int, forceRefresh: Boolean = false): ReviewPage = withContext(Dispatchers.IO) {
        val publicUrl = comicUrl(manga.id)
        val subjectId = subjectId(manga, source, publicUrl)
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comments")
            .addQueryParameter("comic_id", subjectId)
            .addQueryParameter("limit", COMMENT_SIZE.toString())
            .addQueryParameter("offset", offset(page, COMMENT_SIZE).toString()).build()
        commentPage("work:$subjectId:$page", forceRefresh) {
            readComments(apiJson(url, "作品评论", publicUrl), page, publicUrl)
        }
    }

    suspend fun replies(
        manga: Manga,
        source: ReviewSource,
        review: MangaReview,
        page: Int,
    ): ReviewPage = withContext(Dispatchers.IO) {
        val publicUrl = comicUrl(manga.id)
        // Roast IDs (including synthetic display keys) are never work-comment reply IDs.
        val replyId = review.id.toLongOrNull()?.takeIf { it > 0 }
        if (review.kind != ReviewKind.COMMENT || replyId == null) {
            throw SourceException("这条评论没有可读取的作品回复标识。", publicUrl)
        }
        val subjectId = subjectId(manga, source, publicUrl)
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/comments")
            .addQueryParameter("comic_id", subjectId)
            .addQueryParameter("reply_id", replyId.toString())
            .addQueryParameter("_update", "true")
            .addQueryParameter("limit", COMMENT_SIZE.toString())
            .addQueryParameter("offset", offset(page, COMMENT_SIZE).toString()).build()
        commentPage("reply:$subjectId:$replyId:$page") {
            readComments(apiJson(url, "评论回复", publicUrl), page, publicUrl)
        }
    }

    /** Reuse the manga detail's work-comment snapshot before the mixed reader feed refreshes. */
    fun cachedWorkComments(manga: Manga): ReviewPage? {
        if (!validUuid(manga.sourceUuid)) return null
        val subjectId = UUID.fromString(manga.sourceUuid).toString()
        return synchronized(commentCache) { commentCache["work:$subjectId:0"]?.page }
    }

    /** Chapter and work snapshots retain different identities, even in a mixed feed. */
    fun cachedChapterComments(chapter: Chapter): ReviewPage? {
        if (!validUuid(chapter.id)) return null
        val chapterId = UUID.fromString(chapter.id).toString()
        return synchronized(commentCache) { commentCache["chapter:$chapterId:0"]?.page }
    }

    suspend fun chapterComments(
        manga: Manga,
        chapter: Chapter,
        page: Int = 0,
        forceRefresh: Boolean = false,
    ): ReviewPage = withContext(Dispatchers.IO) {
        val chapterId = uuid(chapter.id, "章节")
        val publicUrl = webOrigin.newBuilder().addPathSegment("comic").addPathSegment(segment(manga.id))
            .addPathSegment("chapter").addPathSegment(chapterId).build().toString()
        val url = apiOrigin.newBuilder().addPathSegments("api/v3/roasts")
            .addQueryParameter("chapter_id", chapterId)
            .addQueryParameter("limit", COMMENT_SIZE.toString())
            .addQueryParameter("offset", offset(page, COMMENT_SIZE).toString()).build()
        // Chapter UUID is sufficient; do not make an extra work-detail request here.
        commentPage("chapter:$chapterId:$page", forceRefresh) {
            readComments(apiJson(url, "本话讨论", publicUrl), page, publicUrl, chapterId)
        }
    }

    fun clearCache() = synchronized(commentCache) { commentGeneration++; commentCache.clear() }

    private suspend fun commentPage(key: String, forceRefresh: Boolean = false, load: suspend () -> ReviewPage): ReviewPage {
        val epoch = commentGeneration
        return commentRequests[(key.hashCode() and Int.MAX_VALUE) % commentRequests.size].withLock {
            if (!forceRefresh) synchronized(commentCache) { commentCache[key] }
                ?.takeIf { System.currentTimeMillis() - it.fetchedAt in 0L until 3 * 60_000L }
                ?.let { return@withLock it.page }
            val page = load()
            currentCoroutineContext().ensureActive()
            synchronized(commentCache) {
                if (epoch == commentGeneration) {
                    commentCache.remove(key)
                    commentCache[key] = CachedComments(System.currentTimeMillis(), page)
                    while (commentCache.size > 64) commentCache.remove(commentCache.keys.first())
                }
            }
            page
        }
    }

    private data class CachedComments(val fetchedAt: Long, val page: ReviewPage)

    private suspend fun readComments(data: JSONObject, page: Int, publicUrl: String, chapterId: String? = null): ReviewPage {
        val list = list(data, "评论", publicUrl)
        val pagination = pagination(data, page, COMMENT_SIZE, list.length(), publicUrl)
        val reviews = mutableListOf<MangaReview>()
        for (index in 0 until list.length()) {
            currentCoroutineContext().ensureActive()
            val row = list.optJSONObject(index) ?: throw SourceException("来源评论结构不完整。", publicUrl)
            val body = (row.opt("comment") as? String)?.trim()
                ?: throw SourceException("来源评论缺少正文。", publicUrl)
            val numericId = row.nonNegativeLong("id")?.takeIf { it > 0 }?.toString()
            val id = if (chapterId == null) numericId
                ?: throw SourceException("来源作品评论缺少回复标识。", publicUrl)
            else "copy-chapter:$chapterId:" + (numericId ?: UUID.nameUUIDFromBytes(
                "$chapterId|${row.text("user_name")}|${row.text("create_at")}|$body|${offset(page, COMMENT_SIZE) + index}"
                    .toByteArray(Charsets.UTF_8),
            ).toString())
            reviews += MangaReview(
                id = id,
                author = row.text("user_name").ifBlank { "未署名" },
                summary = body,
                body = body,
                url = publicUrl,
                date = row.text("create_at"),
                // `count` is the number of replies, not a like count or a rating.
                replyCount = if (chapterId == null) row.nonNegativeInt("count") else null,
                replyTo = if (chapterId == null) row.text("parent_user_name") else "",
                kind = if (chapterId == null) ReviewKind.COMMENT else ReviewKind.CHAPTER,
                avatarUrl = coverUrl(row.text("user_avatar")),
            )
        }
        return ReviewPage(reviews.distinctBy(MangaReview::id), pagination.total, pagination.nextPage)
    }

    private suspend fun subjectId(manga: Manga, source: ReviewSource, publicUrl: String): String {
        if (source.id != "copy") throw SourceException("评论来源与当前接口不匹配。", publicUrl)
        return if (source.subjectId.isNotBlank()) uuid(source.subjectId, "漫画") else comicUuid(manga)
    }

    private suspend fun comicUuid(manga: Manga): String = uuid(
        manga.sourceUuid.ifBlank { repository.comicUuid(manga.id) }, "漫画",
    )

    private suspend fun apiJson(url: HttpUrl, operation: String, publicUrl: String): JSONObject {
        val response = http.request(Request.Builder().url(url)
            .header("Accept", "application/json").header("User-Agent", "COPY/3.0.9")
            .header("source", "copyApp").header("platform", "3")
            .header("version", "3.0.9").header("webp", "1").build(), operation, publicUrl)
        val envelope = try { JSONObject(response.text) } catch (error: JSONException) {
            throw SourceException("$operation 返回的格式已变化。", publicUrl, cause = error)
        }
        val code = envelope.opt("code")?.toString()?.toIntOrNull()
            ?: throw SourceException("$operation 缺少来源状态。", publicUrl)
        if (code != 200) {
            val message = envelope.text("message").take(240)
            val retry = if (code == 429) response.retryAfterSeconds.takeIf { it > 0 } ?: 60L
                else response.retryAfterSeconds
            if (retry > 0) http.rememberPause(url, retry)
            throw SourceException(
                "$operation 来源返回提示（$code）" +
                    if (message.isNotBlank()) "：$message" else "，请查看原站提示。",
                publicUrl, retry,
            )
        }
        return envelope.optJSONObject("results")
            ?: throw SourceException("$operation 返回了空数据或不完整的数据结构。", publicUrl)
    }

    private fun pagination(data: JSONObject, page: Int, size: Int, count: Int, publicUrl: String): Pagination {
        val expectedOffset = offset(page, size)
        val actualOffset = data.nonNegativeInt("offset")
        val actualLimit = data.nonNegativeInt("limit")
        val total = data.nonNegativeInt("total")
        if (count > size || actualOffset != null && actualOffset != expectedOffset || actualLimit != null && actualLimit != size) {
            throw SourceException("来源分页方式已变化，请刷新后重试。", publicUrl)
        }
        if (count == 0 && total != null && expectedOffset < total) {
            throw SourceException("来源未返回这一页内容，请稍后重试。", publicUrl)
        }
        if (count in 1 until size && total != null && expectedOffset.toLong() + count < total) {
            // Advancing a fixed page index here would skip the unreturned rows.
            throw SourceException("来源返回的这一页不完整，请稍后重试。", publicUrl)
        }
        val more = count > 0 && if (total != null) expectedOffset.toLong() + count < total else count == size
        return Pagination(total, if (more) page + 1 else null)
    }

    private fun list(data: JSONObject, operation: String, publicUrl: String): JSONArray =
        data.optJSONArray("list") ?: throw SourceException("$operation 列表格式已变化。", publicUrl)

    private fun names(array: JSONArray?): List<String> = if (array == null) emptyList() else
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.text("name")?.takeIf(String::isNotBlank) }

    private fun coverUrl(value: String): String {
        val url = value.toHttpUrlOrNull() ?: return ""
        return if (url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            imageDomains.any { url.host == it || url.host.endsWith(".$it") }) url.toString() else ""
    }

    private fun restricted(value: JSONObject): Boolean {
        for (key in listOf("is_banned", "is_lock", "is_vip", "b_404", "b_hidden", "ban_ip", "vip", "locked", "is_paid")) {
            val flag = value.opt(key)
            if (flag == true || flag is Number && flag.toInt() != 0) return true
        }
        // Numeric comic.ban metadata is not a Boolean access decision.
        // Reading validates the source's explicit is_banned/is_lock result.
        return value.optJSONObject("free_type")?.let { it.optInt("value", 1) != 1 } ?: false
    }

    private fun JSONObject.text(key: String): String = (opt(key) as? String).orEmpty().trim()
    private fun JSONObject.nonNegativeLong(key: String): Long? = opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
    private fun JSONObject.nonNegativeInt(key: String): Int? = nonNegativeLong(key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()

    private fun comicUrl(id: String): String = webOrigin.newBuilder().addPathSegment("comic").addPathSegment(segment(id)).build().toString()
    private fun segment(value: String): String {
        require(value.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "漫画标识无效。" }
        return value
    }

    private fun validUuid(value: String): Boolean = value.matches(UUID_PATTERN) &&
        value != "00000000-0000-0000-0000-000000000000"

    private fun uuid(value: String, label: String): String {
        if (!validUuid(value)) throw SourceException("来源 $label 标识无效。", webOrigin.toString())
        return UUID.fromString(value).toString()
    }

    private fun offset(page: Int, size: Int): Int {
        require(page >= 0 && page <= Int.MAX_VALUE / size) { "分页超出范围。" }
        return page * size
    }

    private fun checkedOrigin(value: String): HttpUrl {
        val url = value.toHttpUrl()
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.encodedPath == "/" && url.query == null && url.fragment == null) { "来源需要完整的 HTTPS 域名。" }
        return url
    }

    private data class Pagination(val total: Int?, val nextPage: Int?)

    private companion object {
        const val BROWSE_SIZE = 21
        const val COMMENT_SIZE = 20
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
