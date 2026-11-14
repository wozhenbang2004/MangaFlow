package dev.mangaflow.storage

import androidx.compose.ui.text.font.FontFamily
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.Request
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.*
import dev.mangaflow.typography.MangaFontAsset
import dev.mangaflow.typography.MangaFontCatalog
import dev.mangaflow.typography.MangaFontFile
import dev.mangaflow.typography.MangaFontOption
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.typography.loadMangaFontFamily
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import kotlin.concurrent.Volatile

internal data class FontTransfer(
    val received: Long = 0,
    val total: Long = 0,
    val active: Boolean = false,
    val error: String? = null,
)

internal data class FontStoreState(
    val selections: Map<MangaFontScene, String>,
    val installed: Set<String> = emptySet(),
    val transfers: Map<String, FontTransfer> = emptyMap(),
    val pendingSelections: Map<MangaFontScene, String> = emptyMap(),
    val revision: Int = 0,
    val loading: Boolean = true,
)

/** The IO command owner publishes files, preferences and selections in one ordered chain. */
internal class FontStore private constructor(context: PlatformContext) {
    private val application = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(FontStoreState(
        selections = MangaFontScene.entries.associateWith(MangaFontCatalog::defaultId),
    ))
    val state = _state.asStateFlow()
    @Volatile private var cachedFamilies: Map<String, FontFamily> = emptyMap()
    @Volatile private var cachedFiles: Map<String, List<MangaFontFile>> = emptyMap()
    private lateinit var root: StorageFile
    private lateinit var prefs: Preferences
    private var storageReady = false
    private var nextAttempt = 0L
    private val attempts = mutableMapOf<String, Attempt>()

    init {
        scope.launch {
            initialize()
            for (command in commands) {
                try {
                    when (command) {
                        is Command.Select -> selectNow(command.scene, command.id)
                        is Command.Download -> downloadNow(command.id, command.scene)
                        is Command.Cancel -> cancelNow(command.id)
                        is Command.Remove -> removeNow(command.id)
                        is Command.Progress -> progressNow(command)
                        is Command.Ready -> readyNow(command)
                        is Command.Failed -> failedNow(command)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    val id = command.id
                    if (command is Command.Ready) command.published.complete(Unit)
                    putTransfer(id, (_state.value.transfers[id] ?: FontTransfer()).copy(
                        active = false, error = "字体操作未完成，请检查存储空间后重试。",
                    ))
                }
            }
        }
    }

    /** Calls never read or parse a font on the UI thread. */
    fun family(id: String): FontFamily? = cachedFamilies[id]
    fun files(id: String): List<MangaFontFile> = cachedFiles[id].orEmpty()

    fun select(scene: MangaFontScene, id: String) {
        commands.trySend(Command.Select(scene, id))
    }

    fun download(id: String, scene: MangaFontScene? = null) {
        commands.trySend(Command.Download(id, scene))
    }

    fun cancel(id: String) { commands.trySend(Command.Cancel(id)) }
    fun remove(id: String) { commands.trySend(Command.Remove(id)) }

    private suspend fun initialize() {
        try {
            root = StorageFile(application.filesDir, "optional-fonts")
            check(root.isDirectory || root.mkdirs())
            prefs = application.getSharedPreferences("mangaflow_fonts", PRIVATE_STORAGE)
            storageReady = true
            // Only this store owns unfinished font transfers.
            StorageFile(root, ".pending").deleteRecursively()
            val savedSelections = MangaFontScene.entries.associateWith { scene ->
                prefs.getString(preferenceKey(scene), MangaFontCatalog.defaultId(scene))
                    ?: MangaFontCatalog.defaultId(scene)
            }
            val selections = savedSelections.mapValues { (scene, id) ->
                id.takeIf { MangaFontCatalog.find(it)?.scenes?.contains(scene) == true }
                    ?: MangaFontCatalog.defaultId(scene)
            }
            _state.value = _state.value.copy(selections = selections)
            val normalized = savedSelections == selections || saveSelections(selections)
            if (!normalized) {
                putTransfer("system", FontTransfer(
                    error = "字体设置迁移未能保存，当前暂用系统字体，请检查存储空间。",
                ))
            } else {
                // Retired choices are app-owned, reproducible optional assets, never user files.
                MangaFontCatalog.retiredIds.forEach { id ->
                    StorageFile(root, id).let { directory ->
                        if (directory.exists()) runCatching { directory.deleteRecursively() }
                    }
                }
            }
            for (option in MangaFontCatalog.options.filter { it.assets.isNotEmpty() }) {
                val paths = option.assets.map { asset -> fontFile(option, asset) }
                if (paths.none { it.exists() }) continue
                try {
                    option.assets.zip(paths).forEach { (asset, file) -> verifyAsset(file, asset) }
                    val fonts = paths.zip(option.assets).map { (file, asset) ->
                        MangaFontFile(file.absolutePath, asset.weight)
                    }
                    val family = loadMangaFontFamily(identity(option), fonts)
                    cachedFiles = cachedFiles + (option.id to fonts)
                    cachedFamilies = cachedFamilies + (option.id to family)
                    _state.value = _state.value.copy(
                        installed = _state.value.installed + option.id,
                        revision = _state.value.revision + 1,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    putTransfer(option.id, FontTransfer(
                        total = option.byteCount, error = "本地字库不完整，暂用系统字体，请重新下载。",
                    ))
                }
            }
            selections.values.distinct().forEach { id ->
                val option = MangaFontCatalog.find(id) ?: return@forEach
                if (option.assets.isNotEmpty() && id !in _state.value.installed &&
                    _state.value.transfers[id]?.error == null) {
                    putTransfer(id, FontTransfer(
                        total = option.byteCount, error = "字库未下载或已被清理，暂用系统字体。",
                    ))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storageReady = false
            putTransfer("system", FontTransfer(error = "无法读取字体设置，当前使用系统字体。"))
        } finally {
            _state.value = _state.value.copy(loading = false)
        }
    }

    private fun optionFor(scene: MangaFontScene, id: String): MangaFontOption? =
        MangaFontCatalog.find(id)?.takeIf { scene in it.scenes }

    private fun selectNow(scene: MangaFontScene, id: String) {
        val option = optionFor(scene, id) ?: return
        // A later explicit choice supersedes any earlier download for this scene.
        _state.value = _state.value.copy(pendingSelections = _state.value.pendingSelections - scene)
        if (!storageReady) {
            putTransfer(id, FontTransfer(error = "字体设置暂时无法保存，请检查存储空间。"))
            return
        }
        if (option.assets.isNotEmpty() && id !in _state.value.installed) {
            putTransfer(id, (_state.value.transfers[id] ?: FontTransfer(total = option.byteCount)).copy(
                error = "请先下载完整字库。",
            ))
            return
        }
        val selections = _state.value.selections + (scene to id)
        if (!saveSelections(selections)) {
            putTransfer(id, (_state.value.transfers[id] ?: FontTransfer()).copy(
                active = false, error = "字体设置保存失败，请检查存储空间后重试。",
            ))
            return
        }
        _state.value = _state.value.copy(selections = selections, revision = _state.value.revision + 1)
        putTransfer(id, (_state.value.transfers[id] ?: FontTransfer()).copy(error = null))
    }

    private fun downloadNow(id: String, scene: MangaFontScene?) {
        val option = MangaFontCatalog.find(id) ?: return
        if (scene != null && scene !in option.scenes) return
        if (option.assets.isEmpty() || id in _state.value.installed) {
            if (scene != null) selectNow(scene, id)
            return
        }
        if (!storageReady) {
            putTransfer(id, FontTransfer(error = "无法创建字库目录，请检查存储空间。"))
            return
        }
        if (scene != null) {
            _state.value = _state.value.copy(
                pendingSelections = _state.value.pendingSelections + (scene to id),
            )
        }
        if (attempts.containsKey(id)) return
        check(option.assets.all { it.byteCount in 1..MAX_ASSET_BYTES })
        check(option.assets.sumOf { it.byteCount } <= MAX_OPTION_BYTES)
        val attempt = ++nextAttempt
        val staged = StorageFile(StorageFile(root, ".pending"), id + "-" + randomUuid())
        val job = scope.launch {
            var network: NetworkClient? = null
            try {
                val client = NetworkClient(
                    connectTimeoutMillis = 15_000, readTimeoutMillis = 30_000, callTimeoutMillis = 600_000,
                    route = DownloadRoutingStore.get(application).snapshot(DownloadCategory.FONTS),
                )
                network = client
                check(staged.isDirectory || staged.mkdirs())
                var completedBytes = 0L
                for (asset in option.assets) {
                    currentCoroutineContext().ensureActive()
                    downloadAsset(client, option, asset, StorageFile(staged, asset.fileName),
                        attempt, completedBytes)
                    completedBytes += asset.byteCount
                }
                val published = CompletableDeferred<Unit>()
                commands.send(Command.Ready(id, attempt, staged, published))
                published.await()
            } catch (cancelled: CancellationException) {
                commands.trySend(Command.Failed(id, attempt, null))
                throw cancelled
            } catch (error: Exception) {
                val message = when (error) {
                    is DownloadRouteException -> error.message ?: "字库下载线路不可用，请检查网络设置。"
                    is FontIntegrityException -> "字库校验未通过，请重试下载。"
                    else -> "字库下载未完成，请检查网络和存储空间后重试。"
                }
                commands.trySend(Command.Failed(id, attempt, message))
            } finally {
                runCatching { network?.close() }
                staged.deleteRecursively()
            }
        }
        attempts[id] = Attempt(attempt, job)
        putTransfer(id, FontTransfer(total = option.byteCount, active = true))
    }

    private suspend fun downloadAsset(
        network: NetworkClient,
        option: MangaFontOption,
        asset: MangaFontAsset,
        target: StorageFile,
        attempt: Long,
        completedBytes: Long,
    ) {
        var lastFailure: Exception? = null
        for (url in asset.urls.distinct()) {
            currentCoroutineContext().ensureActive()
            target.delete()
            try {
                val parsed = url.toHttpUrl()
                require(parsed.isHttps && parsed.port == 443 &&
                    parsed.username.isEmpty() && parsed.password.isEmpty() &&
                    parsed.host in DOWNLOAD_HOSTS)
                var progressAt = -1L
                val output = target.outputStream()
                val response = try {
                    network.download(
                        Request.Builder().url(parsed).get().build(),
                        maximumBytes = asset.byteCount,
                        onChunk = { bytes, count -> output.write(bytes, 0, count) },
                        onProgress = { received, _ ->
                            if (received == asset.byteCount || progressAt < 0L ||
                                received - progressAt >= maxOf(65_536L, option.byteCount / 100)) {
                                progressAt = received
                                commands.trySend(Command.Progress(
                                    option.id, attempt, completedBytes + received, option.byteCount,
                                ))
                            }
                        },
                    )
                } finally { output.close() }
                check(response.code == 200)
                verifyAsset(target, asset)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is DownloadRouteException) throw failure
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("No font download URL")
    }

    private suspend fun cancelNow(id: String) {
        val active = attempts.remove(id)
        active?.job?.cancelAndJoin()
        _state.value = _state.value.copy(
            pendingSelections = _state.value.pendingSelections.filterValues { it != id },
        )
        putTransfer(id, (_state.value.transfers[id] ?: FontTransfer()).copy(active = false, error = null))
    }

    private suspend fun removeNow(id: String) {
        val option = MangaFontCatalog.find(id)?.takeIf { it.assets.isNotEmpty() } ?: return
        cancelNow(id)
        if (!storageReady) return
        val selections = _state.value.selections.mapValues { (scene, selected) ->
            if (selected == id) MangaFontCatalog.defaultId(scene) else selected
        }
        if (!saveSelections(selections)) {
            putTransfer(id, (_state.value.transfers[id] ?: FontTransfer()).copy(
                error = "设置保存失败，已保留字库，请检查存储空间后重试。",
            ))
            return
        }
        _state.value = _state.value.copy(selections = selections)
        val directory = StorageFile(root, option.id)
        val deleted = !directory.exists() || directory.deleteRecursively()
        val intact = !deleted && option.assets.all { asset ->
            fontFile(option, asset).let { it.isFile && it.length() == asset.byteCount }
        }
        if (!intact) {
            cachedFiles = cachedFiles - id
            cachedFamilies = cachedFamilies - id
        }
        _state.value = _state.value.copy(
            installed = if (intact) _state.value.installed else _state.value.installed - id,
            transfers = _state.value.transfers + (id to FontTransfer(
                total = option.byteCount,
                error = if (deleted) null else "部分字库文件无法删除，请重试清理。",
            )),
            revision = _state.value.revision + 1,
        )
    }

    private fun progressNow(command: Command.Progress) {
        if (attempts[command.id]?.number != command.attempt) return
        putTransfer(command.id, FontTransfer(
            received = command.received.coerceIn(0L, command.total),
            total = command.total, active = true,
        ))
    }

    private fun readyNow(command: Command.Ready) {
        try {
            if (attempts[command.id]?.number != command.attempt) return
            val option = MangaFontCatalog.find(command.id) ?: return
            val directory = StorageFile(root, option.id)
            check(directory.isDirectory || directory.mkdirs())
            val published = mutableListOf<StorageFile>()
            try {
                option.assets.forEach { asset ->
                    val source = StorageFile(command.staged, asset.fileName)
                    verifyAsset(source, asset)
                    val destination = fontFile(option, asset)
                    check(source.renameTo(destination))
                    published += destination
                    syncStorageFile(destination)
                }
                val fonts = option.assets.map { asset ->
                    MangaFontFile(fontFile(option, asset).absolutePath, asset.weight)
                }
                val family = loadMangaFontFamily(identity(option), fonts)
                cachedFiles = cachedFiles + (option.id to fonts)
                cachedFamilies = cachedFamilies + (option.id to family)
                val selectedScenes = _state.value.pendingSelections.filterValues { it == option.id }.keys
                val selections = _state.value.selections + selectedScenes.associateWith { option.id }
                val saved = selectedScenes.isEmpty() || saveSelections(selections)
                _state.value = _state.value.copy(
                    selections = if (saved) selections else _state.value.selections,
                    installed = _state.value.installed + option.id,
                    pendingSelections = _state.value.pendingSelections.filterValues { it != option.id },
                    transfers = _state.value.transfers + (option.id to FontTransfer(
                        received = option.byteCount, total = option.byteCount,
                        error = if (saved) null else "字库已下载，设置保存失败，请重新选择。",
                    )),
                    revision = _state.value.revision + 1,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                published.forEach { it.delete() }
                putTransfer(option.id, FontTransfer(
                    total = option.byteCount, error = "字库无法载入，已保留原字体，请重新下载。",
                ))
                _state.value = _state.value.copy(
                    pendingSelections = _state.value.pendingSelections.filterValues { it != option.id },
                )
            } finally {
                attempts.remove(option.id)
            }
        } finally {
            command.published.complete(Unit)
        }
    }

    private fun failedNow(command: Command.Failed) {
        if (attempts[command.id]?.number != command.attempt) return
        attempts.remove(command.id)
        _state.value = _state.value.copy(
            pendingSelections = _state.value.pendingSelections.filterValues { it != command.id },
        )
        putTransfer(command.id, (_state.value.transfers[command.id] ?: FontTransfer()).copy(
            active = false, error = command.error,
        ))
    }

    private fun saveSelections(selections: Map<MangaFontScene, String>): Boolean = try {
        val editor = prefs.edit()
        selections.forEach { (scene, id) -> editor.putString(preferenceKey(scene), id) }
        editor.commit()
    } catch (_: Exception) { false }

    private fun preferenceKey(scene: MangaFontScene): String = "scene_" + scene.name.lowercase()
    private fun identity(option: MangaFontOption): String =
        option.id + ":" + option.assets.joinToString(":") { it.gitBlobSha1 }

    private fun fontFile(option: MangaFontOption, asset: MangaFontAsset): StorageFile {
        check(option.id.matches(Regex("[a-z0-9_]+")))
        check(asset.fileName.matches(Regex("[A-Za-z0-9_.-]+")))
        check(asset.gitBlobSha1.matches(Regex("[a-f0-9]{40}")))
        return StorageFile(StorageFile(root, option.id), asset.gitBlobSha1 + "-" + asset.fileName)
    }

    private fun verifyAsset(file: StorageFile, asset: MangaFontAsset) {
        if (!file.isFile || file.length() != asset.byteCount) throw FontIntegrityException()
        val hash = HashingSink.sha1(blackholeSink())
        val hashed = hash.buffer()
        val input = file.inputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        val header = ByteArray(4)
        try {
            hashed.writeUtf8("blob " + asset.byteCount + "\u0000")
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                for (index in 0 until minOf(count, (4L - total).coerceAtLeast(0).toInt())) {
                    header[total.toInt() + index] = buffer[index]
                }
                hashed.write(buffer, 0, count)
                total += count
                if (total > asset.byteCount) throw FontIntegrityException()
            }
            hashed.flush()
            val trueType = header.contentEquals(byteArrayOf(0, 1, 0, 0))
            val openType = header.contentEquals(byteArrayOf(79, 84, 84, 79))
            if (total != asset.byteCount || (!trueType && !openType) ||
                hash.hash.hex() != asset.gitBlobSha1) throw FontIntegrityException()
        } finally {
            input.close()
            hashed.close()
        }
    }

    private fun putTransfer(id: String, transfer: FontTransfer) {
        _state.value = _state.value.copy(transfers = _state.value.transfers + (id to transfer))
    }

    private data class Attempt(val number: Long, val job: Job)
    private class FontIntegrityException : Exception()

    private sealed interface Command {
        val id: String
        data class Select(val scene: MangaFontScene, override val id: String) : Command
        data class Download(override val id: String, val scene: MangaFontScene?) : Command
        data class Cancel(override val id: String) : Command
        data class Remove(override val id: String) : Command
        data class Progress(
            override val id: String, val attempt: Long, val received: Long, val total: Long,
        ) : Command
        data class Ready(
            override val id: String, val attempt: Long, val staged: StorageFile,
            val published: CompletableDeferred<Unit>,
        ) : Command
        data class Failed(
            override val id: String, val attempt: Long, val error: String?,
        ) : Command
    }

    companion object {
        private const val MAX_ASSET_BYTES = 32L * 1024 * 1024
        private const val MAX_OPTION_BYTES = 64L * 1024 * 1024
        private val DOWNLOAD_HOSTS = setOf(
            "raw.githubusercontent.com", "cdn.jsdelivr.net",
        )
        private val instanceLock = StorageLock()
        @Volatile private var instance: FontStore? = null
        fun get(context: PlatformContext): FontStore = instance ?: synchronized(instanceLock) {
            instance ?: FontStore(context).also { instance = it }
        }
    }
}
