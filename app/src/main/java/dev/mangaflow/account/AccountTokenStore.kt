package dev.mangaflow.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.mangaflow.data.SourceException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only an authenticated AES/GCM envelope is written to these app-private preferences. */
internal class AccountTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("copy_account_private", Context.MODE_PRIVATE)
    private val lock = Any()
    private val associatedData = "MangaFlow.CopyAccount.v1".toByteArray(Charsets.UTF_8)

    fun read(): String? = synchronized(lock) {
        try {
            val envelope = preferences.getString(TOKEN, null) ?: return@synchronized null
            if (envelope.length > 8192) invalidSavedSession()
            val parts = envelope.split(':')
            if (parts.size != 3 || parts[0] != "1") invalidSavedSession()
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            if (iv.size != 12 || ciphertext.size !in 24..2064) invalidSavedSession()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: invalidSavedSession(), GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            val plain = cipher.doFinal(ciphertext)
            try { AccountTokenInput.validate(String(plain, Charsets.UTF_8)) }
            finally { plain.fill(0) }
        } catch (error: SourceException) { throw error }
          catch (_: Exception) { invalidSavedSession() }
    }

    fun write(token: String) = synchronized(lock) {
        val plain = AccountTokenInput.validate(token).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            cipher.updateAAD(associatedData)
            val ciphertext = cipher.doFinal(plain)
            val envelope = "1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(ciphertext, Base64.NO_WRAP)}"
            if (!preferences.edit().putString(TOKEN, envelope).commit()) {
                throw SourceException("账号已验证，但设备无法安全保存登录状态，请检查存储空间后重试。")
            }
        } catch (error: SourceException) { throw error }
          catch (_: Exception) { throw SourceException("设备无法安全保存登录凭据，请重试。") }
        finally { plain.fill(0) }
    }

    fun clear() = synchronized(lock) {
        try {
            val removed = preferences.edit().remove(TOKEN).commit()
            // Deleting the key also makes any old preference backup unusable.
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
            if (!removed) throw SourceException("当前账号已退出，但设备未能完成凭据清理，请重试。")
        } catch (error: SourceException) { throw error }
          catch (_: Exception) { throw SourceException("当前账号已退出，但设备未能完成凭据清理，请重试。") }
    }

    private fun existingKey(): SecretKey? = loadKeyStore().getKey(ALIAS, null) as? SecretKey

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private fun invalidSavedSession(): Nothing = throw SourceException("保存的登录凭据已失效，请重新登录。")

    private companion object {
        const val TOKEN = "encrypted_token"
        const val ALIAS = "dev.mangaflow.copy.account.v1"
    }
}
