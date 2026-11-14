package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot
import kotlinx.coroutines.flow.StateFlow

enum class SteamWallpaperKind { IMAGE, VIDEO }

enum class SteamWallpaperLibrary { SEARCH, FAVORITES, SUBSCRIPTIONS }

enum class SteamWallpaperTransferPhase { NONE, WAITING, CONNECTING, RESOLVING, DOWNLOADING, VERIFYING, IMPORTING }

enum class SteamGuardMethod { EMAIL_CODE, AUTHENTICATOR_CODE, MOBILE_CONFIRMATION, EMAIL_CONFIRMATION }

data class SteamWallpaperAccount(
    val accountName: String,
    val steamId: String,
    val personaName: String? = null,
    val avatarUrl: String? = null,
)

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
    /** A vault credential is available locally; server validation is still pending. */
    val canResumeSession: Boolean = false,
    val profileError: String? = null,
    val profileLoading: Boolean = false,
    val authenticating: Boolean = false,
    val authError: String? = null,
    val qrUrl: String? = null,
    val guardMethods: List<SteamGuardMethod> = emptyList(),
    val guardSubmitting: Boolean = false,
    val guardError: String? = null,
    val loading: Boolean = false,
    val browseError: String? = null,
    val library: SteamWallpaperLibrary = SteamWallpaperLibrary.SEARCH,
    val query: String = "",
    val kind: SteamWallpaperKind = SteamWallpaperKind.VIDEO,
    val page: Int = 1,
    val hasMore: Boolean = false,
    val items: List<SteamWallpaperItem> = emptyList(),
    val transferId: String? = null,
    val transferPhase: SteamWallpaperTransferPhase = SteamWallpaperTransferPhase.NONE,
    val transferProgressKnown: Boolean = false,
    val transferError: String? = null,
    val transferName: String? = null,
    /** The immutable effective route of this transfer, including a retained failed attempt. */
    val transferRoute: DownloadRouteSnapshot? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val importedCount: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

/** The retained client owns distinct authentication, browsing, transfer and profile lifetimes. */
interface SteamWallpaperClient {
    val state: StateFlow<SteamWallpaperState>
    fun login()
    /** Takes ownership of the transient password buffer; implementations must clear it. */
    fun loginWithPassword(accountName: String, password: CharArray)
    fun submitGuardCode(code: String, method: SteamGuardMethod)
    fun search(query: String, kind: SteamWallpaperKind, page: Int = 1)
    fun loadLibrary(library: SteamWallpaperLibrary, kind: SteamWallpaperKind, page: Int = 1)
    fun refreshProfile()
    fun download(item: SteamWallpaperItem, route: DownloadRouteSnapshot? = null)
    fun cancelAuthentication()
    fun cancelTransfer()
    fun cancelBrowse()
    fun setForeground(foreground: Boolean)
    fun cancel()
    fun logout()
    fun close()
}

expect fun createSteamWallpaperClient(context: PlatformContext): SteamWallpaperClient
