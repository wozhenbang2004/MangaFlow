package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.bangumiCommentsPublicUrl
import dev.mangaflow.data.bangumiCommentsRequestUrl
import dev.mangaflow.data.decodeBangumiComments
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.NetworkTransferException
import dev.mangaflow.net.identity
import dev.mangaflow.net.networkFailureKind
import dev.mangaflow.platform.PlatformContext
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
    val available: Boolean = false,
    val sourceUrl: String? = null,
    val items: List<MangaReview> = emptyList(),
    val total: Int? = null,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

/** The visible discussion owns exactly one confirmed Bangumi subject and one frozen route. */
internal class AnimeDiscussionController(context: PlatformContext, private val scope: CoroutineScope) {
    private data class Selection(val subjectId: String, val bangumiId: Int?)
    private data class Target(val selection: Selection, val bangumiId: Int, val route: DownloadRouteSnapshot)

    private val cache = AnimeMetadataCache.get(context)
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(AnimeDiscussionState())
    val state = mutableState.asStateFlow()
    private var selection: Selection? = null
    private var target: Target? = null
    private var routeError: String? = null
    private var generation = 0L
    private var request: Job? = null
    private var nextPage: Int? = null
    private var failedPage: Int? = null
    private var visibleSubject: String? = null

    init {
        scope.launch { routing.state.collect { updateRoute() } }
    }

    fun open(subject: AnimeSubject?, confirmedNativeMetadata: AnimeSubject? = null) {
        val chosen = subject?.let { current ->
            val id = if (current.id.startsWith("native:") && "native-source" in current.externalIds)
                confirmedNativeMetadata?.canonicalBangumiId() else current.canonicalBangumiId()
            Selection(current.id, id)
        }
        if (chosen == selection) return
        selection = chosen
        visibleSubject = null
        target = null
        routeError = null
        resetRequest()
        mutableState.value = AnimeDiscussionState(subjectId = chosen?.subjectId,
            available = chosen?.bangumiId != null, sourceUrl = chosen?.bangumiId?.let(::bangumiCommentsPublicUrl))
        updateRoute()
    }

    fun setVisible(subjectId: String, visible: Boolean) {
        if (!visible) {
            if (visibleSubject == subjectId) visibleSubject = null
            return
        }
        if (selection?.subjectId != subjectId) return
        visibleSubject = subjectId
        updateRoute()
        if (request?.isActive != true && !state.value.loaded && state.value.error == null) load(0, forceRefresh = false)
    }

    fun retry(subjectId: String? = state.value.subjectId) {
        if (subjectId == null || state.value.subjectId != subjectId) return
        updateRoute()
        if (request?.isActive != true) load(failedPage ?: 0, forceRefresh = true)
    }

    fun loadMore(subjectId: String? = state.value.subjectId) {
        if (subjectId == null || state.value.subjectId != subjectId) return
        updateRoute()
        val page = nextPage ?: return
        if (request?.isActive != true && state.value.error == null) load(page, forceRefresh = false)
    }

    private fun snapshot(): DownloadRouteSnapshot =
        DownloadRoutingStore.activeSourceSnapshot(BANGUMI_PROVIDER, DownloadCategory.ANIME)

    private fun updateRoute() {
        val chosen = selection ?: return
        val id = chosen.bangumiId ?: return
        val route = runCatching(::snapshot)
        val next = route.getOrNull()?.let { Target(chosen, id, it) }
        val failure = route.exceptionOrNull()
        val error = failure?.let { it.message ?: "Bangumi 短评的线路配置暂不可用。" }
        if (next == target && error == routeError) return
        target = next
        routeError = error
        resetRequest()
        mutableState.value = AnimeDiscussionState(chosen.subjectId, available = true,
            sourceUrl = bangumiCommentsPublicUrl(id), error = error)
        if (next != null && visibleSubject == chosen.subjectId) load(0, forceRefresh = false)
    }

    private fun resetRequest() {
        ++generation
        request?.cancel()
        request = null
        nextPage = null
        failedPage = null
    }

    private fun load(page: Int, forceRefresh: Boolean) {
        val owner = target ?: return
        val version = ++generation
        val key = "discussion:v1:bangumi:${owner.bangumiId}:$page:${animeHash(owner.route.identity())}"
        val cacheEpoch = cache.generation
        mutableState.value = state.value.copy(loading = page == 0, loadingMore = page > 0, error = null)
        request = scope.launch(start = CoroutineStart.LAZY) {
            fun current() = version == generation && target == owner && runCatching(::snapshot).getOrNull() == owner.route
            fun show(result: ReviewPage, reading: Boolean = false) {
                if (!current()) return
                val previous = state.value
                nextPage = result.nextPage
                failedPage = null
                mutableState.value = previous.copy(
                    items = (if (page == 0) result.items else previous.items + result.items).distinctBy(MangaReview::id),
                    total = result.total ?: previous.total.takeIf { page > 0 },
                    loaded = true, loading = reading, loadingMore = false, hasMore = result.nextPage != null, error = null,
                )
            }
            try {
                val cached = cache.read(key)
                if (!current()) return@launch
                if (cached != null && (!forceRefresh || page == 0 && state.value.items.isEmpty())) {
                    val previous = withContext(Dispatchers.Default) {
                        runCatching { decodeBangumiComments(JSONObject(cached.text), owner.bangumiId, page) }.getOrNull()
                    }
                    if (!current()) return@launch
                    if (previous != null && !forceRefresh && cache.isFresh(cached, ContentCacheKind.COMMENTS)) {
                        show(previous)
                        return@launch
                    }
                    if (previous != null && page == 0 && !state.value.loaded) show(previous, reading = true)
                }
                val result = read(owner, page)
                if (!current()) return@launch
                val (document, decoded) = withContext(Dispatchers.Default) {
                    result.toString() to decodeBangumiComments(result, owner.bangumiId, page)
                }
                if (!current()) return@launch
                cache.write(key, document, cacheEpoch)
                show(decoded)
            } catch (cancel: CancellationException) {
                if (current()) mutableState.value = state.value.copy(loading = false, loadingMore = false)
                throw cancel
            } catch (failure: Exception) {
                if (current()) {
                    failedPage = page
                    mutableState.value = state.value.copy(loading = false, loadingMore = false,
                        error = failure.message ?: "Bangumi 短评暂时无法读取，请重试。")
                }
            } finally {
                if (version == generation) request = null
            }
        }
        request?.start()
    }

    private suspend fun read(owner: Target, page: Int): JSONObject {
        val url = animePublicUrl(bangumiCommentsRequestUrl(owner.bangumiId, page))
        val http = AnimeHttp(BANGUMI_PROVIDER, owner.route)
        try {
            suspend fun attempt(): JSONObject {
                if (snapshot() != owner.route) throw DownloadRouteException("Bangumi 短评的网络线路已更改。")
                return withTimeoutOrNull(8_000L) {
                    http.json(url, AnimeSourceStage.METADATA, sameOriginRedirects = true)
                } ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA,
                    "等待 Bangumi 短评超时。",
                    cause = NetworkTransferException(NetworkFailureKind.RESPONSE_TIMEOUT, "等待 Bangumi 短评超时"))
            }
            return try { attempt() } catch (failure: AnimeSourceException) {
                if (networkFailureKind(failure) == NetworkFailureKind.NO_NETWORK) throw failure
                val wait = discoveryRetryDelay(failure, transientDelay = 450L)?.takeIf { it <= 4_000L } ?: throw failure
                delay(wait)
                attempt()
            }
        } finally { http.close() }
    }
}
