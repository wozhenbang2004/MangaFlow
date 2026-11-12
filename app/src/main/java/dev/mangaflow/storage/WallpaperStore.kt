package dev.mangaflow.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class WallpaperKind { IMAGE, VIDEO }

data class WallpaperState(
    val mediaPath: String? = null,
    val posterPath: String? = null,
    val kind: WallpaperKind = WallpaperKind.IMAGE,
    val name: String = "",
    val strength: Float = .18f,
    val error: String? = null,
    val revision: Int = 0,
)

/** Selected local media is copied, never executed or unpacked as a wallpaper project. */
class WallpaperStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "wallpaper")
    private val prefs = app.getSharedPreferences("mangaflow_wallpaper", Context.MODE_PRIVATE)
    private val lock = Any()
    private val intent = AtomicLong()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<WallpaperState> = _state.asStateFlow()

    suspend fun importMedia(uri: Uri) = withContext(Dispatchers.IO) {
        val request = intent.incrementAndGet()
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty().ifBlank { "本地壁纸" }
        val suffix = name.substringAfterLast('.', "").lowercase()
        require(suffix !in setOf("pkg", "mpkg", "json", "zip", "html", "exe")) {
            "请选择原始图片或视频；Wallpaper Engine 的工程与 .pkg / .mpkg 不能在这里播放。"
        }
        val mime = app.contentResolver.getType(uri).orEmpty()
        val kind = when {
            mime.startsWith("image/") -> WallpaperKind.IMAGE
            mime.startsWith("video/") -> WallpaperKind.VIDEO
            suffix in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif") -> WallpaperKind.IMAGE
            suffix in setOf("mp4", "m4v", "webm", "mkv", "3gp") -> WallpaperKind.VIDEO
            else -> error("这个文件不是可识别的图片或视频。")
        }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建壁纸目录，请检查剩余空间。" }
        val token = UUID.randomUUID().toString()
        val media = File(directory, "$token.media")
        val poster = File(directory, "$token.jpg")
        var published = false
        try {
            app.contentResolver.openInputStream(uri)?.use { source ->
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
            } ?: error("无法读取选择的文件，请重新选择。")
            if (kind == WallpaperKind.IMAGE) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(media.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "此设备无法读取这张图片，请尝试 JPG、PNG 或 WebP。" }
            } else createPoster(media, poster)
            currentCoroutineContext().ensureActive()
            val old = synchronized(lock) {
                if (intent.get() != request) throw CancellationException("Wallpaper selection changed")
                val previous = _state.value
                val next = WallpaperState(media.path, poster.path.takeIf { kind == WallpaperKind.VIDEO },
                    kind, name.take(180), previous.strength, revision = previous.revision + 1)
                check(prefs.edit().putString("path", next.mediaPath).putString("poster", next.posterPath)
                    .putString("kind", next.kind.name).putString("name", next.name)
                    .putFloat("strength", next.strength).commit()) { "壁纸未能保存，请检查剩余空间。" }
                _state.value = next
                published = true
                previous
            }
            deleteOwned(old.mediaPath)
            deleteOwned(old.posterPath)
        } finally {
            if (!published) { media.delete(); poster.delete() }
        }
    }

    fun setStrength(value: Float, persist: Boolean = true) = synchronized(lock) {
        val strength = value.takeIf(Float::isFinite)?.coerceIn(0f, .45f) ?: .18f
        _state.value = _state.value.copy(strength = strength)
        if (persist) prefs.edit().putFloat("strength", strength).apply()
    }

    fun clear() {
        intent.incrementAndGet()
        val old = synchronized(lock) {
            val previous = _state.value
            prefs.edit().remove("path").remove("poster").remove("kind").remove("name").apply()
            _state.value = WallpaperState(strength = previous.strength, revision = previous.revision + 1)
            previous
        }
        cleanup.launch { deleteOwned(old.mediaPath); deleteOwned(old.posterPath) }
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
        return WallpaperState(path, prefs.getString("poster", null),
            runCatching { WallpaperKind.valueOf(prefs.getString("kind", "IMAGE").orEmpty()) }.getOrDefault(WallpaperKind.IMAGE),
            prefs.getString("name", "").orEmpty(), strength,
            error = if (path != null && (!owned(path) || !File(path).isFile)) "壁纸文件不存在，请重新选择。" else null)
    }

    private fun owned(path: String): Boolean = runCatching { File(path).canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false)
    private fun deleteOwned(path: String?) { if (path != null && owned(path)) File(path).delete() }

    @Suppress("DEPRECATION")
    private fun createPoster(media: File, poster: File) {
        val reader = MediaMetadataRetriever()
        val frame: Bitmap
        try {
            reader.setDataSource(media.path)
            val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            require(width > 0 && height > 0) { "此设备无法识别视频，请尝试 MP4（H.264）。" }
            val scale = minOf(1f, 1080f / width, 1920f / height)
            frame = (if (Build.VERSION.SDK_INT >= 27) {
                reader.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            } else ThumbnailUtils.createVideoThumbnail(media.path, MediaStore.Images.Thumbnails.MINI_KIND))
                ?: error("视频首帧无法读取，请尝试 MP4（H.264）。")
        } finally { reader.release() }
        try {
            poster.outputStream().use { output -> check(frame.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "视频预览未能保存。" } }
        } finally { frame.recycle() }
    }

    companion object {
        @Volatile private var instance: WallpaperStore? = null
        fun get(context: Context): WallpaperStore = instance ?: synchronized(this) {
            instance ?: WallpaperStore(context).also { instance = it }
        }
    }
}
