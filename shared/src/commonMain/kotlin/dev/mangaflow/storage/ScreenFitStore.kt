package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

enum class ScreenFitMode { AUTO, FILL, AVOID_CUTOUT, SAFE }

data class ScreenFitState(
    val mode: ScreenFitMode = ScreenFitMode.AUTO,
    val marginDp: Float = 0f,
    val cornerDp: Float = 0f,
)

/** Device-local display choices; defaults never crop or add an authored screen border. */
class ScreenFitStore private constructor(context: PlatformContext) {
    private val prefs = context.applicationContext.getSharedPreferences("mangaflow_screen_fit", PRIVATE_STORAGE)
    private val lock = StorageLock()
    private val _state = MutableStateFlow(ScreenFitState(
        mode = runCatching { ScreenFitMode.valueOf(prefs.getString("mode", "AUTO").orEmpty()) }
            .getOrDefault(ScreenFitMode.AUTO),
        marginDp = bounded(prefs.getFloat("margin", 0f), 32f),
        cornerDp = bounded(prefs.getFloat("corner", 0f), 48f),
    ))
    val state = _state.asStateFlow()
    private val _appliedState = MutableStateFlow(_state.value)
    val appliedState = _appliedState.asStateFlow()

    fun setMode(mode: ScreenFitMode) = synchronized(lock) {
        _state.value = _state.value.copy(mode = mode)
        persistLocked()
    }

    fun setMargin(value: Float, persist: Boolean = true) = synchronized(lock) {
        _state.value = _state.value.copy(marginDp = bounded(value, 32f))
        if (persist) persistLocked()
    }

    fun setCorner(value: Float, persist: Boolean = true) = synchronized(lock) {
        _state.value = _state.value.copy(cornerDp = bounded(value, 48f))
        if (persist) persistLocked()
    }

    fun commit() = synchronized(lock) { persistLocked() }

    fun reset() = synchronized(lock) {
        _state.value = ScreenFitState()
        persistLocked()
    }

    private fun persistLocked() {
        val value = _state.value
        prefs.edit().putString("mode", value.mode.name)
            .putFloat("margin", value.marginDp).putFloat("corner", value.cornerDp).apply()
        _appliedState.value = value
    }

    companion object {
        private fun bounded(value: Float, maximum: Float): Float =
            value.takeIf(Float::isFinite)?.coerceIn(0f, maximum) ?: 0f
        private val instanceLock = StorageLock()
        @Volatile private var instance: ScreenFitStore? = null
        fun get(context: PlatformContext): ScreenFitStore = instance ?: synchronized(instanceLock) {
            instance ?: ScreenFitStore(context).also { instance = it }
        }
    }
}
