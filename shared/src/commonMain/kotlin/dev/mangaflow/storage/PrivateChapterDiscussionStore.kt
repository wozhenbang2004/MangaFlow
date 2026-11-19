package dev.mangaflow.storage

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Native identity for the exact displayed adult chapter, including its readable edition. */
internal data class PrivateChapterKey private constructor(val digest: String) {
    companion object {
        fun from(manga: Manga, chapter: Chapter, adultSources: Set<String>): PrivateChapterKey? {
            if (manga.sourceId !in adultSources || chapter.sourceId !in adultSources ||
                !SourceKeys.isEncoded(manga.id) || !SourceKeys.isEncoded(chapter.id)) return null
            val work = runCatching { SourceKeys.decodeManga(manga.id) }.getOrNull() ?: return null
            val native = runCatching { SourceKeys.decodeChapter(manga.id, chapter.id) }.getOrNull() ?: return null
            if (work.sourceId != manga.sourceId || native.sourceId != chapter.sourceId ||
                (manga.sourceMangaId.isNotBlank() && work.mangaId != manga.sourceMangaId) ||
                (chapter.sourceMangaId.isNotBlank() && chapter.sourceMangaId != native.mangaId) ||
                (chapter.sourceChapterId.isNotBlank() && chapter.sourceChapterId != native.chapterId)) return null
            if (work.sourceId != native.sourceId || work.mangaId != native.mangaId) {
                if (chapter.variants.none { it.sourceId == native.sourceId && it.mangaId == native.mangaId &&
                    it.chapterId == native.chapterId }) return null
            }
            if ('\u0000' in native.mangaId || '\u0000' in native.chapterId) return null
            return PrivateChapterKey(chapterDiscussionDigest("adult-v1\u0000${native.sourceId}\u0000${native.mangaId}\u0000${native.chapterId}"))
        }
    }
}

internal data class PrivateDiscussionSpace(val room: String, val nickname: String)

internal data class PrivateChapterComment(val id: String, val nickname: String, val text: String,
    val time: Long, val parentId: String? = null)

internal data class PrivateChapterThread(val rows: List<PrivateChapterComment> = emptyList(), val canPost: Boolean = false)

/** Separate document family on an explicitly selected private WebDAV directory. */
internal class PrivateChapterDiscussionStore(context: PlatformContext) {
    private val application = applicationContext(context)
    private val settings = preferences(application, "mangaflow_private_chapter_discussion")
    private val webDav = WebDavBackupStore.get(application)
    val configuration = webDav.config

    fun canConnect(config: WebDavBackupConfig): Boolean = config.configured && config.username.isNotBlank() && config.hasPassword

    fun connectionKey(config: WebDavBackupConfig): String = chapterDiscussionDigest(
        listOf(config.endpoint, config.username, config.remotePath, config.hasPassword.toString()).joinToString("\u0000"))

    fun selectedSpace(config: WebDavBackupConfig): PrivateDiscussionSpace? {
        if (!canConnect(config) || settings.getString("connection") != connectionKey(config)) return null
        val room = settings.getString("room").orEmpty()
        val name = settings.getString("nickname").orEmpty()
        return PrivateDiscussionSpace(room, name).takeIf { validRoom(room) && validNickname(name) }
    }

    suspend fun selectSpace(config: WebDavBackupConfig, room: String, nickname: String): PrivateDiscussionSpace = withContext(Dispatchers.IO) {
        val space = PrivateDiscussionSpace(room.trim(), nickname.trim())
        require(validRoom(space.room)) { "请输入 1 至 64 个字符的讨论区名。" }
        require(validNickname(space.nickname)) { "请输入 1 至 32 个字符的昵称。" }
        capture(config)
        check(settings.edit().putString("connection", connectionKey(config)).putString("room", space.room)
            .putString("nickname", space.nickname).commit()) { "未能保存讨论区设置。" }
        space
    }

    suspend fun leaveSpace() = withContext(Dispatchers.IO) {
        check(settings.edit().clear().commit()) { "未能移除本机讨论区设置。" }
    }

    suspend fun read(key: PrivateChapterKey, config: WebDavBackupConfig, space: PrivateDiscussionSpace): PrivateChapterThread {
        val conversation = conversation(key, space)
        val snapshot = capture(config)
        val document = webDav.readCompanion(conversation.filename, snapshot, sharedDirectory = true)
        return decode(document, conversation)
    }

    suspend fun append(key: PrivateChapterKey, config: WebDavBackupConfig, space: PrivateDiscussionSpace,
        message: PrivateChapterComment): PrivateChapterThread {
        require(validMessage(message) && message.nickname == space.nickname) { "评论或回复内容无效。" }
        val conversation = conversation(key, space)
        val snapshot = capture(config)
        val current = webDav.readCompanion(conversation.filename, snapshot, sharedDirectory = true)
        val previous = decode(current, conversation)
        fun PrivateChapterThread.confirmed(): PrivateChapterThread? = rows.firstOrNull { it.id == message.id }?.let { existing ->
            check(existing == message) { "该评论编号已被使用，请刷新讨论。" }
            this
        }
        previous.confirmed()?.let { return it }
        check(previous.canPost) { "此讨论区暂时无法写入，请检查服务器版本或容量。" }
        check(message.parentId == null || previous.rows.any { it.id == message.parentId && it.parentId == null }) {
            "回复的评论暂不可用，请刷新。"
        }
        try {
            webDav.writeCompanion(conversation.filename, encode(conversation, previous.rows + message),
                current, snapshot, sharedDirectory = true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (writeFailure: Exception) {
            // A write can succeed remotely after the connection breaks. Check the same message ID before inviting a retry.
            val receipt = try {
                decode(webDav.readCompanion(conversation.filename, snapshot, sharedDirectory = true), conversation).confirmed()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            if (receipt != null) return receipt
            throw IllegalStateException("发送未确认。请刷新讨论，或重试这条消息。", writeFailure)
        }
        val receipt = decode(webDav.readCompanion(conversation.filename, snapshot, sharedDirectory = true), conversation)
        return receipt.confirmed() ?: error("服务器还未确认这条消息。请刷新讨论后查看。")
    }

    private suspend fun capture(expected: WebDavBackupConfig): WebDavTransferSnapshot {
        check(canConnect(expected)) { "请先在设置中保存带账号和密码的 HTTPS WebDAV 连接。" }
        val route = DownloadRoutingStore.get(application).snapshot(DownloadCategory.WEBDAV)
        val snapshot = webDav.captureTransfer(route)
        check(connectionKey(snapshot.config) == connectionKey(expected) && snapshot.password.isNotBlank()) {
            "WebDAV 连接已变化，请重新进入讨论。"
        }
        return snapshot
    }

    private data class Conversation(val digest: String) {
        val filename: String get() = "mangaflow-private-discussion-v1-$digest.json"
    }

    private fun conversation(key: PrivateChapterKey, space: PrivateDiscussionSpace): Conversation {
        require(validRoom(space.room) && validNickname(space.nickname)) { "讨论区设置无效。" }
        return Conversation(chapterDiscussionDigest("mangaflow-discussion-v1\u0000${space.room}\u0000${key.digest}"))
    }

    private fun decode(document: WebDavCompanionDocument, conversation: Conversation): PrivateChapterThread {
        val rows = decodeRows(document.payload, conversation)
        return PrivateChapterThread(rows, canPost = document.writable && rows.size < MAX_COMMENTS)
    }

    private fun decodeRows(payload: String?, conversation: Conversation): List<PrivateChapterComment> {
        if (payload == null) return emptyList()
        check(payload.length <= MAX_DOCUMENT_CHARACTERS) { "讨论记录超出支持的大小。" }
        val result = try {
            val data = JSONObject(payload)
            check(data.optInt("version") == 1 && data.optString("conversation") == conversation.digest)
            val array = data.getJSONArray("messages")
            check(array.length() <= MAX_COMMENTS)
            List(array.length()) { index ->
                val row = array.getJSONObject(index)
                PrivateChapterComment(row.getString("id"), row.getString("nickname"), row.getString("text"),
                    row.getLong("time"), row.optString("parent", null)).also { check(validMessage(it)) }
            }
        } catch (failure: Exception) { throw IllegalStateException("讨论记录的格式或章节身份无法核对。", failure) }
        val roots = result.filter { it.parentId == null }.mapTo(hashSetOf()) { it.id }
        check(result.distinctBy { it.id }.size == result.size && result.all { it.parentId == null || it.parentId in roots }) {
            "讨论记录包含重复编号或不相符的回复。"
        }
        return result
    }

    private fun encode(conversation: Conversation, rows: List<PrivateChapterComment>) = JSONObject()
        .put("version", 1).put("conversation", conversation.digest).put("messages", JSONArray(rows.map { row ->
            JSONObject().put("id", row.id).put("nickname", row.nickname).put("text", row.text)
                .put("time", row.time).put("parent", row.parentId)
        })).toString()

    private fun validRoom(room: String) = room.length in 1..64 && room.isNotBlank() && room.none { it.isISOControl() }
    private fun validNickname(name: String) = name.length in 1..32 && name.isNotBlank() && name.none { it.isISOControl() }
    private fun validMessage(row: PrivateChapterComment) = row.id.matches(MESSAGE_ID) && validNickname(row.nickname) &&
        row.text.length in 1..MAX_TEXT && row.text.isNotBlank() && row.time in 1L..253_402_300_799_999L &&
        row.text.none { it.isISOControl() && it != '\n' && it != '\t' } &&
        (row.parentId == null || row.parentId != row.id && row.parentId.matches(MESSAGE_ID))

    companion object {
        const val MAX_TEXT = 1_000
        private const val MAX_COMMENTS = 500
        private const val MAX_DOCUMENT_CHARACTERS = 2_000_000
        private val MESSAGE_ID = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    }
}

private fun chapterDiscussionDigest(value: String): String = sha256(value.encodeToByteArray()).joinToString("") {
    it.toUByte().toString(16).padStart(2, '0')
}
