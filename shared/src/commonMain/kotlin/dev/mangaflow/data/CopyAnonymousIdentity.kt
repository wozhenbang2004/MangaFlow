@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.data

import dev.mangaflow.platform.*
import kotlin.io.encoding.Base64

/** Random installation identity for public requests; it contains no hardware or account data. */
data class CopyAnonymousIdentity(
    val device: String,
    val deviceInfo: String,
    val pseudoId: String,
    val umString: String,
) {
    private val loginDate = formatProtocolInstantMillis(wallTimeMillis(), "yyyy-MM-dd HH:mm:ss.SSS'Z'")

    init {
        require(listOf(device, deviceInfo, pseudoId, umString).all { value ->
            value.isNotBlank() && value.length <= 128 && value.all { it in ' '..'~' }
        }) { "匿名来源标识格式无效。" }
    }

    fun headers(): Map<String, String> {
        val now = wallTimeMillis()
        val timestamp = (now / 1000).toString()
        return mapOf(
            "User-Agent" to "COPY/$VERSION",
            "source" to "copyApp",
            "version" to VERSION,
            "versioncode" to VERSION_CODE,
            "platform" to "3",
            "region" to "0",
            "webp" to "1",
            "Accept" to "application/json",
            "Accept-Language" to "",
            "Content-Type" to "application/x-www-form-urlencoded",
            "referer" to "com.copymanga.app-$VERSION",
            "device" to device,
            "deviceinfo" to deviceInfo,
            "pseudoid" to pseudoId,
            "umstring" to umString,
            "dt" to formatProtocolInstantMillis(now, "yyyy.MM.dd"),
            "X-auth-timestamp" to timestamp,
            "X-auth-signature" to hex(hmacSha256(PUBLIC_SIGNING_KEY, timestamp.encodeToByteArray())),
            "Authorization" to "Token",
        )
    }

    /** Protocol metadata only. Account credentials are added by the isolated account client. */
    internal fun accountFormFields(): Map<String, String> = mapOf(
        "platform" to "3",
        "version" to VERSION,
        "source" to "copyApp",
        "region" to "0",
        "webp" to "1",
        "userAgent" to "COPY/$VERSION",
        "isGoogle" to "false",
        "pseudoID" to pseudoId,
        "dt" to loginDate,
        "tapString" to PUBLIC_SIGNING_KEY.decodeToString(),
        "umString" to umString,
        "buildTime" to "2025.05.22",
        "versionCode" to VERSION_CODE,
        "androidId" to device,
        "referer" to "com.copymanga.app-$VERSION",
        "deviceInfo" to deviceInfo,
    )

    companion object {
        private const val VERSION = "3.1.1"
        private const val VERSION_CODE = "85"
        private const val HEX = "0123456789abcdef"
        // Neo's App form uses local wall time with a literal Z, distinct from the header date.
        // buildTime above is its published protocol field, not MangaFlow's build timestamp.

        // Public protocol constant, not an account credential. Sign the timestamp using
        // the decoded ASCII bytes, not a hex-decoded key:
        // https://github.com/venera-app/venera-configs/blob/a04e05591f7832f7166d6660546613c114d6738a/copy_manga.js#L31-L69
        // App header fields were verified against the live 3.1.1 public API and:
        // https://github.com/rchdliang/CopyManga_Neo/blob/36ec484fb6ee166456f8025917963fcf8e9e2ce8/shared/src/commonMain/kotlin/com/rchdliang/copymanga_neo/data/remote/portable/CopyMangaClient.kt#L170-L186
        private val PUBLIC_SIGNING_KEY = Base64.Default.decode("M2FmMDg1OTAzMTEwMzJlZmUwNjYwNTUwYTA1NjNhNTM=")

        /** Call once, then persist and reuse the result. Source errors never rotate it. */
        fun create(): CopyAnonymousIdentity {
            fun nextInt(bound: Int): Int {
                require(bound > 0)
                val range = 1L shl 32
                val limit = range - range % bound
                while (true) {
                    val bytes = secureRandomBytes(4)
                    val value = bytes.fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 255) }
                    if (value < limit) return (value % bound).toInt()
                }
            }
            fun characters(alphabet: String, count: Int): String = buildString(count) {
                repeat(count) { append(alphabet[nextInt(alphabet.length)]) }
            }
            fun randomHex(bytes: Int): String = hex(secureRandomBytes(bytes))
            val letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
            val digits = "0123456789"
            return CopyAnonymousIdentity(
                device = characters(letters, 2) + characters(digits, 1) + characters(letters, 1) +
                    "." + characters(digits, 6) + "." + characters(digits, 3),
                deviceInfo = "${nextInt(9_000_000) + 1_000_000}V-${nextInt(9_000) + 1_000}",
                pseudoId = randomHex(8),
                umString = randomHex(16),
            )
        }

        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }
}
