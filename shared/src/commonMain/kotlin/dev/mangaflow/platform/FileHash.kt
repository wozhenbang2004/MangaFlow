package dev.mangaflow.platform

import okio.Buffer
import okio.FileSystem
import okio.SYSTEM
import okio.HashingSource
import okio.Path.Companion.toPath
import okio.buffer
import okio.use as useOkio

/** Hash installers/media as a bounded stream, rather than loading the whole file into memory. */
fun sha256File(path: String): String = FileSystem.SYSTEM.source(path.toPath()).useOkio { raw ->
    val hashing = HashingSource.sha256(raw)
    hashing.buffer().useOkio { input ->
        val scratch = Buffer()
        while (input.read(scratch, 64L * 1024) != -1L) scratch.clear()
        hashing.hash.hex()
    }
}
