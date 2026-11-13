package dev.mangaflow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.Image
import coil3.request.ImageRequest
import dev.mangaflow.platform.PlatformContext

/** Platform pickers return granted Android content URIs or app-owned local file URIs. */
enum class DocumentPickerKind { OPEN, OPEN_MULTIPLE, FOLDER }
class DocumentPickerLauncher(val request: (Array<String>, String?) -> Unit) {
    fun launch(mimeTypes: Array<String>) = request(mimeTypes, null)
    fun launch(fileName: String) = request(arrayOf("application/json"), fileName)
    fun launch(unused: Nothing?) = request(emptyArray(), null)
}
@Composable
expect fun rememberDocumentPicker(kind: DocumentPickerKind, onResult: (List<String>) -> Unit): DocumentPickerLauncher
@Composable
fun rememberOpenDocumentPicker(onResult: (String?) -> Unit) =
    rememberDocumentPicker(DocumentPickerKind.OPEN) { onResult(it.firstOrNull()) }
@Composable
fun rememberMultipleDocumentPicker(onResult: (List<String>) -> Unit) =
    rememberDocumentPicker(DocumentPickerKind.OPEN_MULTIPLE, onResult)
@Composable
fun rememberFolderPicker(onResult: (String?) -> Unit) =
    rememberDocumentPicker(DocumentPickerKind.FOLDER) { onResult(it.firstOrNull()) }
data class DocumentSaveResult(val saved: Boolean, val error: String? = null)
class TextDocumentSaver(private val save: (String, String) -> Unit) {
    fun launch(suggestedName: String, text: String) = save(suggestedName, text)
}
@Composable
expect fun rememberTextDocumentSaver(onResult: (DocumentSaveResult) -> Unit): TextDocumentSaver

expect suspend fun readDocumentText(context: PlatformContext, uri: String, maxBytes: Int = 8 * 1024 * 1024): String
expect suspend fun writeDocumentText(context: PlatformContext, uri: String, text: String)
expect fun documentName(context: PlatformContext, uri: String): String
expect fun releasePickedDocuments(uris: List<String>)
expect fun copyText(context: PlatformContext, label: String, text: String): Boolean
expect fun showPlatformMessage(context: PlatformContext, text: String)
expect fun notificationsEnabled(context: PlatformContext): Boolean
expect suspend fun queryNotificationsEnabled(context: PlatformContext): Boolean
expect fun openNotificationSettings(context: PlatformContext): Boolean
expect fun openUrl(context: PlatformContext, url: String): Boolean
expect val modernPlatformBlur: Boolean
expect fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder
/** Reads only the requested pixel; it never creates a second full-size bitmap. */
expect fun imagePixelAt(image: Image, x: Int, y: Int): Int?
@Composable
expect fun SystemBarAppearance(lightStatus: Boolean, lightNavigation: Boolean, active: Boolean = true)
@Composable
expect fun WallpaperVideo(path: String, playing: Boolean, modifier: Modifier = Modifier, onError: (String) -> Unit)
@Composable
expect fun NativeWebsiteLogin(
    onBegin: () -> Long,
    onCancel: (Long) -> Unit,
    onCredential: (Long, String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)

enum class CommentTextTool { TRANSLATE, PROCESS_TEXT }
expect fun availableCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean
expect fun openCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean

internal object PlatformNotices {
    val messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    fun show(message: String) { messages.tryEmit(message) }
}
