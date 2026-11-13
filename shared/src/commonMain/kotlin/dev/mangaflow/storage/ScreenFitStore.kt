package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/** Legacy enum values remain readable; new choices are FILL and AVOID_CUTOUT. */
enum class ScreenFitMode { AUTO, FILL, AVOID_CUTOUT, SAFE }

data class ScreenFitState(
    val mode: ScreenFitMode = ScreenFitMode.FILL,
    val marginDp: Float = 0f,
    val cornerDp: Float = 0f,
)

/** Backgrounds fill the window; users only opt into avoiding a cutout for page content. */
class ScreenFitStore private constructor(context: PlatformContext) {
    private val prefs = context.applicationContext.getSharedPreferences("mangaflow_screen_fit", PRIVATE_STORAGE)
    private val lock = StorageLock()
    private val _state = MutableStateFlow(ScreenFitState(
        mode = if (prefs.getString("mode", "FILL") == ScreenFitMode.AVOID_CUTOUT.name)
            ScreenFitMode.AVOID_CUTOUT else ScreenFitMode.FILL,
    ))
    val state = _state.asStateFlow()
    private val _appliedState = MutableStateFlow(_state.value)
    val appliedState = _appliedState.asStateFlow()

    init {
        // Removed micro-adjustments must not leave an invisible, unchangeable screen border.
        prefs.edit().putString("mode", _state.value.mode.name).remove("margin").remove("corner").apply()
    }

    fun setMode(mode: ScreenFitMode) = synchronized(lock) {
        val selected = if (mode == ScreenFitMode.AVOID_CUTOUT) mode else ScreenFitMode.FILL
        _state.value = ScreenFitState(mode = selected)
        prefs.edit().putString("mode", selected.name).apply()
        _appliedState.value = _state.value
    }

    companion object {
        private val instanceLock = StorageLock()
        @Volatile private var instance: ScreenFitStore? = null
        fun get(context: PlatformContext): ScreenFitStore = instance ?: synchronized(instanceLock) {
            instance ?: ScreenFitStore(context).also { instance = it }
        }
    }
}
