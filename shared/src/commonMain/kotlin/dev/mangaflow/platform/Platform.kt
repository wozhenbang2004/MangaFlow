package dev.mangaflow.platform

import androidx.compose.runtime.staticCompositionLocalOf
import coil3.PlatformContext as ImagePlatformContext
import kotlin.time.Clock

/** Native application context, provided once by the real platform application entry. */
expect abstract class PlatformContext

val LocalMangaContext = staticCompositionLocalOf<PlatformContext> {
    error("MangaFlow's platform application context is missing")
}

expect fun applicationContext(context: PlatformContext): PlatformContext
expect fun imagePlatformContext(context: PlatformContext): ImagePlatformContext
fun wallTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
expect fun monotonicTimeMillis(): Long
expect fun normalizeNfkc(value: String): String
expect fun secureRandomBytes(size: Int): ByteArray
expect fun randomUuid(): String
expect fun sha256(value: ByteArray): ByteArray
expect fun md5(value: ByteArray): ByteArray
expect fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray
expect fun transliterate(value: String, transform: String): String
expect fun decodeUrlComponent(value: String): String
expect fun currentLocaleTag(): String
expect fun formatDecimal(value: Double, decimals: Int): String
expect fun formatInstantMillis(value: Long, pattern: String): String
/** Protocol dates keep the existing Gregorian, Latin-digit local-wall-time contract. */
expect fun formatProtocolInstantMillis(value: Long, pattern: String): String

enum class PlatformKind { ANDROID, DESKTOP, IOS }
expect val platformKind: PlatformKind
expect fun platformVersion(context: PlatformContext): String
expect fun openPlatformUrl(context: PlatformContext, url: String): Boolean
expect fun openDownloadedInstaller(context: PlatformContext, path: String): Boolean
expect fun releaseAssetSuffixes(): List<String>
expect fun platformUpdateChannel(context: PlatformContext): String?
