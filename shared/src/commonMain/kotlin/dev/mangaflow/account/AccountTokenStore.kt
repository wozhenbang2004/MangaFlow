package dev.mangaflow.account

import dev.mangaflow.platform.PlatformContext

internal enum class AccountChannel(val value: String) { WEB("web"), APP("app"), WEB_API("web_api"), WEBSITE("website"), RELAY_WEB("relay_web") }
internal class StoredAccountCredential(val token: String, val channel: AccountChannel)

/** Every platform stores authenticated credentials through its native protected storage. */
internal expect class AccountTokenStore(context: PlatformContext) {
    fun read(): StoredAccountCredential?
    fun write(credential: StoredAccountCredential)
    fun clear()
}

object CopyWebsiteEndpoints {
    const val ORIGIN = "https://www.copy4000.com"
    const val LOGIN_PAGE = "$ORIGIN/web/login/loginByAccount?url=person%2Fhome"
}
