package dev.mangaflow.platform

import okio.Buffer
import okio.FileSystem
import okio.SYSTEM
import okio.HashingSource
import okio.Path.Companion.toPath
import okio.buffer

/** Hash installers/media as a bounded stream, rather than loading the whole file into memory. */
fun sha256File(path: String): String = FileSystem.SYSTEM.source(path.toPath()).use { raw ->
    val hashing = HashingSource.sha256(raw)
    hashing.buffer().use { input ->
        val scratch = Buffer()
        while (input.read(scratch, 64L * 1024) != -1L) scratch.clear()
        hashing.hash.hex()
    }
}
