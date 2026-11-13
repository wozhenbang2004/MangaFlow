package dev.mangaflow.platform

import coil3.PlatformContext as ImagePlatformContext
import com.ibm.icu.text.Transliterator
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

actual abstract class PlatformContext(val dataRoot: String, val cacheRoot: String)
actual fun applicationContext(context: PlatformContext): PlatformContext = context
actual fun imagePlatformContext(context: PlatformContext): ImagePlatformContext = ImagePlatformContext.INSTANCE
actual fun monotonicTimeMillis(): Long = System.nanoTime() / 1_000_000
actual fun normalizeNfkc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
actual fun randomUuid(): String = UUID.randomUUID().toString()
actual fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
actual fun md5(value: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(value)
actual fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
}
actual fun transliterate(value: String, transform: String): String = Transliterator.getInstance(transform).transliterate(value)
actual fun decodeUrlComponent(value: String): String = URLDecoder.decode(value, "UTF-8")
actual fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag()
actual fun formatDecimal(value: Double, decimals: Int): String = String.format(Locale.US, "%.$decimals" + "f", value)
actual fun formatInstantMillis(value: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
actual fun formatProtocolInstantMillis(value: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
actual val platformKind: PlatformKind = PlatformKind.DESKTOP
actual fun platformVersion(context: PlatformContext): String =
    System.getProperty("mangaflow.version") ?: PlatformContext::class.java.`package`.implementationVersion ?: "0.1.0"
actual fun openPlatformUrl(context: PlatformContext, url: String): Boolean = runCatching {
    require(url.startsWith("https://"))
    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    true
}.getOrDefault(false)
actual fun openDownloadedInstaller(context: PlatformContext, path: String): Boolean = runCatching {
    val file = java.io.File(path)
    require(file.isFile && releaseAssetSuffixes().any { file.name.endsWith(it, ignoreCase = true) })
    java.awt.Desktop.getDesktop().open(file)
    true
}.getOrDefault(false)
actual fun releaseAssetSuffixes(): List<String> = listOf("-windows-x64.msi", "-windows-x64.exe")
actual fun platformUpdateChannel(context: PlatformContext): String? = null
