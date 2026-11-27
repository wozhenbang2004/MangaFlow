package dev.mangaflow.enhancement

import dev.mangaflow.platform.storage.StorageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import okio.HashingSink
import okio.SYSTEM
import okio.use

/** Pin a mutable local input and verify the bytes being copied without reopening the copy. */
internal suspend fun copyReaderEnhancementInput(source: StorageFile, destination: StorageFile, expectedDigest: String) =
    withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val buffer = Buffer()
        FileSystem.SYSTEM.source(source.nativePath).use { input ->
            HashingSink.sha256(FileSystem.SYSTEM.sink(destination.nativePath, mustCreate = true)).use { output ->
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer, 64L * 1024)
                    if (count == -1L) break
                    output.write(buffer, count)
                }
                check(output.hash.hex() == expectedDigest) { "原图在复制时发生变化" }
            }
        }
    }
