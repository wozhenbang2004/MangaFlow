package dev.mangaflow.tracking

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.MediaType.Companion.toMediaType
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import dev.mangaflow.net.RequestBody.Companion.toRequestBody
import dev.mangaflow.net.retryAfterMillis
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class TrackingFailure { ACCOUNT, CONFLICT, UNSUPPORTED, NETWORK, UNCERTAIN }

internal class TrackingException(
    val reason: TrackingFailure,
    message: String,
    val retryAt: Long = 0L,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Manga-only collection API. Contracts: bangumi/api/open-api/v0.yaml and
 * AniList/docs (auth, Media, SaveMediaListEntry), read 2026-11-22.
 * Neither service offers conditional writes. Fresh comparison and read-back narrow
 * the race, but cannot make a remote read/modify/write server-atomic.
 */
internal class MangaTrackingRepository(context: PlatformContext) {
    private val context = applicationContext(context)
    private val lock = StorageLock()
    private val loaded = mutableSetOf<MangaTrackingProvider>()
    private val credentials = mutableMapOf<MangaTrackingProvider, Credential>()
    private val generations = mutableMapOf<MangaTrackingProvider, Long>()
    private val active = mutableMapOf<MangaTrackingProvider, MutableSet<TrackingHttp>>()
    private val writeLocks = MangaTrackingProvider.entries.associateWith { Mutex() }

    init { DownloadRoutingStore.get(this.context) }

    fun account(provider: MangaTrackingProvider): MangaTrackingAccount? = synchronized(lock) {
        load(provider)?.account
    }

    suspend fun connect(provider: MangaTrackingProvider, token: String): MangaTrackingAccount {
        val clean = token.trim().removePrefix("Bearer ").trim()
        require(clean.length in 1..16_384 && clean.none { it.isWhitespace() || it.code < 0x20 }) {
            "请输入完整的访问令牌。"
        }
        val generation = synchronized(lock) { generations[provider] ?: 0L }
        val http = TrackingHttp(provider)
        val identity = try { identify(http, clean) } finally { http.close() }
        return withContext(Dispatchers.IO) { synchronized(lock) {
            if ((generations[provider] ?: 0L) != generation) throw accountChanged()
            val next = generation + 1
            writeSecret(context, secretKey(provider), JSONObject().put("token", clean)
                .put("ownerId", identity.account.ownerId).put("displayName", identity.account.displayName).toString())
            active.remove(provider)?.toList()?.forEach(TrackingHttp::close)
            credentials[provider] = Credential(identity.account, clean, next)
            generations[provider] = next
            loaded += provider
            identity.account
        } }
    }

    fun disconnect(provider: MangaTrackingProvider) = synchronized(lock) {
        deleteSecret(context, secretKey(provider))
        generations[provider] = (generations[provider] ?: 0L) + 1
        credentials.remove(provider)
        loaded += provider
        active.remove(provider)?.toList()?.forEach(TrackingHttp::close)
        Unit
    }

    suspend fun search(provider: MangaTrackingProvider, query: String): List<MangaTrackingSubject> {
        val keyword = query.trim()
        require(keyword.length in 1..200) { "请输入漫画名称，最多 200 字。" }
        return using(provider) { http, credential ->
            val results = when (provider) {
                MangaTrackingProvider.BANGUMI -> {
                    val response = http.json(BANGUMI_API.newBuilder().addPathSegments("search/subjects")
                        .addQueryParameter("limit", "20").build(), "POST",
                        JSONObject().put("keyword", keyword).put("sort", "match")
                            .put("filter", JSONObject().put("type", JSONArray(listOf(1))).put("nsfw", false)),
                        credential.token)
                    response.requiredArray("data").objects().filter(::bangumiComic).map(::bangumiSubject)
                }
                MangaTrackingProvider.ANILIST -> {
                    val page = graphQl(http, credential.token,
                        "query (\$search: String!) { Page(page: 1, perPage: 20) { media(search: \$search, type: MANGA, format_in: [MANGA, ONE_SHOT], isAdult: false) { " +
                            MEDIA_FIELDS + " } } }", JSONObject().put("search", keyword)).requiredObject("Page")
                    page.requiredArray("media").objects().filter(::aniListComic).map(::aniListSubject)
                }
            }
            ensureCurrent(credential)
            results.distinctBy { it.id }
        }
    }

    suspend fun read(provider: MangaTrackingProvider, remoteId: String): MangaTrackingSnapshot {
        val id = numericId(remoteId).toString()
        return using(provider) { http, credential -> remoteState(http, credential, id) }
    }

    suspend fun write(
        expected: MangaTrackingSnapshot,
        desired: MangaTrackingProgress,
        beforeMutation: suspend () -> Unit,
    ): MangaTrackingSnapshot = writeLocks.getValue(expected.provider).withLock {
        require(desired.status != null) { "请先选择收藏状态。取消收藏请到原站操作。" }
        if (expected.provider == MangaTrackingProvider.BANGUMI && desired.status == MangaTrackingStatus.REPEATING)
            throw TrackingException(TrackingFailure.UNSUPPORTED, "Bangumi 没有独立的重读状态，请选择在读或保留原状态。")
        using(expected.provider) { http, credential ->
            if (credential.account.ownerId != expected.ownerId) throw accountChanged()
            val fresh = remoteState(http, credential, expected.subject.id)
            if (fresh.revision != expected.revision || fresh.ownerId != expected.ownerId)
                throw TrackingException(TrackingFailure.CONFLICT, "云端进度已变化，请重新对照后选择同步方向。")
            if (fresh.progress == desired) return@using fresh
            validateTotals(fresh.subject, desired)
            ensureCurrent(credential)
            var started = false
            try {
                // The outbox must be durable before a mutation can leave this device.
                beforeMutation()
                ensureCurrent(credential)
                started = true
                when (expected.provider) {
                    MangaTrackingProvider.BANGUMI -> {
                        val payload = JSONObject()
                        if (fresh.progress.status != desired.status) payload.put("type", bangumiStatus(desired.status))
                        if (fresh.progress.chapters != desired.chapters) payload.put("ep_status", desired.chapters)
                        if (fresh.progress.volumes != desired.volumes) payload.put("vol_status", desired.volumes)
                        http.request(BANGUMI_API.newBuilder().addPathSegments("users/-/collections")
                            .addPathSegment(expected.subject.id).build(),
                            if (fresh.progress.status == null) "POST" else "PATCH", payload, credential.token)
                    }
                    MangaTrackingProvider.ANILIST -> {
                        val variables = JSONObject().put("id", fresh.entryId ?: JSONObject.NULL)
                            .put("media", numericId(expected.subject.id))
                        val arguments = mutableListOf("id: \$id", "mediaId: \$media")
                        val declarations = mutableListOf("\$id: Int", "\$media: Int!")
                        if (fresh.progress.status != desired.status) {
                            declarations += "\$status: MediaListStatus!"
                            arguments += "status: \$status"
                            variables.put("status", aniListStatus(desired.status))
                        }
                        if (fresh.progress.chapters != desired.chapters) {
                            declarations += "\$progress: Int!"
                            arguments += "progress: \$progress"
                            variables.put("progress", desired.chapters)
                        }
                        if (fresh.progress.volumes != desired.volumes) {
                            declarations += "\$volumes: Int!"
                            arguments += "progressVolumes: \$volumes"
                            variables.put("volumes", desired.volumes)
                        }
                        val saved = graphQl(http, credential.token,
                            "mutation (" + declarations.joinToString(", ") + ") { SaveMediaListEntry(" +
                                arguments.joinToString(", ") + ") { id userId mediaId status progress progressVolumes } }",
                            variables).requiredObject("SaveMediaListEntry")
                        if (saved.requiredPositive("userId").toString() != expected.ownerId ||
                            saved.requiredPositive("mediaId").toString() != expected.subject.id ||
                            aniListProgress(saved) != desired)
                            throw TrackingException(TrackingFailure.UNCERTAIN, "写入回执与所选进度不一致，请重新对照云端结果。")
                    }
                }
                ensureCurrent(credential)
                val after = remoteState(http, credential, expected.subject.id)
                if (after.progress != desired)
                    throw TrackingException(TrackingFailure.UNCERTAIN, "云端结果与所选进度不一致，请重新对照；本次不再重复提交。")
                after
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (started) throw TrackingException(TrackingFailure.UNCERTAIN,
                    "请求已发出，但尚未确认云端结果。请重新读取核对，避免重复覆盖。", cause = failure)
                throw failure
            }
        }
    }

    private suspend fun remoteState(http: TrackingHttp, credential: Credential, id: String): MangaTrackingSnapshot {
        numericId(id)
        ensureCurrent(credential)
        val identity = if (http.provider == MangaTrackingProvider.BANGUMI) identify(http, credential.token)
            else Identity(credential.account, credential.account.ownerId)
        if (identity.account.ownerId != credential.account.ownerId) throw accountChanged()
        ensureCurrent(credential)
        val snapshot = when (http.provider) {
            MangaTrackingProvider.BANGUMI -> {
                val media = http.json(BANGUMI_API.newBuilder().addPathSegment("subjects").addPathSegment(id).build(),
                    token = credential.token)
                if (media.requiredPositive("id").toString() != id || !bangumiComic(media))
                    throw TrackingException(TrackingFailure.UNSUPPORTED, "该 Bangumi 条目不是漫画书籍，请选择正确的漫画版本。")
                val subject = bangumiSubject(media)
                val result = http.request(BANGUMI_API.newBuilder().addPathSegment("users")
                    .addPathSegment(identity.username).addPathSegment("collections").addPathSegment(id).build(),
                    token = credential.token, allowNotFound = true)
                val entry = if (result.code == 404) null else JSONObject(result.text)
                if (entry != null && (entry.requiredPositive("subject_id").toString() != id || entry.optInt("subject_type") != 1))
                    throw TrackingException(TrackingFailure.UNSUPPORTED, "Bangumi 返回的收藏归属不匹配，请重新核对条目。")
                val progress = entry?.let {
                    MangaTrackingProgress(bangumiProgressStatus(it.optInt("type")), it.requiredNonnegative("ep_status"),
                        it.requiredNonnegative("vol_status"))
                } ?: MangaTrackingProgress()
                // updated_at alone misses some collection edits; include values and identity.
                val revision = JSONObject().put("subject", id).put("owner", identity.account.ownerId)
                    .put("status", progress.status?.name ?: JSONObject.NULL).put("chapters", progress.chapters)
                    .put("volumes", progress.volumes).put("rate", entry?.optInt("rate") ?: JSONObject.NULL)
                    .put("comment", entry?.optString("comment") ?: JSONObject.NULL)
                    .put("private", entry?.optBoolean("private") ?: JSONObject.NULL)
                    .put("tags", entry?.optJSONArray("tags") ?: JSONObject.NULL)
                    .put("platform", media.optString("platform"))
                    .put("totalChapters", subject.chapters ?: JSONObject.NULL)
                    .put("totalVolumes", subject.volumes ?: JSONObject.NULL)
                MangaTrackingSnapshot(http.provider, identity.account.ownerId, subject, progress,
                    trackingHash(revision.toString()), wallTimeMillis())
            }
            MangaTrackingProvider.ANILIST -> {
                val data = graphQl(http, credential.token,
                    "query (\$id: Int!) { Viewer { id } Media(id: \$id, type: MANGA) { " + MEDIA_FIELDS +
                        " mediaListEntry { id userId mediaId status progress progressVolumes repeat updatedAt } } }",
                    JSONObject().put("id", numericId(id)))
                if (data.requiredObject("Viewer").requiredPositive("id").toString() != credential.account.ownerId)
                    throw accountChanged()
                val media = data.requiredObject("Media")
                if (media.requiredPositive("id").toString() != id || !aniListComic(media))
                    throw TrackingException(TrackingFailure.UNSUPPORTED, "该 AniList 条目不是漫画，请选择 MANGA 或 ONE_SHOT 版本。")
                val entry = media.optJSONObject("mediaListEntry")
                if (entry != null && (entry.requiredPositive("userId").toString() != identity.account.ownerId ||
                    entry.requiredPositive("mediaId").toString() != id))
                    throw TrackingException(TrackingFailure.ACCOUNT, "AniList 返回了其他账号或条目的记录，已停止同步。")
                val subject = aniListSubject(media)
                val progress = entry?.let(::aniListProgress) ?: MangaTrackingProgress()
                val canonical = JSONObject().put("subject", id).put("owner", identity.account.ownerId)
                    .put("entry", entry?.requiredPositive("id") ?: JSONObject.NULL)
                    .put("status", progress.status?.name ?: JSONObject.NULL).put("progress", progress.chapters)
                    .put("volumes", progress.volumes).put("repeat", entry?.optInt("repeat") ?: JSONObject.NULL)
                    .put("updated", entry?.optLong("updatedAt") ?: JSONObject.NULL).put("format", media.optString("format"))
                    .put("totalChapters", subject.chapters ?: JSONObject.NULL).put("totalVolumes", subject.volumes ?: JSONObject.NULL)
                MangaTrackingSnapshot(http.provider, identity.account.ownerId, subject, progress,
                    trackingHash(canonical.toString()), wallTimeMillis(), entry?.requiredPositive("id"))
            }
        }
        ensureCurrent(credential)
        return snapshot
    }

    private suspend fun identify(http: TrackingHttp, token: String): Identity = when (http.provider) {
        MangaTrackingProvider.BANGUMI -> {
            val me = http.json(BANGUMI_API.newBuilder().addPathSegment("me").build(), token = token)
            val username = me.optString("username").trim()
            require(username.isNotBlank()) { "Bangumi 没有返回完整账号身份。" }
            Identity(MangaTrackingAccount(http.provider, me.requiredPositive("id").toString(),
                me.optString("nickname").ifBlank { username }), username)
        }
        MangaTrackingProvider.ANILIST -> {
            val me = graphQl(http, token, "query { Viewer { id name } }", JSONObject()).requiredObject("Viewer")
            val id = me.requiredPositive("id").toString()
            Identity(MangaTrackingAccount(http.provider, id, me.optString("name").ifBlank { id }), id)
        }
    }

    private suspend fun graphQl(http: TrackingHttp, token: String, query: String, variables: JSONObject): JSONObject {
        val value = http.json(ANILIST_API, "POST", JSONObject().put("query", query).put("variables", variables), token)
        val errors = value.optJSONArray("errors")?.objects().orEmpty()
        if (errors.isNotEmpty()) {
            val expired = errors.any { it.optInt("status") == 401 }
            throw TrackingException(if (expired) TrackingFailure.ACCOUNT else TrackingFailure.UNSUPPORTED,
                if (expired) "AniList 授权已失效，请重新连接账号。"
                else "AniList 没有完成请求，请核对授权、漫画条目和进度后重试。")
        }
        return value.requiredObject("data")
    }

    private suspend fun <T> using(provider: MangaTrackingProvider, block: suspend (TrackingHttp, Credential) -> T): T {
        val credential = withContext(Dispatchers.IO) { synchronized(lock) {
            load(provider) ?: throw TrackingException(TrackingFailure.ACCOUNT, "请先连接 " + provider.label + " 账号。")
        } }
        val http = synchronized(lock) {
            ensureCurrent(credential)
            TrackingHttp(provider).also { active.getOrPut(provider) { mutableSetOf() }.add(it) }
        }
        return try { block(http, credential) } finally {
            synchronized(lock) { active[provider]?.remove(http) }
            http.close()
        }
    }

    private fun load(provider: MangaTrackingProvider): Credential? {
        if (provider !in loaded) {
            val raw = readSecret(context, secretKey(provider))
            if (!raw.isNullOrBlank()) {
                val value = JSONObject(raw)
                val owner = numericId(value.optString("ownerId")).toString()
                val token = value.optString("token")
                require(token.isNotBlank() && token.none(Char::isWhitespace)) { "安全凭据不完整，请重新连接账号。" }
                credentials[provider] = Credential(MangaTrackingAccount(provider, owner, value.optString("displayName")),
                    token, generations[provider] ?: 0L)
            }
            loaded += provider
        }
        return credentials[provider]
    }

    private fun ensureCurrent(credential: Credential) = synchronized(lock) {
        if (credentials[credential.account.provider] !== credential ||
            (generations[credential.account.provider] ?: 0L) != credential.generation) throw accountChanged()
    }

    private fun secretKey(provider: MangaTrackingProvider) = "manga-tracking-" + provider.id
    private fun accountChanged() = TrackingException(TrackingFailure.ACCOUNT, "账号已切换或断开，请重新对照当前账号。")
    private data class Credential(val account: MangaTrackingAccount, val token: String, val generation: Long)
    private data class Identity(val account: MangaTrackingAccount, val username: String)
}

private val BANGUMI_API = requireNotNull("https://api.bgm.tv/v0/".toHttpUrlOrNull())
private val ANILIST_API = requireNotNull("https://graphql.anilist.co".toHttpUrlOrNull())
private const val MEDIA_FIELDS = "id type format title { userPreferred romaji english native } startDate { year } chapters volumes"

private fun numericId(value: String): Int = value.trim().toIntOrNull()?.takeIf { it > 0 }
    ?: throw IllegalArgumentException("请输入有效的数字条目 ID。")
private fun bangumiComic(value: JSONObject): Boolean = value.optInt("type") == 1 &&
    value.optString("platform").trim().lowercase() in setOf("漫画", "comic", "manga")
private fun aniListComic(value: JSONObject): Boolean = value.optString("type") == "MANGA" &&
    value.optString("format") in setOf("MANGA", "ONE_SHOT")
private fun bangumiSubject(value: JSONObject): MangaTrackingSubject {
    val id = value.requiredPositive("id").toString()
    val original = value.optString("name")
    return MangaTrackingSubject(id, value.optString("name_cn").ifBlank { original },
        listOfNotNull(original.takeIf { it.isNotBlank() && it != value.optString("name_cn") },
            value.optString("date").takeIf(String::isNotBlank),
            if (value.optBoolean("series")) "漫画系列" else "漫画书籍", "ID " + id).joinToString(" · "),
        "https://bgm.tv/subject/" + id,
        // Official Subject schema defines eps as book chapter count, volumes as book volume count.
        // total_episodes is the database episode-row count and is deliberately not used.
        value.optInt("eps").takeIf { it > 0 }, value.optInt("volumes").takeIf { it > 0 })
}
private fun aniListSubject(value: JSONObject): MangaTrackingSubject {
    val id = value.requiredPositive("id").toString()
    val title = value.requiredObject("title")
    val name = listOf("userPreferred", "native", "romaji", "english").map { title.optString(it) }.firstOrNull(String::isNotBlank).orEmpty()
    return MangaTrackingSubject(id, name, listOf(title.optString("native"), value.optString("format"),
        value.optJSONObject("startDate")?.optInt("year")?.takeIf { it > 0 }?.toString().orEmpty(), "ID " + id)
        .filter(String::isNotBlank).distinct().joinToString(" · "), "https://anilist.co/manga/" + id,
        value.optInt("chapters").takeIf { it > 0 }, value.optInt("volumes").takeIf { it > 0 })
}
private fun bangumiProgressStatus(value: Int): MangaTrackingStatus = when (value) {
    1 -> MangaTrackingStatus.PLANNING; 2 -> MangaTrackingStatus.COMPLETED; 3 -> MangaTrackingStatus.READING
    4 -> MangaTrackingStatus.PAUSED; 5 -> MangaTrackingStatus.DROPPED
    else -> throw TrackingException(TrackingFailure.UNSUPPORTED, "Bangumi 收藏状态无法识别，请到原站核对。")
}
private fun bangumiStatus(value: MangaTrackingStatus): Int = when (value) {
    MangaTrackingStatus.PLANNING -> 1; MangaTrackingStatus.COMPLETED -> 2; MangaTrackingStatus.READING -> 3
    MangaTrackingStatus.PAUSED -> 4; MangaTrackingStatus.DROPPED -> 5
    MangaTrackingStatus.REPEATING -> throw TrackingException(TrackingFailure.UNSUPPORTED, "Bangumi 没有独立的重读状态。")
}
private fun aniListProgress(value: JSONObject): MangaTrackingProgress = MangaTrackingProgress(
    when (value.optString("status")) {
        "PLANNING" -> MangaTrackingStatus.PLANNING; "CURRENT" -> MangaTrackingStatus.READING
        "COMPLETED" -> MangaTrackingStatus.COMPLETED; "PAUSED" -> MangaTrackingStatus.PAUSED
        "DROPPED" -> MangaTrackingStatus.DROPPED; "REPEATING" -> MangaTrackingStatus.REPEATING
        else -> throw TrackingException(TrackingFailure.UNSUPPORTED, "AniList 收藏状态无法识别，请到原站核对。")
    }, value.requiredNonnegative("progress"), value.requiredNonnegative("progressVolumes"))
private fun aniListStatus(value: MangaTrackingStatus): String = when (value) {
    MangaTrackingStatus.PLANNING -> "PLANNING"; MangaTrackingStatus.READING -> "CURRENT"
    MangaTrackingStatus.COMPLETED -> "COMPLETED"; MangaTrackingStatus.PAUSED -> "PAUSED"
    MangaTrackingStatus.DROPPED -> "DROPPED"; MangaTrackingStatus.REPEATING -> "REPEATING"
}
internal fun validateTotals(subject: MangaTrackingSubject, progress: MangaTrackingProgress) {
    require(subject.chapters?.let { progress.chapters <= it } != false &&
        subject.volumes?.let { progress.volumes <= it } != false) { "所选进度超过该条目的总话数或总卷数，请核对版本。" }
}
internal fun trackingHash(value: String): String =
    sha256(value.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONObject.requiredObject(key: String): JSONObject = optJSONObject(key)
    ?: throw TrackingException(TrackingFailure.UNSUPPORTED, "服务返回的数据缺少必要信息，请稍后重新读取。")
internal fun JSONObject.requiredArray(key: String): JSONArray = optJSONArray(key)
    ?: throw TrackingException(TrackingFailure.UNSUPPORTED, "服务返回的数据缺少列表，请稍后重新读取。")
internal fun JSONObject.requiredPositive(key: String): Int = requiredNonnegative(key).takeIf { it > 0 }
    ?: throw TrackingException(TrackingFailure.UNSUPPORTED, "服务返回了无效的账号或条目 ID。")
internal fun JSONObject.requiredNonnegative(key: String): Int = optString(key).toIntOrNull()?.takeIf { it >= 0 }
    ?: throw TrackingException(TrackingFailure.UNSUPPORTED, "服务返回了无法识别的进度，已停止同步。")

private data class TrackingResponse(val code: Int, val text: String)

/** Fixed official origins; no redirects can carry a token outside its service. */
private class TrackingHttp(val provider: MangaTrackingProvider) : AutoCloseable {
    private val client = NetworkClient(route = DownloadRoutingStore.activeSourceSnapshot("review." + provider.id))

    suspend fun request(
        url: HttpUrl,
        method: String = "GET",
        payload: JSONObject? = null,
        token: String,
        allowNotFound: Boolean = false,
    ): TrackingResponse {
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            url.host == if (provider == MangaTrackingProvider.BANGUMI) "api.bgm.tv" else "graphql.anilist.co")
        val gate = gates.getValue(provider)
        gate.withLock {
            val until = synchronized(paceLock) { maxOf(nextRequest[provider] ?: 0L, pausedUntil[provider] ?: 0L) }
            val wait = until - wallTimeMillis()
            if (wait > 5_000L) throw TrackingException(TrackingFailure.NETWORK, "服务正在限流，稍后会继续核对。", retryAt = until)
            if (wait > 0) delay(wait)
            synchronized(paceLock) { nextRequest[provider] = wallTimeMillis() +
                if (provider == MangaTrackingProvider.ANILIST) 2_200L else 1_000L }
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", "wozhenbang2004/MangaFlow/1.0 (https://github.com/wozhenbang2004/MangaFlow)")
            .header("Accept", "application/json").header("Authorization", "Bearer " + token)
            .method(method, payload?.toString()?.toRequestBody("application/json".toMediaType())).build()
        val response = try { client.execute(request, maximumBytes = 2L * 1024 * 1024) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            throw TrackingException(TrackingFailure.NETWORK, "连接未完成，进度已保留在本机，请稍后重试。", cause = failure)
        }
        if (response.code == 429 || response.code == 503) {
            val until = wallTimeMillis() + (retryAfterMillis(response.header("Retry-After")) ?: 60_000L).coerceIn(1_000L, 86_400_000L)
            synchronized(paceLock) { pausedUntil[provider] = maxOf(pausedUntil[provider] ?: 0L, until) }
            throw TrackingException(TrackingFailure.NETWORK, "服务暂时限流或繁忙，稍后会继续核对。", retryAt = until)
        }
        if (response.header("X-RateLimit-Remaining")?.toIntOrNull() == 0) {
            val reset = response.header("X-RateLimit-Reset")?.toLongOrNull()?.times(1_000)
                ?: wallTimeMillis() + 60_000
            synchronized(paceLock) { pausedUntil[provider] = maxOf(pausedUntil[provider] ?: 0L, reset) }
        }
        if (response.code == 401)
            throw TrackingException(TrackingFailure.ACCOUNT, provider.label + " 授权已失效，请重新连接账号。")
        if (!(response.isSuccessful || allowNotFound && response.code == 404))
            throw TrackingException(if (response.code >= 500) TrackingFailure.NETWORK else TrackingFailure.UNSUPPORTED,
                provider.label + " 请求未完成（HTTP " + response.code + "），请核对授权与条目。")
        return TrackingResponse(response.code, response.text())
    }

    suspend fun json(url: HttpUrl, method: String = "GET", payload: JSONObject? = null, token: String): JSONObject =
        try { JSONObject(request(url, method, payload, token).text) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: TrackingException) { throw failure }
        catch (failure: Exception) {
            throw TrackingException(TrackingFailure.UNSUPPORTED, "服务没有返回有效数据，暂不改动任何进度。", cause = failure)
        }

    override fun close() = client.close()
    companion object {
        private val gates = MangaTrackingProvider.entries.associateWith { Mutex() }
        private val paceLock = StorageLock()
        private val nextRequest = mutableMapOf<MangaTrackingProvider, Long>()
        private val pausedUntil = mutableMapOf<MangaTrackingProvider, Long>()
    }
}
