package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeTrackingAccount
import dev.mangaflow.anime.AnimeTrackingSnapshot
import dev.mangaflow.anime.AnimeWatchStatus
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AnimeTrackingFailure {
    ACCOUNT_CHANGED, REMOTE_CHANGED, UNSUPPORTED_VALUE, PARTIAL_WRITE, WRITE_NOT_CONFIRMED,
}

class AnimeTrackingConflictException(
    val reason: AnimeTrackingFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Explicit collection operations; playback never calls write automatically.
 *
 * Contracts: bangumi/api/open-api/v0.yaml and AniList/docs/reference/{query,mutation}.md
 * (read 2026-11-16). Their APIs do not expose conditional writes: we compare a canonical
 * fresh snapshot before mutation and read back afterwards, but cannot claim server-side CAS.
 * An uncertain or partial write is surfaced for a new review, never retried silently.
 */
class AnimeTrackingRepository(context: PlatformContext) {
    private val context = applicationContext(context)
    val providerIds: List<String> = listOf(BANGUMI_PROVIDER, ANILIST_PROVIDER)
    private val lock = SynchronizedObject()
    private val loaded = mutableSetOf<String>()
    private val credentials = mutableMapOf<String, Credential>()
    private val generations = mutableMapOf<String, Long>()
    private val credentialProblems = mutableMapOf<String, String>()
    private val active = mutableMapOf<String, MutableSet<AnimeHttp>>()
    private val writeLocks = providerIds.associateWith { Mutex() }

    init { DownloadRoutingStore.get(this.context) }

    fun account(providerId: String): AnimeTrackingAccount? = synchronized(lock) {
        requireProvider(providerId)
        try {
            loadCredential(providerId)?.account
        } catch (failure: Exception) {
            // Keep the unreadable secret for recovery. The rest of the anime feature remains usable.
            credentialProblems[providerId] = "安全凭据暂时无法读取，请解锁设备或重新连接这个账号。原凭据未删除。"
            null
        }
    }

    fun credentialError(providerId: String): String? = synchronized(lock) {
        requireProvider(providerId)
        credentialProblems[providerId]
    }

    suspend fun connect(providerId: String, token: String): AnimeTrackingAccount {
        requireProvider(providerId)
        val clean = token.trim().removePrefix("Bearer ").trim()
        require(clean.isNotEmpty() && clean.none { it.isWhitespace() || it.code < 0x20 }) { "请输入完整的访问令牌。" }
        val generation = synchronized(lock) { generations[providerId] ?: 0L }
        val http = AnimeHttp(providerId)
        val verified = try { identify(http, clean) } finally { http.close() }
        return withContext(Dispatchers.IO) { synchronized(lock) {
            if ((generations[providerId] ?: 0L) != generation)
                throw changedAccount()
            val next = generation + 1
            val record = JSONObject().put("token", clean).put("ownerId", verified.account.ownerId)
                .put("displayName", verified.account.displayName)
            // The token and its verified owner are one atomic platform-secret value.
            writeSecret(context, secretKey(providerId), record.toString())
            active[providerId]?.toList()?.forEach(AnimeHttp::close)
            active.remove(providerId)
            val credential = Credential(verified.account, clean, next)
            credentials[providerId] = credential
            generations[providerId] = next
            loaded += providerId
            credentialProblems.remove(providerId)
            verified.account
        } }
    }

    fun disconnect(providerId: String) {
        synchronized(lock) {
            requireProvider(providerId)
            deleteSecret(context, secretKey(providerId))
            generations[providerId] = (generations[providerId] ?: 0L) + 1
            credentials.remove(providerId)
            credentialProblems.remove(providerId)
            loaded += providerId
            active.remove(providerId)?.toList()?.forEach(AnimeHttp::close)
        }
    }

    suspend fun read(providerId: String, remoteSubjectId: String): AnimeTrackingSnapshot {
        val canonicalId = animeNumericId(remoteSubjectId.trim()).toString()
        val credential = withContext(Dispatchers.IO) { credential(providerId) }
        val http = open(credential)
        return try {
            val state = remoteState(http, credential, canonicalId)
            ensureCurrent(credential)
            state.snapshot
        } finally { close(credential, http) }
    }

    suspend fun write(
        expected: AnimeTrackingSnapshot,
        status: AnimeWatchStatus?,
        completedNumbers: Set<Double>,
    ): AnimeTrackingSnapshot {
        requireProvider(expected.providerId)
        animeNumericId(expected.remoteSubjectId)
        require(completedNumbers.all { it.isFinite() && it >= 0 }) { "进度包含无效剧集编号。" }
        return writeLocks.getValue(expected.providerId).withLock {
            val credential = withContext(Dispatchers.IO) { credential(expected.providerId) }
            if (credential.account.ownerId != expected.ownerId) throw changedAccount()
            val http = open(credential)
            var mutationStarted = false
            try {
                val current = remoteState(http, credential, expected.remoteSubjectId)
                ensureCurrent(credential)
                if (current.snapshot.ownerId != expected.ownerId || current.snapshot.revision != expected.revision)
                    throw AnimeTrackingConflictException(AnimeTrackingFailure.REMOTE_CHANGED,
                        "远端状态在预览后发生了变化，请重新读取并确认这次同步。")
                if (status == current.snapshot.status && completedNumbers == current.snapshot.completedNumbers)
                    return@withLock current.snapshot
                if (status == null) throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE,
                    "取消远端收藏会影响远端独有信息，请在原站处理后重新读取；本次没有写入。")

                if (current.bangumi != null) {
                    val data = current.bangumi
                    val unknown = completedNumbers - data.mainByNumber.keys
                    if (unknown.isNotEmpty()) throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE,
                        "所选进度没有唯一对应的 Bangumi 正片章节，请先更正番剧或剧集映射。")
                    val additions = (completedNumbers - current.snapshot.completedNumbers).map { data.mainByNumber.getValue(it) }
                    val removals = (current.snapshot.completedNumbers - completedNumbers).map { data.mainByNumber.getValue(it) }
                    val collectionUrl = BANGUMI_API.newBuilder().addPathSegments("users/-/collections")
                        .addPathSegment(expected.remoteSubjectId).build()
                    if (current.snapshot.status == null) {
                        ensureCurrent(credential)
                        mutationStarted = true
                        http.request(collectionUrl, AnimeSourceStage.TRACKING, "POST",
                            JSONObject().put("type", bangumiStatus(status)), credential.token)
                    }
                    // Only explicitly changed MAIN episode IDs are written. Special episodes and
                    // existing wish/dropped episode flags are not rewritten into a guessed prefix.
                    if (additions.isNotEmpty()) {
                        ensureCurrent(credential)
                        mutationStarted = true
                        http.request(collectionUrl.newBuilder().addPathSegment("episodes").build(),
                            AnimeSourceStage.TRACKING, "PATCH",
                            JSONObject().put("episode_id", JSONArray(additions)).put("type", 2), credential.token)
                    }
                    if (removals.isNotEmpty()) {
                        ensureCurrent(credential)
                        mutationStarted = true
                        http.request(collectionUrl.newBuilder().addPathSegment("episodes").build(),
                            AnimeSourceStage.TRACKING, "PATCH",
                            JSONObject().put("episode_id", JSONArray(removals)).put("type", 0), credential.token)
                    }
                    if (current.snapshot.status != null && status != current.snapshot.status) {
                        ensureCurrent(credential)
                        mutationStarted = true
                        http.request(collectionUrl, AnimeSourceStage.TRACKING, "PATCH",
                            JSONObject().put("type", bangumiStatus(status)), credential.token)
                    }
                } else {
                    val data = requireNotNull(current.anilist)
                    val numbers = completedNumbers.sorted()
                    if (numbers.withIndex().any { (index, number) -> number != (index + 1).toDouble() })
                        throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE,
                            "AniList 只支持连续进度。请选择从第 1 集到第 N 集的完整正片集合；不会替你填补跳过的集数。")
                    if (data.episodeCount != null && numbers.size > data.episodeCount)
                        throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE,
                            "所选进度超过 AniList 该季的集数，请检查季度与剧集映射。")
                    val remoteStatus = if (data.rawStatus == "REPEATING" && status == AnimeWatchStatus.WATCHING)
                        "REPEATING" else anilistStatus(status)
                    ensureCurrent(credential)
                    mutationStarted = true
                    val result = graphQl(http, credential.token,
                        """mutation (${'$'}id: Int, ${'$'}media: Int!, ${'$'}status: MediaListStatus!, ${'$'}progress: Int!) {
                            SaveMediaListEntry(id: ${'$'}id, mediaId: ${'$'}media, status: ${'$'}status, progress: ${'$'}progress) {
                                id userId mediaId status progress
                            }
                        }""".trimIndent(),
                        JSONObject().put("id", data.entryId ?: JSONObject.NULL)
                            .put("media", animeNumericId(expected.remoteSubjectId)).put("status", remoteStatus)
                            .put("progress", numbers.size))
                    val saved = result.requiredObject("SaveMediaListEntry", ANILIST_PROVIDER, AnimeSourceStage.TRACKING)
                    if (saved.optInt("userId").toString() != expected.ownerId ||
                        saved.optInt("mediaId").toString() != expected.remoteSubjectId ||
                        saved.optInt("progress", -1) != numbers.size || saved.text("status") != remoteStatus)
                        throw AnimeTrackingConflictException(AnimeTrackingFailure.WRITE_NOT_CONFIRMED,
                            "远端写入回执与确认内容不一致，请重新读取后检查。")
                }
                ensureCurrent(credential)
                val after = remoteState(http, credential, expected.remoteSubjectId).snapshot
                ensureCurrent(credential)
                if (after.status != status || after.completedNumbers != completedNumbers)
                    throw AnimeTrackingConflictException(AnimeTrackingFailure.WRITE_NOT_CONFIRMED,
                        "远端返回的进度与本次确认不一致，可能发生了并发修改；请重新读取后检查。")
                after
            } catch (cancel: CancellationException) {
                // A sent mutation cannot be assumed to have rolled back when its coroutine is cancelled.
                throw cancel
            } catch (failure: Exception) {
                if (mutationStarted && failure !is AnimeTrackingConflictException)
                    throw AnimeTrackingConflictException(AnimeTrackingFailure.PARTIAL_WRITE,
                        "请求发送后未能确认全部结果，远端可能已保存部分变化。请重新读取后再决定，勿直接重复提交。", failure)
                throw failure
            } finally { close(credential, http) }
        }
    }

    private suspend fun remoteState(http: AnimeHttp, credential: Credential, subjectId: String): RemoteState {
        ensureCurrent(credential)
        val identity = identify(http, credential.token)
        if (identity.account.ownerId != credential.account.ownerId) throw changedAccount()
        ensureCurrent(credential)
        return when (credential.account.providerId) {
            BANGUMI_PROVIDER -> readBangumi(http, credential, identity.username, subjectId)
            ANILIST_PROVIDER -> readAnilist(http, credential, subjectId)
            else -> error("Unknown tracking provider")
        }
    }

    private suspend fun readBangumi(
        http: AnimeHttp, credential: Credential, username: String, subjectId: String,
    ): RemoteState {
        val numericId = animeNumericId(subjectId)
        val subject = http.json(BANGUMI_API.newBuilder().addPathSegment("subjects").addPathSegment(subjectId).build(),
            AnimeSourceStage.TRACKING, token = credential.token)
        if (subject.optInt("type") != 2) throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE,
            "该 Bangumi 条目不是动画，请选择正确的番剧或季度。")
        val collectionResponse = http.request(BANGUMI_API.newBuilder().addPathSegment("users")
            .addPathSegment(username).addPathSegment("collections").addPathSegment(subjectId).build(),
            AnimeSourceStage.TRACKING, token = credential.token, allowNotFound = true)
        val collection = if (collectionResponse.code == 404) null else JSONObject(collectionResponse.text)
        if (collection != null && collection.optInt("subject_id", numericId) != numericId)
            throw AnimeTrackingConflictException(AnimeTrackingFailure.REMOTE_CHANGED, "远端返回了其他番剧的收藏，已停止同步。")
        val catalog = bangumiEpisodes(http, numericId, credential.token)
        val main = catalog.filter { it.optInt("type", -1) == 0 }
        val mainByNumber = linkedMapOf<Double, Int>()
        for (episode in main) {
            val number = episode.finiteNumber("sort")
                ?: throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE, "Bangumi 正片缺少有效集数，无法准确同步。")
            val id = episode.positiveInt("id")!!
            if (number < 0 || mainByNumber.put(number, id) != null)
                throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE,
                    "Bangumi 存在同号正片，无法用集数唯一定位；请在原站整理后再同步。")
        }
        val rows = mutableListOf<JSONObject>()
        val seen = mutableSetOf<Int>()
        var offset = 0
        while (true) {
            val page = http.json(BANGUMI_API.newBuilder().addPathSegments("users/-/collections")
                .addPathSegment(subjectId).addPathSegment("episodes").addQueryParameter("limit", "1000")
                .addQueryParameter("offset", offset.toString()).build(), AnimeSourceStage.TRACKING, token = credential.token)
            val next = if (page.optInt("total", -1) == 0 && page.isNull("data")) emptyList()
                else page.requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.TRACKING).objects()
            val total = page.optInt("total", -1)
            if (next.isEmpty()) {
                if (total > offset) throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.TRACKING, "远端进度分页不完整，请重新读取。")
                break
            }
            for (row in next) {
                val episode = row.requiredObject("episode", BANGUMI_PROVIDER, AnimeSourceStage.TRACKING)
                val id = episode.positiveInt("id")
                    ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.TRACKING, "远端进度缺少剧集身份。")
                if (!seen.add(id) || episode.optInt("subject_id", numericId) != numericId)
                    throw AnimeTrackingConflictException(AnimeTrackingFailure.REMOTE_CHANGED, "远端目录在读取过程中发生变化，请重新读取。")
                rows += row
            }
            offset += next.size
            if (total >= 0 && offset >= total) break
            if (total < 0 && next.size < 1000) break
        }
        if (!seen.containsAll(mainByNumber.values))
            throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.TRACKING, "远端没有返回完整正片进度，无法确认哪些集数已经看过。")
        val byId = mainByNumber.entries.associate { it.value to it.key }
        val completed = rows.filter { it.optInt("type", -1) == 2 }
            .mapNotNull { byId[it.getJSONObject("episode").optInt("id")] }.toSet()
        val status = collection?.let { bangumiWatchStatus(it.optInt("type", -1)) }
        val canonical = JSONObject().put("provider", BANGUMI_PROVIDER).put("owner", credential.account.ownerId)
            .put("subject", subjectId).put("status", status?.name ?: JSONObject.NULL)
            .put("completed", JSONArray(completed.sorted()))
            .put("main", JSONArray(mainByNumber.entries.sortedBy { it.key }.map {
                JSONObject().put("number", it.key).put("id", it.value)
            }))
            .put("episodes", JSONArray(rows.sortedBy { it.getJSONObject("episode").optInt("id") }.map {
                JSONObject().put("id", it.getJSONObject("episode").optInt("id"))
                    .put("type", it.optInt("type", -1)).put("updated", it.optLong("updated_at"))
            }))
        collection?.let {
            canonical.put("collection", JSONObject().put("rate", it.optInt("rate")).put("comment", it.text("comment"))
                .put("private", it.optBoolean("private")).put("tags", JSONArray(
                    it.optJSONArray("tags")?.let { tags -> (0 until tags.length()).map { tags.optString(it) }.sorted() }.orEmpty())))
        }
        val snapshot = AnimeTrackingSnapshot(BANGUMI_PROVIDER, credential.account.ownerId, subjectId,
            status, completed, wallTimeMillis(), animeHash(canonical.toString()))
        return RemoteState(snapshot, bangumi = BangumiState(mainByNumber))
    }

    private suspend fun readAnilist(http: AnimeHttp, credential: Credential, subjectId: String): RemoteState {
        val data = graphQl(http, credential.token,
            """query (${'$'}id: Int!) { Media(id: ${'$'}id, type: ANIME) {
                id type episodes mediaListEntry { id userId mediaId status progress repeat updatedAt }
            } }""".trimIndent(), JSONObject().put("id", animeNumericId(subjectId)))
        val media = data.requiredObject("Media", ANILIST_PROVIDER, AnimeSourceStage.TRACKING)
        if (media.optInt("id").toString() != subjectId || media.text("type") != "ANIME")
            throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE, "请选择正确的 AniList 动画条目。")
        if (!media.has("mediaListEntry"))
            throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.TRACKING, "AniList 未返回收藏状态，请重新读取。")
        val entry = media.optJSONObject("mediaListEntry")
        if (entry != null && (entry.optInt("userId").toString() != credential.account.ownerId ||
                entry.optInt("mediaId").toString() != subjectId))
            throw changedAccount()
        val progress = entry?.optInt("progress", -1) ?: 0
        if (progress < 0) throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.TRACKING, "AniList 返回了无效进度。")
        val rawStatus = entry?.text("status").orEmpty()
        val status = entry?.let { anilistWatchStatus(rawStatus) }
        val completed = (1..progress).mapTo(linkedSetOf()) { it.toDouble() }
        val canonical = JSONObject().put("provider", ANILIST_PROVIDER).put("owner", credential.account.ownerId)
            .put("subject", subjectId).put("status", rawStatus).put("progress", progress)
            .put("entry", entry?.optInt("id") ?: JSONObject.NULL)
            .put("repeat", entry?.optInt("repeat") ?: 0).put("updated", entry?.optLong("updatedAt") ?: 0)
            .put("episodes", media.positiveInt("episodes") ?: JSONObject.NULL)
        return RemoteState(
            AnimeTrackingSnapshot(ANILIST_PROVIDER, credential.account.ownerId, subjectId, status, completed,
                wallTimeMillis(), animeHash(canonical.toString())),
            anilist = AnilistState(entry?.positiveInt("id"), rawStatus, media.positiveInt("episodes")),
        )
    }

    private suspend fun identify(http: AnimeHttp, token: String): Identity = when (http.sourceId) {
        BANGUMI_PROVIDER -> {
            val me = http.json(BANGUMI_API.newBuilder().addPathSegment("me").build(), AnimeSourceStage.AUTH, token = token)
            val id = me.positiveInt("id")
                ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.AUTH, "Bangumi 未返回有效账号身份。")
            val username = me.text("username")
            if (username.isBlank()) throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.AUTH, "Bangumi 账号缺少用户名。")
            Identity(AnimeTrackingAccount(BANGUMI_PROVIDER, id.toString(), me.text("nickname", "username")), username)
        }
        ANILIST_PROVIDER -> {
            val viewer = graphQl(http, token, "query { Viewer { id name } }", JSONObject())
                .requiredObject("Viewer", ANILIST_PROVIDER, AnimeSourceStage.AUTH)
            val id = viewer.positiveInt("id")
                ?: throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.AUTH, "AniList 未返回有效账号身份。")
            Identity(AnimeTrackingAccount(ANILIST_PROVIDER, id.toString(), viewer.text("name")), id.toString())
        }
        else -> error("Unknown tracking provider")
    }

    private suspend fun graphQl(http: AnimeHttp, token: String, query: String, variables: JSONObject): JSONObject {
        val json = http.json(ANILIST_API, AnimeSourceStage.TRACKING, "POST",
            JSONObject().put("query", query).put("variables", variables), token)
        if (json.optJSONArray("errors")?.length()?.let { it > 0 } == true)
            throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.TRACKING,
                "AniList 未完成请求，请确认账号授权、条目 ID 和来源网络后重试。")
        return json.requiredObject("data", ANILIST_PROVIDER, AnimeSourceStage.TRACKING)
    }

    private fun requireProvider(providerId: String) {
        require(providerId in providerIds) { "不支持的追番服务。" }
    }
    private fun loadCredential(providerId: String): Credential? {
        requireProvider(providerId)
        if (providerId !in loaded) {
            val raw = readSecret(context, secretKey(providerId))
            if (!raw.isNullOrBlank()) {
                val value = JSONObject(raw)
                val owner = value.text("ownerId")
                val token = value.text("token")
                require(owner.toIntOrNull()?.let { it > 0 } == true && token.isNotBlank() && token.none(Char::isWhitespace)) {
                    "账号凭证不完整，请重新连接。"
                }
                credentials[providerId] = Credential(
                    AnimeTrackingAccount(providerId, owner, value.text("displayName")),
                    token, generations[providerId] ?: 0L,
                )
            }
            loaded += providerId
            credentialProblems.remove(providerId)
        }
        return credentials[providerId]
    }
    private fun credential(providerId: String): Credential = synchronized(lock) {
        try {
            loadCredential(providerId) ?: throw IllegalStateException("请先在追番设置中连接该账号。")
        } catch (failure: Exception) {
            throw IllegalStateException(credentialProblems[providerId]
                ?: "无法读取这个追番账号，请先在设置中重新连接。", failure)
        }
    }
    private fun ensureCurrent(credential: Credential) = synchronized(lock) {
        if (credentials[credential.account.providerId] !== credential ||
            (generations[credential.account.providerId] ?: 0L) != credential.generation) throw changedAccount()
    }
    private fun open(credential: Credential): AnimeHttp = synchronized(lock) {
        if (credentials[credential.account.providerId] !== credential ||
            (generations[credential.account.providerId] ?: 0L) != credential.generation) throw changedAccount()
        AnimeHttp(credential.account.providerId).also { active.getOrPut(credential.account.providerId) { mutableSetOf() }.add(it) }
    }
    private fun close(credential: Credential, http: AnimeHttp) {
        synchronized(lock) { active[credential.account.providerId]?.remove(http) }
        http.close()
    }
    private fun secretKey(providerId: String) = "anime-tracking-$providerId"
    private fun changedAccount() = AnimeTrackingConflictException(AnimeTrackingFailure.ACCOUNT_CHANGED,
        "追番账号已切换或断开，请重新读取当前账号后再确认。")

    private data class Credential(val account: AnimeTrackingAccount, val token: String, val generation: Long)
    private data class Identity(val account: AnimeTrackingAccount, val username: String)
    private data class BangumiState(val mainByNumber: Map<Double, Int>)
    private data class AnilistState(val entryId: Int?, val rawStatus: String, val episodeCount: Int?)
    private data class RemoteState(
        val snapshot: AnimeTrackingSnapshot,
        val bangumi: BangumiState? = null,
        val anilist: AnilistState? = null,
    )
}

private fun bangumiWatchStatus(value: Int): AnimeWatchStatus = when (value) {
    1 -> AnimeWatchStatus.WISH; 2 -> AnimeWatchStatus.COMPLETED; 3 -> AnimeWatchStatus.WATCHING
    4 -> AnimeWatchStatus.ON_HOLD; 5 -> AnimeWatchStatus.DROPPED
    else -> throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE, "Bangumi 收藏状态无法识别，请在原站确认。")
}
private fun bangumiStatus(value: AnimeWatchStatus): Int = when (value) {
    AnimeWatchStatus.WISH -> 1; AnimeWatchStatus.COMPLETED -> 2; AnimeWatchStatus.WATCHING -> 3
    AnimeWatchStatus.ON_HOLD -> 4; AnimeWatchStatus.DROPPED -> 5
}
private fun anilistWatchStatus(value: String): AnimeWatchStatus = when (value) {
    "PLANNING" -> AnimeWatchStatus.WISH
    "CURRENT", "REPEATING" -> AnimeWatchStatus.WATCHING
    "COMPLETED" -> AnimeWatchStatus.COMPLETED
    "PAUSED" -> AnimeWatchStatus.ON_HOLD
    "DROPPED" -> AnimeWatchStatus.DROPPED
    else -> throw AnimeTrackingConflictException(AnimeTrackingFailure.UNSUPPORTED_VALUE, "AniList 收藏状态无法识别，请在原站确认。")
}
private fun anilistStatus(value: AnimeWatchStatus): String = when (value) {
    AnimeWatchStatus.WISH -> "PLANNING"; AnimeWatchStatus.WATCHING -> "CURRENT"
    AnimeWatchStatus.COMPLETED -> "COMPLETED"; AnimeWatchStatus.ON_HOLD -> "PAUSED"; AnimeWatchStatus.DROPPED -> "DROPPED"
}
