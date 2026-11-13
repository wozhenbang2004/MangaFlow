@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.mangaflow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import coil3.BitmapImage
import coil3.Image
import io.ktor.http.encodeURLParameter
import coil3.request.ImageRequest
import dev.mangaflow.account.CopyWebsiteEndpoints
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.SYSTEM
import okio.Path.Companion.toPath
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSError
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeSymbolicLink
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPasteboard
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.WebKit.WKNavigation
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationActionPolicyAllow
import platform.WebKit.WKNavigationActionPolicyCancel
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTimeAtDocumentStart
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume

private fun foregroundController(): UIViewController? {
    val window = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?.windows?.filterIsInstance<platform.UIKit.UIWindow>()?.firstOrNull { it.isKeyWindow() }
        ?: UIApplication.sharedApplication.keyWindow
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}
private val documentCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Composable
actual fun rememberDocumentPicker(kind: DocumentPickerKind, onResult: (List<String>) -> Unit): DocumentPickerLauncher {
    val latest = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val owner = remember(kind) { IosDocumentPicker(kind) { latest.value(it) } }
    DisposableEffect(owner) { onDispose { owner.close() } }
    return remember(owner) { DocumentPickerLauncher { types, name ->
        owner.launch(types, name) { urls ->
            scope.launch {
                val selected = try {
                    withContext(Dispatchers.IO) { copyPickedFiles(urls) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    PlatformNotices.show(error.message ?: "所选文件无法读取")
                    emptyList()
                }
                latest.value(selected)
            }
        }
    } }
}
/** Uses the native SDK picker pattern also used by FileKit's iosMain implementation. */
private class IosDocumentPicker(private val kind: DocumentPickerKind, private val onCancel: (List<String>) -> Unit) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    private var picker: UIDocumentPickerViewController? = null
    private var selection: ((List<NSURL>) -> Unit)? = null
    fun launch(types: Array<String>, name: String?, selected: (List<NSURL>) -> Unit) {
        if (picker != null) return
        val controller = foregroundController() ?: run { PlatformNotices.show("暂时无法打开文件选择器"); return }
        selection = selected
        val contentTypes = when {
            kind == DocumentPickerKind.FOLDER -> listOf(UTTypeFolder)
            types.isNotEmpty() && types.all { it.startsWith("image/") } -> listOf(UTTypeImage)
            types.isNotEmpty() && types.all { it.startsWith("video/") } -> listOf(UTTypeMovie)
            else -> listOf(UTTypeItem)
        }
        val next = UIDocumentPickerViewController(forOpeningContentTypes = contentTypes)
        picker = next
        next.delegate = this
        next.allowsMultipleSelection = kind == DocumentPickerKind.OPEN_MULTIPLE
        controller.presentViewController(next, animated = true, completion = null)
    }
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        picker = null
        selection?.invoke(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
        selection = null
    }
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentAtURL: NSURL) {
        picker = null; selection?.invoke(listOf(didPickDocumentAtURL)); selection = null
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        picker = null; selection = null; onCancel(emptyList())
    }
    fun close() { picker?.dismissViewControllerAnimated(false, null); picker = null; selection = null }
}
@Composable
actual fun rememberTextDocumentSaver(onResult: (DocumentSaveResult) -> Unit): TextDocumentSaver {
    val latest = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    val owner = remember { IosDocumentSaver { latest.value(it) } }
    DisposableEffect(owner) { onDispose { owner.close() } }
    return remember(owner) { TextDocumentSaver { name, text ->
        scope.launch {
            val folder = NSTemporaryDirectory() + "mangaflow-export-" + randomUuid()
            try {
                val path = withContext(Dispatchers.IO) {
                    FileSystem.SYSTEM.createDirectories(folder.toPath())
                    val safeName = name.replace('/', '_').replace(':', '_').ifBlank { "MangaFlow-backup.json" }
                    (folder + "/" + safeName).toPath().also { target ->
                        FileSystem.SYSTEM.write(target) { writeUtf8(text) }
                    }
                }
                owner.present(path.toString(), folder)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                documentCleanup.launch { runCatching { FileSystem.SYSTEM.deleteRecursively(folder.toPath()) } }
                throw cancelled
            } catch (error: Exception) {
                documentCleanup.launch { runCatching { FileSystem.SYSTEM.deleteRecursively(folder.toPath()) } }
                latest.value(DocumentSaveResult(false, error.message ?: "备份导出失败"))
            }
        }
    } }
}
/** The complete payload exists before the OS provider receives any export request. */
private class IosDocumentSaver(private val onResult: (DocumentSaveResult) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    private var picker: UIDocumentPickerViewController? = null
    private var temporaryFolder: String? = null
    private var closed = false
    fun present(path: String, folder: String) {
        if (closed || picker != null) {
            documentCleanup.launch { runCatching { FileSystem.SYSTEM.deleteRecursively(folder.toPath()) } }
            if (!closed) onResult(DocumentSaveResult(false, "请先完成当前导出"))
            return
        }
        val controller = foregroundController()
        if (controller == null) {
            documentCleanup.launch { runCatching { FileSystem.SYSTEM.deleteRecursively(folder.toPath()) } }
            onResult(DocumentSaveResult(false, "暂时无法打开导出界面"))
            return
        }
        temporaryFolder = folder
        val next = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(path)))
        next.delegate = this
        picker = next
        controller.presentViewController(next, animated = true, completion = null)
    }
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        finish(DocumentSaveResult(didPickDocumentsAtURLs.isNotEmpty()))
    }
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentAtURL: NSURL) {
        finish(DocumentSaveResult(true))
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { finish(DocumentSaveResult(false)) }
    private fun finish(result: DocumentSaveResult) {
        if (picker == null) return
        picker = null
        cleanup()
        if (!closed) onResult(result)
    }
    private fun cleanup() {
        val folder = temporaryFolder
        temporaryFolder = null
        if (folder != null) documentCleanup.launch { runCatching { FileSystem.SYSTEM.deleteRecursively(folder.toPath()) } }
    }
    fun close() {
        closed = true
        picker?.dismissViewControllerAnimated(false, null)
        picker = null
        cleanup()
    }
}

private suspend fun copyPickedFiles(urls: List<NSURL>): List<String> {
    val operation = currentCoroutineContext()
    val folder = NSTemporaryDirectory() + "mangaflow-import-" + randomUuid()
    val system = FileSystem.SYSTEM
    system.createDirectories(folder.toPath())
    var total = 0L
    var count = 0
    fun inspect(path: String) {
        operation.ensureActive()
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null) ?: error("所选文件无法读取")
        require(attributes[NSFileType] != NSFileTypeSymbolicLink) { "不支持导入符号链接" }
        if (attributes[NSFileType] == NSFileTypeDirectory) {
            val children = NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null).orEmpty()
            children.filterIsInstance<String>().forEach { inspect(path + "/" + it) }
        } else {
            count++
            total += (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: 0
            require(count <= 30_000 && total <= 512L * 1024 * 1024) { "导入内容过大，请分批选择" }
        }
    }
    return try {
        urls.mapIndexed { index, url ->
            operation.ensureActive()
            val granted = url.startAccessingSecurityScopedResource()
            try {
                val path = url.path ?: error("所选文件没有本地路径")
                inspect(path)
                val name = url.lastPathComponent.orEmpty().replace('/', '_').ifBlank { "comic" }
                val parent = folder + "/" + index
                system.createDirectories(parent.toPath())
                val target = parent + "/" + name
                check(NSFileManager.defaultManager.copyItemAtPath(path, target, null)) { "无法复制所选文件，请确认文件已下载到设备" }
                NSURL.fileURLWithPath(target).absoluteString ?: error("无法定位导入文件")
            } finally { if (granted) url.stopAccessingSecurityScopedResource() }
        }
    } catch (error: Exception) {
        runCatching { system.deleteRecursively(folder.toPath()) }
        throw error
    }
}
actual suspend fun readDocumentText(context: PlatformContext, uri: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
    val url = NSURL(string = uri) ?: error("无法定位备份文件")
    val granted = url.startAccessingSecurityScopedResource()
    try {
        val path = url.path?.toPath() ?: error("无法定位备份文件")
        val output = Buffer()
        FileSystem.SYSTEM.source(path).use { source ->
            while (true) {
                val count = source.read(output, minOf(16384L, maxBytes + 1L - output.size))
                if (count < 0) break
                require(output.size <= maxBytes) { "备份文件过大或格式异常" }
            }
        }
        output.readUtf8()
    } finally { if (granted) url.stopAccessingSecurityScopedResource() }
}
actual suspend fun writeDocumentText(context: PlatformContext, uri: String, text: String): Unit = withContext(Dispatchers.IO) {
    val url = NSURL(string = uri) ?: error("无法定位备份文件")
    val granted = url.startAccessingSecurityScopedResource()
    try {
        val path = url.path?.toPath() ?: error("无法定位备份文件")
        FileSystem.SYSTEM.write(path) { writeUtf8(text) }
    } finally { if (granted) url.stopAccessingSecurityScopedResource() }
}
actual fun documentName(context: PlatformContext, uri: String): String = NSURL(string = uri)?.lastPathComponent.orEmpty().ifBlank { "本地漫画" }
actual fun releasePickedDocuments(uris: List<String>) {
    val prefix = NSTemporaryDirectory() + "mangaflow-import-"
    val folders = uris.mapNotNull { NSURL(string = it)?.path }.filter { it.startsWith(prefix) }
        .map { prefix + it.removePrefix(prefix).substringBefore('/') }.distinct()
    if (folders.isNotEmpty()) documentCleanup.launch {
        folders.forEach { runCatching { FileSystem.SYSTEM.deleteRecursively(it.toPath()) } }
    }
}
actual fun copyText(context: PlatformContext, label: String, text: String): Boolean = runCatching { UIPasteboard.generalPasteboard.string = text; true }.getOrDefault(false)
actual fun showPlatformMessage(context: PlatformContext, text: String) { PlatformNotices.show(text) }
private var notificationAuthorization = false
actual fun notificationsEnabled(context: PlatformContext): Boolean = notificationAuthorization
actual suspend fun queryNotificationsEnabled(context: PlatformContext): Boolean = suspendCancellableCoroutine { continuation ->
    UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
        val allowed = settings?.authorizationStatus == UNAuthorizationStatusAuthorized ||
            settings?.authorizationStatus == UNAuthorizationStatusProvisional
        notificationAuthorization = allowed
        if (continuation.isActive) continuation.resume(allowed)
    }
}
actual fun openNotificationSettings(context: PlatformContext): Boolean = runCatching {
    val url = NSURL(string = UIApplicationOpenSettingsURLString) ?: return false
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null); true
}.getOrDefault(false)
actual fun openUrl(context: PlatformContext, url: String): Boolean = runCatching {
    val parsed = NSURL(string = url) ?: return false
    require(parsed.scheme == "https" || parsed.scheme == "http")
    if (!UIApplication.sharedApplication.canOpenURL(parsed)) return false
    UIApplication.sharedApplication.openURL(parsed, options = emptyMap<Any?, Any>(), completionHandler = null); true
}.getOrDefault(false)
actual val modernPlatformBlur: Boolean get() = true
actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = this
actual fun imagePixelAt(image: Image, x: Int, y: Int): Int? = runCatching { (image as? BitmapImage)?.bitmap?.getColor(x, y) }.getOrNull()
@Composable
actual fun SystemBarAppearance(lightStatus: Boolean, lightNavigation: Boolean, active: Boolean) {
    SideEffect { if (active) UIApplication.sharedApplication.setStatusBarStyle(
        if (lightStatus) UIStatusBarStyleDarkContent else UIStatusBarStyleLightContent, animated = false) }
}
actual fun availableCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean = tool == CommentTextTool.TRANSLATE
actual fun openCommentTextTool(context: PlatformContext, text: String, tool: CommentTextTool): Boolean {
    if (tool != CommentTextTool.TRANSLATE) return false
    val escaped = text.encodeURLParameter()
    return openUrl(context, "https://translate.google.com/?sl=auto&tl=zh-CN&text=" + escaped + "&op=translate")
}

@Composable
actual fun WallpaperVideo(path: String, playing: Boolean, modifier: Modifier, onError: (String) -> Unit) {
    val latestError = rememberUpdatedState(onError)
    val latestPlaying = rememberUpdatedState(playing)
    val player = remember(path) { AVPlayer(uRL = NSURL.fileURLWithPath(path)).apply { muted = true; volume = 0f } }
    val videoLayer = remember(player) { AVPlayerLayer.playerLayerWithPlayer(player).apply { videoGravity = AVLayerVideoGravityResizeAspectFill } }
    val view = remember(videoLayer) {
        object : UIView(frame = CGRectZero.readValue()) {
            override fun layoutSubviews() { super.layoutSubviews(); videoLayer.frame = bounds }
        }.apply { userInteractionEnabled = false; layer.addSublayer(videoLayer) }
    }
    DisposableEffect(player) {
        val center = NSNotificationCenter.defaultCenter
        val completed = center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, player.currentItem, null) {
            player.seekToTime(kCMTimeZero)
            if (latestPlaying.value) player.play()
        }
        val failed = center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, player.currentItem, null) {
            latestError.value("视频无法播放，已保留静态画面。请换用 MP4（H.264）。")
        }
        onDispose {
            player.pause()
            center.removeObserver(completed); center.removeObserver(failed)
            videoLayer.removeFromSuperlayer()
        }
    }
    UIKitView(factory = { view }, modifier = modifier, update = { if (playing) player.play() else player.pause() })
}

@Composable
actual fun NativeWebsiteLogin(
    onBegin: () -> Long, onCancel: (Long) -> Unit, onCredential: (Long, String) -> Unit,
    onLoading: (Boolean) -> Unit, onError: (String) -> Unit, modifier: Modifier,
) {
    val receipt = remember { WebsiteLoginReceipt(onBegin, onCancel, onCredential) }
    val session = remember { IosWebsiteSession(receipt, onLoading, onError) }
    DisposableEffect(session) { onDispose { receipt.close(); session.close() } }
    UIKitView(factory = { session.webView }, modifier = modifier)
}
private class IosWebsiteSession(
    private val receipt: WebsiteLoginReceipt,
    private val onLoading: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), WKScriptMessageHandlerProtocol, WKNavigationDelegateProtocol, WKUIDelegateProtocol {
    private var closed = false
    private val store = WKWebsiteDataStore.nonPersistentDataStore()
    val webView: WKWebView
    init {
        val configuration = WKWebViewConfiguration().apply {
            websiteDataStore = store
            userContentController.addScriptMessageHandler(this@IosWebsiteSession, "MangaFlowAccount")
            userContentController.addUserScript(WKUserScript(
                source = websiteObserver(receipt.nonce, "window.webkit.messageHandlers.MangaFlowAccount"),
                injectionTime = WKUserScriptInjectionTimeAtDocumentStart, forMainFrameOnly = true))
        }
        webView = WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
            navigationDelegate = this@IosWebsiteSession
            UIDelegate = this@IosWebsiteSession
            allowsBackForwardNavigationGestures = false
        }
        onLoading(true)
        webView.loadRequest(NSURLRequest(uRL = NSURL(string = CopyWebsiteEndpoints.LOGIN_PAGE)))
    }
    override fun webView(webView: WKWebView, runJavaScriptAlertPanelWithMessage: String,
        initiatedByFrame: WKFrameInfo, completionHandler: () -> Unit) {
        try {
            val origin = initiatedByFrame.securityOrigin
            if (!closed && initiatedByFrame.mainFrame && origin.protocol == "https" &&
                origin.host == "www.copy4000.com" && origin.port in listOf(0L, 443L) &&
                allowedWebsitePage(webView.URL?.absoluteString.orEmpty())) {
                runJavaScriptAlertPanelWithMessage.trim().take(4096).takeIf { it.isNotEmpty() }?.let(PlatformNotices::show)
            }
        } finally { completionHandler() }
    }
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        if (closed || !didReceiveScriptMessage.frameInfo.mainFrame) return
        val origin = didReceiveScriptMessage.frameInfo.securityOrigin
        if (origin.protocol != "https" || origin.host != "www.copy4000.com" || origin.port !in listOf(0L, 443L)) return
        val raw = didReceiveScriptMessage.body as? String ?: return
        val frameUrl = didReceiveScriptMessage.frameInfo.request.URL?.absoluteString.orEmpty()
        receipt.receive(raw, webView.URL?.absoluteString ?: frameUrl, true)?.let { token ->
            onLoading(true)
            close {
                onLoading(false)
                receipt.deliverAfterCleanup(token)
            }
        }
    }
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        if (!closed) onLoading(true)
    }
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (!closed) onLoading(false)
    }
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailNavigation: WKNavigation?, withError: NSError) {
        if (!closed) fail("官网登录页未能加载，请检查网络后重试。")
    }
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFailProvisionalNavigation: WKNavigation?, withError: NSError) {
        if (!closed) fail("无法建立官网的安全连接，请检查网络后重试。")
    }
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString.orEmpty()
        val allowed = !closed && decidePolicyForNavigationAction.targetFrame?.mainFrame != false && allowedWebsitePage(url)
        if (allowed && isWebsiteLoginPage(url)) receipt.newDocument()
        if (!allowed && !closed && decidePolicyForNavigationAction.targetFrame?.mainFrame != false) fail("登录页跳转到了未允许的地址，已停止登录。")
        decisionHandler(if (allowed || decidePolicyForNavigationAction.targetFrame?.mainFrame == false) WKNavigationActionPolicyAllow else WKNavigationActionPolicyCancel)
    }
    private fun fail(message: String) {
        receipt.close()
        close { onLoading(false); onError(message) }
    }
    fun close(after: (() -> Unit)? = null) {
        if (closed) return
        closed = true
        webView.stopLoading()
        webView.navigationDelegate = null
        webView.UIDelegate = null
        webView.configuration.userContentController.removeAllUserScripts()
        webView.configuration.userContentController.removeScriptMessageHandlerForName("MangaFlowAccount")
        webView.loadHTMLString("", baseURL = null)
        store.removeDataOfTypes(WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince = NSDate(timeIntervalSince1970 = 0.0)) { after?.invoke() }
    }
}
