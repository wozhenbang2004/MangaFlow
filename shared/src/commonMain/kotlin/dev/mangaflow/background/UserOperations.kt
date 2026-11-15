package dev.mangaflow.background

import coil3.ImageLoader
import dev.mangaflow.data.plugins.ScriptSourcePackage
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryBackupInfo
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.WebDavBackupStore
import dev.mangaflow.storage.StoredCacheItem
import dev.mangaflow.storage.StorageCacheStore
import dev.mangaflow.ui.platform.readDocumentText
import dev.mangaflow.ui.platform.releasePickedDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object UserOperationKeys {
    const val CACHE = "settings:cache"
    const val BACKUP = "settings:backup"
    const val LOCAL_IMPORT = "downloads:local-import"
    const val SOURCE_INSTALL = "sources:install"
}

internal sealed interface LocalImportInput {
    data class Archive(val uri: String) : LocalImportInput
    data class Images(val uris: List<String>) : LocalImportInput
    data class Folder(val uri: String) : LocalImportInput
    fun documents(): List<String> = when (this) {
        is Archive -> listOf(uri)
        is Images -> uris
        is Folder -> listOf(uri)
    }
}

internal data class InspectedReadingBackup(val payload: String, val info: LibraryBackupInfo)
internal data class WebDavOperationDraft(
    val endpoint: String, val username: String, val password: String, val remotePath: String,
)

/** Confirmed intent is copied before submission; these workers never capture a composable. */
internal fun ApplicationOperations.importLocal(
    store: DownloadStore, input: LocalImportInput, mangaTitle: String, chapterTitle: String,
): ApplicationOperationHandle<DownloadEntry> {
    val acceptedInput = when (input) {
        is LocalImportInput.Images -> input.copy(uris = input.uris.toList())
        else -> input
    }
    return submit(UserOperationKeys.LOCAL_IMPORT, ApplicationOperationKind.LOCAL_IMPORT, "导入本地漫画",
        onTerminal = { releasePickedDocuments(acceptedInput.documents()) }) {
        when (acceptedInput) {
            is LocalImportInput.Archive -> store.importArchive(acceptedInput.uri, mangaTitle, chapterTitle)
            is LocalImportInput.Images -> store.importImages(acceptedInput.uris, mangaTitle, chapterTitle)
            is LocalImportInput.Folder -> store.importFolder(acceptedInput.uri, mangaTitle, chapterTitle)
        }
    }
}

internal data class StorageCleanupResult(val freedBytes: Long, val skipped: Int, val failed: Int,
    val accountingComplete: Boolean = true)
internal class StorageCleanupFailure(val result: StorageCleanupResult) :
    IllegalStateException("部分缓存未能清理，请刷新后重试")

/** The reviewed exact identities remain authoritative; deletion rechecks their live protection. */
internal fun ApplicationOperations.clearStorageCache(
    store: StorageCacheStore, imageLoader: ImageLoader, items: List<StoredCacheItem>,
    clearLegacy: Boolean, clearData: Boolean,
    clearBrowse: suspend () -> Unit, clearMetadata: suspend () -> Unit,
): ApplicationOperationHandle<StorageCleanupResult> {
    val reviewed = items.toList()
    return submit(UserOperationKeys.CACHE, ApplicationOperationKind.CACHE_CLEAR, "清理缓存") {
        var freed = 0L
        var skipped = 0
        var failed = 0
        var accountingComplete = false
        try {
            val result = store.clear(reviewed, clearLegacy)
            freed += result.freedBytes
            skipped += result.skipped
            failed += result.failed
            accountingComplete = true
            // Evict only decoded variants belonging to originals actually removed by this review.
            withContext(Dispatchers.IO) {
                imageLoader.memoryCache?.let { memory ->
                    memory.keys.filter { it.key in result.removedKeys ||
                        it.key.removePrefix("manga-cover:") in result.removedKeys }.forEach { memory.remove(it) }
                }
            }
            if (clearData) {
                val before = store.snapshot()
                accountingComplete = false
                try { clearBrowse(); clearMetadata() }
                finally {
                    val after = store.snapshot()
                    freed += ((before.auxiliaryBytes + before.catalogBytes) -
                        (after.auxiliaryBytes + after.catalogBytes)).coerceAtLeast(0L)
                    accountingComplete = true
                }
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            throw StorageCleanupFailure(StorageCleanupResult(freed, skipped, failed + 1, accountingComplete))
        }
        StorageCleanupResult(freed, skipped, failed).also { result ->
            if (result.failed > 0) throw StorageCleanupFailure(result)
        }
    }
}

internal fun ApplicationOperations.exportReadingBackup(library: LibraryStore): ApplicationOperationHandle<String> =
    submit(UserOperationKeys.BACKUP, ApplicationOperationKind.BACKUP_EXPORT, "准备阅读备份") {
        library.exportBackup()
    }

internal fun ApplicationOperations.inspectReadingBackup(
    context: PlatformContext, library: LibraryStore, uri: String,
): ApplicationOperationHandle<InspectedReadingBackup> {
    val application = applicationContext(context)
    return submit(UserOperationKeys.BACKUP, ApplicationOperationKind.BACKUP_INSPECT, "读取阅读备份",
        onTerminal = { releasePickedDocuments(listOf(uri)) }) {
        val payload = readDocumentText(application, uri, maxBytes = 8 * 1024 * 1024)
        InspectedReadingBackup(payload, library.inspectBackup(payload))
    }
}

internal fun ApplicationOperations.restoreReadingBackup(
    library: LibraryStore, payload: String,
): ApplicationOperationHandle<LibraryBackupInfo> = submit(UserOperationKeys.BACKUP,
    ApplicationOperationKind.BACKUP_RESTORE, "恢复阅读记录") { library.restoreBackup(payload) }

/** Configuration and route intent are fixed for this explicit operation, never silently replayed. */
internal fun ApplicationOperations.webDav(
    library: LibraryStore, store: WebDavBackupStore,
    action: String, draft: WebDavOperationDraft?, route: DownloadRouteSnapshot,
): ApplicationOperationHandle<InspectedReadingBackup?> {
    val kind = when (action) {
        "upload" -> ApplicationOperationKind.WEBDAV_UPLOAD
        "download" -> ApplicationOperationKind.WEBDAV_READ
        else -> ApplicationOperationKind.WEBDAV_SAVE
    }
    val title = when (action) { "upload" -> "上传阅读备份"; "download" -> "读取远端备份"; else -> "保存备份连接" }
    return submit(UserOperationKeys.BACKUP, kind, title) {
        if (action == "clear") {
            store.clearConfiguration()
            return@submit null
        }
        val accepted = requireNotNull(draft)
        store.saveConfiguration(accepted.endpoint, accepted.username, accepted.remotePath,
            accepted.password.takeIf { it.isNotEmpty() })
        if (action == "save") return@submit null
        val snapshot = store.captureTransfer(route)
        when (action) {
            "upload" -> { store.upload(library.exportBackup(), snapshot); null }
            "download" -> store.download(snapshot).let { payload ->
                InspectedReadingBackup(payload, library.inspectBackup(payload))
            }
            else -> error("未知 WebDAV 操作")
        }
    }
}

internal fun ApplicationOperations.installSource(
    store: SourcePluginStore, registry: BodySourceRegistry, bundle: ScriptSourcePackage, markAdult: Boolean,
): ApplicationOperationHandle<String> {
    val accepted = bundle.copy(metadata = JSONObject(bundle.metadata.toString()))
    return submit(UserOperationKeys.SOURCE_INSTALL,
        ApplicationOperationKind.SOURCE_INSTALL, "安装漫画源") {
        store.install(accepted)
        withTimeout(10_000L) {
            registry.entries.first { entries -> entries.any {
                it.isInstalled && it.source.id == accepted.sourceId && it.source.revision == accepted.digest
            } }
        }
        if (markAdult) registry.setAdult(accepted.sourceId, true)
        registry.restore(accepted.sourceId)
        accepted.metadata.optString("name")
    }
}
