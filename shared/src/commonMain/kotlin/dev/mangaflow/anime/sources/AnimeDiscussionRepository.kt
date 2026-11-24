package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.data.aniListReviewQuery
import dev.mangaflow.data.aniListReviewVariables
import dev.mangaflow.data.bangumiCommentsPublicUrl
import dev.mangaflow.data.bangumiCommentsRequestUrl
import dev.mangaflow.data.decodeAniListReviews
import dev.mangaflow.data.decodeBangumiComments
import dev.mangaflow.data.decodeBangumiEpisodeComments
import dev.mangaflow.data.decodeMyAnimeListReviews
import dev.mangaflow.data.myAnimeListReviewRequestUrl
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.normalizeNfkc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AnimeDiscussionState(
    val subjectId: String? = null,
    val sources: List<ReviewSource> = emptyList(),
    val matching: Boolean = false,
    val checks: List<AnimeDiscussionSourceCheck> = emptyList(),
    val workContext: AnimeWorkDiscussionContext? = null,
    /** Changes when the confirmed work or its discussion route changes. */
    val episodeContextKey: String? = null,
)

/** A Work tab may only act on the precise native selection it was actually shown. */
internal data class AnimeWorkDiscussionContext(val subjectId: String, val referenceId: Int, val revision: Long)

internal enum class AnimeDiscussionCheckStatus { WAITING, CHECKING, READY, NO_MATCH, UNAVAILABLE }

internal data class AnimeDiscussionSourceCheck(
    val sourceId: String,
    val name: String,
    val status: AnimeDiscussionCheckStatus,
    val message: String,
)

internal data class AnimeEpisodeDiscussion(val source: ReviewSource, val replies: Map<String, List<MangaReview>>)

/** Each provider owns its page, route and cached result independently. */
internal class AnimeDiscussionController(context: PlatformContext, private val scope: CoroutineScope,
    private val discovery: AnimeDiscoveryRepository, private val metadataRepository: AnimeMetadataRepository) {
    private enum class Provider(val id: String, val label: String, val routingId: String) {
        BANGUMI("bangumi", "Bangumi", BANGUMI_PROVIDER),
        ANILIST("anilist", "AniList", ANILIST_PROVIDER),
        MAL("myanimelist", "MyAnimeList", "anime-myanimelist");

        fun publicUrl(id: Int): String = when (this) {
            BANGUMI -> bangumiCommentsPublicUrl(id)
            ANILIST -> "https://anilist.co/anime/$id/reviews"
            MAL -> "https://myanimelist.net/anime/$id/reviews"
        }
        fun route() = DownloadRoutingStore.activeSourceSnapshot(routingId, DownloadCategory.ANIME)
    }

    private data class Selection(val subjectId: String, val metadata: AnimeSubject?)
    private data class MatchContext(val selectionRevision: Long, val route: DownloadRouteSnapshot?)
    private class Lane(val provider: Provider, val remoteId: Int, val route: DownloadRouteSnapshot?, var source: ReviewSource) {
        var initialized = false
        var request: Job? = null
        var failedPage: Int? = null
    }

    private val cache = AnimeMetadataCache.get(context)
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(AnimeDiscussionState())
    val state = mutableState.asStateFlow()
    private val lanes = mutableMapOf<Provider, Lane>()
    private var selection: Selection? = null
    private var selectionRevision = 0L
    private var workContext: AnimeWorkDiscussionContext? = null
    private var visibleWork: AnimeWorkDiscussionContext? = null
    private var match: AnimeAniListMatch? = null
    private var matchRequest: Job? = null
    private var matchFinished = false
    private var matchContext: MatchContext? = null
    private var matchToken = 0L
    private var matchFailure: String? = null
    private var forceMatch = false

    init {
        scope.launch { routing.state.collect { updateSources() } }
    }

    fun open(subject: AnimeSubject?, confirmedNativeMetadata: AnimeSubject? = null) {
        val chosen = subject?.let { current ->
            val metadata = if ("native-source" in current.externalIds || current.id.startsWith("douban:"))
                confirmedNativeMetadata else current
            Selection(current.id, metadata?.takeIf { it.canonicalBangumiId() != null })
        }
        if (chosen == selection) return
        if (chosen?.subjectId != selection?.subjectId) {
            lanes.values.forEach { it.request?.cancel() }
            lanes.clear()
        }
        selection = chosen
        selectionRevision++
        workContext = chosen?.let { selected ->
            selected.metadata?.canonicalBangumiId()?.let { AnimeWorkDiscussionContext(selected.subjectId, it, selectionRevision) }
        }
        visibleWork = null
        matchToken++
        matchRequest?.cancel()
        matchRequest = null
        match = null
        matchFinished = false
        matchContext = null
        matchFailure = null
        forceMatch = false
        updateSources()
    }

    fun setVisible(observed: AnimeWorkDiscussionContext, visible: Boolean) {
        if (!visible) {
            if (visibleWork == observed) visibleWork = null
            return
        }
        if (workContext != observed) return
        visibleWork = observed
        updateSources()
    }

    private fun ownsVisibleWork(observed: AnimeWorkDiscussionContext) =
        workContext == observed && visibleWork == observed

    fun retry(observed: AnimeWorkDiscussionContext, sourceId: String) {
        if (!ownsVisibleWork(observed)) return
        updateSources()
        val lane = lanes.values.firstOrNull { it.provider.id == sourceId }
        if (lane != null) load(lane, lane.failedPage ?: 0, force = true)
        else if (sourceId == Provider.ANILIST.id || sourceId == Provider.MAL.id) retryMatch(observed)
    }

    fun loadMore(observed: AnimeWorkDiscussionContext, sourceId: String) {
        if (!ownsVisibleWork(observed)) return
        val lane = lanes.values.firstOrNull { it.provider.id == sourceId } ?: return
        val next = lane.source.nextPage ?: return
        if (lane.failedPage == null) load(lane, next, force = false)
    }

    fun retryMatch(observed: AnimeWorkDiscussionContext) {
        if (!ownsVisibleWork(observed) || matchRequest != null) return
        matchFinished = false
        matchFailure = null
        forceMatch = true
        updateSources()
    }

    suspend fun readEpisode(episode: AnimeEpisode, catalogue: List<AnimeEpisode>, force: Boolean): AnimeEpisodeDiscussion? {
        val owner = selection?.takeIf { it.subjectId == episode.subjectId } ?: return null
        val revision = selectionRevision
        if (catalogue.none { it.id == episode.id && it.subjectId == episode.subjectId }) return null
        val metadata = owner.metadata ?: return null
        val canonical = if (episode.subjectId == metadata.id) episode else {
            val records = metadataRepository.cachedDetail(metadata)?.episodes
                ?.takeIf { it.isNotEmpty() } ?: metadataRepository.detail(metadata).let {
                    it.episodes ?: error(it.episodeError ?: "暂时无法读取 Bangumi 剧集目录。")
                }
            if (episode.subjectId.startsWith("douban:")) {
                // The local work owns progress; only its separately confirmed remote episode owns comments.
                if (episode.metadataSubjectId != metadata.id || episode.metadataEpisodeId == null) return null
                records.singleOrNull { it.id == episode.metadataEpisodeId && it.subjectId == metadata.id &&
                    it.kind == episode.kind } ?: return null
            } else {
            // A provider may number this season from 1 while Bangumi retains the series total.
            // Only a supplied season number is an alternate axis; never infer an offset or use position.
            val candidates = records.filter { it.kind == episode.kind &&
                (it.number == episode.number || it.numberInSubject == episode.number) }
            val candidate = candidates.singleOrNull() ?: return null
            if (catalogue.count { it.kind == episode.kind && it.number == episode.number } != 1) return null
            val localTitle = normalizedEpisodeTitle(episode.title)
            val sameTitle = localTitle.isNotEmpty() && localTitle == normalizedEpisodeTitle(candidate.title)
            val localNumbers = catalogue.filter { it.kind == AnimeEpisodeKind.MAIN }.map { it.number }.sorted()
            val mainRecords = records.filter { it.kind == AnimeEpisodeKind.MAIN }
            val canonicalNumbers = mainRecords.map { it.number }.sorted()
            val seasonNumbers = mainRecords.mapNotNull { it.numberInSubject?.takeIf { value -> value > 0 && value.isFinite() } }
                .takeIf { it.size == mainRecords.size && it.distinct().size == it.size }?.sorted()
            val sameNumbering = localNumbers.size > 1 && localNumbers.distinct().size == localNumbers.size &&
                (localNumbers == canonicalNumbers || localNumbers == seasonNumbers)
            if (!sameTitle && !(episode.kind == AnimeEpisodeKind.MAIN && sameNumbering)) return null
            candidate
            }
        }
        val id = canonical.id.removePrefix("bangumi:").toIntOrNull()?.takeIf {
            it > 0 && canonical.id == "bangumi:$it" && canonical.subjectId == metadata.id
        } ?: return null
        val provider = Provider.BANGUMI
        val route = provider.route()
        fun current() = selectionRevision == revision && selection == owner &&
            runCatching { provider.route() }.getOrNull() == route
        if (!current()) return null
        val key = "discussion:v1:bangumi-episode:$id:${animeHash(route.identity())}"
        val cacheEpoch = cache.generation
        val cached = if (force) null else cache.read(key)?.takeIf { cache.isFresh(it, ContentCacheKind.COMMENTS) }
        val previous = cached?.let { entry -> withContext(Dispatchers.Default) {
            runCatching { decodeBangumiEpisodeComments(JSONArray(entry.text), id) }.getOrNull()
        } }
        val data = if (previous != null) previous else {
            val http = AnimeHttp(provider.routingId, route)
            val response = try {
                withTimeoutOrNull(8_000L) {
                    http.request(animePublicUrl("https://next.bgm.tv/p1/episodes/$id/comments"),
                        AnimeSourceStage.METADATA, sameOriginRedirects = true)
                } ?: error("等待读取本集讨论超时。")
            } finally { http.close() }
            val decoded = withContext(Dispatchers.Default) { decodeBangumiEpisodeComments(JSONArray(response.text), id) }
            if (!current()) return null
            cache.write(key, response.text, cacheEpoch)
            decoded
        }
        if (!current()) return null
        return AnimeEpisodeDiscussion(ReviewSource(provider.id, provider.label, ReviewStatus.READY,
            "https://bgm.tv/ep/$id", reviews = data.items, subjectId = "episode:$id",
            reviewCount = data.items.size, supportsReplies = true), data.replies)
    }

    private fun normalizedEpisodeTitle(title: String): String = normalizeNfkc(title).lowercase().trim()
        .takeUnless { it.matches(Regex("(?:第\\s*)?\\d+(?:\\.\\d+)?(?:\\s*[集话話])?|(?:episode|ep)\\.?\\s*\\d+(?:\\.\\d+)?")) }
        .orEmpty().filter(Char::isLetterOrDigit)

    private fun publish() {
        val chosen = selection
        if (chosen == null) {
            mutableState.value = AnimeDiscussionState()
            return
        }
        val checks = Provider.entries.map { provider ->
            val lane = lanes[provider]
            val (status, message) = when {
                lane?.source?.reviewsError != null -> AnimeDiscussionCheckStatus.UNAVAILABLE to lane.source.reviewsError.orEmpty()
                lane?.source?.loadingMore == true -> AnimeDiscussionCheckStatus.CHECKING to
                    if (lane.initialized) "正在读取更多评论" else "正在读取评论"
                lane?.initialized == true -> AnimeDiscussionCheckStatus.READY to
                    if (lane.source.reviews.isEmpty()) "已检查，暂时没有评论" else "已载入 ${lane.source.reviews.size} 条评论"
                lane != null -> AnimeDiscussionCheckStatus.WAITING to "打开评论时读取"
                chosen.metadata == null -> AnimeDiscussionCheckStatus.NO_MATCH to "先在简介中确认对应作品与季度"
                provider == Provider.MAL && conflictingMalIds(chosen.metadata) ->
                    AnimeDiscussionCheckStatus.NO_MATCH to "来源中的 MAL 编号冲突，未混入评论"
                matchRequest != null -> AnimeDiscussionCheckStatus.CHECKING to "正在核对作品与季度"
                matchFailure != null -> AnimeDiscussionCheckStatus.UNAVAILABLE to matchFailure.orEmpty()
                !matchFinished -> AnimeDiscussionCheckStatus.WAITING to "打开评论时核对作品与季度"
                else -> AnimeDiscussionCheckStatus.NO_MATCH to "未确认对应作品与季度，未混入评论"
            }
            AnimeDiscussionSourceCheck(provider.id, provider.label, status, message)
        }
        val sources = Provider.entries.mapIndexed { index, provider ->
            lanes[provider]?.source ?: checks[index].let { check ->
                ReviewSource(provider.id, provider.label,
                    if (check.status == AnimeDiscussionCheckStatus.UNAVAILABLE) ReviewStatus.UNAVAILABLE else ReviewStatus.NO_MATCH,
                    url = "", message = check.message,
                    loadingMore = check.status == AnimeDiscussionCheckStatus.CHECKING,
                    reviewsError = check.message.takeIf { check.status == AnimeDiscussionCheckStatus.UNAVAILABLE })
            }
        }
        val reference = chosen.metadata?.canonicalBangumiId()
        val episodeContext = reference?.let {
            val route = runCatching { Provider.BANGUMI.route().identity() }.getOrNull()
            "${chosen.subjectId}:$it:$selectionRevision:${route?.let(::animeHash) ?: "unavailable"}"
        }
        mutableState.value = AnimeDiscussionState(chosen.subjectId, sources, matchRequest != null, checks,
            workContext = workContext, episodeContextKey = episodeContext)
    }

    private fun conflictingMalIds(metadata: AnimeSubject): Boolean = listOfNotNull(
        metadata.externalIds["myanimelist"]?.toIntOrNull()?.takeIf { it > 0 },
        metadata.externalIds["mal"]?.toIntOrNull()?.takeIf { it > 0 },
    ).distinct().size > 1

    private fun updateSources() {
        val chosen = selection
        val metadata = chosen?.metadata
        val context = metadata?.let { MatchContext(selectionRevision, runCatching { Provider.ANILIST.route() }.getOrNull()) }
        if (context != matchContext) {
            matchToken++
            matchRequest?.cancel()
            matchRequest = null
            matchContext = context
            match = null
            matchFinished = false
            matchFailure = null
        }
        val ids = metadata?.externalIds.orEmpty()
        fun id(key: String) = ids[key]?.toIntOrNull()?.takeIf { it > 0 }
        val explicitMal = listOfNotNull(id("myanimelist"), id("mal")).distinct().singleOrNull()
        val targets = mapOf(
            Provider.BANGUMI to metadata?.canonicalBangumiId(),
            Provider.ANILIST to (id("anilist") ?: match?.id),
            Provider.MAL to if (metadata != null && conflictingMalIds(metadata)) null else (explicitMal ?: match?.malId),
        )
        targets.forEach { (provider, remote) ->
            val existing = lanes[provider]
            if (remote == null) {
                lanes.remove(provider)?.request?.cancel()
                return@forEach
            }
            val route = runCatching { provider.route() }
            val snapshot = route.getOrNull()
            if (existing?.remoteId == remote && existing.route == snapshot) return@forEach
            existing?.request?.cancel()
            val failure = route.exceptionOrNull()
            val initial = ReviewSource(provider.id, provider.label,
                if (failure == null) ReviewStatus.READY else ReviewStatus.UNAVAILABLE,
                provider.publicUrl(remote), subjectId = remote.toString(),
                matchedTitle = metadata?.title.orEmpty(),
                reviewsError = failure?.message ?: "评论网络线路暂不可用。".takeIf { failure != null })
            lanes[provider] = Lane(provider, remote, snapshot, initial)
        }
        if (visibleWork != null && workContext == visibleWork) {
            lanes.values.filter { !it.initialized && it.failedPage == null }.forEach { load(it, 0, force = false) }
            if (metadata != null && context != null && !matchFinished && matchRequest == null &&
                (forceMatch || targets[Provider.ANILIST] == null || targets[Provider.MAL] == null)) {
                val token = ++matchToken
                val force = forceMatch
                forceMatch = false
                matchRequest = scope.launch(start = CoroutineStart.LAZY) {
                    fun current() = matchToken == token && matchContext == context && selection == chosen &&
                        runCatching { Provider.ANILIST.route() }.getOrNull() == context.route
                    try {
                        val found = discovery.lookupAniListMatch(metadata, forceRefresh = force, reportFailure = true)
                        if (!current()) return@launch
                        match = found
                        matchFailure = null
                        matchFinished = true
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        if (current()) {
                            matchFailure = failure.message ?: "暂时无法核对评论来源，请重试。"
                            matchFinished = true
                        }
                    } finally {
                        if (matchToken == token) {
                            matchRequest = null
                            if (matchFinished) updateSources() else publish()
                        }
                    }
                }
                matchRequest?.start()
            }
        }
        publish()
    }

    private fun load(lane: Lane, page: Int, force: Boolean) {
        val route = lane.route ?: return
        if (lane.request != null) return
        val provider = lane.provider
        val cacheEpoch = cache.generation
        val key = "discussion:v2:${provider.id}:${lane.remoteId}:$page:${animeHash(route.identity())}"
        lane.source = lane.source.copy(loadingMore = true, reviewsError = null)
        lane.request = scope.launch(start = CoroutineStart.LAZY) {
            fun current() = lanes[provider] === lane && runCatching { provider.route() }.getOrNull() == route
            fun show(result: ReviewPage, reading: Boolean = false) {
                if (!current()) return
                val previous = lane.source
                lane.initialized = true
                if (!reading) lane.failedPage = null
                lane.source = previous.copy(
                    reviews = (if (page == 0) result.items else previous.reviews + result.items).distinctBy(MangaReview::id),
                    reviewCount = result.total ?: previous.reviewCount.takeIf { page > 0 },
                    nextPage = result.nextPage, loadingMore = reading, reviewsError = null)
                publish()
            }
            try {
                val cached = cache.read(key)
                if (!current()) return@launch
                if (cached != null && (!force || page == 0 && !lane.initialized)) {
                    val old = withContext(Dispatchers.Default) {
                        runCatching { decode(lane, JSONObject(cached.text), page) }.getOrNull()
                    }
                    if (!current()) return@launch
                    if (old != null && !force && cache.isFresh(cached, ContentCacheKind.COMMENTS)) {
                        show(old)
                        return@launch
                    }
                    if (old != null && page == 0 && !lane.initialized) show(old, reading = true)
                }
                val response = read(lane, route, page)
                if (!current()) return@launch
                val (document, result) = withContext(Dispatchers.Default) { response.toString() to decode(lane, response, page) }
                if (!current()) return@launch
                cache.write(key, document, cacheEpoch)
                show(result)
            } catch (cancel: CancellationException) {
                if (current()) lane.source = lane.source.copy(loadingMore = false)
                throw cancel
            } catch (failure: Exception) {
                if (current()) {
                    lane.failedPage = page
                    lane.source = lane.source.copy(loadingMore = false,
                        reviewsError = failure.message ?: "${provider.label} 评论暂时无法读取。")
                }
            } finally {
                if (lanes[provider] === lane) {
                    lane.request = null
                    publish()
                }
            }
        }
        publish()
        lane.request?.start()
    }

    private fun decode(lane: Lane, response: JSONObject, page: Int): ReviewPage = when (lane.provider) {
        Provider.BANGUMI -> decodeBangumiComments(response, lane.remoteId, page)
        Provider.ANILIST -> {
            val media = response.optJSONObject("data")?.optJSONObject("Media")
                ?: throw IllegalStateException("AniList 未返回有效的动画长评。")
            decodeAniListReviews(media, lane.remoteId, page, lane.source.url)
        }
        Provider.MAL -> decodeMyAnimeListReviews(response, page, lane.source.url, mediaType = "anime")
    }

    private suspend fun read(lane: Lane, route: DownloadRouteSnapshot, page: Int): JSONObject {
        val provider = lane.provider
        val http = AnimeHttp(provider.routingId, route)
        suspend fun attempt(): JSONObject = withTimeoutOrNull(8_000L) {
            when (provider) {
                Provider.BANGUMI -> http.json(animePublicUrl(bangumiCommentsRequestUrl(lane.remoteId, page)),
                    AnimeSourceStage.METADATA, sameOriginRedirects = true)
                Provider.ANILIST -> http.json(animePublicUrl("https://graphql.anilist.co"), AnimeSourceStage.METADATA,
                    "POST", JSONObject().put("query", aniListReviewQuery(anime = true))
                        .put("variables", aniListReviewVariables(lane.remoteId, page)), sameOriginRedirects = true)
                Provider.MAL -> http.json(animePublicUrl(myAnimeListReviewRequestUrl(lane.remoteId.toString(), page, anime = true)),
                    AnimeSourceStage.METADATA, sameOriginRedirects = true)
            }
        } ?: throw AnimeSourceException(provider.routingId, AnimeSourceStage.METADATA, "等待 ${provider.label} 评论超时。")
        try {
            return try { attempt() } catch (failure: AnimeSourceException) {
                val wait = discoveryRetryDelay(failure, transientDelay = 450L)?.takeIf { it <= 4_000L } ?: throw failure
                delay(wait)
                attempt()
            }
        } finally { http.close() }
    }
}
