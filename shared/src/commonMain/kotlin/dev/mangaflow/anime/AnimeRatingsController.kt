package dev.mangaflow.anime

import dev.mangaflow.anime.sources.ANILIST_PROVIDER
import dev.mangaflow.anime.sources.AnimeDiscoveryRepository
import dev.mangaflow.anime.sources.AnimeMetadataCache
import dev.mangaflow.anime.sources.AnimeMetadataIdentityException
import dev.mangaflow.anime.sources.AnimeMetadataRepository
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.anime.sources.DOUBAN_PROVIDER
import dev.mangaflow.anime.sources.DoubanAnimeRepository
import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AnimeRatingSnapshot(
    val scores: List<AnimeSourceScore> = emptyList(),
    val checks: Map<AnimeDiscoveryProvider, AnimeScoreCheck> = emptyMap(),
)

/** Visible cards share one metadata request per work; no episodes or comments are fetched here. */
internal class AnimeRatingsController(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val metadata: AnimeMetadataRepository,
    private val discovery: AnimeDiscoveryRepository,
    private val douban: DoubanAnimeRepository,
    private val library: AnimeLibraryStore,
) {
    private data class Selection(
        val key: String,
        val diskKey: String,
        val reference: AnimeSubject?,
        val manual: Boolean,
        val disabled: Boolean,
        val restoreEpoch: Long,
        val cacheEpoch: Long,
        val routes: Map<AnimeDiscoveryProvider, DownloadRouteSnapshot?>,
    )

    private class Entry(var subject: AnimeSubject, var selection: Selection) {
        var references = 0
        var primary: AnimeSubject? = null
        var metadata: AnimeSubject? = selection.reference
        var primaryRejected = false
        var metadataRejected = false
        var snapshot = AnimeRatingSnapshot()
        val checkedAt = mutableMapOf<AnimeDiscoveryProvider, Long>()
        val providerVersions = mutableMapOf<AnimeDiscoveryProvider, Long>()
        var restored = false
        var job: Job? = null
        var release: Job? = null
        var requestVersion = 0L
        var pendingWrite: Selection? = null
        var writer: Job? = null
    }

    private val cache = AnimeMetadataCache.get(context)
    private val routing = DownloadRoutingStore.get(context)
    private val slots = Semaphore(3)
    private val entries = LinkedHashMap<String, Entry>()
    private val mutableValues = MutableStateFlow<Map<String, AnimeRatingSnapshot>>(emptyMap())
    val values: StateFlow<Map<String, AnimeRatingSnapshot>> = mutableValues.asStateFlow()
    private var maintenance: Job? = null

    /** Pure lookup for composition/open-detail; event collectors may not have run yet. */
    fun currentSnapshot(subject: AnimeSubject): AnimeRatingSnapshot? {
        val entry = entries[subject.id] ?: return null
        if (selection(fuller(entry.subject, subject)).key != entry.selection.key) return null
        val now = wallTimeMillis()
        val retention = contentCachePreferences(context).retentionMillis
        if (entry.checkedAt.values.all { now - it in 0L..retention }) return entry.snapshot
        val expired = entry.checkedAt.filterValues { now - it !in 0L..retention }.keys
        return entry.snapshot.copy(scores = entry.snapshot.scores.filterNot { it.provider in expired },
            checks = entry.snapshot.checks.filterKeys { it !in expired })
    }

    init {
        scope.launch { library.awaitLoaded(); library.state.collect { reconcile() } }
        scope.launch { routing.state.collect { reconcile() } }
    }

    fun retain(subject: AnimeSubject, seeds: List<AnimeSourceScore> = emptyList()) {
        val entry = entry(subject)
        entry.release?.cancel()
        entry.release = null
        entry.references++
        seed(entry, seeds)
        start(entry)
        if (maintenance?.isActive != true) maintenance = scope.launch {
            // Cache eviction has no UI event. Only retained cards observe its generation;
            // this does not schedule background refreshes for off-screen works.
            while (entries.values.any { it.references > 0 }) {
                delay(30_000L)
                reconcile()
            }
        }
    }

    fun release(subjectId: String) {
        val entry = entries[subjectId] ?: return
        if (entry.references <= 0) return
        entry.references--
        if (entry.references > 0) return
        entry.release?.cancel()
        entry.release = scope.launch {
            delay(600L)
            if (entry.references == 0 && entries[subjectId] === entry) {
                entry.job?.cancel()
                entry.job = null
                entry.snapshot = entry.snapshot.copy(checks = entry.snapshot.checks.mapValues { (_, check) ->
                    check.copy(loading = false)
                })
                if (!contentCachePreferences(context).enabled) {
                    entries.remove(subjectId)
                    mutableValues.value = mutableValues.value - subjectId
                } else publish(entry)
                trim()
            }
            entry.release = null
        }
    }

    fun refresh(subject: AnimeSubject, provider: AnimeDiscoveryProvider? = null) {
        val entry = entry(subject)
        start(entry, provider?.let { setOf(it) } ?: providers(subject).toSet())
    }

    /** A detail retry after identity rejection must use the full, retained matching path. */
    suspend fun recheck(subject: AnimeSubject, provider: AnimeDiscoveryProvider): AnimeRatingSnapshot? {
        retain(subject)
        try {
            val entry = entry(subject)
            start(entry, setOf(provider))
            val ticket = entry.requestVersion
            entry.job?.join()
            return currentSnapshot(subject)?.takeIf { ticket == entry.requestVersion }
        } finally { release(subject.id) }
    }

    /** The caller has already fenced these completed detail observations by owner and route. */
    fun accept(subject: AnimeSubject, scores: List<AnimeSourceScore>,
        checks: Map<AnimeDiscoveryProvider, AnimeScoreCheck>, confirmedMetadata: AnimeSubject? = null): Boolean {
        val completed = checks.filterValues { !it.loading }
        if (completed.isEmpty()) return false
        val entry = entry(subject)
        val chosen = entry.selection
        val accepted = completed.filterKeys { provider -> provider in providers(subject) &&
            (!entry.primaryRejected || provider == AnimeDiscoveryProvider.DOUBAN) &&
            (!entry.metadataRejected || provider == AnimeDiscoveryProvider.DOUBAN) }
        if (accepted.isEmpty()) return false
        confirmedMetadata?.takeIf { it.canonicalBangumiId() != null && !chosen.disabled && !entry.metadataRejected &&
            (chosen.reference?.let { known -> known.id == it.id } ?: true) }?.let { entry.metadata = it }
        if (subject.canonicalDoubanId() != null || subject.canonicalBangumiId() != null)
            entry.primary = fuller(entry.primary ?: subject, subject)
        accepted.forEach { (provider, check) ->
            if (provider == AnimeDiscoveryProvider.DOUBAN && check.error == null) entry.primaryRejected = false
            entry.providerVersions[provider] = (entry.providerVersions[provider] ?: 0L) + 1L
            record(entry, provider, scores.firstOrNull { it.provider == provider && valid(it) }, check)
        }
        publish(entry)
        queueWrite(entry, chosen)
        return true
    }

    private fun entry(subject: AnimeSubject): Entry {
        val existing = entries[subject.id]
        if (existing != null) {
            existing.subject = fuller(existing.subject, subject)
            updateSelection(existing)
            return existing
        }
        val created = Entry(subject, selection(subject))
        entries[subject.id] = created
        seed(created, emptyList())
        trim()
        return created
    }

    private fun selection(subject: AnimeSubject): Selection {
        val saved = library.state.value
        val stored = saved.entries[subject.id]
        val manual = subject.canonicalBangumiId() != null || stored?.nativeMetadata != null
        val disabled = stored?.metadataAutoMatchDisabled == true && stored.nativeMetadata == null
        val reference = if (subject.canonicalBangumiId() != null) subject else
            stored?.nativeMetadata ?: stored?.catalogDetail?.metadataSubject.takeUnless { disabled }
        val confirmed = reference?.takeIf { it.canonicalBangumiId() != null }
        val routes = providers(subject).associateWith { provider ->
            runCatching { route(provider) }.getOrNull()
        }
        val epoch = cache.generation
        val previous = entries[subject.id]?.selection
        if (previous != null && previous.restoreEpoch == saved.restoreEpoch && previous.cacheEpoch == epoch &&
            previous.manual == manual && previous.disabled == disabled && previous.routes == routes &&
            previous.reference?.id == confirmed?.id && previous.reference?.externalIds == confirmed?.externalIds) return previous
        // A sparse list row and a rich detail row are the same catalogue identity. Their
        // aliases/rating fields must not repeatedly cancel each other's visible request.
        val referenceKey = confirmed?.let { value -> value.id + ":" +
            value.externalIds.toList().sortedBy { it.first }.joinToString(";") { "${it.first}=${it.second}" } }.orEmpty()
        val identity = JSONObject().put("owner", subject.id).put("reference", referenceKey)
            .put("manual", manual).put("disabled", disabled).put("routes", JSONObject(routes.mapKeys { it.key.name }
                .mapValues { it.value?.identity()?.let(::animeHash) ?: "unavailable" })).toString()
        return Selection("$identity:${saved.restoreEpoch}:$epoch", "anime-ratings:v1:${animeHash(identity)}",
            confirmed, manual, disabled, saved.restoreEpoch, epoch, routes)
    }

    private fun updateSelection(entry: Entry): Boolean {
        val next = selection(entry.subject)
        if (next.key == entry.selection.key) return false
        val before = entry.selection
        entry.job?.cancel()
        entry.requestVersion++
        entry.job = null
        val keepPrimary = entry.subject.canonicalDoubanId() != null && before.cacheEpoch == next.cacheEpoch &&
            before.restoreEpoch == next.restoreEpoch && before.routes[AnimeDiscoveryProvider.DOUBAN] == next.routes[AnimeDiscoveryProvider.DOUBAN]
        entry.snapshot = if (keepPrimary) AnimeRatingSnapshot(
            entry.snapshot.scores.filter { it.provider == AnimeDiscoveryProvider.DOUBAN },
            entry.snapshot.checks.filterKeys { it == AnimeDiscoveryProvider.DOUBAN }.mapValues { it.value.copy(loading = false) })
            else AnimeRatingSnapshot()
        entry.checkedAt.keys.toList().filterNot { keepPrimary && it == AnimeDiscoveryProvider.DOUBAN }.forEach(entry.checkedAt::remove)
        entry.providerVersions.clear()
        if (!keepPrimary) entry.primary = null
        if (!keepPrimary) entry.primaryRejected = false
        entry.metadataRejected = false
        entry.metadata = next.reference
        entry.selection = next
        entry.restored = false
        seed(entry, emptyList())
        publish(entry)
        return true
    }

    private fun reconcile() {
        entries.values.toList().forEach { entry ->
            if (updateSelection(entry) && entry.references > 0) start(entry)
        }
    }

    private fun seed(entry: Entry, values: List<AnimeSourceScore>) {
        val own = when {
            entry.subject.canonicalDoubanId() != null -> AnimeDiscoveryProvider.DOUBAN
            entry.subject.canonicalBangumiId() != null -> AnimeDiscoveryProvider.BANGUMI
            else -> null
        }
        val initial = values + listOfNotNull(own?.let { provider -> entry.subject.rating?.let {
            AnimeSourceScore(provider, it, 10, entry.subject.ratingCount)
        } })
        val accepted = initial.filter { valid(it) && (!entry.selection.disabled || it.provider == AnimeDiscoveryProvider.DOUBAN) &&
            it.provider !in entry.snapshot.checks &&
            entry.snapshot.scores.none { old -> old.provider == it.provider } }
        if (accepted.isNotEmpty()) {
            entry.snapshot = entry.snapshot.copy(scores = (entry.snapshot.scores + accepted).distinctBy { it.provider })
            publish(entry)
        }
    }

    private fun start(entry: Entry, forced: Set<AnimeDiscoveryProvider> = emptySet()) {
        if (entry.job?.isActive == true && forced.isEmpty()) return
        entry.job?.cancel()
        val chosen = entry.selection
        val ticket = ++entry.requestVersion
        val request = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = coroutineContext[Job]
            try {
                library.awaitLoaded()
                if (!current(entry, chosen)) {
                    if (updateSelection(entry) && entry.references > 0) start(entry, forced)
                    return@launch
                }
                try { restore(entry, chosen) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { entry.restored = true }
                if (!current(entry, chosen) || ticket != entry.requestVersion) return@launch
                val now = wallTimeMillis()
                val due = providers(entry.subject).filter { provider ->
                    val checked = entry.checkedAt[provider]
                    val state = entry.snapshot.checks[provider]
                    val ttl = contentCachePreferences(context).freshMillis(ContentCacheKind.METRICS,
                        state?.error != null || state?.message != null)
                    provider in forced || checked == null || now - checked !in 0L until ttl
                }.toMutableSet()
                if (entry.metadataRejected && AnimeDiscoveryProvider.ANILIST in due)
                    due += AnimeDiscoveryProvider.BANGUMI
                if (entry.primaryRejected && due.any { it != AnimeDiscoveryProvider.DOUBAN })
                    due += AnimeDiscoveryProvider.DOUBAN
                if (due.isEmpty()) return@launch
                val versions = due.associateWith { entry.providerVersions[it] ?: 0L }
                entry.snapshot = entry.snapshot.copy(checks = entry.snapshot.checks + due.associateWith {
                    (entry.snapshot.checks[it] ?: AnimeScoreCheck()).copy(loading = true)
                })
                publish(entry)
                slots.withPermit {
                    if (current(entry, chosen) && ticket == entry.requestVersion)
                        load(entry, chosen, due, forced, versions, ticket)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (current(entry, chosen) && ticket == entry.requestVersion) {
                    entry.snapshot.checks.filterValues { it.loading }.keys.forEach { provider ->
                        record(entry, provider, null, AnimeScoreCheck(error = failure.message ?: "评分资料未能读取，请重试。"))
                    }
                    publish(entry)
                }
            }
            finally {
                if (entry.job === ownJob) {
                    entry.job = null
                    if (current(entry, chosen)) {
                        entry.snapshot = entry.snapshot.copy(checks = entry.snapshot.checks.mapValues { it.value.copy(loading = false) })
                        publish(entry)
                    }
                }
            }
        }
        entry.job = request
        request.start()
    }

    private suspend fun load(entry: Entry, chosen: Selection, due: Set<AnimeDiscoveryProvider>,
        forced: Set<AnimeDiscoveryProvider>, versions: Map<AnimeDiscoveryProvider, Long>, ticket: Long) {
        fun active() = current(entry, chosen) && ticket == entry.requestVersion
        fun owns(provider: AnimeDiscoveryProvider) = active() &&
            entry.providerVersions[provider].orZero() == versions[provider].orZero()
        fun result(provider: AnimeDiscoveryProvider, score: AnimeSourceScore?, check: AnimeScoreCheck) {
            if (!owns(provider)) return
            record(entry, provider, score, check)
            publish(entry)
            queueWrite(entry, chosen)
        }
        fun failed(provider: AnimeDiscoveryProvider, failure: Exception) {
            if (failure is CancellationException) throw failure
            result(provider, null, AnimeScoreCheck(error = failure.message ?: "评分读取失败，请重试。"))
        }
        var primary = entry.primary ?: entry.subject
        if (primary.canonicalDoubanId() != null && (AnimeDiscoveryProvider.DOUBAN in due || entry.primary == null)) {
            try {
                requireRoute(chosen, AnimeDiscoveryProvider.DOUBAN)
                primary = withTimeoutOrNull(12_000L) { douban.detail(primary, AnimeDiscoveryProvider.DOUBAN in forced) }
                    ?: error("等待读取豆瓣评分超时。")
                if (!active()) return
                if (owns(AnimeDiscoveryProvider.DOUBAN)) {
                    entry.primary = primary
                    entry.primaryRejected = false
                }
                else primary = entry.primary ?: entry.subject
                if (AnimeDiscoveryProvider.DOUBAN in due) result(AnimeDiscoveryProvider.DOUBAN,
                    score(primary, AnimeDiscoveryProvider.DOUBAN), noScore(primary, "豆瓣"))
            } catch (failure: Exception) {
                if (failure is AnimeMetadataIdentityException && owns(AnimeDiscoveryProvider.DOUBAN)) {
                    entry.primary = null
                    entry.metadata = null
                    entry.primaryRejected = true
                    entry.metadataRejected = true
                    providers(entry.subject).forEach { provider ->
                        record(entry, provider, null, AnimeScoreCheck(message = failure.message))
                    }
                    publish(entry)
                    queueWrite(entry, chosen)
                    return
                }
                if (AnimeDiscoveryProvider.DOUBAN in due) failed(AnimeDiscoveryProvider.DOUBAN, failure)
                else if (failure is CancellationException) throw failure
            }
        }
        if (!active()) return
        val supplementary = due - AnimeDiscoveryProvider.DOUBAN
        if (supplementary.isEmpty()) return
        if (entry.primaryRejected) {
            supplementary.forEach { provider -> result(provider, null,
                AnimeScoreCheck(error = "豆瓣作品身份尚未重新确认，请先刷新豆瓣资料。")) }
            return
        }
        var reference = chosen.reference ?: entry.metadata
        var resolvedNow = false
        var matchingFailure: Exception? = null
        if (reference == null && primary.canonicalDoubanId() != null && !chosen.disabled) {
            try {
                val route = requireRoute(chosen, AnimeDiscoveryProvider.BANGUMI)
                val match = withTimeoutOrNull(15_000L) {
                    Result.success(metadata.resolveBangumiReference(primary, route,
                        forceRefresh = AnimeDiscoveryProvider.BANGUMI in forced))
                } ?: error("等待读取 Bangumi 作品资料超时。")
                reference = if (owns(AnimeDiscoveryProvider.BANGUMI)) match.getOrThrow() else entry.metadata
                resolvedNow = reference != null
                if (owns(AnimeDiscoveryProvider.BANGUMI)) entry.metadata = reference
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                matchingFailure = failure
            }
        }
        if (!active()) return
        val confirmed = reference?.takeIf { it.canonicalBangumiId() != null }
        if (confirmed == null) {
            supplementary.forEach { provider ->
                val problem = matchingFailure
                if (problem != null) failed(provider, problem)
                else result(provider, null, AnimeScoreCheck(message = if (chosen.disabled)
                    "已关闭自动资料关联" else "尚未匹配到同一部番剧"))
            }
            return
        }
        if (AnimeDiscoveryProvider.BANGUMI in due) {
            try {
                val route = requireRoute(chosen, AnimeDiscoveryProvider.BANGUMI)
                val fresh = if (resolvedNow) confirmed else withTimeoutOrNull(8_000L) {
                    metadata.subjectByBangumiId(requireNotNull(confirmed.canonicalBangumiId()), route)
                        ?: throw AnimeMetadataIdentityException("Bangumi 返回的作品身份与当前关联不一致。")
                } ?: error("等待读取 Bangumi 评分超时。")
                if (!chosen.manual && primary.canonicalDoubanId() != null && !animeMetadataSubjectsMatch(primary, fresh))
                    throw AnimeMetadataIdentityException("Bangumi 资料与当前作品的季度或形式不一致。")
                if (!active()) return
                if (owns(AnimeDiscoveryProvider.BANGUMI)) {
                    entry.metadata = fuller(confirmed, fresh)
                    entry.metadataRejected = false
                }
                result(AnimeDiscoveryProvider.BANGUMI, score(fresh, AnimeDiscoveryProvider.BANGUMI), noScore(fresh, "Bangumi"))
            } catch (failure: Exception) {
                if (failure is AnimeMetadataIdentityException && owns(AnimeDiscoveryProvider.BANGUMI)) {
                    entry.metadata = null
                    entry.metadataRejected = true
                    listOf(AnimeDiscoveryProvider.BANGUMI, AnimeDiscoveryProvider.ANILIST).forEach { provider ->
                        record(entry, provider, null, AnimeScoreCheck(message = failure.message))
                    }
                    publish(entry)
                    queueWrite(entry, chosen)
                    return
                }
                failed(AnimeDiscoveryProvider.BANGUMI, failure)
            }
        }
        if (entry.metadataRejected && AnimeDiscoveryProvider.ANILIST in due) {
            result(AnimeDiscoveryProvider.ANILIST, null,
                AnimeScoreCheck(error = "Bangumi 作品身份尚未重新确认，请先刷新 Bangumi 资料。"))
            return
        }
        if (AnimeDiscoveryProvider.ANILIST in due && active()) {
            try {
                requireRoute(chosen, AnimeDiscoveryProvider.ANILIST)
                val match = discovery.lookupAniListMatch(entry.metadata ?: confirmed,
                    forceRefresh = AnimeDiscoveryProvider.ANILIST in forced, reportFailure = true)
                val value = match?.rating?.let { AnimeSourceScore(AnimeDiscoveryProvider.ANILIST, it, 100, match.ratingCount) }
                result(AnimeDiscoveryProvider.ANILIST, value, AnimeScoreCheck(message = if (value != null) null else
                    if (match == null) "AniList 未匹配到同一部番剧" else "AniList 暂无评分"))
            } catch (failure: Exception) { failed(AnimeDiscoveryProvider.ANILIST, failure) }
        }
    }

    private fun record(entry: Entry, provider: AnimeDiscoveryProvider, score: AnimeSourceScore?, check: AnimeScoreCheck) {
        val retained = if (check.error != null) entry.snapshot.scores.firstOrNull { it.provider == provider } else null
        entry.snapshot = AnimeRatingSnapshot(entry.snapshot.scores.filterNot { it.provider == provider } +
            listOfNotNull(score?.takeIf(::valid) ?: retained), entry.snapshot.checks + (provider to check.copy(loading = false)))
        entry.checkedAt[provider] = wallTimeMillis()
    }

    private suspend fun restore(entry: Entry, chosen: Selection) {
        if (entry.restored) return
        val saved = cache.read(chosen.diskKey)
        if (!current(entry, chosen)) return
        entry.restored = true
        if (saved == null) return
        val json = runCatching { JSONObject(saved.text) }.getOrNull() ?: return
        if (AnimeDiscoveryProvider.DOUBAN !in entry.checkedAt)
            entry.primaryRejected = json.optBoolean("primaryRejected")
        if (AnimeDiscoveryProvider.BANGUMI !in entry.checkedAt)
            entry.metadataRejected = json.optBoolean("metadataRejected")
        val primary = entry.primary ?: runCatching { AnimeDocumentCodec.subject(json.getJSONObject("primary")) }.getOrNull()
            ?.takeIf { it.id == entry.subject.id }
        val storedReference = runCatching { AnimeDocumentCodec.subject(json.getJSONObject("reference")) }.getOrNull()
            ?.takeIf { it.canonicalBangumiId() != null && !chosen.disabled }
        val reference = chosen.reference ?: entry.metadata ?: storedReference?.takeIf { candidate -> primary != null &&
            (primary.canonicalBangumiId() == candidate.canonicalBangumiId() || animeMetadataSubjectsMatch(primary, candidate)) }
        if (primary != null) entry.primary = primary
        entry.metadata = reference
        val rows = json.optJSONArray("sources") ?: return
        val now = wallTimeMillis()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val provider = runCatching { AnimeDiscoveryProvider.valueOf(row.getString("provider")) }.getOrNull() ?: continue
            if (provider !in providers(entry.subject) || provider in entry.checkedAt) continue
            val time = row.optLong("checked", -1L)
            if (now - time !in 0L..contentCachePreferences(context).retentionMillis) continue
            val value = if (row.has("score") && !row.isNull("score")) runCatching {
                AnimeSourceScore(provider, row.getDouble("score"), row.getInt("scale"),
                    row.optInt("count", -1).takeIf { it >= 0 })
            }.getOrNull()?.takeIf(::valid) else null
            if (value != null && (entry.primaryRejected || entry.metadataRejected && provider != AnimeDiscoveryProvider.DOUBAN)) continue
            if (value != null && provider != AnimeDiscoveryProvider.DOUBAN && reference == null) continue
            val check = AnimeScoreCheck(error = row.optString("error", null), message = row.optString("message", null))
            entry.snapshot = AnimeRatingSnapshot(entry.snapshot.scores.filterNot { it.provider == provider } + listOfNotNull(value),
                entry.snapshot.checks + (provider to check))
            entry.checkedAt[provider] = time
        }
        publish(entry)
    }

    /** A completed score can persist after its card leaves. Disk work never holds a visible request slot. */
    private fun queueWrite(entry: Entry, chosen: Selection) {
        if (!contentCachePreferences(context).enabled) return
        entry.pendingWrite = chosen
        if (entry.writer != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val next = entry.pendingWrite ?: break
                    entry.pendingWrite = null
                    try { writeSnapshot(entry, next) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* The observed score remains usable. */ }
                }
            } finally {
                entry.writer = null
                trim()
            }
        }
        entry.writer = job
        if (!job.start()) entry.writer = null
    }

    private suspend fun writeSnapshot(entry: Entry, chosen: Selection) {
        if (!current(entry, chosen)) return
        val completed = entry.checkedAt.toMap()
        val snapshot = entry.snapshot
        val primary = entry.primary ?: entry.subject
        val primaryRejected = entry.primaryRejected
        val metadataRejected = entry.metadataRejected
        val reference = entry.metadata
        val text = withContext(Dispatchers.Default) {
            val rows = completed.mapNotNull { (provider, time) ->
                // A refresh keeps the last completed check/value until its own response arrives.
                val check = snapshot.checks[provider] ?: return@mapNotNull null
                val score = snapshot.scores.firstOrNull { it.provider == provider }
                JSONObject().put("provider", provider.name).put("checked", time).put("score", score?.rating)
                    .put("scale", score?.scale).put("count", score?.ratingCount).put("error", check.error).put("message", check.message)
            }
            JSONObject().put("primary", AnimeDocumentCodec.subject(primary))
                .put("primaryRejected", primaryRejected).put("metadataRejected", metadataRejected)
                .put("reference", reference?.let(AnimeDocumentCodec::subject)).put("sources", JSONArray(rows)).toString()
        }
        if (current(entry, chosen)) cache.write(chosen.diskKey, text, chosen.cacheEpoch)
    }

    private fun current(entry: Entry, chosen: Selection): Boolean = entries[entry.subject.id] === entry &&
        entry.selection.key == chosen.key && selection(entry.subject).key == chosen.key

    private fun publish(entry: Entry) {
        if (entries[entry.subject.id] === entry)
            mutableValues.value = mutableValues.value + (entry.subject.id to entry.snapshot)
    }

    private fun trim() {
        while (entries.size > 160) {
            val old = entries.entries.firstOrNull { it.value.references == 0 && it.value.job?.isActive != true &&
                it.value.writer?.isActive != true } ?: break
            old.value.release?.cancel()
            entries.remove(old.key)
            mutableValues.value = mutableValues.value - old.key
        }
    }

    private fun providers(subject: AnimeSubject) = if (subject.canonicalDoubanId() != null)
        listOf(AnimeDiscoveryProvider.DOUBAN, AnimeDiscoveryProvider.BANGUMI, AnimeDiscoveryProvider.ANILIST)
        else listOf(AnimeDiscoveryProvider.BANGUMI, AnimeDiscoveryProvider.ANILIST)

    private fun route(provider: AnimeDiscoveryProvider) = DownloadRoutingStore.activeSourceSnapshot(when (provider) {
        AnimeDiscoveryProvider.DOUBAN -> DOUBAN_PROVIDER
        AnimeDiscoveryProvider.BANGUMI -> BANGUMI_PROVIDER
        AnimeDiscoveryProvider.ANILIST -> ANILIST_PROVIDER
    }, DownloadCategory.ANIME)

    private fun requireRoute(chosen: Selection, provider: AnimeDiscoveryProvider): DownloadRouteSnapshot {
        val route = chosen.routes[provider] ?: error("评分网络线路暂不可用。")
        check(this.route(provider) == route) { "评分网络线路已变化，请重新读取。" }
        return route
    }

    private fun score(subject: AnimeSubject, provider: AnimeDiscoveryProvider) = subject.rating?.let {
        AnimeSourceScore(provider, it, 10, subject.ratingCount)
    }?.takeIf(::valid)

    private fun noScore(subject: AnimeSubject, name: String) = AnimeScoreCheck(message =
        if (subject.rating?.let { it.isFinite() && it > 0.0 && it <= 10.0 } == true) null else "$name 暂无评分")

    private fun valid(score: AnimeSourceScore) = score.scale > 0 && score.rating.isFinite() && score.rating > 0.0 &&
        score.rating <= score.scale.toDouble() && (score.ratingCount == null || score.ratingCount >= 0)

    private fun fuller(before: AnimeSubject, after: AnimeSubject): AnimeSubject = before.copy(
        originalTitle = after.originalTitle.ifBlank { before.originalTitle },
        description = after.description.ifBlank { before.description },
        coverUrl = after.coverUrl.ifBlank { before.coverUrl },
        year = after.year ?: before.year, format = after.format ?: before.format,
        episodeCount = after.episodeCount ?: before.episodeCount,
        aliases = (before.aliases + after.aliases).distinct().take(64),
        externalIds = before.externalIds + after.externalIds,
        rating = after.rating ?: before.rating, ratingCount = after.ratingCount ?: before.ratingCount,
    )

    private fun Long?.orZero(): Long = this ?: 0L
}
