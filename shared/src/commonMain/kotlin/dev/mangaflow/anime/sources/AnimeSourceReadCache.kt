package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reuse raw source evidence only. Media resolution and parser authorization remain provider-owned. */
internal class AnimeSourceReadCache(
    private val context: PlatformContext,
    private val currentRevision: (String) -> String?,
) {
    private val metadata = AnimeMetadataCache.get(context)
    private val searches = Reads<List<AnimeSourceTitle>>(context, ContentCacheKind.SEARCH)
    private val catalogues = Reads<AnimeSourceCatalog>(context, ContentCacheKind.DETAILS)

    private data class Owner(val sourceId: String, val revision: String,
        val route: DownloadRouteSnapshot, val generation: Long)
    private data class Key(val owner: Owner, val query: String? = null, val page: Int? = null,
        val title: AnimeSourceTitle? = null)
    private data class GateKey(val owner: Owner, val query: String?, val page: Int?,
        val catalogId: String?, val catalogUrl: String?)

    private fun owner(provider: AnimeProvider) = Owner(provider.id, provider.revision,
        DownloadRoutingStore.activeSourceSnapshot(provider.id, DownloadCategory.ANIME), metadata.generation)

    private fun checkCurrent(owner: Owner) {
        check(currentRevision(owner.sourceId) == owner.revision) { "来源已更新或停用，请重新读取。" }
        if (DownloadRoutingStore.activeSourceSnapshot(owner.sourceId, DownloadCategory.ANIME) != owner.route)
            throw DownloadRouteException("来源网络线路已变化，请重新读取。")
        check(metadata.generation == owner.generation) { "番剧资料缓存已更新，请重新读取。" }
    }

    suspend fun search(provider: AnimeProvider, query: String, page: Int,
        forceRefresh: Boolean): List<AnimeSourceTitle> = withContext(Dispatchers.Default) {
        val key = Key(owner(provider), query = query, page = page)
        searches.read(key, forceRefresh, { checkCurrent(key.owner) }, { rows -> rows.sumOf(::titleBytes) }) {
            provider.search(query, page).also { rows ->
                check(rows.all { it.sourceId == provider.id &&
                    (it.sourceRevision == null || it.sourceRevision == key.owner.revision) }) {
                    "来源搜索身份已变化，请重新读取。"
                }
            }
        }
    }

    suspend fun catalog(provider: AnimeProvider, title: AnimeSourceTitle,
        forceRefresh: Boolean): AnimeSourceCatalog = withContext(Dispatchers.Default) {
        val owner = owner(provider)
        // Refresh a saved native identity with this provider revision, without changing its id or URL.
        val requested = title.copy(sourceRevision = owner.revision)
        val key = Key(owner, title = requested)
        catalogues.read(key, forceRefresh, { checkCurrent(key.owner) }, { catalog ->
            if (catalog.notice != null || catalog.episodes.isEmpty()) null else
                titleBytes(catalog.title) + catalog.description.length.toLong() * 2 + catalog.episodes.sumOf { episode ->
                    128L + 2L * (episode.id.length.toLong() + episode.title.length + episode.url.length +
                        episode.group.length + episode.edition.length + (episode.playbackData?.length ?: 0))
                }
        }, additionalKey = { catalog -> key.copy(title = catalog.title.copy(sourceRevision = key.owner.revision)) }) {
            provider.catalog(requested).also { catalog ->
                check(catalog.title.sourceId == provider.id && catalog.title.id == title.id && catalog.title.url == title.url &&
                    (catalog.title.sourceRevision == null || catalog.title.sourceRevision == key.owner.revision)) {
                    "来源目录身份已变化，请重新读取。"
                }
            }
        }
    }

    private fun titleBytes(title: AnimeSourceTitle): Long = 128L + 2L * (
        title.sourceId.length.toLong() + title.id.length + title.title.length + title.url.length +
            title.coverUrl.length + title.updateText.length + title.ratingText.length +
            (title.sourceRevision?.length ?: 0) + title.externalIds.entries.sumOf { it.key.length.toLong() + it.value.length })

    /** Like manga's source search cache, concurrent readers share a completed read without detached jobs. */
    private class Reads<T>(private val context: PlatformContext, private val kind: ContentCacheKind) {
        private data class Entry<T>(val value: T, val savedAt: Long, val version: Long, val bytes: Long)
        private class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
        private val lock = SynchronizedObject()
        private val entries = LinkedHashMap<Key, Entry<T>>()
        private val gates = mutableMapOf<GateKey, Gate>()
        private var generation = -1L
        private var version = 0L

        suspend fun read(key: Key, forceRefresh: Boolean, checkCurrent: () -> Unit,
            cacheBytes: (T) -> Long?, additionalKey: (T) -> Key? = { null }, fetch: suspend () -> T): T {
            // Display/enriched title variants can write the same catalogue alias, so they
            // share admission by native identity while retaining their distinct cache keys.
            val gateKey = GateKey(key.owner, key.query, key.page, key.title?.id, key.title?.url)
            val (gate, requestedVersion) = synchronized(lock) {
                val policy = contentCachePreferences(context)
                if (!policy.enabled || generation < key.owner.generation) entries.clear()
                generation = maxOf(generation, key.owner.generation)
                gates.getOrPut(gateKey) { Gate() }.also { it.users++ } to version
            }
            try {
                return gate.mutex.withLock {
                    checkCurrent()
                    val cached = synchronized(lock) {
                        val policy = contentCachePreferences(context)
                        if (!policy.enabled) entries.clear()
                        entries.remove(key)?.takeIf { value ->
                            policy.enabled && (value.version > requestedVersion || !forceRefresh &&
                                wallTimeMillis() - value.savedAt in 0L until policy.freshMillis(kind))
                        }?.also { entries[key] = it }?.value
                    }
                    if (cached != null) {
                        checkCurrent()
                        return@withLock cached
                    }
                    if (forceRefresh) synchronized(lock) {
                        // Evict after admission: an older in-flight read may have restored
                        // aliases while this explicit refresh was waiting for the same gate.
                        val title = key.title
                        if (title == null) entries.remove(key) else {
                            entries.keys.filter { cached -> cached.owner == key.owner &&
                                cached.title?.let { it.id == title.id && it.url == title.url } == true
                            }.forEach { entries.remove(it) }
                        }
                    }
                    val result = fetch()
                    checkCurrent()
                    val bytes = cacheBytes(result)
                    synchronized(lock) {
                        val policy = contentCachePreferences(context)
                        // Search and catalogue each own half of the configured metadata memory allowance.
                        val maxBytes = ExperiencePolicyStore.get(context).state.value.runtime.catalogMemoryMiB * 512L * 1024L
                        if (bytes != null && bytes <= maxBytes && policy.enabled && generation == key.owner.generation) {
                            val entry = Entry(result, wallTimeMillis(), ++version, bytes)
                            entries.remove(key)
                            entries[key] = entry
                            // Automatic bindings keep the provider's enriched title and current revision.
                            // Retain that exact request identity too, so the first revisit can reuse it.
                            additionalKey(result)?.takeIf { it != key }?.let { canonical ->
                                entries.remove(canonical)
                                entries[canonical] = entry
                            }
                            while (entries.size > policy.snapshotMemoryEntries || entries.values.sumOf { it.bytes } > maxBytes) {
                                entries.remove(entries.keys.first())
                            }
                        }
                    }
                    result
                }
            } finally {
                synchronized(lock) {
                    gate.users--
                    if (gate.users == 0 && gates[gateKey] === gate) gates.remove(gateKey)
                }
            }
        }
    }
}
