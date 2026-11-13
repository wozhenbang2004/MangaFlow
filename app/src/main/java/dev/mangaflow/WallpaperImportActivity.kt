@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mangaflow

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.MangaFlowTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Explicit image/video shares only; transfer applications retain their own connection protocols. */
class WallpaperImportActivity : ComponentActivity() {
    private lateinit var importer: WallpaperImportModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application
        importer = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == WallpaperImportModel::class.java)
                return WallpaperImportModel(app) as T
            }
        })[WallpaperImportModel::class.java]
        importer.prepare(intent, restored = savedInstanceState?.getBoolean(PREPARED) == true)
        // Do not save the shared external URIs as our Activity's navigation state.
        setIntent(Intent())
        enableEdgeToEdge()
        setContent {
            val state by importer.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalMangaContext provides this@WallpaperImportActivity) {
                MangaFlowTheme {
                    BackHandler { importer.close(); finish() }
                    WallpaperImportContent(state, importer::start, importer::stop,
                        onClose = { importer.close(); finish() },
                        onOpen = {
                            startActivity(Intent(this@WallpaperImportActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                            finish()
                        })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (importer.isImporting) Toast.makeText(this, R.string.wallpaper_import_busy, Toast.LENGTH_SHORT).show()
        else importer.prepare(intent, replace = true)
        setIntent(Intent())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(PREPARED, true)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (::importer.isInitialized && !isChangingConfigurations) importer.stop()
        super.onStop()
    }

    private companion object { const val PREPARED = "wallpaper_share_prepared" }
}

private enum class ImportPhase { CHECKING, CONFIRM, IMPORTING, FINISHED, STOPPED, INTERRUPTED }
private enum class ImportResult { READY, IMPORTING, SAVED, FAILED, STOPPED }
private data class SharedWallpaper(
    val uri: String?, val name: String, val video: Boolean, val size: Long?,
    val result: ImportResult = ImportResult.READY, val error: String? = null,
)
private data class WallpaperImportState(
    val phase: ImportPhase = ImportPhase.CHECKING,
    val items: List<SharedWallpaper> = emptyList(), val error: String? = null,
) {
    val saved get() = items.count { it.result == ImportResult.SAVED }
    val ready get() = items.count { it.result == ImportResult.READY }
    val importingIndex get() = items.indexOfFirst { it.result == ImportResult.IMPORTING }
}

private class WallpaperImportModel(private val app: Application) : ViewModel() {
    private val mutableState = MutableStateFlow(WallpaperImportState())
    val state = mutableState.asStateFlow()
    private var prepared = false
    private var job: Job? = null
    val isImporting get() = mutableState.value.phase == ImportPhase.IMPORTING

    fun prepare(intent: Intent, restored: Boolean = false, replace: Boolean = false) {
        if (prepared && !replace) return
        prepared = true
        job?.cancel()
        if (restored) {
            mutableState.value = WallpaperImportState(ImportPhase.INTERRUPTED,
                error = app.getString(R.string.wallpaper_import_interrupted))
            return
        }
        val uris = try { sharedUris(intent) } catch (_: Exception) {
            mutableState.value = WallpaperImportState(ImportPhase.CONFIRM,
                error = app.getString(R.string.wallpaper_import_empty))
            return
        }
        if (uris.isEmpty() || uris.size > MAX_FILES) {
            mutableState.value = WallpaperImportState(ImportPhase.CONFIRM, error = app.getString(
                if (uris.size > MAX_FILES) R.string.wallpaper_import_limit else R.string.wallpaper_import_empty))
            return
        }
        mutableState.value = WallpaperImportState()
        job = viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapIndexed { index, uri -> coroutineContext.ensureActive(); inspect(uri, index) }
            }
            mutableState.value = WallpaperImportState(ImportPhase.CONFIRM, files)
        }
    }

    fun start() {
        val current = mutableState.value
        if (current.phase != ImportPhase.CONFIRM || current.ready == 0) return
        mutableState.value = current.copy(phase = ImportPhase.IMPORTING)
        job = viewModelScope.launch {
            val store = WallpaperStore.get(app)
            try {
                for (index in current.items.indices) {
                    val item = current.items[index]
                    if (item.result != ImportResult.READY || item.uri == null) continue
                    coroutineContext.ensureActive()
                    updateItem(index) { it.copy(result = ImportResult.IMPORTING) }
                    try {
                        store.importMedia(item.uri)
                        updateItem(index) { it.copy(uri = null, result = ImportResult.SAVED) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        updateItem(index) { it.copy(uri = null, result = ImportResult.FAILED,
                            error = app.getString(R.string.wallpaper_import_failed)) }
                    }
                }
                mutableState.update { it.copy(phase = ImportPhase.FINISHED, items = it.items.map { row -> row.copy(uri = null) }) }
            } catch (cancelled: CancellationException) {
                stopped()
                throw cancelled
            }
        }
    }

    fun stop() {
        if (!isImporting) return
        job?.cancel()
        stopped()
    }

    fun close() {
        job?.cancel()
        mutableState.update { it.copy(items = it.items.map { row -> row.copy(uri = null) }) }
    }

    override fun onCleared() { close(); super.onCleared() }

    private fun stopped() = mutableState.update { state ->
        state.copy(phase = ImportPhase.STOPPED, items = state.items.map {
            it.copy(uri = null, result = if (it.result in setOf(ImportResult.READY, ImportResult.IMPORTING)) ImportResult.STOPPED else it.result)
        })
    }

    private fun updateItem(index: Int, update: (SharedWallpaper) -> SharedWallpaper) = mutableState.update { state ->
        state.copy(items = state.items.mapIndexed { position, item -> if (position == index) update(item) else item })
    }

    private fun inspect(uri: Uri, index: Int): SharedWallpaper {
        var name = app.getString(R.string.wallpaper_import_unknown_name, index + 1)
        var size: Long? = null
        var video = false
        return try {
            require(uri.scheme == "content" && !uri.authority.isNullOrBlank())
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) name = cursor.getString(nameColumn)?.filterNot(Char::isISOControl)?.take(180)?.ifBlank { name } ?: name
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
                }
            }
            val mime = app.contentResolver.getType(uri).orEmpty().substringBefore(';').lowercase(Locale.ROOT)
            val suffix = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val generic = mime.isBlank() || mime == "application/octet-stream" || mime == "application/binary"
            video = mime.startsWith("video/") || (generic && suffix in VIDEO_SUFFIXES)
            val image = mime.startsWith("image/") || (generic && suffix in IMAGE_SUFFIXES)
            val error = when {
                suffix in setOf("pkg", "mpkg", "json", "zip", "html", "exe") || (!image && !video) -> R.string.wallpaper_import_unsupported
                size == 0L -> R.string.wallpaper_import_empty_file
                (size ?: 0L) > MAX_BYTES -> R.string.wallpaper_import_too_large
                else -> null
            }
            if (error != null) SharedWallpaper(null, name, video, size, ImportResult.FAILED, app.getString(error))
            else {
                requireNotNull(app.contentResolver.openAssetFileDescriptor(uri, "r")).close()
                SharedWallpaper(uri.toString(), name, video, size)
            }
        } catch (_: Exception) {
            SharedWallpaper(null, name, video, size, ImportResult.FAILED, app.getString(R.string.wallpaper_import_unreadable))
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return emptyList()
        val streams = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        }
        val distinct = LinkedHashSet<Uri>()
        for (uri in streams) {
            distinct.add(uri)
            if (distinct.size > MAX_FILES) return distinct.toList()
        }
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(distinct::add)
                if (distinct.size > MAX_FILES) return distinct.toList()
            }
        }
        return distinct.toList()
    }

    private companion object {
        const val MAX_FILES = 20
        const val MAX_BYTES = 512L * 1024 * 1024
        val IMAGE_SUFFIXES = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
        val VIDEO_SUFFIXES = setOf("mp4", "m4v", "webm", "mkv", "3gp")
    }
}

@Composable
private fun WallpaperImportContent(state: WallpaperImportState, onAdd: () -> Unit, onStop: () -> Unit,
    onClose: () -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize().safeDrawingPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.wallpaper_import_title), style = MaterialTheme.typography.headlineSmall)
                if (state.phase == ImportPhase.CHECKING) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.wallpaper_import_checking), color = colors.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.wallpaper_import_count, state.items.size), color = colors.onSurfaceVariant)
                }
                state.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
                if (state.phase == ImportPhase.CONFIRM && state.ready > 0)
                    Text(stringResource(R.string.wallpaper_import_confirm_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (state.phase == ImportPhase.IMPORTING) {
                    Text(stringResource(R.string.wallpaper_import_progress, state.importingIndex + 1, state.items.size),
                        style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = {
                        state.items.count { it.result in setOf(ImportResult.SAVED, ImportResult.FAILED) }.toFloat() /
                            state.items.size.coerceAtLeast(1)
                    }, modifier = Modifier.fillMaxWidth())
                }
                if (state.phase == ImportPhase.STOPPED)
                    Text(stringResource(R.string.wallpaper_import_stopped), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (state.phase in setOf(ImportPhase.FINISHED, ImportPhase.STOPPED))
                    Text(stringResource(R.string.wallpaper_import_result, state.saved, state.items.size - state.saved), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(state.items) { _, item -> SharedWallpaperRow(item, state.phase == ImportPhase.CONFIRM) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (state.phase) {
                        ImportPhase.CHECKING -> TextButton(onClick = onClose) { Text(stringResource(R.string.wallpaper_import_cancel)) }
                        ImportPhase.CONFIRM -> {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.wallpaper_import_cancel)) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = onAdd, enabled = state.ready > 0) { Text(stringResource(R.string.wallpaper_import_confirm, state.ready)) }
                        }
                        ImportPhase.IMPORTING -> TextButton(onClick = onStop) { Text(stringResource(R.string.wallpaper_import_stop)) }
                        else -> {
                            TextButton(onClick = onClose) { Text(stringResource(R.string.wallpaper_import_done)) }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = onOpen) { Text(stringResource(R.string.wallpaper_import_open)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedWallpaperRow(item: SharedWallpaper, preview: Boolean) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerLow), contentAlignment = Alignment.Center) {
            Icon(if (item.video) Icons.Outlined.VideoFile else Icons.Outlined.Image, null, tint = colors.onSurfaceVariant)
            if (preview && !item.video && item.uri != null) {
                val request = remember(item.uri, context) {
                    ImageRequest.Builder(context).data(Uri.parse(item.uri))
                        .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.DISABLED).build()
                }
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.size?.let { Text(Formatter.formatFileSize(context, it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            item.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
        when (item.result) {
            ImportResult.IMPORTING -> CircularProgressIndicator(Modifier.size(20.dp))
            ImportResult.SAVED -> Icon(Icons.Outlined.CheckCircle, stringResource(R.string.wallpaper_import_saved), tint = colors.primary)
            ImportResult.FAILED -> Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.wallpaper_import_not_added), tint = colors.error)
            ImportResult.STOPPED -> Icon(Icons.Outlined.Pause, stringResource(R.string.wallpaper_import_not_added), tint = colors.onSurfaceVariant)
            ImportResult.READY -> Unit
        }
    }
}
