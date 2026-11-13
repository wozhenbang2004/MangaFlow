package dev.mangaflow.storage

import dev.mangaflow.platform.storage.StorageFile
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.SYSTEM

internal fun localDocumentFile(uri: String): StorageFile {
    val path = if (uri.startsWith("file://", true)) {
        val raw = uri.substring(7)
        require(raw.startsWith('/')) { "只能读取本机文件。" }
        raw.decodeURLPart().let { if (Regex("^/[A-Za-z]:/").containsMatchIn(it)) it.drop(1) else it }
    } else uri.also { require(!it.contains("://")) { "此文件地址无法在本机读取。" } }
    return StorageFile(path)
}

internal suspend fun collectLocalFolderImages(uri: String): List<LocalDocument> {
    val directory = localDocumentFile(uri)
    require(directory.isDirectory) { "所选内容不是可读取的文件夹。" }
    val documents = mutableListOf<LocalDocument>()
    val root = directory.canonicalFile.nativePath
    var visited = 0
    for (path in FileSystem.SYSTEM.listRecursively(root, followSymlinks = false)) {
        currentCoroutineContext().ensureActive()
        require(++visited <= 100_000) { "文件夹包含过多内容。" }
        require(path.segments.size - root.segments.size <= 16) { "文件夹层级过深。" }
        val metadata = FileSystem.SYSTEM.metadata(path)
        if (metadata.isRegularFile && metadata.symlinkTarget == null && isImageName(path.name)) {
            documents += LocalDocument(StorageFile(path).toURI(), path.relativeTo(root).toString())
            require(documents.size <= 5_000) { "文件夹图片超过 5000 页。" }
        }
    }
    return documents.sortedWith(compareByNatural(LocalDocument::name))
}
