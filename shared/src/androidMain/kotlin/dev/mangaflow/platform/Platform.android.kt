package dev.mangaflow.platform

import android.content.Context
import android.icu.text.Transliterator
import android.os.SystemClock
import coil3.PlatformContext as ImagePlatformContext
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual typealias PlatformContext = Context
actual fun applicationContext(context: PlatformContext): PlatformContext = context.applicationContext
actual fun imagePlatformContext(context: PlatformContext): ImagePlatformContext = context
actual fun monotonicTimeMillis(): Long = SystemClock.elapsedRealtime()
actual fun normalizeNfkc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
actual fun randomUuid(): String = UUID.randomUUID().toString()
actual fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
actual fun md5(value: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(value)
actual fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
}
actual fun transliterate(value: String, transform: String): String {
    if (android.os.Build.VERSION.SDK_INT < 29) return value
    return try { Transliterator.getInstance(transform).transliterate(value) }
    catch (_: IllegalArgumentException) { value }
}
actual fun decodeUrlComponent(value: String): String = URLDecoder.decode(value, "UTF-8")
actual fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag()
actual fun formatDecimal(value: Double, decimals: Int): String = String.format(Locale.US, "%.$decimals" + "f", value)
actual fun formatInstantMillis(value: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
actual fun formatProtocolInstantMillis(value: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
actual val platformKind: PlatformKind = PlatformKind.ANDROID
actual fun platformVersion(context: PlatformContext): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
actual fun openPlatformUrl(context: PlatformContext, url: String): Boolean = runCatching {
    require(url.startsWith("https://"))
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)
actual fun openDownloadedInstaller(context: PlatformContext, path: String): Boolean = false // The signed Android updater owns APK installation.
actual fun releaseAssetSuffixes(): List<String> = listOf(".apk")
actual fun platformUpdateChannel(context: PlatformContext): String? = null
