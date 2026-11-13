package dev.mangaflow.data

import dev.mangaflow.platform.*

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.platform.storage.cacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.MediaType.Companion.toMediaType
import dev.mangaflow.net.Request
import dev.mangaflow.net.RequestBody.Companion.toRequestBody
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import com.fleeksoft.ksoup.Ksoup as Jsoup

/** Anonymous, attributed reviews. Each provider keeps its own scale and identity. */
class ReviewRepository(context: Context) {
    private val http = SourceHttp()
    private val providerHttp = ReviewProviderHttp(http)
    private val mangaBaka = MangaBakaReviewProvider(providerHttp)
    private val externalProviders: List<ExternalReviewProvider> = listOf(
        MangaUpdatesReviewProvider(providerHttp), MyAnimeListReviewProvider(providerHttp),
        KitsuReviewProvider(providerHttp), MangaDexReviewProvider(providerHttp),
        ShikimoriReviewProvider(providerHttp), TencentReviewProvider(providerHttp),
        BahamutReviewProvider(providerHttp), ManhuaguiReviewProvider(providerHttp), mangaBaka,
    )
    private val providerSlots = Semaphore(3)
    private val metricProviderSlots = ReviewProviders.metricIds.associateWith { Semaphore(2) }
    private val chapterProviders = ChapterReviewProviders(providerHttp)
    private val chapterIdentityCache = LinkedHashMap<String, ChapterIdentitySnapshot>()
    private val associations = context.applicationContext.getSharedPreferences("review_associations", 0)
    private val cache = LinkedHashMap<String, CachedSource>()
    private val confirmedNames = LinkedHashMap<String, List<String>>()
    @kotlin.concurrent.Volatile private var cacheGeneration = 0L

    /** Source callbacks run on IO workers and can arrive concurrently. */
    suspend fun lookup(manga: Manga, forceRefresh: Boolean = false, onSource: (ReviewSource) -> Unit = {}): List<ReviewSource> = withContext(Dispatchers.IO) {
        val key = identityKey(manga)
        val epoch = cacheGeneration
        val savedBangumi = if (forceRefresh) null else cachedSource(key, "bangumi")
        val savedAniList = if (forceRefresh) null else cachedSource(key, "anilist")
        coroutineScope {
            val bgm = async {
                (savedBangumi?.let { BangumiMatch(it.source, it.names) } ?: bangumi(manga)
                    .let { match -> match.copy(source = rememberSource(key, match.source, match.names, epoch)) })
                    .also { if (epoch == cacheGeneration && key == identityKey(manga)) onSource(it.source) }
            }
            val ani = async {
                (savedAniList?.source ?: rememberSource(key, aniList(manga, listOf(manga.title)), epoch = epoch))
                    .also { if (epoch == cacheGeneration && key == identityKey(manga)) onSource(it) }
            }
            val bangumi = bgm.await()
            var anilist = ani.await()
            // Only a confirmed manga may supply aliases for a second, bounded search.
            if (savedAniList == null && anilist.status == ReviewStatus.NO_MATCH && bangumi.source.status == ReviewStatus.READY &&
                bangumi.names.any { normalize(it) != normalize(manga.title) }) {
                anilist = rememberSource(key, aniList(manga, bangumi.names + manga.title), epoch = epoch)
                    .also { if (epoch == cacheGeneration && key == identityKey(manga)) onSource(it) }
            }
            val primary = listOf(bangumi.source, anilist)
            val savedMapping = if (forceRefresh) null else cachedSource(key, mangaBaka.id)
            var mapping = savedMapping?.source ?: providerSlots.withPermit {
                externalLookup(manga, mangaBaka, confirmedAliases(manga, primary), confirmedIds(primary),
                    includeComments = false).let { rememberSource(key, it, epoch = epoch) }
            }
            currentCoroutineContext().ensureActive()
            if (epoch == cacheGeneration && key == identityKey(manga)) onSource(mapping)
            // Mapping contributes identities and titles only; every score/comment still comes
            // from its original provider, which checks the linked manga again.
            val mappedIds = confirmedIds(primary + mapping)
            val mappedAniList = mappedIds["anilist"].orEmpty()
            if (anilist.status == ReviewStatus.NO_MATCH && mappedAniList.isNotBlank()) {
                anilist = rememberSource(key, aniList(manga, confirmedAliases(manga, primary + mapping),
                    knownIds = mappedIds), epoch = epoch)
                    .also { if (epoch == cacheGeneration && key == identityKey(manga)) onSource(it) }
                if (anilist.status == ReviewStatus.NO_MATCH && hasConflictingLinks(anilist, mappedIds)) {
                    mapping = rememberSource(key, conflictingLinkChoice(mapping), epoch = epoch)
                    if (epoch == cacheGeneration && key == identityKey(manga)) onSource(mapping)
                }
            }
            val confirmed = listOf(bangumi.source, anilist, mapping)
            val aliases = confirmedAliases(manga, confirmed)
            val ids = confirmedIds(confirmed)
            val expanded = externalProviders.filterNot { it.id == mangaBaka.id }.map { provider -> async {
                val cached = if (forceRefresh) null else cachedSource(key, provider.id)
                val source = cached?.source ?: providerSlots.withPermit {
                    externalLookup(manga, provider, aliases, ids).let { rememberSource(key, it, epoch = epoch) }
                }
                currentCoroutineContext().ensureActive()
                if (epoch == cacheGeneration && key == identityKey(manga)) onSource(source)
                source
            } }.map { it.await() }
            confirmed + expanded
        }
    }

    /** Only identity and native metrics are fetched; card badges never load comment pages. */
    suspend fun lookupMetrics(manga: Manga, sourceIds: Set<String>, onSource: (ReviewSource) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val key = identityKey(manga)
            val epoch = cacheGeneration
            val requested = ReviewProviders.metricIds.filter { it in sourceIds }
            if (requested.isEmpty()) return@withContext
            val identityLock = SynchronizedObject()
            val knownNames = synchronized(reviewRepositoryStateLock) { confirmedNames[key] }.orEmpty()
            // Metadata identities are local to this batch; READY here does not mean comments were loaded.
            // The parallel score batch shares original-provider confirmations only.
            // MangaBaka mappings are verified in the ordered detail lookup before reuse.
            val knownSources = cachedSources(manga).filter { it.status == ReviewStatus.READY && it.id != mangaBaka.id }
                .associateByTo(LinkedHashMap<String, ReviewSource>(), ReviewSource::id)

            fun isCurrent() = epoch == cacheGeneration && key == identityKey(manga)

            fun identitySnapshot(): MetricLookupIdentity = synchronized(identityLock) {
                val sources = knownSources.values.toList()
                MetricLookupIdentity(
                    aliases = (knownNames + confirmedAliases(manga, sources))
                        .filter(String::isNotBlank).distinctBy(::normalize),
                    externalIds = confirmedIds(sources),
                )
            }

            fun rememberIdentity(source: ReviewSource) {
                if (source.status != ReviewStatus.READY || !isCurrent()) return
                synchronized(identityLock) { knownSources[source.id] = source }
            }

            fun publish(source: ReviewSource) {
                if (!isCurrent()) return
                rememberIdentity(source)
                onSource(source)
            }

            suspend fun attempt(sourceId: String, previous: MetricLookupIdentity? = null): MetricLookupAttempt? =
                metricProviderSlots.getValue(sourceId).withPermit {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@withPermit null
                    // A queued source can use identities confirmed while it waited for its slot.
                    val identity = identitySnapshot()
                    val previousNames = previous?.aliases?.map(::normalize)?.toSet().orEmpty()
                    val newNames = if (previous == null) emptyList() else
                        identity.aliases.filter { normalize(it) !in previousNames }
                    val aliases = (preferredMetricAliases(manga, newNames) + preferredMetricAliases(manga, identity.aliases))
                        .distinctBy(::normalize)
                    val info = ReviewProviders.find(sourceId)!!
                    fun unavailable(message: String) = ReviewSource(sourceId, info.name,
                        ReviewStatus.UNAVAILABLE, info.homeUrl, scoreScale = info.scoreScale ?: 10,
                        message = message, hasWorkReviews = info.hasWorkReviews)
                    val source = try {
                        withTimeoutOrNull(30_000L) {
                            val linked = savedId(manga, sourceId).ifBlank { identity.externalIds[sourceId].orEmpty() }
                            when (sourceId) {
                                "bangumi" -> {
                                    val match = if (linked.isNotBlank()) bangumiSubject(requireSubjectId(linked), includeComments = false)
                                        else bangumi(manga, includeComments = false, aliases = aliases)
                                    if (linked.isNotBlank() && match.source.status == ReviewStatus.READY)
                                        rememberNames(manga, key, epoch, match.names)
                                    match.source
                                }
                                "anilist" -> aniList(manga, aliases, includeComments = false,
                                    knownIds = identity.externalIds)
                                else -> externalLookup(manga, externalProvider(sourceId), aliases,
                                    identity.externalIds, includeComments = false)
                            }
                        } ?: unavailable("${info.name} 评分读取超时，请稍后重试。")
                    } catch (cancel: CancellationException) { throw cancel }
                      catch (failure: Exception) {
                        unavailable(failure.reviewMessage("${info.name} 暂时无法连接，请稍后重试。"))
                    }
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@withPermit null
                    MetricLookupAttempt(source, identity)
                }

            coroutineScope {
                val completed = Channel<Pair<String, MetricLookupAttempt?>>(requested.size + 1)
                val unmatched = LinkedHashMap<String, MetricLookupAttempt>()
                val retried = mutableSetOf<String>()
                var inFlight = 0
                var recoveryAttempted = false

                fun start(sourceId: String, previous: MetricLookupIdentity? = null) {
                    inFlight++
                    launch { completed.send(sourceId to attempt(sourceId, previous)) }
                }

                fun retryConfirmedIdentities() {
                    val latest = identitySnapshot()
                    val ready = unmatched.filter { (sourceId, earlier) ->
                        val oldNames = earlier.identity.aliases.map(::normalize).toSet()
                        val linked = latest.externalIds[sourceId]
                        (!linked.isNullOrBlank() && linked != earlier.identity.externalIds[sourceId]) ||
                            latest.aliases.any { normalize(it) !in oldNames }
                    }
                    ready.forEach { (sourceId, earlier) ->
                        unmatched.remove(sourceId)
                        retried.add(sourceId)
                        start(sourceId, earlier.identity)
                    }
                }

                requested.forEach { start(it) }
                try {
                    while (true) {
                        if (inFlight == 0) {
                            // Scores survive a restart; recover missing aliases at most once.
                            if (unmatched.isNotEmpty() && !recoveryAttempted && "bangumi" !in requested &&
                                identitySnapshot().aliases.none { normalize(it) != normalize(manga.title) }) {
                                recoveryAttempted = true
                                start("bangumi")
                            } else break
                        }
                        val (sourceId, result) = completed.receive()
                        inFlight--
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent()) throw CancellationException("评分请求已失效")
                        if (result != null) {
                            when {
                                sourceId !in requested -> rememberIdentity(result.source)
                                result.source.status == ReviewStatus.NO_MATCH && sourceId !in retried ->
                                    unmatched[sourceId] = result
                                else -> publish(result.source)
                            }
                        }
                        // A retry can confirm IDs useful to another source too. Process every
                        // result immediately; no provider waits for unrelated work to finish.
                        retryConfirmedIdentities()
                    }
                    unmatched.values.forEach { publish(it.source) }
                } finally {
                    completed.cancel()
                }
            }
        }

    internal fun identityKey(manga: Manga): String = (listOf(manga.id, manga.title, manga.authors.joinToString("|")) +
        ReviewProviders.ids.map { savedId(manga, it) }).joinToString("\u0000")

    internal fun metricIdentityKey(manga: Manga): String = (listOf(manga.id) +
        ReviewProviders.ids.map { savedId(manga, it) }).joinToString("\u0000")

    /** Retain the last known snapshot while a reader refreshes; never cross saved associations. */
    fun cachedSources(manga: Manga): List<ReviewSource> {
        val key = identityKey(manga)
        return synchronized(reviewRepositoryStateLock) {
            ReviewProviders.ids.mapNotNull { cache["$key\u0000$it"]?.source }
        }
    }

    fun clearCache() {
        synchronized(reviewRepositoryStateLock) { cacheGeneration++; cache.clear() }
        synchronized(reviewRepositoryStateLock) { confirmedNames.clear() }
        synchronized(reviewRepositoryStateLock) { chapterIdentityCache.clear() }
        externalProviders.forEach(ExternalReviewProvider::clearCache)
    }

    val chapterProviderIds: List<String> get() = chapterProviders.providerIds

    /** Only metadata is resolved here; work-review endpoints never enter the chapter path. */
    suspend fun chapterSources(manga: Manga, chapter: Chapter): List<ReviewSource> = withContext(Dispatchers.IO) {
        val key = identityKey(manga)
        val epoch = cacheGeneration
        var ids = confirmedIds(cachedSources(manga))
        val stored = synchronized(reviewRepositoryStateLock) { chapterIdentityCache[key] }
            ?.takeIf { wallTimeMillis() - it.time in 0 until 6 * 60 * 60_000L }
        if (stored != null) ids = stored.ids + ids
        val selectedAniList = savedId(manga, "anilist")
        if (selectedAniList.isNotBlank()) ids = ids + ("anilist" to selectedAniList)
        if (ids["anilist"].isNullOrBlank()) {
            val aliases = preferredAniListAliases(manga, confirmedAliases(manga, cachedSources(manga)))
            var source = aniList(manga, aliases, includeComments = false)
            if (source.status == ReviewStatus.NO_MATCH) {
                val matched = bangumi(manga, includeComments = false)
                if (matched.source.status == ReviewStatus.READY && matched.names.any { normalize(it) != normalize(manga.title) })
                    source = aniList(manga, matched.names + manga.title, includeComments = false)
            }
            currentCoroutineContext().ensureActive()
            if (source.status == ReviewStatus.UNAVAILABLE) {
                return@withContext chapterProviderIds.map { id -> ReviewSource(id, "Dantotsu", ReviewStatus.UNAVAILABLE,
                    "https://api.dantotsu.app", subjectId = chapter.id,
                    message = "本话关联暂时无法确认：${source.message}", nextPage = 0) }
            }
            if (source.status == ReviewStatus.READY) ids = ids + confirmedIds(listOf(source))
        }
        currentCoroutineContext().ensureActive()
        if (epoch != cacheGeneration || key != identityKey(manga))
            throw SourceException("作品关联已更改，请重新读取本话评论。")
        if (ids.isNotEmpty()) synchronized(reviewRepositoryStateLock) {
            chapterIdentityCache[key] = ChapterIdentitySnapshot(wallTimeMillis(), ids)
            while (chapterIdentityCache.size > 96) chapterIdentityCache.remove(chapterIdentityCache.keys.first())
        }
        chapterProviders.lookup(manga, chapter, ids)
    }

    suspend fun chapterPage(manga: Manga, chapter: Chapter, source: ReviewSource, page: Int): ReviewPage =
        withContext(Dispatchers.IO) {
            requireChapterAssociation(manga, source)
            chapterProviders.page(manga, chapter, source, page)
        }

    suspend fun chapterReplies(manga: Manga, chapter: Chapter, source: ReviewSource, review: MangaReview, page: Int): ReviewPage =
        withContext(Dispatchers.IO) {
            requireChapterAssociation(manga, source)
            chapterProviders.replies(manga, chapter, source, review, page)
        }

    private fun requireChapterAssociation(manga: Manga, source: ReviewSource) {
        val selected = savedId(manga, "anilist")
        if (selected.isNotBlank() && source.externalIds["anilist"] != selected)
            throw SourceException("作品关联已更改，请刷新本话评论。", source.url)
    }

    private fun cachedSource(key: String, sourceId: String): CachedSource? = synchronized(reviewRepositoryStateLock) {
        cache["$key\u0000$sourceId"]?.takeIf {
            val ttl = if (it.source.status == ReviewStatus.UNAVAILABLE || it.source.reviewsError != null) 5 * 60_000L else 3 * 60_000L
            wallTimeMillis() - it.time in 0L until ttl
        }
    }

    private fun rememberSource(key: String, source: ReviewSource, names: List<String> = emptyList(), epoch: Long = cacheGeneration): ReviewSource = synchronized(reviewRepositoryStateLock) {
        if (epoch != cacheGeneration) return@synchronized source
        val sourceKey = "$key\u0000${source.id}"
        val previous = cache[sourceKey]?.source?.takeIf { it.status == ReviewStatus.READY }
        val retained = when {
            source.status == ReviewStatus.UNAVAILABLE && previous != null -> previous.copy(reviewsError = source.message)
            source.reviewsError != null && previous != null && source.subjectId == previous.subjectId && previous.reviews.isNotEmpty() ->
                source.copy(reviews = (source.reviews + previous.reviews).distinctBy(MangaReview::id))
            else -> source
        }
        cache.remove(sourceKey)
        cache[sourceKey] = CachedSource(wallTimeMillis(), retained, names)
        while (cache.size > 256) cache.remove(cache.keys.first())
        retained
    }

    /** Pages are zero-based in the app, regardless of the provider's pagination scheme. */
    suspend fun loadMore(manga: Manga, source: ReviewSource, page: Int): ReviewPage = withContext(Dispatchers.IO) {
        val key = identityKey(manga)
        val epoch = cacheGeneration
        requireSource(source.id)
        if (!source.hasWorkReviews) throw SourceException("这个条目只提供评分或作品资料。", source.url)
        val id = source.subjectId.takeIf(String::isNotBlank)
            ?: throw SourceException("作品条目标识无效，请重新选择。")
        if (source.status != ReviewStatus.READY || page < 0 || page > Int.MAX_VALUE / PAGE_SIZE - 1) {
            throw SourceException("评论分页信息无效，请重新加载作品。")
        }
        val saved = savedId(manga, source.id)
        if (saved.isNotBlank() && saved != source.subjectId) {
            throw SourceException("作品关联已更改，请重新加载评论。")
        }
        val result = when (source.id) {
            "bangumi" -> bangumiComments(requireSubjectId(id), page)
            "anilist" -> aniListReviews(requireSubjectId(id), page)
            else -> externalProvider(source.id).page(id, page)
        }
        currentCoroutineContext().ensureActive()
        if (epoch != cacheGeneration || key != identityKey(manga))
            throw CancellationException("作品关联或评论缓存已更新")
        if (page == 0 && epoch == cacheGeneration && key == identityKey(manga)) {
            val names = synchronized(reviewRepositoryStateLock) { cache["$key\u0000${source.id}"]?.names }.orEmpty()
            rememberSource(key, source.copy(reviews = result.items, reviewCount = result.total,
                nextPage = result.nextPage, reviewsError = result.error, loadingMore = false), names, epoch)
        }
        result
    }

    /** Persist only an explicitly chosen, fetched and format-checked provider identity. */
    suspend fun choose(manga: Manga, sourceId: String, subjectId: String): ReviewSource = withContext(Dispatchers.IO) {
        requireSource(sourceId)
        val id = subjectId.trim().takeIf(String::isNotBlank)
            ?: throw SourceException("作品条目标识无效，请重新选择。")
        val source = when (sourceId) {
            "bangumi" -> bangumiSubject(requireSubjectId(id)).source
            "anilist" -> aniListSubject(requireSubjectId(id))
            else -> externalProvider(sourceId).subject(id)
        }
        if (source.id != sourceId || source.subjectId != id || source.status != ReviewStatus.READY)
            throw SourceException("来源未能确认这部漫画，请重新选择。", source.url)
        if (!associations.edit().putString(associationKey(manga, sourceId), id).commit()) {
            throw SourceException("作品已确认，但关联未能保存，请重试。", source.url)
        }
        clearCache()
        rememberSource(identityKey(manga), source)
    }

    /** Clear a manual association and repeat the same guarded matching used by lookup. */
    suspend fun relink(manga: Manga, sourceId: String): ReviewSource = withContext(Dispatchers.IO) {
        requireSource(sourceId)
        if (!associations.edit().remove(associationKey(manga, sourceId)).commit()) {
            throw SourceException("已保存的作品关联未能清除，请重试。")
        }
        clearCache()
        when (sourceId) {
            "bangumi" -> bangumi(manga).source
            "anilist" -> {
                val source = aniList(manga, listOf(manga.title))
                if (source.status != ReviewStatus.NO_MATCH) return@withContext source
                val confirmed = bangumi(manga)
                if (confirmed.source.status == ReviewStatus.READY &&
                    confirmed.names.any { normalize(it) != normalize(manga.title) }) {
                    aniList(manga, confirmed.names + manga.title)
                } else source
            }
            else -> lookupSource(manga, sourceId)
        }
    }

    /** Refresh only the requested provider; a retry must not reload every other site. */
    suspend fun lookupSource(manga: Manga, sourceId: String): ReviewSource = withContext(Dispatchers.IO) {
        requireSource(sourceId)
        val key = identityKey(manga)
        val epoch = cacheGeneration
        val source = when (sourceId) {
            "bangumi" -> bangumi(manga).source
            "anilist" -> {
                val known = cachedSources(manga)
                val knownIds = confirmedIds(known)
                val aliases = preferredAniListAliases(manga, confirmedAliases(manga, known))
                val first = aniList(manga, aliases, knownIds = knownIds)
                if (first.status == ReviewStatus.NO_MATCH && knownIds[sourceId].isNullOrBlank() &&
                    savedId(manga, sourceId).isBlank() && aliases.none { normalize(it) != normalize(manga.title) }) {
                    val matched = bangumi(manga, includeComments = false)
                    if (matched.source.status == ReviewStatus.READY && matched.names.any { normalize(it) != normalize(manga.title) })
                        aniList(manga, preferredAniListAliases(manga, matched.names + manga.title))
                    else first
                } else first
            }
            else -> {
                val known = cachedSources(manga)
                providerSlots.withPermit {
                    externalLookup(manga, externalProvider(sourceId), confirmedAliases(manga, known), confirmedIds(known))
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (epoch != cacheGeneration || key != identityKey(manga))
            throw CancellationException("作品关联或评论缓存已更新")
        if (source.id == "anilist" && source.status == ReviewStatus.NO_MATCH) {
            cachedSources(manga).firstOrNull { it.id == mangaBaka.id && it.status == ReviewStatus.READY }
                ?.takeIf { hasConflictingLinks(source, it.externalIds) }
                ?.let { rememberSource(key, conflictingLinkChoice(it), epoch = epoch) }
        }
        rememberSource(key, source, epoch = epoch)
    }

    suspend fun replies(manga: Manga, source: ReviewSource, review: MangaReview, page: Int): ReviewPage =
        withContext(Dispatchers.IO) {
            requireSource(source.id)
            if (!source.supportsReplies || source.status != ReviewStatus.READY || source.subjectId.isBlank() ||
                page !in 0..100_000 || review.kind == ReviewKind.CHAPTER ||
                source.reviews.none { it.id == review.id && it.kind == review.kind }) {
                throw SourceException("回复信息已失效，请重新打开评论。", source.url)
            }
            val saved = savedId(manga, source.id)
            if (saved.isNotBlank() && saved != source.subjectId)
                throw SourceException("作品关联已更改，请重新加载评论。", source.url)
            externalProvider(source.id).replies(source.subjectId, review, page)
        }

    private fun externalProvider(id: String): ExternalReviewProvider = externalProviders.firstOrNull { it.id == id }
        ?: throw SourceException("不支持这个评价来源。")

    private fun confirmedAliases(manga: Manga, sources: List<ReviewSource>): List<String> =
        (listOf(manga.title) + sources.filter { it.status == ReviewStatus.READY }
            .flatMap { it.aliases + it.matchedTitle }).filter(String::isNotBlank).distinct()

    private fun preferredAniListAliases(manga: Manga, names: List<String>): List<String> =
        names.filter { normalize(it) != normalize(manga.title) } + names.filter { normalize(it) == normalize(manga.title) }

    private fun preferredMetricAliases(manga: Manga, names: List<String>): List<String> {
        val candidates = preferredAniListAliases(manga, names).filter(String::isNotBlank).distinctBy(::normalize)
        val alternatives = candidates.filter { normalize(it) != normalize(manga.title) }.ifEmpty { candidates }
        // Keep bounded searches from spending every query on translated-name variants.
        val representative = listOfNotNull(
            alternatives.firstOrNull { name -> name.any {
                it in '\u3040'..'\u30ff' || it in '\u1100'..'\u11ff' || it in '\uac00'..'\ud7af'
            } },
            alternatives.firstOrNull { name -> name.any { it in 'A'..'Z' || it in 'a'..'z' } },
            alternatives.firstOrNull(),
        )
        return (representative + candidates).distinctBy(::normalize)
    }

    private fun confirmedIds(sources: List<ReviewSource>): Map<String, String> {
        val candidates = sources.filter { it.status == ReviewStatus.READY && it.subjectId.isNotBlank() }
            .flatMap { listOf(it.id to it.subjectId) + it.externalIds.toList() }
            .filter { it.second.isNotBlank() }.groupBy({ it.first }, { it.second })
        // Conflicting cross-links never silently choose an upstream identity.
        return candidates.mapNotNull { (id, values) -> values.distinct().singleOrNull()?.let { id to it } }.toMap()
    }

    private suspend fun externalLookup(
        manga: Manga, provider: ExternalReviewProvider, aliases: List<String>,
        externalIds: Map<String, String>, includeComments: Boolean = true,
    ): ReviewSource {
        try {
            val selectedId = savedId(manga, provider.id)
            if (selectedId.isNotBlank()) return provider.subject(selectedId, includeComments)
            val linked = externalIds[provider.id].orEmpty()
            if (linked.isNotBlank()) {
                val source = provider.subject(linked, includeComments)
                if (!hasConflictingLinks(source, externalIds)) return source
                return conflictingLinkChoice(source)
            }
            if (provider is MangaBakaReviewProvider) {
                provider.fromKnownIds(externalIds)?.let { return it }
            }
            val names = aliases.filter(String::isNotBlank).distinctBy(::normalize)
            val known = names.map(::normalize).filter(String::isNotBlank).toSet()
            val candidates = LinkedHashMap<String, ProviderCandidate>()
            for (query in names.take(3)) {
                provider.search(query).forEach { candidate ->
                    if (candidate.match.id.isNotBlank() && candidate.match.title.isNotBlank())
                        candidates[candidate.match.id] = candidate
                }
                val exact = candidates.values.filter { item -> item.titles.any { normalize(it) in known } }
                val knownAuthors = manga.authors.flatMap(::authorNames).toSet()
                val byAuthor = exact.filter { it.authors.flatMap(::authorNames).any { name -> name in knownAuthors } }
                val compatible = exact.filter { knownAuthors.isEmpty() || it.authors.isEmpty() || it in byAuthor }
                val selected = byAuthor.singleOrNull() ?: compatible.singleOrNull()
                if (selected != null) {
                    val source = provider.subject(selected.match.id, includeComments)
                    if (!hasConflictingLinks(source, externalIds)) return source
                    return conflictingLinkChoice(source)
                }
            }
            val exact = candidates.values.filter { item -> item.titles.any { normalize(it) in known } }
            val choices = (exact.ifEmpty { candidates.values.toList() }).take(12).map(ProviderCandidate::match)
            return ReviewSource(provider.id, provider.name, ReviewStatus.NO_MATCH, provider.homeUrl,
                message = if (choices.isEmpty()) "尚未找到对应漫画" else "请确认对应的漫画条目，选择后会记住关联",
                candidates = choices, hasWorkReviews = provider.hasWorkReviews)
        } catch (cancel: CancellationException) { throw cancel }
          catch (failure: Exception) {
            return ReviewSource(provider.id, provider.name, ReviewStatus.UNAVAILABLE, provider.homeUrl,
                message = failure.reviewMessage("${provider.name} 暂时无法连接，请稍后重试"),
                hasWorkReviews = provider.hasWorkReviews)
        }
    }

    private fun hasConflictingLinks(source: ReviewSource, known: Map<String, String>): Boolean =
        source.externalIds.any { (id, value) -> known[id]?.let { it != value } == true }

    private fun conflictingLinkChoice(source: ReviewSource): ReviewSource =
        ReviewSource(source.id, source.name, ReviewStatus.NO_MATCH, source.url,
            message = "作品编号关联不一致，请确认对应漫画。",
            candidates = listOf(ReviewMatch(source.subjectId, source.matchedTitle,
                "跨站编号关联不一致", source.url)), hasWorkReviews = source.hasWorkReviews,
            externalIds = source.externalIds)

    private suspend fun bangumi(
        manga: Manga, includeComments: Boolean = true, aliases: List<String> = listOf(manga.title),
    ): BangumiMatch {
        val key = identityKey(manga)
        val epoch = cacheGeneration
        val queryName = aliases.firstOrNull(String::isNotBlank) ?: manga.title
        val searchUrl = "https://bgm.tv".toHttpUrl().newBuilder().addPathSegment("subject_search")
            .addPathSegment(queryName).addQueryParameter("cat", "1").build().toString()
        try {
            savedId(manga, "bangumi").takeIf(String::isNotBlank)?.let {
                return bangumiSubject(requireSubjectId(it), includeComments).also { rememberNames(manga, key, epoch, it.names) }
            }
            val body = JSONObject().put("keyword", queryName).put("sort", "match")
                .put("filter", JSONObject().put("type", JSONArray().put(1)))
            val result = jsonRequest("https://api.bgm.tv/v0/search/subjects?limit=20&offset=0", "Bangumi 作品", searchUrl, body)
            val rows = result.optJSONArray("data") ?: throw SourceException("Bangumi 没有返回有效的搜索结果。", searchUrl)
            // Book (type 1) includes novels. Filter format BEFORE deciding whether names collide.
            val comics = rows.objects().filter { it.optInt("id") > 0 && it.isBangumiComic() }.distinctBy { it.optInt("id") }
            val targets = (aliases + manga.title).map(::normalize).filter(String::isNotBlank).toSet()
            val matches = comics.filter { item -> bangumiNames(item).any { normalize(it) in targets } }
            val candidate = resolveBangumiMatch(matches, manga.authors)
            if (candidate != null) return bangumiSubject(candidate.optInt("id"), includeComments)
                .also { rememberNames(manga, key, epoch, it.names) }
            val choices = (matches.ifEmpty { comics }).take(8).map(::bangumiCandidate)
            return BangumiMatch(ReviewSource("bangumi", "Bangumi", ReviewStatus.NO_MATCH, searchUrl,
                message = if (choices.isEmpty()) "尚未找到对应漫画" else "请确认对应的漫画条目，选择后会记住关联",
                candidates = choices))
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            return BangumiMatch(ReviewSource("bangumi", "Bangumi", ReviewStatus.UNAVAILABLE, searchUrl,
                message = error.userMessage("Bangumi 暂时无法连接，请稍后重试")))
        }
    }

    private suspend fun bangumiSubject(id: Int, includeComments: Boolean = true): BangumiMatch {
        val url = "https://bgm.tv/subject/$id"
        val subject = jsonRequest("https://api.bgm.tv/v0/subjects/$id", "Bangumi 评分", url)
        if (subject.optInt("id") != id || !subject.isBangumiComic()) {
            throw SourceException("这个 Bangumi 条目不是已确认的漫画，请重新选择。", url)
        }
        val rating = subject.optJSONObject("rating")
        val total = rating?.nonNegativeInt("total")
        val base = ReviewSource("bangumi", "Bangumi", ReviewStatus.READY, "$url/comments",
            matchedTitle = subject.text("name_cn").ifBlank { subject.text("name") },
            score = rating?.nullableScore("score", 10)?.takeIf { total != 0 }, scoreScale = 10,
            ratingCount = total, subjectId = id.toString(), message = "中文短评 · 最新动态",
            aliases = bangumiNames(subject))
        return BangumiMatch(if (includeComments) withFirstPage(base) { bangumiComments(id, 0) } else base, bangumiNames(subject))
    }

    private suspend fun bangumiComments(id: Int, page: Int): ReviewPage {
        val publicUrl = "https://bgm.tv/subject/$id/comments"
        val offset = page * PAGE_SIZE
        // p1 serves Bangumi's public next-generation frontend; anonymous GET only.
        // It is not a stable v0 API, so a failure must leave the independent score intact.
        val result = jsonRequest("https://next.bgm.tv/p1/subjects/$id/comments?limit=$PAGE_SIZE&offset=$offset",
            "Bangumi 短评", publicUrl)
        val rows = result.optJSONArray("data") ?: throw SourceException("Bangumi 短评暂时没有返回有效数据。", publicUrl)
        val total = result.nonNegativeInt("total")
        if (rows.length() == 0 && total != null && offset < total) {
            throw SourceException("Bangumi 短评这一页暂时未能载入，请重试。", publicUrl)
        }
        val reviews = rows.objects().mapNotNull { row ->
            val reviewId = row.optLong("id", 0)
            val user = row.optJSONObject("user")
            val username = user?.text("username").orEmpty()
            val author = user?.text("nickname").orEmpty().ifBlank { username }
            val raw = row.text("comment")
            val content = plainText(raw)
            if (reviewId <= 0 || author.isBlank() || content.isBlank()) return@mapNotNull null
            MangaReview(id = reviewId.toString(), author = author, summary = content.take(360), url = publicUrl,
                score = row.nullableScore("rate", 10), scoreScale = 10, body = content,
                date = date(row.optLong("updatedAt", 0)), spoiler = hasSpoilerMarkup(raw), kind = ReviewKind.COMMENT,
                authorUrl = username.takeIf(String::isNotBlank)?.let { profileUrl("https://bgm.tv", it) }.orEmpty(),
                avatarUrl = avatar(user?.optJSONObject("avatar"), setOf("lain.bgm.tv")))
        }
        val more = if (total != null) offset.toLong() + rows.length() < total else rows.length() == PAGE_SIZE
        return ReviewPage(reviews, total, if (more && rows.length() > 0) page + 1 else null)
    }

    private suspend fun aniList(
        manga: Manga, aliases: List<String>, includeComments: Boolean = true,
        knownIds: Map<String, String> = emptyMap(),
    ): ReviewSource {
        val searchUrl = "https://anilist.co/search/manga".toHttpUrl().newBuilder()
            .addQueryParameter("search", manga.title).build().toString()
        try {
            val selectedId = savedId(manga, "anilist")
            selectedId.ifBlank { knownIds["anilist"].orEmpty() }.takeIf(String::isNotBlank)?.let {
                val source = aniListSubject(requireSubjectId(it), includeComments)
                return if (selectedId.isBlank() && hasConflictingLinks(source, knownIds))
                    conflictingLinkChoice(source) else source
            }
            val knownNames = aliases.map(::normalize).filter(String::isNotBlank).toSet()
            val queryName = aliases.firstOrNull(String::isNotBlank) ?: manga.title
            val query = """
                query FindManga(${'$'}search: String!) {
                  Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: MANGA) {
                      id type format startDate { year } title { native romaji english } synonyms
                    }
                  }
                }
            """.trimIndent()
            val result = graphql(query, JSONObject().put("search", queryName), searchUrl)
            val rows = result.optJSONObject("Page")?.optJSONArray("media")
                ?: throw SourceException("AniList 没有返回有效的搜索结果。", searchUrl)
            val comics = rows.objects().filter { it.optInt("id") > 0 && it.isAniListComic() }.distinctBy { it.optInt("id") }
            val matches = comics.filter { item -> mediaNames(item).any { normalize(it) in knownNames } }
            val candidate = matches.singleOrNull()
            if (candidate != null) {
                val source = aniListSubject(candidate.optInt("id"), includeComments)
                return if (hasConflictingLinks(source, knownIds)) conflictingLinkChoice(source) else source
            }
            val choices = matches.ifEmpty { comics }.take(8).map { item ->
                val id = item.optInt("id")
                val year = item.optJSONObject("startDate")?.nonNegativeInt("year")?.takeIf { it > 0 }?.toString().orEmpty()
                ReviewMatch(id.toString(), mediaNames(item).firstOrNull().orEmpty(),
                    listOf(if (item.text("format") == "ONE_SHOT") "短篇漫画" else "漫画", year).filter(String::isNotBlank).joinToString(" · "),
                    "https://anilist.co/manga/$id")
            }
            return ReviewSource("anilist", "AniList", ReviewStatus.NO_MATCH, searchUrl,
                message = if (choices.isEmpty()) "尚未找到对应漫画" else "请确认对应的漫画条目，选择后会记住关联",
                candidates = choices)
        } catch (error: CancellationException) { throw error }
          catch (error: Exception) {
            return ReviewSource("anilist", "AniList", ReviewStatus.UNAVAILABLE, searchUrl,
                message = error.userMessage("AniList 暂时无法连接，请稍后重试"))
        }
    }

    private suspend fun aniListSubject(id: Int, includeComments: Boolean = true): ReviewSource {
        val url = "https://anilist.co/manga/$id"
        // Keep metadata independent: unavailable comments must not erase a valid rating.
        val query = """
            query MangaRating(${'$'}id: Int!) {
              Media(id: ${'$'}id, type: MANGA) {
                id idMal type format title { native romaji english } synonyms averageScore
                stats { scoreDistribution { amount } }
              }
            }
        """.trimIndent()
        val media = graphql(query, JSONObject().put("id", id), url).optJSONObject("Media")
            ?: throw SourceException("AniList 没有返回漫画评分。", url)
        if (media.optInt("id") != id || !media.isAniListComic()) {
            throw SourceException("这个 AniList 条目不是已确认的漫画，请重新选择。", url)
        }
        // popularity is collection count, not number of ratings. Preserve the distribution sum.
        val amounts = media.optJSONObject("stats")?.optJSONArray("scoreDistribution")?.objects()
        val total = amounts?.takeIf { rows -> rows.all { it.nonNegativeInt("amount") != null } }
            ?.sumOf { it.nonNegativeInt("amount")!!.toLong() }?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        val base = ReviewSource("anilist", "AniList", ReviewStatus.READY, url,
            matchedTitle = mediaNames(media).firstOrNull().orEmpty(),
            score = media.nullableScore("averageScore", 100), scoreScale = 100,
            ratingCount = total, subjectId = id.toString(), message = "作品长评 · 最新发布 · 可能涉及后续剧情",
            aliases = mediaNames(media), externalIds = media.nonNegativeInt("idMal")?.takeIf { it > 0 }
                ?.let { mapOf("myanimelist" to it.toString()) }.orEmpty())
        return if (includeComments) withFirstPage(base) { aniListReviews(id, 0) } else base
    }

    private suspend fun aniListReviews(id: Int, page: Int): ReviewPage {
        val url = "https://anilist.co/manga/$id"
        val query = """
            query MangaReviews(${'$'}id: Int!, ${'$'}page: Int!, ${'$'}perPage: Int!) {
              Media(id: ${'$'}id, type: MANGA) {
                id
                reviews(page: ${'$'}page, perPage: ${'$'}perPage, sort: [ID_DESC]) {
                  pageInfo { hasNextPage }
                  nodes { id summary body(asHtml: false) score private createdAt user { name avatar { large medium } } }
                }
              }
            }
        """.trimIndent()
        val media = graphql(query, JSONObject().put("id", id).put("page", page + 1).put("perPage", PAGE_SIZE), url)
            .optJSONObject("Media") ?: throw SourceException("AniList 长评暂时没有返回有效数据。", url)
        if (media.optInt("id") != id) throw SourceException("AniList 长评的漫画归属不一致。", url)
        val connection = media.optJSONObject("reviews") ?: throw SourceException("AniList 长评暂时不可用。", url)
        val rows = connection.optJSONArray("nodes") ?: throw SourceException("AniList 长评暂时没有返回有效数据。", url)
        val info = connection.optJSONObject("pageInfo") ?: throw SourceException("AniList 没有返回评论分页信息。", url)
        val more = info.opt("hasNextPage") as? Boolean ?: throw SourceException("AniList 没有返回评论分页信息。", url)
        if (more && rows.length() == 0) {
            throw SourceException("AniList 长评这一页暂时未能载入，请重试。", url)
        }
        val reviews = rows.objects().mapNotNull { review ->
            if (review.optBoolean("private", false)) return@mapNotNull null
            val reviewId = review.optInt("id")
            val author = review.optJSONObject("user")?.text("name").orEmpty()
            val heading = plainText(review.text("summary"), markdown = true)
            val content = plainText(review.text("body"), markdown = true)
            val excerpt = content.ifBlank { heading }
            if (reviewId <= 0 || author.isBlank() || excerpt.isBlank()) return@mapNotNull null
            // Review has no API spoiler flag. Hide whole-work long reviews conservatively.
            MangaReview(id = reviewId.toString(), author = author, summary = excerpt.take(360),
                url = "https://anilist.co/review/$reviewId", score = review.nullableScore("score", 100, allowZero = true),
                scoreScale = 100, body = listOf(heading, content).filter(String::isNotBlank).distinct().joinToString("\n\n"),
                date = date(review.optLong("createdAt", 0)), spoiler = true, kind = ReviewKind.REVIEW,
                authorUrl = profileUrl("https://anilist.co", author),
                avatarUrl = avatar(review.optJSONObject("user")?.optJSONObject("avatar"), setOf("anilist.co")))
        }
        // AniList documents PageInfo.total as inaccurate; do not present it as a review count.
        return ReviewPage(reviews, total = null, nextPage = if (more) page + 1 else null)
    }

    private suspend fun withFirstPage(source: ReviewSource, load: suspend () -> ReviewPage): ReviewSource = try {
        val page = load()
        source.copy(reviews = page.items, reviewCount = page.total, nextPage = page.nextPage)
    } catch (error: CancellationException) { throw error }
      catch (error: Exception) {
        source.copy(reviewsError = error.userMessage("评论暂时无法载入，请稍后重试。"), nextPage = 0)
    }

    private fun resolveBangumiMatch(matches: List<JSONObject>, authors: List<String>): JSONObject? {
        if (matches.size < 2) return matches.singleOrNull()
        val knownAuthors = authors.flatMap(::authorNames).toSet()
        val byAuthor = matches.filter { item -> bangumiAuthors(item).flatMap(::authorNames).any { it in knownAuthors } }
        if (byAuthor.size == 1) return byAuthor.single()
        val pool = byAuthor.ifEmpty { matches }
        // A series entry describes the whole work; individual volumes have separate scores.
        return pool.filter { it.optBoolean("series", false) }.singleOrNull()
    }

    private fun bangumiCandidate(subject: JSONObject): ReviewMatch {
        val id = subject.optInt("id")
        val description = listOf("漫画", if (subject.optBoolean("series", false)) "全系列" else "",
            subject.text("date").take(4), bangumiAuthors(subject).joinToString(" / "))
            .filter(String::isNotBlank).joinToString(" · ")
        return ReviewMatch(id.toString(), subject.text("name_cn").ifBlank { subject.text("name") }, description,
            "https://bgm.tv/subject/$id")
    }

    private fun bangumiNames(subject: JSONObject): List<String> =
        (listOf(subject.text("name"), subject.text("name_cn")) + infoboxValues(subject, setOf("别名", "中文名")))
            .filter(String::isNotBlank).distinct()

    private fun bangumiAuthors(subject: JSONObject): List<String> =
        infoboxValues(subject, setOf("作者", "原作", "作画", "脚本", "绘画"))

    private fun infoboxValues(subject: JSONObject, keys: Set<String>): List<String> =
        subject.optJSONArray("infobox")?.objects().orEmpty().filter { normalize(it.text("key")) in keys }
            .flatMap { stringValues(it.opt("value")) }.filter(String::isNotBlank).distinct()

    private fun stringValues(value: Any?): List<String> = when (value) {
        is String -> listOf(value.trim())
        is JSONArray -> (0 until value.length()).flatMap { stringValues(value.opt(it)) }
        is JSONObject -> stringValues(value.opt("v"))
        else -> emptyList()
    }

    private fun authorNames(value: String): List<String> =
        (listOf(value) + value.split(Regex("[,，、/／;；&＆（）()\\[\\]【】]"))).map(::normalize).filter(String::isNotBlank)

    private fun JSONObject.isBangumiComic(): Boolean = optInt("type") == 1 && normalize(text("platform")) in setOf("漫画", "comic", "manga")
    private fun JSONObject.isAniListComic(): Boolean = text("type") == "MANGA" && text("format") in setOf("MANGA", "ONE_SHOT")
    private fun requireSource(sourceId: String) {
        if (sourceId !in ReviewProviders.ids) throw SourceException("不支持这个评价来源。")
    }
    private fun requireSubjectId(value: String): Int = value.takeIf { it.matches(Regex("[1-9][0-9]{0,9}")) }
        ?.toIntOrNull()?.takeIf { it > 0 } ?: throw SourceException("作品条目标识无效，请重新选择。")
    private fun associationKey(manga: Manga, sourceId: String) = "$sourceId:${manga.id}"
    private fun savedId(manga: Manga, sourceId: String) = associations.getString(associationKey(manga, sourceId), "").orEmpty()

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
            .filter(String::isNotBlank).distinct()
    }

    private fun rememberNames(manga: Manga, key: String, epoch: Long, names: List<String>) = synchronized(reviewRepositoryStateLock) {
        if (epoch != cacheGeneration || key != identityKey(manga)) return@synchronized
        confirmedNames[key] = names
        while (confirmedNames.size > 96) confirmedNames.remove(confirmedNames.keys.first())
    }

    private fun avatar(value: JSONObject?, domains: Set<String>): String = sequenceOf("medium", "large", "small")
        .mapNotNull { size -> value?.text(size)?.toHttpUrlOrNull() }
        .firstOrNull { url -> url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            domains.any { url.host == it || url.host.endsWith(".$it") } }?.toString().orEmpty()

    private fun plainText(raw: String, markdown: Boolean = false): String {
        var text = raw.replace(Regex("(?is)\\[img(?:=[^]]*)?].*?\\[/img]"), "")
            .replace(Regex("(?i)\\[/?(?:b|i|u|s|quote|spoiler|url|size|color|align)(?:=[^]]*)?]"), "")
        if (markdown) {
            text = text.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("(?m)^#{1,6}\\s+"), "")
                .replace("~!", "").replace("!~", "").replace("**", "").replace("__", "")
        }
        val document = Jsoup.parse(text)
        document.select("script, style").remove()
        document.select("br").forEach { it.appendText("\n") }
        document.select("p, div, li, blockquote, h1, h2, h3").forEach { it.appendText("\n") }
        return document.wholeText().replace("\r\n", "\n").replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n[\\t ]+"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun hasSpoilerMarkup(text: String): Boolean = Regex("(?i)\\[spoiler(?:=[^]]*)?]|~!|剧透|劇透|spoiler").containsMatchIn(text)
    private fun profileUrl(origin: String, name: String): String = origin.toHttpUrl().newBuilder().addPathSegment("user")
        .addPathSegment(name).build().toString()
    private fun date(seconds: Long): String = if (seconds > 0) try {
        formatInstantMillis(seconds * 1000, "yyyy-MM-dd HH:mm:ss")
    } catch (_: Exception) { "" } else ""

    private fun normalize(text: String): String {
        var normalized = normalizeNfkc(text).lowercase()
        normalized = transliterate(normalized, "Traditional-Simplified")
        return normalized.filter(Char::isLetterOrDigit)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun JSONObject.text(key: String) = (opt(key) as? String).orEmpty().trim()
    private fun JSONObject.nonNegativeInt(key: String): Int? = (opt(key) as? Number)?.toLong()
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    private fun JSONObject.nullableScore(key: String, scale: Int, allowZero: Boolean = false): Double? =
        (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && (it > 0 || (allowZero && it == 0.0)) && it <= scale }
    private fun Exception.userMessage(fallback: String) = if (this is SourceException) message.orEmpty().ifBlank { fallback } else fallback
    private data class BangumiMatch(val source: ReviewSource, val names: List<String> = emptyList())
    private data class MetricLookupIdentity(val aliases: List<String>, val externalIds: Map<String, String>)
    private data class MetricLookupAttempt(val source: ReviewSource, val identity: MetricLookupIdentity)
    private data class CachedSource(val time: Long, val source: ReviewSource, val names: List<String> = emptyList())
    private data class ChapterIdentitySnapshot(val time: Long, val ids: Map<String, String>)

    private companion object { const val PAGE_SIZE = 20 }
}

private val reviewRepositoryStateLock = SynchronizedObject()
