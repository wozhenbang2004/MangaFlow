package dev.mangaflow.tracking

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterSemantics
import dev.mangaflow.data.Manga
import dev.mangaflow.storage.ReadingProgress

enum class MangaTrackingProvider(val id: String, val label: String) {
    BANGUMI("bangumi", "Bangumi"), ANILIST("anilist", "AniList");
}

enum class MangaTrackingStatus(val label: String) {
    PLANNING("想读"), READING("在读"), COMPLETED("读过"), PAUSED("搁置"), DROPPED("放弃"), REPEATING("重读");
}

enum class MangaTrackingUnit(val label: String) { CHAPTER("话"), VOLUME("卷") }

data class MangaTrackingAccount(
    val provider: MangaTrackingProvider,
    val ownerId: String,
    val displayName: String,
)

data class MangaTrackingProgress(
    val status: MangaTrackingStatus? = null,
    val chapters: Int = 0,
    val volumes: Int = 0,
) {
    init { require(chapters >= 0 && volumes >= 0) }
    fun number(unit: MangaTrackingUnit): Int = if (unit == MangaTrackingUnit.CHAPTER) chapters else volumes
    fun withNumber(unit: MangaTrackingUnit, value: Int): MangaTrackingProgress =
        if (unit == MangaTrackingUnit.CHAPTER) copy(chapters = value) else copy(volumes = value)
}

data class MangaTrackingSubject(
    val id: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val chapters: Int? = null,
    val volumes: Int? = null,
)

data class MangaTrackingSnapshot(
    val provider: MangaTrackingProvider,
    val ownerId: String,
    val subject: MangaTrackingSubject,
    val progress: MangaTrackingProgress,
    val revision: String,
    val readAt: Long,
    val entryId: Int? = null,
)

/** A user-confirmed numbering lane, never a reader page index or a catalogue row index. */
data class MangaTrackingNumbering(val unit: MangaTrackingUnit, val branch: String) {
    val key: String get() = unit.name + "\u0000" + branch
    val label: String get() = (if (unit == MangaTrackingUnit.CHAPTER) "连载话数" else "单行本卷数") +
        branch.takeIf(String::isNotBlank)?.let { " · " + it }.orEmpty()
}

data class MangaTrackingBinding(
    val mangaId: String,
    val mangaTitle: String,
    val provider: MangaTrackingProvider,
    val ownerId: String,
    val subject: MangaTrackingSubject,
    val numbering: MangaTrackingNumbering?,
    val autoSync: Boolean = false,
    /** Explicitly accepted tracker facts. They do not create page-level reading history. */
    val local: MangaTrackingProgress = MangaTrackingProgress(),
    val remote: MangaTrackingSnapshot? = null,
    val completedNumbers: Set<Int> = emptySet(),
) {
    val key: String get() = provider.id + "\u0000" + ownerId + "\u0000" + mangaId
}

data class MangaTrackingReview(
    val token: String,
    val mangaId: String,
    val remote: MangaTrackingSnapshot,
    val local: MangaTrackingProgress,
    val localRevision: Long,
    val message: String? = null,
)

data class MangaTrackingPending(
    val bindingKey: String,
    val token: String,
    val expected: MangaTrackingSnapshot,
    val desired: MangaTrackingProgress,
    val automatic: Boolean = false,
    val submitted: Boolean = false,
    val attempts: Int = 0,
    val retryAt: Long = 0L,
    val blocked: Boolean = false,
    val message: String? = null,
)

data class MangaTrackingState(
    val ready: Boolean = false,
    val accounts: Map<MangaTrackingProvider, MangaTrackingAccount> = emptyMap(),
    val manga: Manga? = null,
    val chapters: List<Chapter> = emptyList(),
    val numberings: List<MangaTrackingNumbering> = emptyList(),
    val suggestedIds: Map<MangaTrackingProvider, String> = emptyMap(),
    val bindings: List<MangaTrackingBinding> = emptyList(),
    val reviews: Map<MangaTrackingProvider, MangaTrackingReview> = emptyMap(),
    val candidates: Map<MangaTrackingProvider, List<MangaTrackingSubject>> = emptyMap(),
    val preview: MangaTrackingSnapshot? = null,
    val pending: List<MangaTrackingPending> = emptyList(),
    val busy: Boolean = false,
    val checkingProvider: MangaTrackingProvider? = null,
    val syncing: Boolean = false,
    val aniListClientId: String = "",
    val message: String? = null,
    val error: String? = null,
)

internal data class MangaTrackingOrdinal(val numbering: MangaTrackingNumbering, val number: Int)

internal fun trackingOrdinal(chapter: Chapter): MangaTrackingOrdinal? {
    val meaning = ChapterSemantics.parse(chapter, confirmedSerialSystem = true) ?: return null
    val unit = when (meaning.system) {
        "chapter" -> MangaTrackingUnit.CHAPTER
        "volume" -> MangaTrackingUnit.VOLUME
        else -> return null
    }
    if (meaning.part.isNotBlank()) return null
    val raw = meaning.number?.toPlainString() ?: return null
    if (!raw.matches(Regex("[0-9]+(?:\\.0+)?"))) return null
    val number = raw.substringBefore('.').toIntOrNull()?.takeIf { it > 0 } ?: return null
    return MangaTrackingOrdinal(MangaTrackingNumbering(unit, meaning.branch), number)
}

internal fun trackingNumberings(chapters: List<Chapter>): List<MangaTrackingNumbering> =
    chapters.mapNotNull(::trackingOrdinal).map { it.numbering }.distinctBy { it.key }

/** Only an unambiguous original chapter can be a continuation target. */
internal fun trackingChapter(chapters: List<Chapter>, numbering: MangaTrackingNumbering, number: Int): Chapter? {
    val matching = chapters.filter { trackingOrdinal(it)?.let { value ->
        value.numbering == numbering && value.number == number
    } == true }
    val logical = matching.groupBy { it.logicalId.ifBlank { it.id } }
    return logical.values.singleOrNull()?.firstOrNull()
}

internal fun trackingSameChapter(expected: Chapter, actual: Chapter): Boolean =
    expected.id == actual.id ||
        expected.logicalId.isNotBlank() && expected.logicalId == actual.logicalId ||
        expected.variants.any { it.storageId == actual.id } ||
        actual.variants.any { it.storageId == expected.id }

internal fun trackingCompleted(
    chapters: List<Chapter>,
    history: List<ReadingProgress>,
    numbering: MangaTrackingNumbering,
): Set<Int> {
    val finished = history.filter { it.pageCount > 0 && it.pageIndex == it.pageCount - 1 }.map { it.chapterId }.toSet()
    return chapters.mapNotNull { chapter ->
        val ordinal = trackingOrdinal(chapter)?.takeIf { it.numbering == numbering } ?: return@mapNotNull null
        if (trackingChapter(chapters, numbering, ordinal.number) == null) return@mapNotNull null
        if (chapter.id in finished || chapter.variants.any { it.storageId in finished }) ordinal.number else null
    }.toSet()
}
