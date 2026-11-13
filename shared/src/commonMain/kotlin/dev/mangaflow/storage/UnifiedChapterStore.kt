package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterSemantics
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.resolveChapter
import dev.mangaflow.data.sourceDisplayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.AtomicLong
import kotlin.concurrent.Volatile

data class LinkedMangaSource(
    val manga: Manga,
    val language: String = "简中",
    val edition: String = "连载版",
    val sameChapterSystem: Boolean = true,
    val confirmedAt: Long = wallTimeMillis(),
    val association: String = "manual",
    val matchEvidence: String = "",
)

data class UnifiedWorkSettings(
    val links: List<LinkedMangaSource> = emptyList(),
    val preferredSources: List<String> = emptyList(),
    /** Logical chapter ID -> exact variant storage ID. */
    val chapterOverrides: Map<String, String> = emptyMap(),
    /** Exact variant storage ID -> explicitly confirmed logical chapter ID. */
    val manualLinks: Map<String, String> = emptyMap(),
    val separated: Set<String> = emptySet(),
    /** Persisted anchors keep a missing chapter's logical ID when the primary source later adds it. */
    val logicalIds: Map<String, String> = emptyMap(),
    val excludedSources: Set<String> = emptySet(),
)

/**
 * The parent/child and priority-view mechanism adapts Komikku/TachiyomiSY's merged catalogue.
 * Source records remain independent; no migration copies page numbers or synthesizes history.
 * The shared library preference keeps associations in the existing JSON/WebDAV backup.
 */
class UnifiedChapterStore(context: Context) {
    private val disk = context.applicationContext.getSharedPreferences("mangaflow_library", PRIVATE_STORAGE)
    private val lock = StorageLock()
    private val anchorLock = StorageLock()
    private val anchorMemory = mutableMapOf<String, Map<String, String>>()
    private val pendingAnchors = mutableMapOf<String, Map<String, String>>()
    private val pendingAnchorEpochs = mutableMapOf<String, Long>()
    private val anchorSignal = Channel<Unit>(Channel.CONFLATED)
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ownAnchorWrite: String? = null
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    private val listener = PreferenceChangeListener { _, key ->
        if (key == KEY || key == null) _revision.value += 1L
        if (key == ANCHOR_KEY || key == null) {
            if (disk.getString(ANCHOR_KEY, "{}") != ownAnchorWrite) {
                synchronized(anchorLock) { anchorMemory.clear(); pendingAnchors.clear(); pendingAnchorEpochs.clear() }
                _revision.value += 1L
            }
        }
    }
    private val anchorWriter = writerScope.launch {
        for (ignored in anchorSignal) persistPendingAnchors()
    }

    init { disk.registerOnSharedPreferenceChangeListener(listener) }
    fun close() {
        disk.unregisterOnSharedPreferenceChangeListener(listener)
        // Drain the last queued identity snapshot without blocking ViewModel disposal on disk IO.
        anchorSignal.close()
        anchorWriter.invokeOnCompletion { writerScope.cancel() }
    }

    fun settings(workId: String): UnifiedWorkSettings = synchronized(lock) {
        val saved = readRoot().optJSONObject(workId)?.let(::readSettings) ?: UnifiedWorkSettings()
        val anchors = synchronized(anchorLock) {
            anchorMemory.getOrPut(workId) {
                val root = JSONObject(disk.getString(ANCHOR_KEY, "{}") ?: "{}")
                root.optJSONObject(workId)?.let { json ->
                    json.keys().asSequence().associateWith { json.getString(it) }
                }.orEmpty()
            }
        }
        saved.copy(logicalIds = saved.logicalIds + anchors)
    }

    fun linkedSources(workId: String): List<LinkedMangaSource> = settings(workId).links

    /** Invoke only after the user confirms this is the same work and the indicated edition. */
    fun attach(workId: String, manga: Manga, language: String = "简中", edition: String = "连载版",
        sameChapterSystem: Boolean = true) = mutate(workId) { old ->
        require(manga.id != workId) { "这个来源已经是作品的主来源" }
        val ref = SourceKeys.decodeManga(manga.id)
        require(ref.sourceId == manga.sourceId) { "来源身份不一致" }
        old.copy(links = old.links.filterNot { it.manga.id == manga.id } +
            LinkedMangaSource(manga, language.take(60), edition.take(60), sameChapterSystem),
            excludedSources = old.excludedSources - manga.sourceId)
    }

    /** Called on IO only after independent title, author and chapter-system verification. */
    fun attachAutomatically(workId: String, manga: Manga, language: String = "简中", edition: String = "连载版",
        sameChapterSystem: Boolean = true, evidence: String = ""): Boolean = synchronized(lock) {
        require(workId.isNotBlank() && manga.id != workId)
        val ref = SourceKeys.decodeManga(manga.id)
        require(ref.sourceId == manga.sourceId) { "来源身份不一致" }
        val root = readRoot()
        val old = root.optJSONObject(workId)?.let(::readSettings) ?: UnifiedWorkSettings()
        if (manga.sourceId in old.excludedSources || old.links.any { it.manga.sourceId == manga.sourceId }) return@synchronized false
        val next = old.copy(links = old.links + LinkedMangaSource(manga, language.take(60), edition.take(60),
            sameChapterSystem, association = "automatic", matchEvidence = evidence.take(300)))
        root.put(workId, writeSettings(next))
        check(disk.edit().putString(KEY, root.toString()).commit()) { "自动关联未能保存，请检查存储空间。" }
        true
    }

    /** Upgrade numbering evidence only while the exact confirmed association is still current. */
    fun confirmChapterSystem(workId: String, expected: LinkedMangaSource): Boolean = synchronized(lock) {
        require(workId.isNotBlank())
        val root = readRoot()
        val old = root.optJSONObject(workId)?.let(::readSettings) ?: return@synchronized false
        val current = old.links.firstOrNull { it.manga.id == expected.manga.id } ?: return@synchronized false
        if (current != expected || current.confirmedAt <= 0L || current.sameChapterSystem) return@synchronized false
        val next = old.copy(links = old.links.map {
            if (it.manga.id == current.manga.id) it.copy(sameChapterSystem = true) else it
        })
        root.put(workId, writeSettings(next))
        check(disk.edit().putString(KEY, root.toString()).commit()) { "章节编号确认未能保存，请重试。" }
        true
    }

    fun detach(workId: String, linkedMangaId: String) = mutate(workId) { old ->
        // Preferences may keep a dormant exact choice. Reattaching can restore it; history is untouched.
        val removed = old.links.firstOrNull { it.manga.id == linkedMangaId }
        old.copy(links = old.links.filterNot { it.manga.id == linkedMangaId },
            excludedSources = if (removed == null) old.excludedSources else old.excludedSources + removed.manga.sourceId)
    }

    fun excludeSource(workId: String, sourceId: String) = mutate(workId) { old ->
        old.copy(links = old.links.filterNot { it.manga.sourceId == sourceId }, excludedSources = old.excludedSources + sourceId)
    }

    fun preferSource(workId: String, sourceId: String) = mutate(workId) { old ->
        require(sourceId.isNotBlank())
        old.copy(preferredSources = listOf(sourceId) + old.preferredSources.filterNot { it == sourceId })
    }

    fun choose(workId: String, logicalId: String, variantStorageId: String) = mutate(workId) { old ->
        require(logicalId.isNotBlank() && variantStorageId.isNotBlank())
        old.copy(chapterOverrides = old.chapterOverrides + (logicalId to variantStorageId))
    }

    fun clearChoice(workId: String, logicalId: String) = mutate(workId) { old ->
        old.copy(chapterOverrides = old.chapterOverrides - logicalId)
    }

    fun associate(workId: String, variantStorageId: String, targetLogicalId: String) = mutate(workId) { old ->
        require(variantStorageId.isNotBlank() && targetLogicalId.isNotBlank())
        old.copy(manualLinks = old.manualLinks + (variantStorageId to targetLogicalId), separated = old.separated - variantStorageId)
    }

    fun split(workId: String, variantStorageId: String) = mutate(workId) { old ->
        require(variantStorageId.isNotBlank())
        old.copy(manualLinks = old.manualLinks - variantStorageId, separated = old.separated + variantStorageId)
    }

    fun build(workManga: Manga, baseChapters: List<Chapter>, linkedCatalogs: List<MangaDetail> = emptyList()): List<Chapter> {
        if (workManga.sourceUrl == DownloadStore.LOCAL_SOURCE_URL) return baseChapters
        val state = settings(workManga.id)
        data class Bucket(val chapter: Chapter, val variants: MutableList<ChapterVariant>, val order: Int)
        val buckets = linkedMapOf<String, Bucket>()
        val meanings = mutableMapOf<String, String>()
        val anchors = state.logicalIds.toMutableMap()
        val priority = (state.preferredSources + workManga.sourceId + state.links.map { it.manga.sourceId }).distinct()
        val baseRef = SourceKeys.decodeManga(workManga.id)
        fun nativeDirectory(manga: Manga, chapters: List<Chapter>): List<Chapter> {
            val owner = SourceKeys.decodeManga(manga.id)
            return chapters.mapNotNull { original ->
                val native = original.variants.firstOrNull {
                    it.sourceId == owner.sourceId && it.mangaId == owner.mangaId
                }
                when {
                    native != null -> original.copy(id = native.chapterId, title = native.title,
                        variants = listOf(native), sourceId = native.sourceId,
                        sourceMangaId = native.mangaId, sourceChapterId = native.chapterId)
                    original.variants.isEmpty() -> original
                    else -> null
                }
            }.distinctBy { it.id }
        }
        // A saved projection may already contain linked-only cells. They are not primary raw chapters.
        val nativeBase = nativeDirectory(workManga, baseChapters)
        val nativeCatalogs = linkedCatalogs.map { it.copy(chapters = nativeDirectory(it.manga, it.chapters)) }
        val verifiedVolumeSources = nativeCatalogs.filter { catalog ->
            state.links.any { it.manga.id == catalog.manga.id && it.confirmedAt > 0L } &&
                ChapterSemantics.volumeSystemMatches(nativeBase, catalog.chapters)
        }.map { it.manga.id }.toSet()
        val ambiguousBaseKeys = nativeBase.mapNotNull { chapter ->
            val meaning = ChapterSemantics.parse(chapter, confirmedSerialSystem = true) ?: return@mapNotNull null
            val native = chapter.variants.firstOrNull { it.sourceId == baseRef.sourceId && it.mangaId == baseRef.mangaId }
            val edition = native?.edition ?: if (meaning.system.startsWith("volume")) "单行本" else "连载版"
            ChapterSemantics.editionFamily(edition, meaning, true) + "\u0000" + meaning.key
        }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val serialDisplayGroup = nativeBase.firstOrNull {
            ChapterSemantics.parse(it, true)?.let { meaning -> meaning.system == "chapter" && meaning.branch.isBlank() } == true
        }?.group?.substringBefore('·')?.trim() ?: "连载"
        val ambiguousLinkedKeys = nativeCatalogs.associate { catalog ->
            val confirmed = state.links.firstOrNull { it.manga.id == catalog.manga.id }?.sameChapterSystem == true
            val byMeaning = catalog.chapters.mapNotNull { chapter ->
                val meaning = ChapterSemantics.parse(chapter, confirmed) ?: return@mapNotNull null
                val variant = chapter.variants.firstOrNull()
                val edition = variant?.edition ?: "连载版"
                val key = ChapterSemantics.editionFamily(edition, meaning, confirmed) + "\u0000" + meaning.key
                val volume = Regex("(?:第\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
                    .find(edition + " " + chapter.group)?.value.orEmpty()
                key to volume
            }.groupBy({ it.first }, { it.second })
            val duplicateVolumes = catalog.chapters.mapNotNull { chapter ->
                val meaning = ChapterSemantics.parse(chapter, confirmed) ?: return@mapNotNull null
                if (meaning.system != "volume") return@mapNotNull null
                val edition = chapter.variants.firstOrNull()?.edition ?: "单行本"
                ChapterSemantics.editionFamily(edition, meaning, confirmed) + "\u0000" + meaning.key
            }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            catalog.manga.id to (byMeaning.filterValues { volumes ->
                volumes.filter(String::isNotBlank).distinct().size > 1
            }.keys + duplicateVolumes)
        }

        fun insert(manga: Manga, chapter: Chapter, link: LinkedMangaSource?, isBase: Boolean) {
            val ref = SourceKeys.decodeManga(manga.id)
            val upstreamMangaId = manga.sourceMangaId.ifBlank { ref.mangaId }
            val upstreamChapter = if (SourceKeys.isEncoded(chapter.id))
                SourceKeys.decodeChapter(manga.id, chapter.id).chapterId else chapter.sourceChapterId.ifBlank { chapter.id }
            val confirmedSystem = isBase || link?.sameChapterSystem == true
            val meaning = ChapterSemantics.parse(chapter, confirmedSystem)
            val confirmedVolume = meaning?.system == "volume" && (isBase || manga.id in verifiedVolumeSources)
            val canMergeMeaning = confirmedSystem || confirmedVolume
            val nativeVariant = chapter.variants.firstOrNull { it.sourceId == manga.sourceId &&
                it.mangaId == upstreamMangaId && it.chapterId == upstreamChapter }
            val fallbackEdition = if (meaning?.system?.startsWith("volume") == true) "单行本" else link?.edition ?: "连载版"
            val variant = nativeVariant ?: ChapterVariant(manga.sourceId, sourceDisplayName(manga.sourceId), upstreamMangaId,
                upstreamChapter, chapter.title, link?.language ?: "简中", fallbackEdition)
            val edition = variant.edition
            val storageId = variant.storageId
            // Matching requires verified work identity and chapter system, by the user or discovery.
            val semanticKey = meaning?.key?.let { ChapterSemantics.editionFamily(edition, meaning, confirmedSystem) + "\u0000" + it }
            val ambiguous = semanticKey != null && (semanticKey in ambiguousBaseKeys ||
                semanticKey in ambiguousLinkedKeys[manga.id].orEmpty())
            val independent = if (isBase && !SourceKeys.isEncoded(chapter.id)) chapter.logicalId.ifBlank { chapter.id }
                else ChapterSemantics.independentId(storageId)
            val logicalId = state.manualLinks[storageId]
                ?: if (storageId in state.separated) ChapterSemantics.independentId(storageId)
                else if (isBase) semanticKey?.takeUnless { ambiguous }?.let { anchors[it] }
                    ?: chapter.logicalId.ifBlank { chapter.id }
                else if (canMergeMeaning && semanticKey != null && !ambiguous)
                    meanings[semanticKey] ?: anchors[semanticKey] ?: independent
                else independent
            val bucket = buckets.getOrPut(logicalId) {
                val displayGroup = if (!isBase && confirmedSystem && !ambiguous &&
                    meaning?.system == "chapter" && meaning.branch.isBlank()) serialDisplayGroup else chapter.group
                Bucket(chapter.copy(logicalId = logicalId, variants = emptyList(), group = displayGroup), mutableListOf(), buckets.size)
            }
            if (bucket.variants.none { it.storageId == storageId }) bucket.variants.add(variant)
            if (semanticKey != null && !ambiguous && storageId !in state.separated &&
                canMergeMeaning) {
                meanings.getOrPut(semanticKey) { logicalId }
                anchors.getOrPut(semanticKey) { logicalId }
            }
        }

        nativeBase.forEach { insert(workManga, it, null, true) }
        nativeCatalogs.forEach { catalog ->
            val link = state.links.firstOrNull { it.manga.id == catalog.manga.id } ?: return@forEach
            if (link.confirmedAt <= 0L) return@forEach
            catalog.chapters.forEach { insert(catalog.manga, it, link, false) }
        }

        fun family(bucket: Bucket): String {
            val meaning = ChapterSemantics.parse(bucket.chapter)
            return bucket.chapter.group + "\u0000" + (meaning?.system ?: "unknown") + "\u0000" + meaning?.branch.orEmpty()
        }
        val groupOrder = buckets.values.map { it.chapter.group }.distinct().withIndex().associate { it.value to it.index }
        val familyOrder = buckets.values.map(::family).distinct().withIndex().associate { it.value to it.index }
        val ordered = buckets.values.sortedWith { a, b ->
            val ga = groupOrder[a.chapter.group] ?: Int.MAX_VALUE
            val gb = groupOrder[b.chapter.group] ?: Int.MAX_VALUE
            if (ga != gb) ga.compareTo(gb) else {
                val ma = ChapterSemantics.parse(a.chapter)
                val mb = ChapterSemantics.parse(b.chapter)
                val fa = familyOrder.getValue(family(a))
                val fb = familyOrder.getValue(family(b))
                if (fa != fb) fa.compareTo(fb)
                else if (ma != null && mb != null && ma.number != null && mb.number != null) {
                    ma.number.compareTo(mb.number).takeUnless { it == 0 } ?: a.order.compareTo(b.order)
                } else a.order.compareTo(b.order)
            }
        }
        if (anchors != state.logicalIds) queueAnchors(workManga.id, anchors)
        return ordered.mapIndexed { index, bucket ->
            val variants = bucket.variants.sortedWith(compareBy<ChapterVariant> {
                priority.indexOf(it.sourceId).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE
            }.thenBy { it.storageId })
            val preferred = state.chapterOverrides[bucket.chapter.logicalId]
                ?: variants.firstOrNull { variant ->
                    state.chapterOverrides[ChapterSemantics.independentId(variant.storageId)] == variant.storageId
                }?.storageId
            val chosen = variants.firstOrNull { it.storageId == preferred } ?: variants.first()
            resolveChapter(bucket.chapter.copy(index = index, variants = variants), chosen)
        }
    }

    private fun readRoot(): JSONObject = JSONObject(disk.getString(KEY, "{}") ?: "{}")

    private fun queueAnchors(workId: String, anchors: Map<String, String>) {
        synchronized(anchorLock) {
            val snapshot = anchors.toMap()
            anchorMemory[workId] = snapshot
            pendingAnchors[workId] = snapshot
            pendingAnchorEpochs[workId] = restoreEpoch.get()
        }
        if (anchorSignal.trySend(Unit).isFailure) _persistenceError.value = "章节身份尚未保存，请重新打开作品。"
    }

    private fun persistPendingAnchors() {
        val batch = synchronized(anchorLock) {
            pendingAnchors.mapValues { (workId, anchors) -> (pendingAnchorEpochs[workId] ?: -1L) to anchors }
                .also { pendingAnchors.clear(); pendingAnchorEpochs.clear() }
        }
        if (batch.isEmpty()) return
        try {
            synchronized(anchorBackupGuard) {
                val root = JSONObject(disk.getString(ANCHOR_KEY, "{}") ?: "{}")
                val current = batch.filterValues { it.first == restoreEpoch.get() }
                if (current.isEmpty()) return
                current.forEach { (workId, snapshot) -> root.put(workId, JSONObject(snapshot.second)) }
                val encoded = root.toString()
                ownAnchorWrite = encoded
                check(disk.edit().putString(ANCHOR_KEY, encoded).commit()) { "章节身份未能保存，请检查存储空间。" }
            }
            _persistenceError.value = null
        } catch (failure: Exception) {
            _persistenceError.value = failure.message ?: "章节身份未能保存，请检查存储空间。"
        }
    }

    private fun mutate(workId: String, transform: (UnifiedWorkSettings) -> UnifiedWorkSettings) = synchronized(lock) {
        require(workId.isNotBlank())
        val root = readRoot()
        val old = root.optJSONObject(workId)?.let(::readSettings) ?: UnifiedWorkSettings()
        root.put(workId, writeSettings(transform(old)))
        check(disk.edit().putString(KEY, root.toString()).commit()) { "统一章节设置未能保存，请检查存储空间" }
    }

    companion object {
        const val KEY = "unified_chapters_v1"
        const val ANCHOR_KEY = "unified_chapter_anchors_v1"
        private val anchorBackupGuard = StorageLock()
        private val restoreEpoch = AtomicLong(0L)

        internal fun <T> withLibraryRestore(action: () -> T): T = synchronized(anchorBackupGuard) {
            restoreEpoch.incrementAndGet()
            action()
        }

        internal fun validateAnchors(encoded: String) {
            val root = JSONObject(encoded)
            root.keys().forEach { workId ->
                val anchors = root.getJSONObject(workId)
                anchors.keys().forEach { anchors.getString(it) }
            }
        }

        /** Validated before backup restore clears any existing library state. */
        internal fun validateBackup(encoded: String) {
            val root = JSONObject(encoded)
            root.keys().forEach { key -> readSettings(root.getJSONObject(key)) }
        }

        private fun readSettings(json: JSONObject): UnifiedWorkSettings {
            fun stringMap(key: String): Map<String, String> = json.optJSONObject(key)?.let { map ->
                map.keys().asSequence().associateWith { map.getString(it) }
            }.orEmpty()
            fun strings(key: String): List<String> = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            }.orEmpty()
            val links = json.optJSONArray("links")?.let { array ->
                (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    LinkedMangaSource(readManga(item.getJSONObject("manga")), item.optString("language", "简中"),
                        item.optString("edition", "连载版"), item.optBoolean("sameChapterSystem", false),
                        item.optLong("confirmedAt", 0L), item.optString("association", "manual"), item.optString("matchEvidence"))
                }
            }.orEmpty()
            return UnifiedWorkSettings(links, strings("preferredSources"), stringMap("chapterOverrides"),
                stringMap("manualLinks"), strings("separated").toSet(), stringMap("logicalIds"), strings("excludedSources").toSet())
        }

        private fun writeSettings(state: UnifiedWorkSettings): JSONObject = JSONObject().apply {
            put("links", JSONArray(state.links.map { link -> JSONObject().apply {
                put("manga", link.manga.toJson())
                put("language", link.language)
                put("edition", link.edition)
                put("sameChapterSystem", link.sameChapterSystem)
                put("confirmedAt", link.confirmedAt)
                put("association", link.association)
                put("matchEvidence", link.matchEvidence)
            } }))
            put("preferredSources", JSONArray(state.preferredSources))
            put("chapterOverrides", JSONObject(state.chapterOverrides))
            put("manualLinks", JSONObject(state.manualLinks))
            put("separated", JSONArray(state.separated.toList()))
            put("logicalIds", JSONObject(state.logicalIds))
            put("excludedSources", JSONArray(state.excludedSources.toList()))
        }
    }
}
