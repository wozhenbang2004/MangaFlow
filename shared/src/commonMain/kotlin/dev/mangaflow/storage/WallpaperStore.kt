package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import dev.mangaflow.platform.storage.StorageFile as File
import dev.mangaflow.platform.AtomicLong
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WallpaperKind { IMAGE, VIDEO }

data class WallpaperItem(
    val mediaPath: String,
    val posterPath: String? = null,
    val kind: WallpaperKind = WallpaperKind.IMAGE,
    val name: String = "",
)

data class WallpaperState(
    val mediaPath: String? = null,
    val posterPath: String? = null,
    val kind: WallpaperKind = WallpaperKind.IMAGE,
    val name: String = "",
    val strength: Float = .18f,
    val error: String? = null,
    val revision: Int = 0,
    val items: List<WallpaperItem> = emptyList(),
    val rotationEnabled: Boolean = false,
    val rotationMinutes: Int = 10,
)

/** Selected local media is copied, never executed or unpacked as a wallpaper project. */
class WallpaperStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "wallpaper")
    private val prefs = app.getSharedPreferences("mangaflow_wallpaper", PRIVATE_STORAGE)
    private val lock = StorageLock()
    private val intent = AtomicLong()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeOwners = mutableSetOf<Any>()
    private var rotationJob: Job? = null
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<WallpaperState> = _state.asStateFlow()

    suspend fun importMedia(uri: String) = withContext(Dispatchers.IO) {
        val request = intent.incrementAndGet()
        val name = documentDisplayName(app, uri).ifBlank { "本地壁纸" }
        val suffix = name.substringAfterLast('.', "").lowercase()
        require(suffix !in setOf("pkg", "mpkg", "json", "zip", "html", "exe")) {
            "请选择原始图片或视频；Wallpaper Engine 的工程与 .pkg / .mpkg 不能在这里播放。"
        }
        val mime = documentMimeType(app, uri)
        val kind = when {
            mime.startsWith("image/") -> WallpaperKind.IMAGE
            mime.startsWith("video/") -> WallpaperKind.VIDEO
            suffix in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif") -> WallpaperKind.IMAGE
            suffix in setOf("mp4", "m4v", "webm", "mkv", "3gp") -> WallpaperKind.VIDEO
            else -> error("这个文件不是可识别的图片或视频。")
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建壁纸目录，请检查剩余空间。" }
        val token = randomUuid()
        val media = File(directory, "$token.${suffix.ifBlank { "media" }}")
        val poster = File(directory, "$token.jpg")
        var published = false
        try {
            openDocument(app, uri).use { source ->
                media.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (intent.get() != request) throw CancellationException("Wallpaper selection changed")
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "壁纸超过 512 MB，请先裁成较短的视频。" }
                        target.write(buffer, 0, count)
                    }
                    require(total > 0L) { "选择的文件是空的。" }
                }
            }
            if (kind == WallpaperKind.IMAGE) {
                require(storageImageBounds(media) != null) { "此设备无法读取这张图片，请尝试 JPG、PNG 或 WebP。" }
            } else createWallpaperVideoPoster(media, poster)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (intent.get() != request) throw CancellationException("Wallpaper selection changed")
                val previous = _state.value
                val added = WallpaperItem(media.path, poster.path.takeIf { kind == WallpaperKind.VIDEO }, kind, name.take(180))
                val retained = previous.items + listOfNotNull(previous.mediaPath?.let { path ->
                    WallpaperItem(path, previous.posterPath, previous.kind, previous.name)
                })
                publishLocked(current(previous.copy(items = (retained + added).distinctBy(WallpaperItem::mediaPath)), added))
                updateRotationLocked(restart = true)
                published = true
            }
        } finally {
            if (!published) { media.delete(); poster.delete() }
        }
    }

    fun setStrength(value: Float, persist: Boolean = true) = synchronized(lock) {
        val strength = value.takeIf(Float::isFinite)?.coerceIn(0f, .45f) ?: .18f
        _state.value = _state.value.copy(strength = strength)
        if (persist) prefs.edit().putFloat("strength", strength).apply()
        updateRotationLocked()
    }

    fun clear() {
        intent.incrementAndGet()
        val old = synchronized(lock) {
            val previous = _state.value
            publishLocked(WallpaperState(strength = previous.strength, revision = previous.revision + 1,
                rotationMinutes = previous.rotationMinutes))
            updateRotationLocked()
            previous
        }
        cleanup.launch { old.items.forEach { deleteOwned(it.mediaPath); deleteOwned(it.posterPath) } }
    }

    fun select(path: String) = synchronized(lock) {
        val previous = _state.value
        val item = previous.items.firstOrNull { it.mediaPath == path } ?: return@synchronized
        publishLocked(current(previous, item))
        updateRotationLocked(restart = true)
    }

    fun remove(path: String) {
        val removed = synchronized(lock) {
            val previous = _state.value
            val item = previous.items.firstOrNull { it.mediaPath == path } ?: return
            val remaining = previous.items.filterNot { it.mediaPath == path }
            val selected = remaining.firstOrNull { it.mediaPath == previous.mediaPath } ?: remaining.firstOrNull()
            publishLocked(current(previous.copy(items = remaining,
                rotationEnabled = previous.rotationEnabled && remaining.size > 1), selected))
            updateRotationLocked(restart = true)
            item
        }
        cleanup.launch { deleteOwned(removed.mediaPath); deleteOwned(removed.posterPath) }
    }

    fun setRotationEnabled(enabled: Boolean) = synchronized(lock) {
        publishLocked(_state.value.copy(rotationEnabled = enabled && _state.value.items.size > 1))
        updateRotationLocked(restart = true)
    }

    fun setRotationMinutes(minutes: Int) = synchronized(lock) {
        require(minutes in ROTATION_INTERVALS) { "请选择可用的轮换间隔。" }
        publishLocked(_state.value.copy(rotationMinutes = minutes))
        updateRotationLocked(restart = true)
    }

    /** Many retained route compositions share exactly one foreground rotation timer. */
    fun setActive(owner: Any, active: Boolean) = synchronized(lock) {
        if (active) activeOwners.add(owner) else activeOwners.remove(owner)
        updateRotationLocked()
    }

    private fun updateRotationLocked(restart: Boolean = false) {
        val selected = _state.value
        if (restart || activeOwners.isEmpty() || selected.strength <= 0f || !selected.rotationEnabled || selected.items.size < 2) {
            rotationJob?.cancel()
            rotationJob = null
        }
        if (activeOwners.isEmpty() || selected.strength <= 0f || !selected.rotationEnabled || selected.items.size < 2 || rotationJob?.isActive == true) return
        rotationJob = cleanup.launch {
            while (true) {
                delay(_state.value.rotationMinutes * 60_000L)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    val previous = _state.value
                    if (activeOwners.isEmpty() || previous.strength <= 0f || !previous.rotationEnabled || previous.items.size < 2) return@launch
                    val index = previous.items.indexOfFirst { it.mediaPath == previous.mediaPath }
                    val next = previous.items[(index + 1) % previous.items.size]
                    try { publishLocked(current(previous, next)) }
                    catch (_: Exception) {
                        _state.value = previous.copy(rotationEnabled = false, error = "壁纸轮换未能保存，请检查剩余空间后重新开启。")
                        return@launch
                    }
                }
            }
        }
    }

    private fun current(previous: WallpaperState, item: WallpaperItem?): WallpaperState = previous.copy(
        mediaPath = item?.mediaPath, posterPath = item?.posterPath, kind = item?.kind ?: WallpaperKind.IMAGE,
        name = item?.name.orEmpty(), error = null, revision = previous.revision + 1,
    )

    private fun publishLocked(next: WallpaperState) {
        val items = JSONArray()
        next.items.forEach { item -> items.put(JSONObject().put("path", item.mediaPath).put("poster", item.posterPath)
            .put("kind", item.kind.name).put("name", item.name)) }
        check(prefs.edit().putString("items", items.toString()).putString("path", next.mediaPath)
            .putString("poster", next.posterPath).putString("kind", next.kind.name).putString("name", next.name)
            .putFloat("strength", next.strength).putBoolean("rotation", next.rotationEnabled)
            .putInt("rotation_minutes", next.rotationMinutes).commit()) { "壁纸未能保存，请检查剩余空间。" }
        _state.value = next
    }

    fun retry() = synchronized(lock) {
        _state.value = _state.value.copy(error = null, revision = _state.value.revision + 1)
    }

    fun reportError(path: String, message: String, revision: Int? = null) = synchronized(lock) {
        if (_state.value.mediaPath == path && (revision == null || _state.value.revision == revision) &&
            _state.value.error == null) _state.value = _state.value.copy(error = message)
    }

    private fun readState(): WallpaperState {
        val path = prefs.getString("path", null)
        val strength = prefs.getFloat("strength", .18f).takeIf(Float::isFinite)?.coerceIn(0f, .45f) ?: .18f
        val items = runCatching {
            val array = JSONArray(prefs.getString("items", "[]").orEmpty())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val media = item.optString("path").takeIf { it.isNotBlank() && owned(it) } ?: return@mapNotNull null
                WallpaperItem(media, item.optString("poster").takeIf { it.isNotBlank() && owned(it) },
                    runCatching { WallpaperKind.valueOf(item.optString("kind")) }.getOrDefault(WallpaperKind.IMAGE),
                    item.optString("name").take(180))
            }.distinctBy(WallpaperItem::mediaPath)
        }.getOrDefault(emptyList()).let { saved ->
            // Older builds update only the single selection keys; keep that image alongside the saved list.
            val legacy = path?.takeIf(::owned)?.let { media ->
                WallpaperItem(media, prefs.getString("poster", null)?.takeIf(::owned),
                    runCatching { WallpaperKind.valueOf(prefs.getString("kind", "IMAGE").orEmpty()) }.getOrDefault(WallpaperKind.IMAGE),
                    prefs.getString("name", "").orEmpty().take(180))
            }
            (saved + listOfNotNull(legacy)).distinctBy(WallpaperItem::mediaPath)
        }
        val selected = items.firstOrNull { it.mediaPath == path } ?: items.firstOrNull()
        val minutes = prefs.getInt("rotation_minutes", 10).takeIf { it in ROTATION_INTERVALS } ?: 10
        return WallpaperState(selected?.mediaPath, selected?.posterPath, selected?.kind ?: WallpaperKind.IMAGE,
            selected?.name.orEmpty(), strength, items = items,
            rotationEnabled = prefs.getBoolean("rotation", false) && items.size > 1, rotationMinutes = minutes,
            error = if (path != null && (selected == null || !File(selected.mediaPath).isFile)) "壁纸文件不存在，请重新选择。" else null)
    }

    private fun owned(path: String): Boolean = runCatching { File(path).canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false)
    private fun deleteOwned(path: String?) { if (path != null && owned(path)) File(path).delete() }

    companion object {
        val ROTATION_INTERVALS = listOf(1, 5, 10, 30, 60)
        private val instanceLock = StorageLock()
        @Volatile private var instance: WallpaperStore? = null
        fun get(context: Context): WallpaperStore = instance ?: synchronized(instanceLock) {
            instance ?: WallpaperStore(context).also { instance = it }
        }
    }
}
