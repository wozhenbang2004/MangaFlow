package dev.mangaflow.account

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.result.contract.ActivityResultContract
import java.net.URI
import java.util.Locale
import java.util.UUID

sealed interface WebsiteLoginResult {
    data class Completed(val handoffId: String) : WebsiteLoginResult
    data object Cancelled : WebsiteLoginResult
    data class Failed(val message: String) : WebsiteLoginResult
}

/** Intents carry only an opaque handoff ID, never cookies or account credentials. */
class WebsiteLoginContract : ActivityResultContract<Unit, WebsiteLoginResult>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, WebsiteLoginActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?): WebsiteLoginResult {
        val failure = intent?.getStringExtra(EXTRA_FAILURE)
        if (failure != null) return WebsiteLoginResult.Failed(failure)
        if (resultCode != Activity.RESULT_OK) return WebsiteLoginResult.Cancelled
        val id = intent?.getStringExtra(EXTRA_HANDOFF)
        return if (id != null && runCatching { UUID.fromString(id) }.isSuccess) {
            WebsiteLoginResult.Completed(id)
        } else WebsiteLoginResult.Failed("本次官网登录已失效，请重新打开。")
    }

    internal companion object {
        const val EXTRA_HANDOFF = "dev.mangaflow.website_login.handoff"
        const val EXTRA_FAILURE = "dev.mangaflow.website_login.failure"
    }
}

internal object WebsiteLoginPolicy {
    const val ENTRY_URL = "https://copy4000.com/web/login/loginByAccount?url=person%2Fhome"
    val COOKIE_URLS = listOf("https://copy4000.com/", "https://www.copy4000.com/")
    private val hosts = setOf("copy4000.com", "www.copy4000.com")

    fun allowsMainFrame(rawUrl: String?): Boolean {
        val uri = rawUrl?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return uri.scheme?.lowercase(Locale.ROOT) == "https" && host in hosts &&
            uri.port in setOf(-1, 443) && uri.rawUserInfo == null
    }
}

/** One active website visit and one expiring, consume-once credential, all in memory. */
object WebsiteLoginSession {
    private const val TOKEN_TTL_MS = 120_000L
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private var activeId: String? = null
    private var closeActive: (() -> Unit)? = null
    private var pending: PendingToken? = null
    // Cleanup callbacks and the WebView APIs below are confined to the main thread.
    private var clearing = false
    private val clearCallbacks = mutableListOf<(Boolean) -> Unit>()

    fun takeToken(handoffId: String): String? = synchronized(lock) {
        val token = pending ?: return@synchronized null
        if (SystemClock.elapsedRealtime() >= token.expiresAt) {
            pending = null
            return@synchronized null
        }
        if (token.id != handoffId) return@synchronized null
        pending = null
        token.rawValue
    }

    /** Logout invalidates late page callbacks before starting asynchronous cookie removal. */
    fun invalidate() {
        val close = synchronized(lock) {
            activeId = null
            pending = null
            closeActive.also { closeActive = null }
        }
        onMain {
            close?.invoke()
            clearWebViewData { }
        }
    }

    internal fun begin(onInvalidated: () -> Unit): String {
        check(Looper.myLooper() == Looper.getMainLooper())
        val id = UUID.randomUUID().toString()
        val oldClose = synchronized(lock) {
            pending = null
            activeId = id
            closeActive.also { closeActive = onInvalidated }
        }
        oldClose?.invoke()
        return id
    }

    internal fun isCurrent(id: String): Boolean = synchronized(lock) { activeId == id }

    internal fun abandon(id: String) = synchronized(lock) {
        if (activeId == id) {
            activeId = null
            closeActive = null
        }
    }

    internal fun handoff(id: String, rawCookieValue: String): String? {
        val handoff = synchronized(lock) {
            if (activeId != id) return@synchronized null
            activeId = null
            closeActive = null
            UUID.randomUUID().toString().also {
                pending = PendingToken(it, rawCookieValue, SystemClock.elapsedRealtime() + TOKEN_TTL_MS)
            }
        } ?: return null
        main.postDelayed({
            synchronized(lock) { if (pending?.id == handoff) pending = null }
        }, TOKEN_TTL_MS)
        return handoff
    }

    internal fun clearWebViewData(onComplete: (Boolean) -> Unit) = onMain {
        clearCallbacks += onComplete
        if (clearing) return@onMain
        clearing = true
        try {
            val cookies = CookieManager.getInstance()
            // Stop late responses from restoring cookies while a visit is being torn down.
            // Only createWebView enables them again, after this callback has completed.
            cookies.setAcceptCookie(false)
            WebStorage.getInstance().deleteAllData()
            cookies.removeAllCookies {
                // false means there were no cookies to remove, not that cleanup failed.
                val completed = runCatching { cookies.flush() }.isSuccess
                finishClear(completed)
            }
        } catch (_: Exception) {
            finishClear(false)
        }
    }

    private fun finishClear(completed: Boolean) {
        clearing = false
        val callbacks = clearCallbacks.toList()
        clearCallbacks.clear()
        callbacks.forEach { it(completed) }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    private data class PendingToken(val id: String, val rawValue: String, val expiresAt: Long)
}
