package dev.mangaflow.account

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.json.JSONObject
import dev.mangaflow.data.SourceException

internal actual class AccountTokenStore actual constructor(private val context: PlatformContext) {
    actual fun read(): StoredAccountCredential? {
        val text = readSecret(context, KEY) ?: return null
        try {
            require(text.length <= 16384)
            val value = JSONObject(text)
            val channel = AccountChannel.entries.singleOrNull { it.value == value.optString("channel") }
                ?: throw IllegalArgumentException("Unknown account channel")
            return StoredAccountCredential(AccountTokenInput.validate(value.getString("token")), channel)
        } catch (_: Exception) { throw SourceException("保存的登录凭据已失效，请重新登录。") }
    }
    actual fun write(credential: StoredAccountCredential) {
        val text = JSONObject().put("token", AccountTokenInput.validate(credential.token))
            .put("channel", credential.channel.value).toString()
        writeSecret(context, KEY, text)
    }
    actual fun clear() { deleteSecret(context, KEY) }
    private companion object { const val KEY = "copy_account_private" }
}
