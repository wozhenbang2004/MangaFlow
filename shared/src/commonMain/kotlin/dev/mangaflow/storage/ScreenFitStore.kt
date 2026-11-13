package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.Volatile

/** Legacy enum values remain readable; all current content uses FILL. */
enum class ScreenFitMode { AUTO, FILL, AVOID_CUTOUT, SAFE }

data class ScreenFitState(
    val mode: ScreenFitMode = ScreenFitMode.FILL,
    val marginDp: Float = 0f,
    val cornerDp: Float = 0f,
)

/** Content fills the window; interactive controls keep their own system-bar safety. */
class ScreenFitStore private constructor(context: PlatformContext) {
    private val prefs = context.applicationContext.getSharedPreferences("mangaflow_screen_fit", PRIVATE_STORAGE)
    private val lock = StorageLock()
    private val _state = MutableStateFlow(ScreenFitState())
    val state = _state.asStateFlow()
    private val _appliedState = MutableStateFlow(_state.value)
    val appliedState = _appliedState.asStateFlow()

    init {
        // Removed viewport controls must not leave a saved cutout gap or hidden screen border.
        prefs.edit().putString("mode", ScreenFitMode.FILL.name).remove("margin").remove("corner").apply()
    }

    fun setMode(mode: ScreenFitMode) = synchronized(lock) {
        val selected = when (mode) {
            ScreenFitMode.FILL -> mode
            else -> ScreenFitMode.FILL
        }
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
