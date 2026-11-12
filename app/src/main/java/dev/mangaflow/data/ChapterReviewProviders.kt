package dev.mangaflow.data

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Additional chapter sources must preserve the caller's chapter UUID throughout pagination. */
internal class ChapterReviewProviders(private val http: ReviewProviderHttp) {
    val providerIds: List<String> = listOf("dantotsu")

    suspend fun lookup(manga: Manga, chapter: Chapter, identities: Map<String, String>): List<ReviewSource> {
        val number = chapterNumber(chapter)
        val mediaId = identities["anilist"]?.toIntOrNull()?.takeIf { it > 0 }
        val source = ReviewSource(
            id = "dantotsu", name = "Dantotsu", status = ReviewStatus.NO_MATCH,
            url = API, matchedTitle = "${manga.title} · ${chapter.title}", subjectId = chapter.id,
        )
        if (mediaId == null) return listOf(source.copy(message = "尚未准确关联 AniList 漫画，暂不能匹配本章讨论。"))
        if (number == null || chapter.id.isBlank()) {
            return listOf(source.copy(message = "本章编号无法与 Dantotsu 精确对应；番外、拆分章和卷内编号暂不合并。"))
        }
        val matched = source.copy(
            status = ReviewStatus.READY,
            externalIds = mapOf("anilist" to mediaId.toString(), "chapterNumber" to number.toString(), "copyManga" to manga.id),
            supportsReplies = true,
        )
        val loaded = try {
            val first = page(manga, chapter, matched, 0)
            matched.copy(reviews = first.items, reviewCount = first.total, nextPage = first.nextPage)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            matched.copy(
                status = ReviewStatus.UNAVAILABLE,
                message = failure.reviewMessage("Dantotsu 本章讨论暂时无法读取，请稍后重试。"),
                nextPage = 0,
            )
        }
        return listOf(loaded)
    }

    suspend fun page(manga: Manga, chapter: Chapter, source: ReviewSource, page: Int): ReviewPage {
        val identity = identity(manga, chapter, source)
        val upstreamPage = upstreamPage(page)
        val url = "$API/comments/${identity.mediaId}/$upstreamPage".toHttpUrl().newBuilder()
            .addQueryParameter("tag", identity.number.toString())
            .addQueryParameter("sort", "newest").build().toString()
        return readPage(request(url), identity, page)
    }

    suspend fun replies(
        manga: Manga, chapter: Chapter, source: ReviewSource, review: MangaReview, page: Int,
    ): ReviewPage {
        val identity = identity(manga, chapter, source)
        val commentId = review.id.toIntOrNull()?.takeIf { it > 0 }
        if (commentId == null || review.kind != ReviewKind.CHAPTER ||
            source.reviews.none { it.id == review.id && it.kind == ReviewKind.CHAPTER }) {
            throw SourceException("这条回复不属于当前章节的 Dantotsu 讨论。", API)
        }
        val url = "$API/comments/parent/$commentId/${upstreamPage(page)}"
        return readPage(request(url), identity, page, commentId, review.author)
    }

    private fun identity(manga: Manga, chapter: Chapter, source: ReviewSource): ChapterIdentity {
        val mediaId = source.externalIds["anilist"]?.toIntOrNull()?.takeIf { it > 0 }
        val number = source.externalIds["chapterNumber"]?.toIntOrNull()
        if (source.id != "dantotsu" || source.subjectId != chapter.id || chapter.id.isBlank() ||
            source.externalIds["copyManga"] != manga.id || mediaId == null || number == null ||
            chapterNumber(chapter) != number) {
            throw SourceException("本章讨论关联已经改变，请重新载入当前章节。", API)
        }
        return ChapterIdentity(mediaId, number)
    }

    private suspend fun request(url: String): JSONObject = http.json(
        url, "Dantotsu 本章讨论", API,
        headers = mapOf("appauth" to PUBLIC_APP_HANDSHAKE),
        minIntervalMillis = 600L,
    )

    private fun readPage(
        result: JSONObject, identity: ChapterIdentity, page: Int,
        parentId: Int? = null, parentAuthor: String = "",
    ): ReviewPage {
        val rows = result.optJSONArray("comments") ?: malformed()
        val totalPages = result.integer("totalPages")?.takeIf { it >= 0 } ?: malformed()
        if (rows.length() > 0 && totalPages <= page) malformed()
        val comments = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: malformed()
                if (row.integer("media_id") != identity.mediaId) wrongChapter()
                if (!row.has("parent_comment_id")) malformed()
                val rowParent = if (row.isNull("parent_comment_id")) null else row.integer("parent_comment_id") ?: malformed()
                if (rowParent != parentId) wrongChapter()
                val tag = if (row.isNull("tag")) null else row.integer("tag") ?: malformed()
                // Replies may omit a tag; their already-verified parent anchors the chapter.
                if ((parentId == null && tag != identity.number) || (tag != null && tag != identity.number)) wrongChapter()
                val deleted = when (val flag = row.opt("deleted")) {
                    null, JSONObject.NULL -> false
                    is Boolean -> flag
                    else -> when (flag.toString()) { "0" -> false; "1" -> true; else -> malformed() }
                }
                if (deleted) continue
                val commentId = row.integer("comment_id")?.takeIf { it > 0 } ?: malformed()
                val raw = row.opt("content") as? String ?: malformed()
                val body = reviewPlainText(raw.replace("||", ""))
                if (body.isBlank()) continue
                val userId = row.reviewId("user_id").toLongOrNull()?.takeIf { it > 0 }
                add(MangaReview(
                    id = commentId.toString(),
                    author = reviewPlainText(row.reviewText("username")).ifBlank { "未署名" },
                    summary = body.take(180),
                    body = body,
                    url = "$API/comments/$commentId",
                    // The integration guide and CommentItem use ISO-8601 UTC with Z.
                    // Unknown/no-zone values stay empty; never infer a SQL date's zone.
                    date = reviewDate(row.reviewText("timestamp")),
                    replyCount = row.reviewCount("reply_count"),
                    canLoadReplies = parentId == null,
                    replyTo = parentAuthor,
                    spoiler = raw.contains("~!") || raw.contains("||") || raw.contains("[spoiler", ignoreCase = true),
                    kind = ReviewKind.CHAPTER,
                    // docs.html explicitly identifies user_id as the author's AniList ID.
                    authorUrl = userId?.let { "https://anilist.co/user/$it" }.orEmpty(),
                    avatarUrl = reviewAvatar(row.reviewText("profile_picture_url"), setOf("anilist.co")),
                    upVotes = row.reviewCount("upvotes"),
                    downVotes = row.reviewCount("downvotes"),
                ))
            }
        }
        return ReviewPage(
            items = comments.distinctBy(MangaReview::id),
            // totalPages is not a comment count. Empty arrays mark the upstream end.
            nextPage = if (rows.length() > 0 && page + 1 < totalPages) page + 1 else null,
        )
    }

    /** Never use Chapter.index: it is an ordering offset, not a published chapter number. */
    private fun chapterNumber(chapter: Chapter): Int? {
        val group = Normalizer.normalize(chapter.group, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        if (group !in MAIN_GROUPS) return null
        val title = Normalizer.normalize(chapter.title, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        if (SPECIAL.containsMatchIn(title) || SPLIT.containsMatchIn(title)) return null
        val match = NUMBER.matchEntire(title)
            ?: CHINESE.matchEntire(title)
            ?: ENGLISH.matchEntire(title)
            ?: return null
        val number = match.groups[1] ?: return null
        // A subtitle may contain prose, but another number can name a merged chapter.
        if (title.removeRange(number.range).any(Char::isDigit)) return null
        return number.value.toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun upstreamPage(page: Int): Int {
        if (page !in 0 until Int.MAX_VALUE) throw SourceException("Dantotsu 讨论页码无效。", API)
        return page + 1
    }

    private fun JSONObject.integer(key: String): Int? = when (val value = opt(key)) {
        is Number, is String -> value.toString().toIntOrNull()
        else -> null
    }

    private fun malformed(): Nothing = throw SourceException("Dantotsu 讨论数据结构无法识别，请稍后重试。", API)
    private fun wrongChapter(): Nothing = throw SourceException("Dantotsu 返回的讨论与本章不一致，已停止合并。", API)
    private data class ChapterIdentity(val mediaId: Int, val number: Int)

    private companion object {
        const val API = "https://api.dantotsu.app"
        // Published client handshake, not a user token. The contributor's integration
        // guide explicitly describes third-party clients; no Authorization is sent.
        // https://github.com/itsmechinmoy/dantotsu-comment-db/blob/main/docs.html
        // This is a contributor-documented contract, not a verified operator SLA.
        const val PUBLIC_APP_HANDSHAKE = "6*45Qp%W2RS@t38jkXoSKY588Ynj%n"
        val MAIN_GROUPS = setOf("默认", "default", "正篇", "正文", "本篇", "本編", "话", "話", "单话", "單話", "连载", "連載", "main", "main story", "chapters")
        val NUMBER = Regex("^([0-9]+)$")
        val CHINESE = Regex("^第?\\s*([0-9]+)\\s*[话話章回](?:\\s+[^0-9].*)?$")
        val ENGLISH = Regex("^(?:chapter|chap|ch)\\.?\\s*([0-9]+)(?:\\s+[^0-9].*)?$")
        val SPECIAL = Regex("番外|特[别別]|外[传傳]|附[录錄]|合[集本卷刊章]|单行本|單行本|[卷巻]|extra|omake|special|prologue|epilogue|volume|vol\\.")
        val SPLIT = Regex("[0-9]\\s*[.、,/+&~～—–-]\\s*[0-9]|其[一二三四五六七八九十]|[上中下]篇|前[篇編]|[后後][篇編]|[上中下]部|[上中下前后後]段|[上下前后後]半|拆分|分割|part\\s*[0-9]|(?:\\(|（|\\[)\\s*[上中下]\\s*(?:\\)|）|\\])|[话話章回]\\s*[上中下]$")
    }
}
