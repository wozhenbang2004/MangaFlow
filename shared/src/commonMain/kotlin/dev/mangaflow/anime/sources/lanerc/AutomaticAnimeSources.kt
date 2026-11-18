package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SourcePreparationPhase { PENDING, PREPARING, RETRYING, READY, DISABLED, FAILED }
data class SourcePreparationState(val phase: SourcePreparationPhase, val message: String? = null)

/** The packaged manifest is the durable desired state; successful packages and opt-outs are persisted. */
internal class AutomaticAnimeSources(
    context: PlatformContext,
    private val store: LanercSourceStore,
    private val scope: CoroutineScope,
) {
    private val preferences = preferences(context, "anime-source-preparation")
    private val gate = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow(AUTOMATIC_ANIME_SOURCE_IDS.associateWith {
        SourcePreparationState(SourcePreparationPhase.PENDING)
    })
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            store.ready.await()
            store.state.collect { listings ->
                for (id in AUTOMATIC_ANIME_SOURCE_IDS) {
                    val listing = listings.firstOrNull { it.id == id }
                    if (listing?.installed == true) {
                        publish(id, if (listing.enabled) SourcePreparationPhase.READY else SourcePreparationPhase.DISABLED,
                            if (id in AUTOMATIC_ANIME_INACTIVE_IDS && !listing.enabled)
                                "来源已准备；当前网络协议尚未适配，未启用。" else null)
                    } else if (preferences.getBoolean("removed:$id")) {
                        publish(id, SourcePreparationPhase.DISABLED, "已按你的选择移除，不会自动重装。")
                    }
                }
            }
        }
    }

    suspend fun start() = gate.withLock { startLocked(AUTOMATIC_ANIME_SOURCE_IDS) }

    private fun startLocked(ids: Set<String>) {
        for (id in ids) {
            if (jobs[id]?.isActive == true) continue
            val listing = store.state.value.firstOrNull { it.id == id }
            if (listing?.installed == true) {
                publish(id, if (listing.enabled) SourcePreparationPhase.READY else SourcePreparationPhase.DISABLED)
                continue
            }
            if (preferences.getBoolean("removed:$id")) {
                publish(id, SourcePreparationPhase.DISABLED, "已按你的选择移除，不会自动重装。")
                continue
            }
            publish(id, SourcePreparationPhase.PENDING)
            jobs[id] = scope.launch {
                // Resume missing manifest entries after process restart. Do not auto-update a saved
                // package or replace its exact revision/settings just because a remote file changed.
                for (attempt in 0..2) {
                    if (attempt > 0) {
                        publish(id, SourcePreparationPhase.RETRYING, "准备未完成，稍后自动重试。")
                        delay(if (attempt == 1) 15_000L else 60_000L)
                    }
                    publish(id, SourcePreparationPhase.PREPARING)
                    try {
                        store.installAutomatically(id)
                        val installed = requireNotNull(store.state.value.firstOrNull { it.id == id })
                        check(installed.installed) { "来源未能保存。" }
                        publish(id, if (installed.enabled) SourcePreparationPhase.READY else SourcePreparationPhase.DISABLED,
                            if (id in AUTOMATIC_ANIME_INACTIVE_IDS) "来源已准备；当前网络协议尚未适配，未启用。" else null)
                        return@launch
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        publish(id, SourcePreparationPhase.FAILED, failure.message ?: "来源暂未准备完成。")
                        // Retrying identical incompatible bytes cannot fix the engine/host contract.
                        if (failure is LanercCapabilityException || attempt == 2) return@launch
                    }
                }
            }
        }
    }

    suspend fun restore(id: String) = gate.withLock {
        require(id in AUTOMATIC_ANIME_SOURCE_IDS) { "此来源不属于自动准备目录。" }
        check(preferences.edit().remove("removed:$id").commit()) { "无法保存来源恢复选择。" }
        // Persist and schedule under the same gate. A later removal can cancel this exact job;
        // global retry still respects all other tombstones and disabled installed packages.
        startLocked(setOf(id))
    }

    suspend fun suppress(id: String) {
        if (id !in AUTOMATIC_ANIME_SOURCE_IDS) return
        gate.withLock {
            check(preferences.edit().putBoolean("removed:$id", true).commit()) { "无法保存来源移除偏好。" }
            jobs.remove(id)?.cancelAndJoin()
            publish(id, SourcePreparationPhase.DISABLED, "已按你的选择移除，不会自动重装。")
        }
    }

    private fun publish(id: String, phase: SourcePreparationPhase, message: String? = null) {
        mutableState.update { it + (id to SourcePreparationState(phase, message)) }
    }
}

// Each pinned public script is prepared automatically with the bundled Android engine.
// Native alternatives do not require a script download. Removed/disabled choices remain authoritative.
internal val AUTOMATIC_ANIME_SOURCE_IDS = setOf(
    "anime-lanerc-lanerc", "anime-lanerc-guazi", "anime-lanerc-sx", "anime-lanerc-lmm85",
    "anime-lanerc-gugu", "anime-lanerc-manguo", "anime-lanerc-23", "anime-lanerc-ain",
    "anime-lanerc-nmeng", "anime-lanerc-sd", "anime-lanerc-07",
    "anime-lanerc-douban", "anime-lanerc-danmu_b61mt3",
)
// Existing disabled packages are never silently re-enabled by a later compatibility change.
internal val AUTOMATIC_ANIME_INACTIVE_IDS = emptySet<String>()

internal val ANIME_BUNDLED_ALTERNATIVES = mapOf(
    "anime-lanerc-ani" to "anime-native-ani",
    "anime-lanerc-akianime" to "anime-native-akianime",
    "anime-lanerc-dmbus" to "anime-native-dmbus",
    "anime-lanerc-jinpai" to "anime-native-jinpai",
    "anime-lanerc-yzx" to "anime-native-yzx",
)
