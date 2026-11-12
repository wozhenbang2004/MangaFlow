package dev.mangaflow.data

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The repository owns matching, saved associations and cache lifetimes. */
internal interface ExternalReviewProvider {
    val id: String
    val name: String
    val homeUrl: String
    val hasWorkReviews: Boolean get() = true
    fun clearCache() = Unit
    suspend fun search(query: String): List<ProviderCandidate>
    suspend fun subject(id: String, includeComments: Boolean = true): ReviewSource
    suspend fun page(id: String, page: Int): ReviewPage
    suspend fun replies(subjectId: String, review: MangaReview, page: Int): ReviewPage =
        throw SourceException("这个来源暂不支持读取回复。", homeUrl)
}

internal data class ProviderCandidate(
    val match: ReviewMatch,
    val titles: List<String>,
    val authors: List<String> = emptyList(),
)

/** Public requests have no account client, cookies or credential store. */
internal class ReviewProviderHttp(private val http: SourceHttp) {
    suspend fun text(
        url: String, label: String, publicUrl: String,
        body: JSONObject? = null, headers: Map<String, String> = emptyMap(),
        minIntervalMillis: Long = 600L,
    ): String {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty())
        val gate = gates.getOrPut(parsed.host) { RequestGate() }
        gate.lock.withLock {
            val now = System.nanoTime() / 1_000_000
            val remaining = gate.nextAt - now
            if (remaining > 0) delay(remaining)
            gate.nextAt = System.nanoTime() / 1_000_000 + minIntervalMillis.coerceAtLeast(0)
        }
        val builder = Request.Builder().url(parsed).header("Accept", "application/json")
            .header("User-Agent", "MangaFlow/0.1.0 (https://github.com/wozhenbang2004/MangaFlow)")
        headers.forEach { (key, value) -> builder.header(key, value) }
        if (body != null) builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return http.request(builder.build(), label, publicUrl).text
    }

    suspend fun json(
        url: String, label: String, publicUrl: String,
        body: JSONObject? = null, headers: Map<String, String> = emptyMap(),
        minIntervalMillis: Long = 600L,
    ): JSONObject = JSONObject(text(url, label, publicUrl, body, headers, minIntervalMillis))

    private class RequestGate { val lock = Mutex(); var nextAt = 0L }
    private companion object { val gates = ConcurrentHashMap<String, RequestGate>() }
}

internal suspend fun providerFirstPage(source: ReviewSource, load: suspend () -> ReviewPage): ReviewSource = try {
    val result = load()
    source.copy(reviews = result.items, reviewCount = result.total, nextPage = result.nextPage,
        reviewsError = result.error)
} catch (cancel: CancellationException) { throw cancel }
  catch (failure: Exception) {
    source.copy(reviewsError = failure.reviewMessage("评论暂时无法载入，请稍后重试。"), nextPage = 0)
}

internal fun normalizeReviewTitle(value: String): String {
    var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    if (Build.VERSION.SDK_INT >= 29) normalized = try {
        android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(normalized)
    } catch (_: IllegalArgumentException) { normalized }
    return normalized.filter(Char::isLetterOrDigit)
}

internal fun reviewPlainText(raw: String): String {
    val prepared = raw.replace(Regex("(?is)\\[img(?:=[^]]*)?].*?\\[/img]"), "")
        .replace(Regex("(?i)\\[/?(?:b|i|u|s|quote|spoiler|url|size|color|align)(?:=[^]]*)?]"), "")
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace("~!", "").replace("!~", "").replace("**", "").replace("__", "")
    val document = Jsoup.parse(prepared)
    document.select("script, style").remove()
    document.select("br, p, div, li, blockquote, h1, h2, h3").forEach { it.appendText("\n") }
    return document.wholeText().replace("\r\n", "\n").replace(Regex("[\\t ]+"), " ")
        .replace(Regex("\\n[\\t ]+"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}

internal fun reviewAvatar(raw: String, domains: Set<String>): String = raw.toHttpUrlOrNull()?.takeIf { url ->
    url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
        domains.any { url.host == it || url.host.endsWith(".$it") }
}?.toString().orEmpty()

internal fun reviewDate(seconds: Long): String = if (seconds > 0) try {
    Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
} catch (_: Exception) { "" } else ""

internal fun reviewDate(value: String): String = try { reviewDate(Instant.parse(value).epochSecond) }
    catch (_: Exception) { "" }
internal fun JSONArray.reviewObjects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONObject.reviewText(key: String): String = (opt(key) as? String).orEmpty().trim()
internal fun JSONObject.reviewId(key: String): String = when (val value = opt(key)) {
    is String -> value.trim()
    is Number -> value.toString()
    else -> ""
}
internal fun JSONObject.reviewCount(key: String): Int? = when (val value = opt(key)) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
internal fun JSONObject.reviewScore(key: String, scale: Int, allowZero: Boolean = false): Double? =
    when (val value = opt(key)) { is Number -> value.toDouble(); is String -> value.toDoubleOrNull(); else -> null }
        ?.takeIf { it.isFinite() && (it > 0 || (allowZero && it == 0.0)) && it <= scale }
internal fun Exception.reviewMessage(fallback: String): String =
    if (this is SourceException) message.orEmpty().ifBlank { fallback } else fallback
