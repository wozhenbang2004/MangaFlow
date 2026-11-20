package dev.mangaflow.anime.sources

import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Source-local HTTPS session. The password is used for sign-in and the bearer stays in the secret store. */
internal class CycityAccount(private val context: PlatformContext) {
    private val mutex = Mutex()
    private var initialized = false
    private var session: Session? = null
    private val mutableAccount = MutableStateFlow<String?>(null)
    val account = mutableAccount.asStateFlow()

    suspend fun restore() = mutex.withLock { loadLocked() }

    suspend fun signIn(username: String, password: String) = mutex.withLock {
        require(username.isNotBlank() && username.length <= 256 && password.isNotEmpty() && password.length <= 1024)
        val http = AnimeHttp(CYCITY_SOURCE_ID)
        try {
            val stage = AnimeSourceStage.AUTH
            val payload = JSONObject().put("username", username).put("password", password)
            val data = cycityData(http, "auth/login", stage, method = "POST", payload = payload).cycityObject(stage)
            val user = data.optJSONObject("user")
            val displayName = user?.text("nickname", "username").orEmpty().ifBlank { username }
            cycityCheckRoute(http, stage)
            saveLocked(Session(randomUuid(), data.sessionToken(), displayName))
        } catch (e: AnimeSourceException) {
            if (e.httpStatus == 401) throw cycityError(AnimeSourceStage.AUTH, "次元城登录失败，请核对用户名和密码。", 401)
            throw e
        } finally { http.close() }
    }

    suspend fun signOut() = mutex.withLock { saveLocked(null) }

    suspend fun <T> authorized(http: AnimeHttp, request: suspend (String) -> T): T {
        val original = mutex.withLock { loadLocked(); session } ?: throw accountRequired()
        val result = try { request(original.token) } catch (failure: AnimeSourceException) {
            if (failure.httpStatus != 401) throw failure
            val next = refresh(http, original)
            try { request(next.token) } catch (rejected: AnimeSourceException) {
                if (rejected.httpStatus != 401) throw rejected
                mutex.withLock { if (session == next) saveLocked(null) }
                throw accountRequired()
            }
        }
        mutex.withLock {
            if (session?.identity != original.identity) throw accountRequired()
        }
        return result
    }

    private suspend fun refresh(http: AnimeHttp, original: Session): Session = mutex.withLock {
        val current = session?.takeIf { it.identity == original.identity } ?: throw accountRequired()
        if (current.token != original.token) return@withLock current
        val stage = AnimeSourceStage.AUTH
        try {
            val data = cycityData(http, "auth/refresh", stage, method = "POST", token = current.token).cycityObject(stage)
            cycityCheckRoute(http, stage)
            val renewed = current.copy(token = data.sessionToken())
            saveLocked(renewed)
            renewed
        } catch (failure: AnimeSourceException) {
            if (failure.httpStatus != 401) throw failure
            saveLocked(null)
            throw accountRequired()
        }
    }

    private suspend fun loadLocked() {
        if (initialized) return
        val saved = withContext(Dispatchers.IO) { readSecret(context, SECRET) }
        val result = saved?.let {
            val record = JSONObject(it)
            Session(randomUuid(), record.sessionToken(), record.text("name").ifBlank { "次元城账号" })
        }
        session = result
        mutableAccount.value = result?.displayName
        initialized = true
    }

    private suspend fun saveLocked(value: Session?) = withContext(Dispatchers.IO + NonCancellable) {
        if (value == null) deleteSecret(context, SECRET)
        else writeSecret(context, SECRET, JSONObject().put("token", value.token).put("name", value.displayName).toString())
        session = value
        mutableAccount.value = value?.displayName
        initialized = true
    }

    private fun JSONObject.sessionToken() = text("token").takeIf { token ->
        token.isNotEmpty() && token.length <= 8192 && token.all { it in '!'..'~' }
    } ?: throw cycityError(AnimeSourceStage.AUTH, "次元城未返回有效的账号会话。")

    private fun accountRequired() = cycityError(AnimeSourceStage.AUTH, CYCITY_SIGN_IN_REQUIRED, 401)

    private class Session(val identity: String, val token: String, val displayName: String) {
        fun copy(token: String) = Session(identity, token, displayName)
    }

    companion object {
        private const val SECRET = "anime-cycity-session"
    }
}
