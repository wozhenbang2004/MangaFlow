package dev.mangaflow.account

import android.content.Context
import android.os.SystemClock
import dev.mangaflow.data.CopyAnonymousIdentityStore
import dev.mangaflow.data.CopyMangaConfig
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.SourceResponse
import dev.mangaflow.data.sourceClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Relay's owner-only routes. Public catalogs, pages and downloads never use this client. */
class CopyAccountRepository(context: Context) {
    private val store = AccountTokenStore(context)
    // Relay's HttpClient requests use HTTP/1.1. Keep that wire profile for
    // owner requests instead of negotiating OkHttp's default HTTP/2 path.
    private val accountClient = sourceClient().newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
    // Owner profile/member requests never inherit public-reader cooldowns.
    private val accountPauses = ConcurrentHashMap<String, Long>()
    private val accountHttp = SourceHttp(accountClient, accountPauses)
    private val appContext = context.applicationContext
    private val appProtocol by lazy { CopyAppAccountProtocol(CopyAnonymousIdentityStore.load(appContext)) }
    private val requests = Mutex()
    private val sessionLock = Any()
    private val mutableProfile = MutableStateFlow<AccountProfile?>(null)
    val profile: StateFlow<AccountProfile?> = mutableProfile.asStateFlow()
    @Volatile private var version = 0L
    val sessionVersion: Long get() = version
    private var sessionCredential: StoredAccountCredential? = null
    private var pendingLogin: PendingLogin? = null
    val hasPendingLogin: Boolean get() = synchronized(sessionLock) { pendingLogin?.lease?.version == version }
    val canRetryLogin: Boolean get() = synchronized(sessionLock) {
        pendingLogin?.let { it.lease.version == version && SystemClock.elapsedRealtime() < it.expiresAt } == true
    }
    private val apiOrigin = "https://api.copy202601.com".toHttpUrl()
    private val websiteOrigin = "https://copy4000.com".toHttpUrl()
    private val webProfileUrl = "https://copy4000.com/api/v2/web/user/info".toHttpUrl()
    private val websiteProfileUrl = "${CopyWebsiteLogin.ORIGIN}/api/v2/web/user/info".toHttpUrl()
    private val relayProfileUrl = apiUrl("member/info")
    private val publicConfig = CopyMangaConfig()

    suspend fun restore(): AccountProfile? {
        val expected = synchronized(sessionLock) { version }
        return withContext(Dispatchers.IO) {
            requests.withLock {
                ensureVersion(expected)
                val pending = synchronized(sessionLock) { pendingLogin?.takeIf { it.lease.version == expected } }
                if (pending != null) {
                    if (SystemClock.elapsedRealtime() >= pending.expiresAt) {
                        clearPending(pending)
                        throw SourceException("本次登录验证已超时，请重新输入账号密码。")
                    }
                    return@withLock finishLogin(pending)
                }
                val current = synchronized(sessionLock) {
                    if (version != expected) staleSession()
                    mutableProfile.value
                }
                if (current != null) {
                    ensureVersion(expected)
                    return@withLock current
                }
                val credential = store.read()
                ensureVersion(expected)
                if (credential == null) return@withLock null
                val lease = SessionLease(expected, credential, committed = false)
                val account = verify(lease)
                commit(lease, account, persist = false)
            }
        }
    }

    suspend fun loginWithCookie(input: String): AccountProfile {
        val expected = beginAttempt()
        return withContext(Dispatchers.IO) {
            val token = AccountTokenInput.parse(input)
            requests.withLock {
                ensureVersion(expected)
                val pending = retainLogin(SessionLease(expected, StoredAccountCredential(token, AccountChannel.WEB_API), committed = false))
                finishLogin(pending)
            }
        }
    }

    /** The actual official form owns password entry, encoding and submission. */
    fun beginWebsiteLogin(): Long = beginAttempt()

    fun cancelWebsiteLogin(expected: Long) = synchronized(sessionLock) {
        if (version == expected) {
            version++
            pendingLogin = null
            accountClient.dispatcher.cancelAll()
        }
    }

    suspend fun loginWithWebsiteToken(expected: Long, token: String): AccountProfile {
        return withContext(Dispatchers.IO) {
            ensureVersion(expected)
            val candidate = AccountTokenInput.validate(token)
            requests.withLock {
                ensureVersion(expected)
                val lease = SessionLease(expected, StoredAccountCredential(candidate, AccountChannel.WEBSITE), committed = false)
                finishLogin(retainLogin(lease))
            }
        }
    }

    fun logout() = synchronized(sessionLock) {
        version++
        pendingLogin = null
        sessionCredential = null
        mutableProfile.value = null
        accountClient.dispatcher.cancelAll()
        store.clear()
    }

    suspend fun favorites(page: Int): CloudPage<Manga> {
        val lease = requireSession()
        return withContext(Dispatchers.IO) {
            requirePage(page, 1200)
            requests.withLock {
                val offset = page * 18
                val data = accountObject(lease, apiUrl("member/collect/comics", "limit" to "18", "offset" to "$offset",
                    "free_type" to "1", "ordering" to "-datetime_updated"), "个人收藏", maxChars = 1024 * 1024)
                val rows = pageRows(data, offset, 18, "个人收藏")
                val items = rows.items.mapNotNull { row -> row.optJSONObject("comic")?.let(::parseManga) }.distinctBy(Manga::id)
                if (rows.items.isNotEmpty() && items.isEmpty()) throw SourceException("个人收藏中没有可识别的作品资料。")
                ensureLease(lease)
                CloudPage(items, rows.hasMore)
            }
        }
    }

    suspend fun history(page: Int): CloudPage<CloudHistoryEntry> {
        val lease = requireSession()
        return withContext(Dispatchers.IO) {
            requirePage(page, 500)
            requests.withLock {
                val offset = page * 20
                val data = accountObject(lease, apiUrl("member/browse/comics", "free_type" to "1", "offset" to "$offset",
                    "limit" to "20", "_update" to "true", "platform" to "3"), "云端阅读记录", maxChars = 512 * 1024)
                val rows = pageRows(data, offset, 20, "云端阅读记录")
                val items = rows.items.mapNotNull { row ->
                    val manga = row.optJSONObject("comic")?.let(::parseManga) ?: return@mapNotNull null
                    val chapterId = row.text("last_chapter_id").takeIf { SEGMENT_PATTERN.matches(it) }.orEmpty()
                    CloudHistoryEntry(manga, chapterId, row.text("last_chapter_name").take(120))
                }.distinctBy { it.manga.id }
                if (rows.items.isNotEmpty() && items.isEmpty()) throw SourceException("云端阅读记录中没有可识别的作品资料。")
                ensureLease(lease)
                CloudPage(items, rows.hasMore)
            }
        }
    }

    /** True means a readback confirmed the requested state, including removal. */
    suspend fun setFavorite(mangaId: String, comicUuid: String, favorite: Boolean): Boolean {
        val lease = requireSession()
        val slug = segment(mangaId)
        val id = uuid(comicUuid)
        return withContext(Dispatchers.IO) {
            requests.withLock {
                val queryUrl = apiUrl("comic2/$slug/query", "platform" to "3")
                if (collected(accountObject(lease, queryUrl, "个人收藏状态")) == favorite) {
                    ensureLease(lease)
                    return@withLock true
                }
                val body = FormBody.Builder().add("comic_id", id).add("is_collect", if (favorite) "1" else "0")
                    .add("authorization", "Token ${lease.token}").build()
                accountRequest(lease, apiUrl("member/collect/comic", "platform" to "3"), "更新个人收藏", body = body)
                val confirmed = accountObject(lease, queryUrl, "个人收藏状态")
                ensureLease(lease)
                collected(confirmed) == favorite
            }
        }
    }

    /** Only chapter identity is synced. Page images and exact page progress remain on the public/local paths. */
    suspend fun syncChapter(mangaId: String, chapterId: String) {
        val lease = requireSession()
        val slug = segment(mangaId)
        val id = uuid(chapterId)
        withContext(Dispatchers.IO) {
            requests.withLock {
                val data = accountObject(lease, apiUrl("comic/$slug/chapter2/$id", "platform" to "3"),
                    "云端章节进度同步", maxChars = 8 * 1024 * 1024)
                val chapter = data.optJSONObject("chapter")
                if (chapter == null || chapter.text("uuid") != id || chapter.text("comic_path_word") != slug) {
                    throw SourceException("云端返回的章节归属不匹配，未确认同步成功。")
                }
                val query = accountObject(lease, apiUrl("comic2/$slug/query", "platform" to "3"), "云端阅读状态")
                ensureLease(lease)
                if (query.optJSONObject("browse")?.text("chapter_uuid") != id) {
                    throw SourceException("云端尚未确认这一章的阅读记录，请稍后手动重试。")
                }
            }
        }
    }

    private suspend fun verify(lease: SessionLease): AccountProfile {
        // Official-page logins verify on that same website; saved legacy channels
        // retain their existing routes. Never promote a browser message alone.
        val data = accountObject(lease, profileUrl(lease), "登录状态验证")
        val username = data.text("username").ifBlank { data.text("mobile") }.ifBlank { data.text("email") }
        val nickname = data.text("nickname").ifBlank { data.text("name") }.ifBlank { username }
        if (username.length !in 1..160 || nickname.length > 160 ||
            username.any(Char::isISOControl) || nickname.any(Char::isISOControl)) {
            throw SourceException("来源没有返回有效的账号资料。")
        }
        return AccountProfile(username, nickname)
    }

    private fun profileUrl(lease: SessionLease): HttpUrl = when (lease.channel) {
        AccountChannel.APP -> CopyAppAccountProtocol.profileUrl
        AccountChannel.WEB_API -> relayProfileUrl
        AccountChannel.WEB -> webProfileUrl
        AccountChannel.WEBSITE -> websiteProfileUrl
    }

    private fun websiteRequest(url: HttpUrl): Request.Builder = Request.Builder().url(url)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("platform", "2").header("Origin", "https://${url.host}")
            .header("Referer", if (url == websiteProfileUrl) "${CopyWebsiteLogin.ORIGIN}/web/person/home"
                else "https://copy4000.com/web/login/loginByAccount?url=person%2Fhome")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"155\", \"Chromium\";v=\"155\", \"Not(A:Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", "?0").header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-site", "same-origin").header("sec-fetch-mode", "cors").header("sec-fetch-dest", "empty")
            .header("Cookie", "webp=1")

    private suspend fun accountObject(lease: SessionLease, url: HttpUrl, operation: String, maxChars: Int = 256 * 1024): JSONObject =
        accountRequest(lease, url, operation, maxChars = maxChars) as? JSONObject
            ?: throw SourceException("$operation 没有返回有效数据，请稍后重试。")

    private suspend fun accountRequest(lease: SessionLease, url: HttpUrl, operation: String,
        body: FormBody? = null, maxChars: Int = 256 * 1024): Any? {
        ensureLease(lease)
        val method = if (body == null) "GET" else "POST"
        if (!allowed(url, method) || (!lease.committed && url != profileUrl(lease))) {
            throw SourceException("该操作不属于个人账号接口，未发送登录凭据。")
        }
        val websiteProfile = (lease.channel == AccountChannel.WEB && url == webProfileUrl) ||
            (lease.channel == AccountChannel.WEBSITE && url == websiteProfileUrl)
        val request = if (!websiteProfile) {
            val appUrl = url.newBuilder().removeAllQueryParameters("_t").removeAllQueryParameters("_update")
                .setQueryParameter("platform", "3").apply {
                if (CHAPTER_PATH.matches(url.encodedPath)) {
                    setQueryParameter("in_mainland", "false")
                    setQueryParameter("request_id", "")
                }
            }.build()
            appProtocol.memberRequest(appUrl, lease.token, body)
        } else {
            // Website-issued credentials are checked by the official website profile.
            val builder = websiteRequest(url).header("Authorization", "Token ${lease.token}")
            if (body != null) builder.post(body)
            builder.build()
        }
        val response = try { accountHttp.request(request, operation, readAccountErrors = true) }
            catch (error: Exception) { ensureLease(lease); throw error }
        ensureLease(lease)
        val result = envelope(response, request.url, accountHttp, operation, maxChars, listOf(lease.token))
        ensureLease(lease)
        return result
    }

    private fun envelope(response: SourceResponse, url: HttpUrl, http: SourceHttp, operation: String, maxChars: Int,
        secrets: List<String> = emptyList()): Any? {
        if (response.text.length > maxChars) throw SourceException("$operation 返回的数据过大。")
        val root = try { JSONObject(response.text) } catch (_: Exception) {
            throw SourceException("$operation 返回的数据无法识别（HTTP ${response.statusCode}），请稍后重试。",
                httpStatus = response.statusCode)
        }
        val code = if (response.statusCode in setOf(209, 210) || response.statusCode >= 400) response.statusCode
            else root.integer("code") ?: throw SourceException("$operation 缺少有效状态。")
        val message = accountSourceMessage(root, secrets)
        when (code) {
            401, 403 -> throw SourceException("$operation 未通过（$code）" +
                (if (message.isNotBlank()) "：$message" else "，登录状态无效或已过期，请重新登录。"), httpStatus = code)
            209, 429 -> {
                val retry = response.retryAfterSeconds
                http.rememberPause(url, retry)
                throw SourceException("$operation 暂时被来源限制（$code）" +
                    (if (message.isNotBlank()) "：$message" else "，请稍后重试。") +
                    (if (retry > 0) "（$retry 秒后可重试）" else ""),
                    retryAfterSeconds = retry, httpStatus = code)
            }
            210 -> {
                val retry = response.retryAfterSeconds
                http.rememberPause(url, retry)
                val detail = if (message.isNotBlank()) "：$message" else "，来源未提供具体原因，请稍后重试。"
                val retryHint = if (retry > 0) "（$retry 秒后可重试）" else ""
                throw SourceException("$operation 未成功（210）$detail$retryHint", retryAfterSeconds = retry, httpStatus = code)
            }
        }
        if (code != 200) {
            throw SourceException("$operation 失败（$code）" +
                (if (message.isNotBlank()) "：$message" else "，请核对账号信息或稍后重试。"), httpStatus = code)
        }
        return root.opt("results").takeUnless { it == JSONObject.NULL }
    }

    private fun accountSourceMessage(root: JSONObject, secrets: List<String>): String {
        val results = root.optJSONObject("results")
        var message = listOf(root.opt("message"), root.opt("detail"), results?.opt("message"), results?.opt("detail"))
            .filterIsInstance<String>().filter(String::isNotBlank).distinct().joinToString("；")
        // Only the short, human-readable message is displayed, never the raw
        // response. Redact submitted secrets before truncating a message.
        for (secret in secrets.filter(String::isNotEmpty).sortedByDescending(String::length)) {
            message = message.replace(secret, "[已隐藏]")
        }
        return message.take(512).replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .replace(Regex("(?i)(token|authorization|cookie)\\s*[:=]\\s*(?:(?:Token|Bearer)\\s+)?[^\\s;,]+"), "$1=[已隐藏]")
            .trim().take(240)
    }

    private fun apiUrl(route: String, vararg parameters: Pair<String, String>): HttpUrl =
        apiOrigin.newBuilder().addPathSegments("api/v3/$route").apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()

    private fun allowed(url: HttpUrl, method: String): Boolean {
        if (url.scheme != "https" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
        if (url.host == websiteOrigin.host) return method == "GET" && url == webProfileUrl
        if (url == websiteProfileUrl) return method == "GET"
        if (url == CopyAppAccountProtocol.profileUrl || url == relayProfileUrl) return method == "GET"
        if (url.host != apiOrigin.host) return false
        val path = url.encodedPath
        if (method == "POST") return path == "/api/v3/member/collect/comic"
        if (method != "GET") return false
        if (path in setOf("/api/v3/member/collect/comics", "/api/v3/member/browse/comics")) return true
        return QUERY_PATH.matches(path) || CHAPTER_PATH.matches(path)
    }

    private fun pageRows(data: JSONObject, offset: Int, limit: Int, operation: String): AccountRows {
        val array = data.optJSONArray("list") ?: throw SourceException("$operation 没有返回有效列表。")
        val total = data.integer("total") ?: throw SourceException("$operation 缺少总数。")
        if (total !in 0..20_000 || array.length() > limit ||
            (array.length() > 0 && offset + array.length() > total) || (array.length() == 0 && offset < total)) {
            throw SourceException("$operation 分页数据不完整，请刷新后重试。")
        }
        val rows = (0 until array.length()).mapNotNull(array::optJSONObject)
        if (array.length() > 0 && rows.isEmpty()) throw SourceException("$operation 返回的记录无法识别。")
        return AccountRows(rows, array.length() > 0 && offset + array.length() < total)
    }

    private fun parseManga(comic: JSONObject): Manga? {
        val slug = comic.text("path_word").takeIf { SEGMENT_PATTERN.matches(it) } ?: return null
        val title = comic.text("name").takeIf(String::isNotBlank) ?: return null
        val cover = comic.text("cover").toHttpUrlOrNull()?.takeIf { url ->
            url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                publicConfig.trustedImageDomains.any { url.host == it || url.host.endsWith(".$it") }
        }?.toString().orEmpty()
        val authors = comic.optJSONArray("author")?.let { values ->
            (0 until values.length()).mapNotNull { values.optJSONObject(it)?.text("name")?.takeIf(String::isNotBlank) }
        }.orEmpty()
        return Manga(slug, title, cover, authors = authors,
            latestChapter = comic.text("last_chapter_name").ifBlank { comic.optJSONObject("last_chapter")?.text("name").orEmpty() },
            sourceUrl = "${publicConfig.websiteOrigin}/comic/$slug")
    }

    private fun collected(data: JSONObject): Boolean? = data.integer("collect")?.takeIf { it >= 0 }?.let { it > 0 }

    private fun beginAttempt(): Long = synchronized(sessionLock) {
        version++
        pendingLogin = null
        accountClient.dispatcher.cancelAll()
        version
    }

    private suspend fun retainLogin(lease: SessionLease): PendingLogin {
        ensureLease(lease)
        return synchronized(sessionLock) {
            if (version != lease.version) staleSession()
            PendingLogin(lease, SystemClock.elapsedRealtime() + 5 * 60_000L).also { pendingLogin = it }
        }
    }

    private suspend fun finishLogin(pending: PendingLogin): AccountProfile {
        val lease = pending.lease
        val account = try {
            ensureLease(lease)
            verify(lease)
        } catch (cancelled: CancellationException) {
            clearPending(pending)
            throw cancelled
        } catch (error: SourceException) {
            if (error.httpStatus in setOf(400, 401, 403, 422)) clearPending(pending)
            val retryHint = if (canRetryLogin) "\n可点重试连接继续验证，无需重新输入密码。" else ""
            throw SourceException("已收到登录凭据，但账号资料验证未完成。\n${error.message}$retryHint",
                retryAfterSeconds = error.retryAfterSeconds, cause = error, httpStatus = error.httpStatus)
        }
        // Verification and encrypted persistence report their own failures.
        // A disk/Keystore failure is not another failed server authentication.
        return try { commit(lease, account, persist = true) }
        catch (cancelled: CancellationException) {
            clearPending(pending)
            throw cancelled
        }
    }

    private fun clearPending(pending: PendingLogin) = synchronized(sessionLock) {
        if (pendingLogin === pending) pendingLogin = null
    }

    private fun requireSession(): SessionLease = synchronized(sessionLock) {
        SessionLease(version, sessionCredential ?: throw SourceException("请先登录账号。"), committed = true)
    }

    private suspend fun ensureLease(lease: SessionLease) {
        currentCoroutineContext().ensureActive()
        synchronized(sessionLock) {
            if (version != lease.version || (lease.committed && sessionCredential !== lease.credential)) staleSession()
        }
    }

    private suspend fun ensureVersion(expected: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(sessionLock) { if (version != expected) staleSession() }
    }

    private suspend fun commit(lease: SessionLease, account: AccountProfile, persist: Boolean): AccountProfile {
        currentCoroutineContext().ensureActive()
        return synchronized(sessionLock) {
            if (version != lease.version) staleSession()
            if (persist) store.write(lease.credential)
            pendingLogin = null
            sessionCredential = lease.credential
            version++
            mutableProfile.value = account
            account
        }
    }

    private fun requirePage(page: Int, maximum: Int) {
        if (page !in 0..maximum) throw SourceException("账号列表页码超出范围。")
    }

    private fun segment(value: String): String = value.takeIf { SEGMENT_PATTERN.matches(it) }
        ?: throw SourceException("漫画标识格式无效。")

    private fun uuid(value: String): String = value.takeIf { UUID_PATTERN.matches(it) && it.any { char -> char != '0' && char != '-' } }
        ?: throw SourceException("漫画或章节标识格式无效。")

    private fun JSONObject.text(name: String): String = (opt(name) as? String).orEmpty().trim()
    private fun JSONObject.integer(name: String): Int? = (opt(name) as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
    private fun staleSession(): Nothing = throw CancellationException("账号会话已改变，已忽略旧请求。")
    private class SessionLease(val version: Long, val credential: StoredAccountCredential, val committed: Boolean) {
        val token: String get() = credential.token
        val channel: AccountChannel get() = credential.channel
    }
    private class PendingLogin(val lease: SessionLease, val expiresAt: Long)
    private data class AccountRows(val items: List<JSONObject>, val hasMore: Boolean)

    private companion object {
        val SEGMENT_PATTERN = Regex("[A-Za-z0-9_-]{1,160}")
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val QUERY_PATH = Regex("/api/v3/comic2/[A-Za-z0-9_-]{1,160}/query")
        val CHAPTER_PATH = Regex("/api/v3/comic/[A-Za-z0-9_-]{1,160}/chapter2/[0-9a-fA-F-]{36}")
    }
}
