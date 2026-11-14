package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.flow.StateFlow

enum class SteamWallpaperKind { IMAGE, VIDEO }

enum class SteamGuardMethod { EMAIL_CODE, AUTHENTICATOR_CODE, MOBILE_CONFIRMATION, EMAIL_CONFIRMATION }

data class SteamWallpaperAccount(val accountName: String, val steamId: String)

data class SteamWallpaperItem(
    val id: String,
    val title: String,
    val previewUrl: String,
    val kind: SteamWallpaperKind,
    val sizeBytes: Long? = null,
)

data class SteamWallpaperState(
    val supported: Boolean = true,
    val restoring: Boolean = true,
    val preparing: Boolean = false,
    val componentBytes: Long = 0,
    val componentTotalBytes: Long = 0,
    val account: SteamWallpaperAccount? = null,
    /** A saved credential was parsed locally; successful account validation is still pending. */
    val canResumeSession: Boolean = false,
    val authenticating: Boolean = false,
    val qrUrl: String? = null,
    val guardMethods: List<SteamGuardMethod> = emptyList(),
    val guardSubmitting: Boolean = false,
    val guardError: String? = null,
    val loading: Boolean = false,
    val query: String = "",
    val kind: SteamWallpaperKind = SteamWallpaperKind.VIDEO,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val items: List<SteamWallpaperItem> = emptyList(),
    val transferId: String? = null,
    val transferName: String? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val importedCount: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

/** One visible Workshop panel owns its cancellable Steam jobs. */
interface SteamWallpaperClient {
    val state: StateFlow<SteamWallpaperState>
    fun login()
    /** Takes ownership of the transient password buffer; implementations must clear it. */
    fun loginWithPassword(accountName: String, password: CharArray)
    fun submitGuardCode(code: String, method: SteamGuardMethod)
    fun search(query: String, kind: SteamWallpaperKind, page: Int = 1)
    fun download(item: SteamWallpaperItem)
    fun cancel()
    fun logout()
    fun close()
}

expect fun createSteamWallpaperClient(context: PlatformContext): SteamWallpaperClient
