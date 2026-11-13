@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("CAST_NEVER_SUCCEEDS")

package dev.mangaflow.platform

import coil3.PlatformContext as ImagePlatformContext
import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.Foundation.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication

actual abstract class PlatformContext
actual fun applicationContext(context: PlatformContext): PlatformContext = context
actual fun imagePlatformContext(context: PlatformContext): ImagePlatformContext = ImagePlatformContext.INSTANCE
actual fun monotonicTimeMillis(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000).toLong()
actual fun normalizeNfkc(value: String): String = (value as NSString).precomposedStringWithCompatibilityMapping
actual fun secureRandomBytes(size: Int): ByteArray {
    require(size >= 0)
    return ByteArray(size).also { bytes ->
        if (bytes.isNotEmpty()) bytes.usePinned { pinned ->
            check(SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0)) == 0) { "安全随机数生成失败" }
        }
    }
}
actual fun randomUuid(): String = NSUUID().UUIDString.lowercase()
actual fun sha256(value: ByteArray): ByteArray {
    val output = ByteArray(CC_SHA256_DIGEST_LENGTH)
    (if (value.isEmpty()) byteArrayOf(0) else value).usePinned { input -> output.usePinned { digest ->
        CC_SHA256(input.addressOf(0), value.size.convert(), digest.addressOf(0).reinterpret())
    } }
    return output
}
actual fun md5(value: ByteArray): ByteArray {
    val output = ByteArray(CC_MD5_DIGEST_LENGTH)
    (if (value.isEmpty()) byteArrayOf(0) else value).usePinned { input -> output.usePinned { digest ->
        CC_MD5(input.addressOf(0), value.size.convert(), digest.addressOf(0).reinterpret())
    } }
    return output
}
actual fun hmacSha256(key: ByteArray, value: ByteArray): ByteArray {
    val output = ByteArray(CC_SHA256_DIGEST_LENGTH)
    (if (key.isEmpty()) byteArrayOf(0) else key).usePinned { keyBytes ->
        (if (value.isEmpty()) byteArrayOf(0) else value).usePinned { input -> output.usePinned { digest ->
            CCHmac(kCCHmacAlgSHA256, keyBytes.addressOf(0), key.size.convert(), input.addressOf(0), value.size.convert(), digest.addressOf(0))
        } }
    }
    return output
}
actual fun transliterate(value: String, transform: String): String =
    (value as NSString).stringByApplyingTransform(transform, reverse = false) ?: value
actual fun decodeUrlComponent(value: String): String =
    (value.replace("+", " ") as NSString).stringByRemovingPercentEncoding ?: throw IllegalArgumentException("无效的URL编码")
actual fun currentLocaleTag(): String = NSLocale.currentLocale.localeIdentifier.replace('_', '-')
actual fun formatDecimal(value: Double, decimals: Int): String = NSNumberFormatter().run {
    locale = NSLocale("en_US_POSIX")
    usesGroupingSeparator = false
    minimumFractionDigits = decimals.coerceIn(0, 12).toULong()
    maximumFractionDigits = decimals.coerceIn(0, 12).toULong()
    stringFromNumber(NSNumber(double = value)) ?: value.toString()
}
actual fun formatInstantMillis(value: Long, pattern: String): String = NSDateFormatter().run {
    locale = NSLocale.currentLocale
    timeZone = NSTimeZone.localTimeZone
    dateFormat = pattern
    stringFromDate(NSDate.dateWithTimeIntervalSince1970(value / 1_000.0))
}
actual fun formatProtocolInstantMillis(value: Long, pattern: String): String = NSDateFormatter().run {
    locale = NSLocale("en_US_POSIX")
    calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    timeZone = NSTimeZone.localTimeZone
    dateFormat = pattern
    stringFromDate(NSDate.dateWithTimeIntervalSince1970(value / 1_000.0))
}
actual val platformKind: PlatformKind = PlatformKind.IOS
actual fun platformVersion(context: PlatformContext): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "0.1.0"
actual fun openPlatformUrl(context: PlatformContext, url: String): Boolean {
    if (!url.startsWith("https://")) return false
    val nativeUrl = NSURL.URLWithString(url) ?: return false
    if (!UIApplication.sharedApplication.canOpenURL(nativeUrl)) return false
    UIApplication.sharedApplication.openURL(nativeUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    return true
}
actual fun openDownloadedInstaller(context: PlatformContext, path: String): Boolean = false
actual fun releaseAssetSuffixes(): List<String> = emptyList() // iOS updates through its signed distribution channel.
actual fun platformUpdateChannel(context: PlatformContext): String? =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("MangaFlowDistributionURL") as? String)
        ?.takeIf { it.startsWith("https://") }
