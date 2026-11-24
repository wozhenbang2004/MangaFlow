package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.NATIVE_LMM85_SOURCE
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class SourcePreparationPhase { PENDING, PREPARING, RETRYING, READY, DISABLED, FAILED }
data class SourcePreparationState(
    val phase: SourcePreparationPhase,
    val message: String? = null,
    val canRetry: Boolean = false,
)

/** The packaged manifest is the durable desired state; successful packages and opt-outs are persisted. */
internal class AutomaticAnimeSources(
    context: PlatformContext,
    private val store: LanercSourceStore,
    private val scope: CoroutineScope,
) {
    private val preferences = preferences(context, "anime-source-preparation")
    private val gate = Mutex()
    private val permits = Semaphore(2)
    private val jobs = mutableMapOf<String, Job>()
    private val mutableState = MutableStateFlow(AUTOMATIC_ANIME_SOURCE_IDS.associateWith {
        SourcePreparationState(SourcePreparationPhase.PENDING)
    })
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            store.ready.await()
            store.state.collect {
                gate.withLock {
                    for (id in AUTOMATIC_ANIME_SOURCE_IDS) {
                        if (!publishExisting(id) && jobs[id]?.isActive != true &&
                            mutableState.value[id]?.phase in setOf(SourcePreparationPhase.READY, SourcePreparationPhase.DISABLED)) {
                            publish(id, SourcePreparationPhase.FAILED, "来源包已变更，可重新准备。", canRetry = true)
                        }
                    }
                }
            }
        }
    }

    suspend fun start() = gate.withLock { startLocked(AUTOMATIC_ANIME_SOURCE_IDS) }

    suspend fun retry(ids: Set<String> = AUTOMATIC_ANIME_SOURCE_IDS) = gate.withLock {
        startLocked(ids.filterTo(linkedSetOf()) { id ->
            state.value[id]?.let { it.phase == SourcePreparationPhase.FAILED && it.canRetry } == true
        })
    }

    private fun startLocked(ids: Set<String>) {
        for (id in ids) {
            if (jobs[id]?.isActive == true) continue
            if (publishExisting(id)) continue
            publish(id, SourcePreparationPhase.PENDING)
            jobs[id] = scope.launch {
                // Resume missing manifest entries after process restart. Do not auto-update a saved
                // package or replace its exact revision/settings just because a remote file changed.
                for (attempt in 0..2) {
                    try {
                        permits.withPermit {
                            val prepare = gate.withLock {
                                if (publishExisting(id)) false
                                else {
                                    publish(id, SourcePreparationPhase.PREPARING)
                                    true
                                }
                            }
                            if (!prepare) return@launch
                            store.installAutomatically(id)
                        }
                        gate.withLock {
                            jobs.remove(id)
                            if (!publishExisting(id)) publish(id, SourcePreparationPhase.FAILED,
                                "来源记录已更改，可重新准备。", canRetry = true)
                        }
                        return@launch
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        val stage = (failure as? LanercPackagePreparationException)?.stage
                        val willRetry = stage == LanercPackageStage.DOWNLOAD && attempt < 2
                        val message = failure.message ?: "来源暂未准备完成。"
                        gate.withLock {
                            if (publishExisting(id)) {
                                jobs.remove(id)
                                return@launch
                            }
                            if (willRetry) publish(id, SourcePreparationPhase.RETRYING, "$message 稍后自动重试。")
                            else {
                                jobs.remove(id)
                                publish(id, SourcePreparationPhase.FAILED, message, canRetry = stage != LanercPackageStage.INTEGRITY)
                            }
                        }
                        if (!willRetry) return@launch
                        delay(if (attempt == 0) 15_000L else 60_000L)
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

    private fun publishExisting(id: String): Boolean {
        val listing = store.state.value.firstOrNull { it.id == id }
        if (listing?.installed == true) {
            publish(id, if (listing.enabled) SourcePreparationPhase.READY else SourcePreparationPhase.DISABLED,
                if (id in AUTOMATIC_ANIME_INACTIVE_IDS && !listing.enabled)
                    "来源已准备；当前网络协议尚未适配，未启用。" else null)
            return true
        }
        if (!preferences.getBoolean("removed:$id")) return false
        publish(id, SourcePreparationPhase.DISABLED, "已按你的选择移除，不会自动重装。")
        return true
    }

    private fun publish(id: String, phase: SourcePreparationPhase, message: String? = null, canRetry: Boolean = false) {
        mutableState.update { it + (id to SourcePreparationState(phase, message, canRetry)) }
    }
}

// Each pinned public script is prepared automatically with the bundled Android engine.
// Native alternatives do not require a script download. Removed/disabled choices remain authoritative.
internal val AUTOMATIC_ANIME_SOURCE_IDS = setOf(
    "anime-lanerc-lanerc", "anime-lanerc-guazi", "anime-lanerc-sx",
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
    "anime-lanerc-lmm85" to NATIVE_LMM85_SOURCE,
)
