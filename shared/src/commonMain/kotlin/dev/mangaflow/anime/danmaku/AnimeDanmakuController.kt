package dev.mangaflow.anime.danmaku

import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.sources.lanerc.LanercSourceStore
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.PRIVATE_STORAGE
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.getSharedPreferences
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Current playback owns every search, selection and rendered frame. No title-to-episode guesswork. */
class AnimeDanmakuController(context: PlatformContext, scope: CoroutineScope, private val extensions: LanercSourceStore) {
    private val application = context.applicationContext
    private val owned = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val repository = DandanplayRepository(application)
    private val preferences = application.getSharedPreferences("anime_danmaku", PRIVATE_STORAGE)
    private val lock = SynchronizedObject()
    private val configurationWriter = Mutex()
    private val settingsWriter = Mutex()
    private val mutableState = MutableStateFlow(AnimeDanmakuState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var searchSequence = 0L
    private var loadSequence = 0L
    private var settingsRevision = 0L
    private var configurationRevision = 0L
    private var viewportRows = 1
    private var viewportRevision = 0L
    private var searchJob: Job? = null
    private var loadJob: Job? = null
    private var rebuildJob: Job? = null
    private var comments: List<AnimeDanmakuComment> = emptyList()
    private var timeline = AnimeDanmakuTimeline(emptyList(), AnimeDanmakuSettings())
    private var closed = false

    val ready: Deferred<Unit> = owned.async(Dispatchers.IO) {
        try {
            val saved = preferences.getString("settings", null)?.let(::JSONObject)
            val settings = saved?.let(::decodeSettings) ?: AnimeDanmakuSettings()
            val configured = repository.configured()
            synchronized(lock) {
                if (!closed) mutableState.value = mutableState.value.copy(
                    settings = if (settingsRevision == 0L) settings else mutableState.value.settings,
                    configured = if (configurationRevision == 0L) configured else mutableState.value.configured,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { synchronized(lock) {
            if (!closed) mutableState.value = mutableState.value.copy(notice = "弹幕设置未能完整读取，可重新配置。")
        } }
    }

    fun setContext(ownerKey: String?) = synchronized(lock) {
        if (closed || mutableState.value.ownerKey == ownerKey) return@synchronized
        generation++; searchSequence++; loadSequence++
        searchJob?.cancel(); loadJob?.cancel(); rebuildJob?.cancel()
        comments = emptyList()
        timeline = AnimeDanmakuTimeline(emptyList(), mutableState.value.settings)
        mutableState.value = AnimeDanmakuState(ownerKey = ownerKey, configured = mutableState.value.configured,
            settings = mutableState.value.settings)
    }

    fun updatePosition(ownerKey: String, positionMs: Long, playing: Boolean) = synchronized(lock) {
        if (closed || ownerKey != mutableState.value.ownerKey) return@synchronized
        val position = positionMs.coerceAtLeast(0L)
        mutableState.value = mutableState.value.copy(positionMs = position, playing = playing,
            visible = timeline.frame(position, mutableState.value.settings))
    }

    fun search(query: String, episode: String = "") {
        val job = synchronized(lock) {
            if (closed) return
            val owner = mutableState.value.ownerKey ?: run {
                mutableState.value = mutableState.value.copy(error = "请先打开要匹配弹幕的剧集。")
                return
            }
            val epoch = generation
            val sequence = ++searchSequence
            searchJob?.cancel()
            mutableState.value = mutableState.value.copy(query = query, searching = true, candidates = emptyList(), error = null, notice = null)
            owned.launch(start = CoroutineStart.LAZY) {
                try {
                    ready.await()
                    val result = withContext(Dispatchers.Default) { repository.search(query, episode) }
                    synchronized(lock) {
                        if (current(owner, epoch) && sequence == searchSequence) mutableState.value = mutableState.value.copy(
                            candidates = result.candidates, searching = false,
                            notice = when { result.hasMore -> "匹配结果较多，请补充名称或集数缩小范围。"; result.candidates.isEmpty() -> "没有找到匹配剧集，可换用别名搜索。"; else -> "请核对番剧与集数后选择弹幕库。" })
                    }
                } catch (failure: Exception) { finishFailure(owner, epoch, sequence, true, failure) }
                finally { synchronized(lock) {
                    if (current(owner, epoch) && sequence == searchSequence) mutableState.value = mutableState.value.copy(searching = false)
                } }
            }.also { searchJob = it }
        }
        job.start()
    }

    fun select(candidate: AnimeDanmakuCandidate) {
        val job = synchronized(lock) {
            if (closed) return
            val owner = mutableState.value.ownerKey ?: return
            if (candidate !in mutableState.value.candidates) {
                mutableState.value = mutableState.value.copy(error = "搜索结果已变化，请重新搜索并选择。")
                return
            }
            searchSequence++; searchJob?.cancel()
            val epoch = generation
            val sequence = ++loadSequence
            loadJob?.cancel(); rebuildJob?.cancel()
            comments = emptyList(); timeline = AnimeDanmakuTimeline(emptyList(), mutableState.value.settings)
            mutableState.value = mutableState.value.copy(selected = candidate,
                selectionLabel = "${candidate.animeTitle} · ${candidate.episodeTitle}", searching = false,
                loading = true, totalCount = 0, visible = emptyList(), error = null, notice = null)
            owned.launch(start = CoroutineStart.LAZY) {
                try {
                    val values = withContext(Dispatchers.Default) { repository.comments(candidate.episodeId) }
                    acceptComments(owner, epoch, sequence, values)
                } catch (failure: Exception) { finishFailure(owner, epoch, sequence, false, failure) }
                finally { synchronized(lock) {
                    if (current(owner, epoch) && sequence == loadSequence) mutableState.value = mutableState.value.copy(loading = false)
                } }
            }.also { loadJob = it }
        }
        job.start()
    }

    /** Only the observed AniCh flag protocol identifies the actual selected station episode. */
    fun loadSource(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode) {
        val job = synchronized(lock) {
            if (closed) return
            val owner = mutableState.value.ownerKey ?: return
            if (catalog.title.sourceId != "anime-lanerc-ani" || episode !in catalog.episodes || episode.playbackData == null) {
                mutableState.value = mutableState.value.copy(error = "该来源尚无可核实的精确弹幕映射，请通过弹幕库搜索选择。")
                return
            }
            val ticket = try { JSONObject(episode.playbackData) } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(error = "剧集标记已失效，请刷新来源。")
                return
            }
            if (ticket.optString("catalogId") != catalog.title.id || ticket.optString("group") != episode.group) {
                mutableState.value = mutableState.value.copy(error = "所选弹幕线路与剧集不一致。")
                return
            }
            val epoch = generation
            val sequence = ++loadSequence
            searchSequence++; searchJob?.cancel(); loadJob?.cancel(); rebuildJob?.cancel()
            comments = emptyList(); timeline = AnimeDanmakuTimeline(emptyList(), mutableState.value.settings)
            mutableState.value = mutableState.value.copy(selected = null, selectionLabel = "AniCh · ${episode.title}",
                searching = false, loading = true, visible = emptyList(), totalCount = 0, error = null, notice = null)
            owned.launch(start = CoroutineStart.LAZY) {
                try {
                    ready.await()
                    val raw = extensions.danmaku(catalog.title.sourceId, JSONObject().put("flag", ticket.getString("flag")).toString(), ticket.getString("revision"))
                    val values = withContext(Dispatchers.Default) { parseLanercComments(raw) }
                    acceptComments(owner, epoch, sequence, values)
                } catch (failure: Exception) { finishFailure(owner, epoch, sequence, false, failure) }
                finally { synchronized(lock) {
                    if (current(owner, epoch) && sequence == loadSequence) mutableState.value = mutableState.value.copy(loading = false)
                } }
            }.also { loadJob = it }
        }
        job.start()
    }

    fun updateViewport(availableRows: Int) {
        synchronized(lock) {
            if (closed || viewportRows == availableRows.coerceAtLeast(1)) return
            viewportRows = availableRows.coerceAtLeast(1)
            viewportRevision++
        }
        rebuild()
    }

    fun updateSettings(value: AnimeDanmakuSettings) {
        val checked = try { value.validate() } catch (_: Exception) {
            synchronized(lock) { mutableState.value = mutableState.value.copy(error = "弹幕设置超出范围，请检查字号、密度和偏移。") }
            return
        }
        val version = synchronized(lock) {
            if (closed) return
            settingsRevision++
            mutableState.value = mutableState.value.copy(settings = checked, error = null,
                visible = if (checked.enabled) mutableState.value.visible else emptyList())
            settingsRevision
        }
        owned.launch(Dispatchers.IO) { settingsWriter.withLock {
            if (synchronized(lock) { closed || settingsRevision != version }) return@withLock
            try { check(preferences.edit().putString("settings", encodeSettings(checked).toString()).commit()) }
            catch (_: Exception) { synchronized(lock) {
                if (!closed && settingsRevision == version) mutableState.value = mutableState.value.copy(notice = "弹幕设置已应用，但本次未能保存。")
            } }
        } }
        rebuild()
    }

    fun configure(appId: String, appSecret: String) = changeConfiguration { repository.configure(appId.trim(), appSecret.trim()); true }
    fun clearConfiguration() = changeConfiguration { repository.clearConfiguration(); false }

    private fun changeConfiguration(change: suspend () -> Boolean) {
        val version = synchronized(lock) {
            if (closed) return
            configurationRevision++; searchSequence++; loadSequence++
            searchJob?.cancel(); loadJob?.cancel()
            mutableState.value = mutableState.value.copy(searching = false, loading = false, error = null)
            configurationRevision
        }
        owned.launch {
            try {
                val configured = configurationWriter.withLock {
                    if (synchronized(lock) { closed || configurationRevision != version }) return@launch
                    change()
                }
                synchronized(lock) { if (!closed && configurationRevision == version)
                    mutableState.value = mutableState.value.copy(configured = configured,
                        notice = if (configured) "弹幕服务配置已保存，可搜索并核对剧集。" else "弹幕服务凭据已移除。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(lock) {
                if (!closed && configurationRevision == version) mutableState.value = mutableState.value.copy(error = "弹幕服务配置未能保存，请检查输入与本地存储。")
            } }
        }
    }

    fun cancelSearch() = synchronized(lock) {
        searchSequence++; searchJob?.cancel()
        mutableState.value = mutableState.value.copy(searching = false)
    }
    fun clearSelection() = synchronized(lock) {
        loadSequence++; loadJob?.cancel(); rebuildJob?.cancel()
        comments = emptyList(); timeline = AnimeDanmakuTimeline(emptyList(), mutableState.value.settings)
        mutableState.value = mutableState.value.copy(selected = null, selectionLabel = null, totalCount = 0,
            visible = emptyList(), loading = false, error = null)
    }
    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true; generation++
        owned.cancel()
        comments = emptyList()
        mutableState.value = mutableState.value.copy(ownerKey = null, visible = emptyList(), searching = false, loading = false)
    }

    private fun acceptComments(owner: String, epoch: Long, sequence: Long, values: List<AnimeDanmakuComment>) {
        synchronized(lock) {
            if (!current(owner, epoch) || sequence != loadSequence) return
            comments = values
            mutableState.value = mutableState.value.copy(totalCount = values.size,
                notice = if (values.isEmpty()) "此弹幕库目前没有弹幕。" else null)
        }
        rebuild()
    }

    private fun rebuild() {
        val job = synchronized(lock) {
            if (closed) return
            val epoch = generation
            val sequence = loadSequence
            val version = settingsRevision
            val viewport = viewportRevision
            val rows = viewportRows
            val values = comments
            val settings = mutableState.value.settings
            rebuildJob?.cancel()
            owned.launch(start = CoroutineStart.LAZY) {
                val result = withContext(Dispatchers.Default) { AnimeDanmakuTimeline(values, settings, rows) }
                synchronized(lock) {
                    if (!closed && generation == epoch && loadSequence == sequence && settingsRevision == version && viewportRevision == viewport) {
                        timeline = result
                        mutableState.value = mutableState.value.copy(visible = result.frame(mutableState.value.positionMs, settings))
                    }
                }
            }.also { rebuildJob = it }
        }
        job.start()
    }

    private fun current(owner: String, epoch: Long) = !closed && generation == epoch && mutableState.value.ownerKey == owner
    private fun finishFailure(owner: String, epoch: Long, sequence: Long, search: Boolean, failure: Exception) {
        if (failure is CancellationException && failure !is TimeoutCancellationException) return
        synchronized(lock) {
            if (current(owner, epoch) && sequence == (if (search) searchSequence else loadSequence)) {
                mutableState.value = mutableState.value.copy(error = when (failure) {
                    is AnimeDanmakuException -> failure.message
                    is TimeoutCancellationException -> "弹幕服务响应超时，可稍后重试。"
                    else -> "弹幕请求或解析未完成，请检查来源、服务配置或网络。"
                }, searching = if (search) false else mutableState.value.searching,
                    loading = if (!search) false else mutableState.value.loading)
            }
        }
    }

    private fun encodeSettings(value: AnimeDanmakuSettings) = JSONObject().put("enabled", value.enabled)
        .put("opacity", value.opacity).put("fontScale", value.fontScale).put("showColored", value.showColored)
        .put("deduplicate", value.deduplicate).put("density", value.density).put("offsetMs", value.offsetMs)
        .put("blockedWords", JSONArray(value.blockedWords))
    private fun decodeSettings(value: JSONObject): AnimeDanmakuSettings {
        val words = value.optJSONArray("blockedWords") ?: JSONArray()
        return AnimeDanmakuSettings(value.optBoolean("enabled", true), value.optDouble("opacity", 0.85).toFloat(),
            value.optDouble("fontScale", 1.0).toFloat(), value.optBoolean("showColored", true), value.optBoolean("deduplicate", true),
            value.optInt("density", 8), value.optLong("offsetMs"), (0 until words.length()).map { words.optString(it) }).validate()
    }
}
