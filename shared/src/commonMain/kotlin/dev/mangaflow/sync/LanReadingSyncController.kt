package dev.mangaflow.sync

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.LibraryStore
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class LanReadingChoice(val id: String, val device: String, val position: String, val selected: Boolean)
internal data class LanReadingConflict(val key: String, val title: String, val kind: String, val choices: List<LanReadingChoice>)
internal data class LanReadingSyncState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val foreground: Boolean = false,
    val active: Boolean = false,
    val devices: List<LanReadingDevice> = emptyList(),
    val pairingOpen: Boolean = false,
    val pairing: LanReadingPairing? = null,
    val conflicts: List<LanReadingConflict> = emptyList(),
    val busy: Boolean = false,
    val lastSyncAt: Long = 0L,
    val error: String? = null,
)

/** One application owner; dismissing its sheet never revokes an established pairing. */
internal class LanReadingSyncController(
    context: PlatformContext,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    private val ordinarySourceIds: () -> Set<String>,
) {
    private val application = applicationContext(context)
    private val disk = preferences(application, "mangaflow_lan_reading")
    private val _state = MutableStateFlow(LanReadingSyncState(enabled = disk.getBoolean("enabled", false)))
    val state: StateFlow<LanReadingSyncState> = _state.asStateFlow()
    private val generation = atomic(0L)
    private val lifecycle = Mutex()
    private val transactions = Mutex()
    private val ready = atomic(false)
    private var ownId = ""
    private val transport = createLanReadingTransport(application, scope, object : LanReadingTransportListener {
        override fun devices(value: List<LanReadingDevice>) { _state.update { it.copy(devices = value) } }
        override fun pairing(value: LanReadingPairing?) { _state.update { it.copy(pairing = value) } }
        override fun pairingOpen(value: Boolean) { _state.update { it.copy(pairingOpen = value) } }
        override fun activity(deviceId: String?, busy: Boolean, error: String?) {
            _state.update { it.copy(busy = busy, error = error ?: it.error) }
        }
        override fun synchronized(deviceId: String) {
            _state.update { it.copy(lastSyncAt = wallTimeMillis(), error = null) }
        }
        override suspend fun document(deviceId: String) = transact(deviceId, null)
        override suspend fun merge(deviceId: String, payload: String) = transact(deviceId, payload)
    })

    init {
        _state.update { it.copy(supported = transport.supported) }
        scope.launch(Dispatchers.IO) {
            try {
                if (transport.supported) ownId = transport.identity()
                ready.value = true
                updateLifecycle()
                if (transport.supported) {
                    val sources = currentSources()
                    library.captureLanReading(sources).ledger?.let {
                        publishConflicts(LanReadingCodec.decode(it, ownId, local = true), currentSources())
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
        scope.launch {
            var observed = library.lanReadingRevision.value
            library.lanReadingRevision.collect { revision ->
                if (revision != observed) {
                    observed = revision
                    delay(600L)
                    if (_state.value.active) transport.synchronize()
                }
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (_state.value.foreground == value) return
        generation.incrementAndGet()
        _state.update { it.copy(foreground = value) }
        scheduleLifecycle()
    }

    fun setEnabled(value: Boolean) {
        if (_state.value.enabled == value) return
        generation.incrementAndGet()
        _state.update { it.copy(enabled = value, error = null) }
        if (!value) transport.closePairing()
        scope.launch(Dispatchers.IO) {
            try {
                updateLifecycle(persistEnabled = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun refreshSourceScope() {
        generation.incrementAndGet()
        if (_state.value.active) transport.synchronize()
    }

    fun beginPairing() {
        if (canPair()) { _state.update { it.copy(error = null) }; transport.openPairing() }
    }
    fun closePairing() = transport.closePairing()
    fun pair(deviceId: String) {
        _state.update { it.copy(error = null) }
        if (canPair()) transport.pair(deviceId)
    }
    fun confirmPairing(session: String) { if (canPair()) transport.confirm(session) }
    fun rejectPairing(session: String) = transport.reject(session)
    private fun canPair() = _state.value.let { it.active && it.enabled && it.foreground }

    fun syncNow() {
        _state.update { it.copy(error = null) }
        scope.launch(Dispatchers.IO) {
            try {
                if (!ready.value && transport.supported) { ownId = transport.identity(); ready.value = true }
                updateLifecycle()
                if (_state.value.active) transport.synchronize(force = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun forgetDevice(deviceId: String) {
        generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try { transport.forget(deviceId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    fun chooseConflict(key: String, version: String) {
        scope.launch(Dispatchers.IO) {
            try {
                transact(null, null, key to version)
                if (_state.value.active) transport.synchronize()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    private fun scheduleLifecycle() {
        scope.launch(Dispatchers.IO) {
            try { updateLifecycle() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure) }
        }
    }

    private suspend fun updateLifecycle(persistEnabled: Boolean = false) = lifecycle.withLock {
        val shouldRun = ready.value && transport.supported && _state.value.enabled && _state.value.foreground
        if (!shouldRun) {
            transport.stop()
            _state.update { it.copy(active = false, busy = false, pairing = null, pairingOpen = false) }
        } else if (!_state.value.active) {
            transport.start()
            // A foreground or enabled change may arrive while platform discovery is starting.
            if (_state.value.enabled && _state.value.foreground) {
                _state.update { it.copy(active = true, error = null) }
                transport.synchronize()
            } else {
                transport.stop()
                _state.update { it.copy(active = false, busy = false, pairing = null, pairingOpen = false) }
            }
        }
        // Stop/start follows the latest in-memory user intent, even when preferences cannot commit.
        // Keeping persistence inside this owner also orders quick off/on changes consistently.
        if (persistEnabled) check(disk.edit().putBoolean("enabled", _state.value.enabled).commit()) {
            "无法保存局域网同步设置；当前开关已生效，重启后可能恢复旧设置。"
        }
    }

    private fun currentSources() = ordinarySourceIds().toSet() - "local"

    private suspend fun transact(deviceId: String?, payload: String?, selection: Pair<String, String>? = null): LanReadingDocument =
        transactions.withLock {
            val ticket = generation.value
            val sources = currentSources()
            fun current(): Boolean = generation.value == ticket && sources == currentSources() &&
                ready.value && (deviceId == null || _state.value.enabled && _state.value.foreground && transport.isTrusted(deviceId))
            check(current()) { "同步范围或设备授权已改变。" }
            val remote = payload?.let { LanReadingCodec.decode(it, ownId, local = false) }
            repeat(4) {
                check(current()) { "同步范围或设备授权已改变。" }
                val snapshot = library.captureLanReading(sources)
                var ledger = snapshot.ledger?.let { LanReadingCodec.decode(it, ownId, local = true) }
                    ?: LanReadingLedger(ownId)
                ledger = ledger.capture(snapshot, sources, wallTimeMillis())
                if (remote != null) ledger = ledger.merge(remote, sources)
                if (selection != null) {
                    val record = ledger.records[selection.first] ?: error("这条冲突已变化，请重新查看。")
                    check(record.allowed(ledger.books, sources)) { "此作品不在普通漫画同步范围。" }
                    ledger = ledger.choose(selection.first, selection.second, wallTimeMillis())
                }
                val projection = ledger.projection(sources)
                ledger = ledger.copy(baseline = ledger.baseline.filterValues { !it.allowed(ledger.books, sources) } + projection)
                val saved = LanReadingCodec.encode(ledger, local = true)
                if (library.applyLanReading(snapshot, ledger.books, projection, saved, sources, ::current)) {
                    check(current()) { "同步范围或设备授权已改变。" }
                    publishConflicts(ledger, sources)
                    return@withLock LanReadingDocument(LanReadingCodec.encode(ledger, local = false, sources = sources), ::current)
                }
            }
            error("本机阅读位置正在更新，将在下一次连接时自动重试。")
        }

    private fun publishConflicts(ledger: LanReadingLedger, sources: Set<String>) {
        val names = _state.value.devices.associate { it.id to it.name }
        val conflicts = ledger.records.values.filter { it.allowed(ledger.books, sources) && it.conflicting() }
            .map { record ->
                val winner = record.winner().id
                LanReadingConflict(record.key, ledger.books.getValue(record.identity.mangaId).title,
                    kindName(record.identity.kind), record.versions.map { version ->
                        LanReadingChoice(version.id, if (version.actor == ownId) "本机" else names[version.actor] ?: "另一台设备",
                            positionName(version.item), version.id == winner)
                    })
            }.sortedBy { it.title }
        _state.update { it.copy(conflicts = conflicts) }
    }

    private fun fail(failure: Exception) { _state.update { it.copy(error = failure.message ?: "局域网同步暂不可用。", busy = false) } }
}

private fun kindName(kind: LanReadingKind): String = when (kind) {
    LanReadingKind.FAVORITE -> "收藏"
    LanReadingKind.CHAPTER -> "章节位置"
    LanReadingKind.RECENT -> "继续阅读"
    LanReadingKind.BOOKMARK -> "页码书签"
    LanReadingKind.PICTURE -> "图片收藏"
}

private fun positionName(value: LanReadingItem?): String = when {
    value == null -> "已删除"
    value.kind == LanReadingKind.FAVORITE -> "保留收藏"
    value.kind == LanReadingKind.BOOKMARK -> "第 ${value.page + 1} 页" + value.note.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
    value.kind == LanReadingKind.PICTURE -> value.chapterTitle + " · 第 ${value.page + 1}" +
        (if (value.lastPage > value.page) "–${value.lastPage + 1}" else "") + " 页"
    else -> value.chapterTitle + " · 第 ${value.page + 1}/${value.pages} 页" +
        (if (value.offset > 0) " · 页内 ${value.offset}px" else "")
}
