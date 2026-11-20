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
    private var visibleSubject: String? = null
    private var match: AnimeAniListMatch? = null
    private var matchRequest: Job? = null
    private var matchFinished = false

    init {
        scope.launch { routing.state.collect { updateSources() } }
    }

    fun open(subject: AnimeSubject?, confirmedNativeMetadata: AnimeSubject? = null) {
        val chosen = subject?.let { current ->
            val metadata = if ("native-source" in current.externalIds) confirmedNativeMetadata else current
            Selection(current.id, metadata?.takeIf { it.canonicalBangumiId() != null })
        }
        if (chosen == selection) return
        if (chosen?.subjectId != selection?.subjectId) {
            visibleSubject = null
            lanes.values.forEach { it.request?.cancel() }
            lanes.clear()
        }
        selection = chosen
        matchRequest?.cancel()
        matchRequest = null
        match = null
        matchFinished = false
        updateSources()
    }

    fun setVisible(subjectId: String, visible: Boolean) {
        if (!visible) {
            if (visibleSubject == subjectId) visibleSubject = null
            return
        }
        if (selection?.subjectId != subjectId) return
        visibleSubject = subjectId
        updateSources()
    }

    fun retry(subjectId: String, sourceId: String) {
        if (selection?.subjectId != subjectId) return
        updateSources()
        lanes.values.firstOrNull { it.provider.id == sourceId }?.let { load(it, it.failedPage ?: 0, force = true) }
    }

    fun loadMore(subjectId: String, sourceId: String) {
        if (selection?.subjectId != subjectId) return
        val lane = lanes.values.firstOrNull { it.provider.id == sourceId } ?: return
        val next = lane.source.nextPage ?: return
        if (lane.failedPage == null) load(lane, next, force = false)
    }

    fun retryMatch(subjectId: String) {
        if (selection?.subjectId != subjectId || matchRequest != null) return
        matchFinished = false
        updateSources()
    }

    suspend fun readEpisode(episode: AnimeEpisode, catalogue: List<AnimeEpisode>, force: Boolean): AnimeEpisodeDiscussion? {
        val owner = selection?.takeIf { it.subjectId == episode.subjectId } ?: return null
        val metadata = owner.metadata ?: return null
        val canonical = if (episode.subjectId == metadata.id) episode else {
            val records = metadataRepository.cachedDetail(metadata)?.episodes
                ?.takeIf { it.isNotEmpty() } ?: metadataRepository.detail(metadata).let {
                    it.episodes ?: error(it.episodeError ?: "暂时无法读取 Bangumi 剧集目录。")
                }
            val candidate = records.singleOrNull { it.kind == episode.kind && it.number == episode.number } ?: return null
            if (catalogue.count { it.kind == episode.kind && it.number == episode.number } != 1) return null
            val localTitle = normalizedEpisodeTitle(episode.title)
            val sameTitle = localTitle.isNotEmpty() && localTitle == normalizedEpisodeTitle(candidate.title)
            val localNumbers = catalogue.filter { it.kind == AnimeEpisodeKind.MAIN }.map { it.number }.sorted()
            val canonicalNumbers = records.filter { it.kind == AnimeEpisodeKind.MAIN }.map { it.number }.sorted()
            if (!sameTitle && !(episode.kind == AnimeEpisodeKind.MAIN && localNumbers.size > 1 && localNumbers == canonicalNumbers)) return null
            candidate
        }
        val id = canonical.id.removePrefix("bangumi:").toIntOrNull()?.takeIf {
            it > 0 && canonical.id == "bangumi:$it" && canonical.subjectId == metadata.id
        } ?: return null
        val provider = Provider.BANGUMI
        val route = provider.route()
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
            if (selection != owner || provider.route() != route) return null
            cache.write(key, response.text, cacheEpoch)
            decoded
        }
        if (selection != owner || provider.route() != route) return null
        return AnimeEpisodeDiscussion(ReviewSource(provider.id, provider.label, ReviewStatus.READY,
            "https://bgm.tv/ep/$id", reviews = data.items, subjectId = "episode:$id",
            reviewCount = data.items.size, supportsReplies = true), data.replies)
    }

    private fun normalizedEpisodeTitle(title: String): String = normalizeNfkc(title).lowercase().trim()
        .takeUnless { it.matches(Regex("(?:第\\s*)?\\d+(?:\\.\\d+)?(?:\\s*[集话話])?|(?:episode|ep)\\.?\\s*\\d+(?:\\.\\d+)?")) }
        .orEmpty().filter(Char::isLetterOrDigit)

    private fun publish() {
        mutableState.value = AnimeDiscussionState(selection?.subjectId,
            Provider.entries.mapNotNull { lanes[it]?.source }, matchRequest != null)
    }

    private fun updateSources() {
        val chosen = selection
        val metadata = chosen?.metadata
        val ids = metadata?.externalIds.orEmpty()
        fun id(key: String) = ids[key]?.toIntOrNull()?.takeIf { it > 0 }
        val explicitMal = listOfNotNull(id("myanimelist"), id("mal")).distinct().singleOrNull()
        val targets = mapOf(
            Provider.BANGUMI to metadata?.canonicalBangumiId(),
            Provider.ANILIST to (id("anilist") ?: match?.id),
            Provider.MAL to (explicitMal ?: match?.malId),
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
        if (visibleSubject != null && chosen?.subjectId == visibleSubject) {
            lanes.values.filter { !it.initialized && it.failedPage == null }.forEach { load(it, 0, force = false) }
            if (metadata != null && !matchFinished && matchRequest == null &&
                (targets[Provider.ANILIST] == null || targets[Provider.MAL] == null)) {
                matchRequest = scope.launch(start = CoroutineStart.LAZY) {
                    val found = discovery.lookupAniListMatch(metadata)
                    if (selection != chosen) return@launch
                    match = found
                    matchRequest = null
                    matchFinished = true
                    updateSources()
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
