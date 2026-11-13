package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DetailAppearance(val blur: Float = 12f, val strength: Float = .60f)

/** Only the local cover atmosphere changes; book content and original images are untouched. */
class DetailAppearanceStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mangaflow_detail_appearance", PRIVATE_STORAGE)
    private val lock = StorageLock()
    private val mutableState = MutableStateFlow(DetailAppearance(
        prefs.getFloat("blur", 12f).takeIf(Float::isFinite)?.coerceIn(0f, 24f) ?: 12f,
        prefs.getFloat("strength", .60f).takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .60f,
    ))
    val state = mutableState.asStateFlow()

    fun setBlur(value: Float) = synchronized(lock) {
        mutableState.value = mutableState.value.copy(blur = value.takeIf(Float::isFinite)?.coerceIn(0f, 24f) ?: 12f)
    }
    fun setStrength(value: Float) = synchronized(lock) {
        mutableState.value = mutableState.value.copy(strength = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .60f)
    }
    fun save() = synchronized(lock) {
        val value = mutableState.value
        prefs.edit().putFloat("blur", value.blur).putFloat("strength", value.strength).apply()
    }
    fun reset() = synchronized(lock) {
        mutableState.value = DetailAppearance()
        prefs.edit().putFloat("blur", 12f).putFloat("strength", .60f).apply()
    }

    companion object {
        private val instanceLock = StorageLock()
        @Volatile private var instance: DetailAppearanceStore? = null
        fun get(context: Context): DetailAppearanceStore = instance ?: synchronized(instanceLock) {
            instance ?: DetailAppearanceStore(context).also { instance = it }
        }
    }
}
