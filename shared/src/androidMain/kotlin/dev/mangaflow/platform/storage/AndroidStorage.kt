package dev.mangaflow.platform.storage

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.mangaflow.platform.PlatformContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun dataDirectory(context: PlatformContext): StorageFile = StorageFile(context.filesDir.absolutePath)
actual fun cacheDirectory(context: PlatformContext): StorageFile = StorageFile(context.cacheDir.absolutePath)
actual fun availableSpace(file: StorageFile): Long = java.io.File(file.path).usableSpace
actual fun syncStorageFile(file: StorageFile) { java.io.FileOutputStream(file.path, true).use { it.fd.sync() } }
actual fun preferences(context: PlatformContext, name: String): Preferences = AndroidPreferences(context.getSharedPreferences(name, 0))

private class AndroidPreferences(private val native: SharedPreferences) : Preferences {
    private val listeners = mutableMapOf<PreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
    override val all: Map<String, Any?> get() = native.all
    override fun contains(key: String) = native.contains(key)
    override fun getString(key: String, fallback: String?) = native.getString(key, fallback)
    override fun getBoolean(key: String, fallback: Boolean) = native.getBoolean(key, fallback)
    override fun getInt(key: String, fallback: Int) = native.getInt(key, fallback)
    override fun getLong(key: String, fallback: Long) = native.getLong(key, fallback)
    override fun getFloat(key: String, fallback: Float) = native.getFloat(key, fallback)
    override fun getStringSet(key: String, fallback: Set<String>?): Set<String>? = native.getStringSet(key, fallback)?.toSet()
    override fun edit(): PreferencesEditor = Editor(native.edit())
    override fun registerOnSharedPreferenceChangeListener(listener: PreferenceChangeListener) {
        val actual = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> listener.onChanged(this, key) }
        synchronized(listeners) { listeners[listener] = actual }
        native.registerOnSharedPreferenceChangeListener(actual)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: PreferenceChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }?.let(native::unregisterOnSharedPreferenceChangeListener)
    }
    private class Editor(private val native: SharedPreferences.Editor) : PreferencesEditor {
        override fun putString(key: String, value: String?) = apply { native.putString(key, value) }
        override fun putBoolean(key: String, value: Boolean) = apply { native.putBoolean(key, value) }
        override fun putInt(key: String, value: Int) = apply { native.putInt(key, value) }
        override fun putLong(key: String, value: Long) = apply { native.putLong(key, value) }
        override fun putFloat(key: String, value: Float) = apply { native.putFloat(key, value) }
        override fun putStringSet(key: String, value: Set<String>?) = apply { native.putStringSet(key, value) }
        override fun remove(key: String) = apply { native.remove(key) }
        override fun clear() = apply { native.clear() }
        override fun commit() = native.commit()
        override fun apply() = native.apply()
    }
}

private fun keystoreKey(alias: String, create: Boolean): SecretKey? {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    if (!create) return null
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true).build())
    return generator.generateKey()
}

actual fun readSecret(context: PlatformContext, key: String): String? {
    val disk = context.getSharedPreferences("mangaflow_secure_values", 0)
    var iv = disk.getString("$key.iv", null)
    var value = disk.getString("$key.value", null)
    var alias = "mangaflow.secret.$key.v1"
    if (value == null && key == "webdav.password") {
        val legacy = context.getSharedPreferences("mangaflow_webdav_backup", 0)
        iv = legacy.getString("secret_iv", null)
        value = legacy.getString("secret_value", null)
        alias = "mangaflow.webdav.password.v1"
    }
    if (iv == null && value == null) return null
    check(iv != null && value != null) { "已保存的安全凭据不完整，请重新输入。" }
    val secret = keystoreKey(alias, create = false) ?: error("设备密钥不可用，请重新输入凭据。")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
    return cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
}

actual fun writeSecret(context: PlatformContext, key: String, value: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, keystoreKey("mangaflow.secret.$key.v1", create = true))
    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    check(context.getSharedPreferences("mangaflow_secure_values", 0).edit()
        .putString("$key.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        .putString("$key.value", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
}

actual fun deleteSecret(context: PlatformContext, key: String) {
    check(context.getSharedPreferences("mangaflow_secure_values", 0).edit().remove("$key.iv").remove("$key.value").commit())
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val alias = "mangaflow.secret.$key.v1"
    if (store.containsAlias(alias)) store.deleteEntry(alias)
    if (key == "webdav.password" && store.containsAlias("mangaflow.webdav.password.v1")) store.deleteEntry("mangaflow.webdav.password.v1")
}
