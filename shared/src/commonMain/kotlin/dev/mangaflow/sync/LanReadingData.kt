package dev.mangaflow.sync

import dev.mangaflow.APP_DISPLAY_NAME
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

/** The LAN document deliberately has no image URLs, paths, accounts, scripts or reader settings. */
internal data class LanReadingBook(
    val id: String, val title: String, val sourceId: String, val sourceMangaId: String,
    val sourceName: String = "",
) {
    fun manga() = Manga(id, title, "", sourceId = sourceId, sourceMangaId = sourceMangaId, sourceName = sourceName)
    fun allowed(sources: Set<String>): Boolean = runCatching {
        val ref = SourceKeys.decodeManga(id)
        sourceId in sources && sourceId !in setOf("local", "jm", "jmcomic", "picacg", "ehentai", "exhentai") &&
            !id.startsWith("local:") && ref.sourceId == sourceId &&
            (sourceMangaId.isBlank() || sourceMangaId == ref.mangaId)
    }.getOrDefault(false)

    companion object {
        fun from(manga: Manga) = LanReadingBook(manga.id, manga.title.take(256), manga.sourceId,
            manga.sourceMangaId, manga.sourceName.take(80))
    }
}

internal enum class LanReadingKind { FAVORITE, CHAPTER, RECENT, BOOKMARK, PICTURE }

/** Original-image coordinates are portable data; page grouping and reading mode remain local. */
internal data class LanReadingItem(
    val kind: LanReadingKind,
    val mangaId: String,
    val chapterId: String = "",
    val chapterTitle: String = "",
    val page: Int = 0,
    val pages: Int = 0,
    val offset: Int = 0,
    val panel: Int = -1,
    val panelLayout: String = "",
    val longImage: Float? = null,
    val lastPage: Int = page,
    val note: String = "",
    val at: Long = 0L,
) {
    val key: String get() {
        val chapter = if (kind == LanReadingKind.RECENT) "" else chapterId
        return "${kind.name}:${mangaId.length}:$mangaId${chapter.length}:$chapter" +
            if (kind == LanReadingKind.BOOKMARK || kind == LanReadingKind.PICTURE) ":$page:$lastPage" else ""
    }

    fun sameContent(other: LanReadingItem?): Boolean = other != null && copy(at = 0L) == other.copy(at = 0L)

    fun allowed(books: Map<String, LanReadingBook>, sources: Set<String>): Boolean = runCatching {
        val book = books[mangaId] ?: return false
        if (!book.allowed(sources)) return false
        if (kind == LanReadingKind.FAVORITE) return chapterId.isEmpty()
        val work = SourceKeys.decodeManga(mangaId)
        val chapter = SourceKeys.decodeChapter(mangaId, chapterId)
        chapterId.isNotBlank() && chapter.sourceId in sources &&
            chapter.sourceId !in setOf("local", "jm", "jmcomic", "picacg", "ehentai", "exhentai") &&
            (chapter.sourceId != work.sourceId || chapter.mangaId == work.mangaId)
    }.getOrDefault(false)
}

internal data class LanLibrarySnapshot(
    val revision: Long,
    val books: Map<String, LanReadingBook>,
    val items: Map<String, LanReadingItem>,
    val ledger: String?,
)

/** A compact multi-value register for one existing library item, including durable removals. */
internal data class LanReadingVersion(
    val actor: String,
    val counter: Long,
    val seen: Map<String, Long>,
    val item: LanReadingItem?,
    val changedAt: Long,
) {
    val id: String get() = "$actor:$counter"
    fun supersedes(other: LanReadingVersion): Boolean =
        (seen[other.actor] ?: 0L) >= other.counter || actor == other.actor && counter > other.counter

    /** Continue this branch without implicitly acknowledging the other concurrent candidates. */
    fun context(): Map<String, Long> = seen + (actor to counter)
}

internal data class LanReadingRecord(
    val key: String,
    /** Kept even for a deletion, so source membership is checked before sending a tombstone. */
    val identity: LanReadingItem,
    val versions: List<LanReadingVersion>,
    /** An existing difference still needs confirmation even if its live branches reach the same page. */
    val pendingChoice: Boolean = false,
) {
    // RECENT has one key per book, while its versions can refer to different chapter sources.
    // Check every alternative, not just the retained identity, before exporting or applying it.
    fun allowed(books: Map<String, LanReadingBook>, sources: Set<String>): Boolean =
        identity.allowed(books, sources) && versions.all { it.item?.allowed(books, sources) != false }

    fun winner(): LanReadingVersion = versions.maxWithOrNull(
        compareBy<LanReadingVersion> { it.item == null }.thenBy { it.item?.at ?: it.changedAt }.thenBy { it.changedAt }
            .thenBy { it.counter }.thenBy { it.actor })!!

    fun conflicting(): Boolean {
        if (pendingChoice && versions.size > 1) return true
        val first = versions.first().item
        return versions.drop(1).any { version ->
            if (first == null) version.item != null else !first.sameContent(version.item)
        }
    }

    fun context(): Map<String, Long> = buildMap {
        versions.forEach { version ->
            version.seen.forEach { (actor, counter) -> put(actor, maxOf(get(actor) ?: 0L, counter)) }
            put(version.actor, maxOf(get(version.actor) ?: 0L, version.counter))
        }
    }

    fun merge(other: LanReadingRecord): LanReadingRecord {
        require(key == other.key && identity.key == other.identity.key) { "阅读记录的身份不一致。" }
        val values = (versions + other.versions).groupBy { it.id }.map { (_, copies) ->
            require(copies.all { it == copies.first() }) { "同一阅读版本的内容不一致。" }
            copies.first()
        }
        val remaining = values.filter { candidate -> values.none { it !== candidate && it.supersedes(candidate) } }
        require(remaining.isNotEmpty() && remaining.size <= 64) { "阅读冲突过多，请先处理已有冲突。" }
        val merged = copy(versions = remaining.sortedBy { it.id }, pendingChoice = false)
        // An explicit choice causally covers its candidates and leaves one version. A stale peer's
        // flag cannot resurrect that conflict; an unseen third branch still needs confirmation.
        return merged.copy(pendingChoice = remaining.size > 1 &&
            (pendingChoice || other.pendingChoice || merged.conflicting()))
    }
}

internal data class LanReadingLedger(
    val actor: String,
    val counter: Long = 0L,
    val books: Map<String, LanReadingBook> = emptyMap(),
    val records: Map<String, LanReadingRecord> = emptyMap(),
    /** The exact last projection, used to distinguish a local edit from an imported one. */
    val baseline: Map<String, LanReadingItem> = emptyMap(),
) {
    fun capture(snapshot: LanLibrarySnapshot, sources: Set<String>, now: Long): LanReadingLedger {
        val nextBooks = books + snapshot.books
        val nextRecords = records.toMutableMap()
        val suspended = records.filterValues { it.conflicting() && !it.allowed(nextBooks, sources) }.keys
        var sequence = counter
        (baseline.keys + snapshot.items.keys).forEach { key ->
            // A scope change hides these candidates from transport, not from the local journal.
            if (key in suspended) return@forEach
            val current = snapshot.items[key]
            val previous = baseline[key]
            val identity = current ?: previous ?: return@forEach
            if (!identity.allowed(nextBooks, sources)) return@forEach
            val record = nextRecords[key]
            val pending = record?.conflicting() == true && record.allowed(nextBooks, sources)
            // A fresh last-read time changes ordering, without making stationary chapter saves conflict.
            // While a choice is pending, a RECENT timestamp refresh must not replace a candidate.
            val equal = if (identity.kind == LanReadingKind.RECENT && !pending) current == previous
                else current?.sameContent(previous) ?: (previous == null)
            if (equal && record?.allowed(nextBooks, sources) == true) return@forEach
            check(sequence < Long.MAX_VALUE) { "此设备的同步版本已用尽，请重新配对。" }
            sequence++
            val branch = if (pending) record!!.versions.firstOrNull { it.actor == actor } ?: record.winner() else null
            val seen = branch?.context() ?: record?.context().orEmpty()
            val update = LanReadingRecord(key, identity, listOf(LanReadingVersion(actor, sequence, seen, current, now)))
            // Keep one live version per branch, not every past page. Only choose() acknowledges all
            // candidates; ordinary reading advances the local branch (or the adopted one if new).
            nextRecords[key] = if (pending) record!!.merge(update) else update
        }
        val captured = baseline.filterValues { !it.allowed(nextBooks, sources) } + snapshot.items
        return copy(counter = sequence, books = nextBooks, records = nextRecords,
            baseline = captured.filterKeys { it !in suspended } + baseline.filterKeys { it in suspended })
    }

    fun merge(remote: LanReadingLedger, sources: Set<String>): LanReadingLedger {
        val nextBooks = books + remote.books.filterValues { it.allowed(sources) }
        val next = records.toMutableMap()
        remote.records.values.filter { it.allowed(nextBooks, sources) }.forEach { record ->
            val local = next[record.key]
            // Local reading may have advanced while capture is suspended for an excluded source.
            // Resume capture first when its scope returns; a remote choice cannot skip that fence.
            if (local != null && local.conflicting() && !local.allowed(nextBooks, sources)) return@forEach
            next[record.key] = local?.merge(record) ?: record
        }
        val ownCounter = next.values.flatMap { it.versions }.maxOfOrNull { version ->
            maxOf(if (version.actor == actor) version.counter else 0L, version.seen[actor] ?: 0L)
        } ?: 0L
        return copy(counter = maxOf(counter, ownCounter), books = nextBooks, records = next)
    }

    fun projection(sources: Set<String>): Map<String, LanReadingItem> = records.values
        .filter { it.allowed(books, sources) }
        .mapNotNull { record -> record.winner().item?.let { record.key to it } }.toMap()

    fun choose(key: String, versionId: String, now: Long): LanReadingLedger {
        val record = records[key] ?: error("这条冲突已变化，请重新查看。")
        val chosen = record.versions.singleOrNull { it.id == versionId } ?: error("这条冲突已变化，请重新查看。")
        check(counter < Long.MAX_VALUE)
        val version = LanReadingVersion(actor, counter + 1, record.context(), chosen.item, now)
        return copy(counter = counter + 1,
            records = records + (key to record.copy(versions = listOf(version), pendingChoice = false)))
    }
}

internal object LanReadingCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val FORMAT = "mangaflow-lan-reading-v2"
    private const val LEGACY_LOCAL_FORMAT = "mangaflow-lan-reading-v1"
    private val actorPattern = Regex("[a-f0-9]{64}")

    fun encode(ledger: LanReadingLedger, local: Boolean, sources: Set<String>? = null): String {
        val records = ledger.records.values.filter { sources == null || it.allowed(ledger.books, sources) }
        val workIds = records.mapTo(mutableSetOf()) { it.identity.mangaId }
        val json = JSONObject().put("format", FORMAT)
            .put("books", JSONArray(ledger.books.values.filter { it.id in workIds }.sortedBy { it.id }.map { book ->
                JSONObject().put("id", book.id).put("title", book.title).put("source", book.sourceId)
                    .put("native", book.sourceMangaId).put("sourceName", book.sourceName)
            }))
            .put("records", JSONArray(records.sortedBy { it.key }.map { record ->
                JSONObject().put("identity", itemJson(record.identity)).put("pendingChoice", record.conflicting())
                    .put("versions", JSONArray(record.versions.sortedBy { it.id }.map { version ->
                        JSONObject().put("actor", version.actor).put("counter", version.counter)
                            .put("seen", JSONObject(version.seen)).put("time", version.changedAt)
                            .put("deleted", version.item == null)
                            .apply { version.item?.let { put("item", itemJson(it)) } }
                    }))
            }))
        if (local) json.put("localActor", ledger.actor).put("localCounter", ledger.counter)
            .put("baseline", JSONArray(ledger.baseline.values.sortedBy { it.key }.map(::itemJson)))
        return json.toString().also { require(it.encodeToByteArray().size <= MAX_BYTES) { "阅读同步记录超过 8 MiB，请减少待处理的旧记录后重试。" } }
    }

    fun decode(payload: String, localActor: String, local: Boolean): LanReadingLedger {
        require(payload.encodeToByteArray().size <= MAX_BYTES) { "阅读同步记录过大。" }
        require(actorPattern.matches(localActor)) { "设备身份无效。" }
        val json = JSONObject(payload)
        val format = json.getString("format")
        // v1 peers may treat ordinary reading as confirmation and discard competing versions.
        // Migrate only our durable v1 journal; never downgrade the new wire semantics.
        require(format == FORMAT || local && format == LEGACY_LOCAL_FORMAT) {
            "两台设备的 $APP_DISPLAY_NAME 阅读同步版本不兼容，请将两端更新到支持保留待确认进度的版本。"
        }
        if (local) require(json.getString("localActor") == localActor) { "同步身份已变化，请先移除旧设备配对。" }
        val books = linkedMapOf<String, LanReadingBook>()
        val bookRows = json.getJSONArray("books")
        for (index in 0 until bookRows.length()) {
            val row = bookRows.getJSONObject(index)
            val book = LanReadingBook(row.getString("id"), row.getString("title"), row.getString("source"),
                row.getString("native"), row.optString("sourceName"))
            require(book.id.length in 1..16384 && book.title.length <= 256 && book.sourceName.length <= 80 &&
                book.sourceMangaId.length <= 4096 && book.id !in books) { "作品身份无效。" }
            books[book.id] = book
        }
        val records = linkedMapOf<String, LanReadingRecord>()
        val rows = json.getJSONArray("records")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val identity = readItem(row.getJSONObject("identity"))
            require(identity.mangaId in books && identity.key !in records) { "阅读记录缺少作品或存在重复。" }
            val versions = row.getJSONArray("versions")
            require(versions.length() in 1..64) { "阅读版本数量无效。" }
            val decoded = (0 until versions.length()).map { versionIndex ->
                val value = versions.getJSONObject(versionIndex)
                val actor = value.getString("actor")
                val counter = value.getLong("counter")
                val seen = value.getJSONObject("seen")
                val context = seen.keys().asSequence().associateWith { seen.getLong(it) }
                require(actorPattern.matches(actor) && counter > 0L && context.size <= 64 &&
                    context.all { actorPattern.matches(it.key) && it.value > 0L } &&
                    (context[actor] ?: 0L) < counter) { "阅读版本时钟无效。" }
                val item = if (value.getBoolean("deleted")) null else readItem(value.getJSONObject("item"))
                require(item == null || item.key == identity.key) { "阅读版本身份无效。" }
                val time = value.getLong("time")
                require(time >= 0L)
                LanReadingVersion(actor, counter, context, item, time)
            }
            require(decoded.distinctBy { it.id }.size == decoded.size) { "阅读版本重复。" }
            val pending = if (format == FORMAT) row.getBoolean("pendingChoice") else false
            val record = LanReadingRecord(identity.key, identity, decoded, pending)
            records[identity.key] = record.merge(record)
        }
        val baseline = linkedMapOf<String, LanReadingItem>()
        if (local) {
            val values = json.getJSONArray("baseline")
            for (index in 0 until values.length()) {
                val item = readItem(values.getJSONObject(index))
                require(item.key !in baseline && item.mangaId in books)
                baseline[item.key] = item
            }
        }
        val counter = if (local) json.getLong("localCounter") else 0L
        require(counter >= 0L && (!local || records.values.flatMap { it.versions }
            .filter { it.actor == localActor }.all { it.counter <= counter })) { "设备同步时钟无效。" }
        return LanReadingLedger(localActor, counter, books, records, baseline)
    }

    private fun itemJson(value: LanReadingItem) = JSONObject()
        .put("kind", value.kind.name).put("manga", value.mangaId).put("chapter", value.chapterId)
        .put("title", value.chapterTitle).put("page", value.page).put("pages", value.pages)
        .put("offset", value.offset).put("panel", value.panel).put("panelLayout", value.panelLayout)
        .put("lastPage", value.lastPage).put("note", value.note).put("at", value.at)
        .apply { value.longImage?.let { put("longImage", it.toDouble()) } }

    private fun readItem(row: JSONObject): LanReadingItem {
        val kind = LanReadingKind.entries.singleOrNull { it.name == row.getString("kind") }
            ?: error("阅读记录类型不受支持。")
        val value = LanReadingItem(kind, row.getString("manga"), row.optString("chapter"), row.optString("title"),
            row.optInt("page"), row.optInt("pages"), row.optInt("offset"), row.optInt("panel", -1),
            row.optString("panelLayout"), if (row.has("longImage")) row.getDouble("longImage").toFloat() else null,
            row.optInt("lastPage"), row.optString("note"), row.optLong("at"))
        require(value.mangaId.length in 1..16384 && value.chapterId.length <= 24576 &&
            value.chapterTitle.length <= 160 && value.panelLayout.length <= 160 && value.note.length <= 240 &&
            value.page >= 0 && value.pages >= 0 && value.offset >= 0 && value.panel in -1..127 && value.at >= 0L &&
            (value.longImage == null || value.longImage.isFinite() && value.longImage > 0f && value.longImage <= 1f)) {
            "阅读位置的值无效。"
        }
        if (kind == LanReadingKind.CHAPTER || kind == LanReadingKind.RECENT)
            require(value.page in 0 until value.pages) { "阅读页码超出原图范围。" }
        if (kind == LanReadingKind.PICTURE)
            require(value.lastPage.toLong() in value.page.toLong()..value.page.toLong() + 1L) { "图片收藏的页码无效。" }
        return value
    }
}
