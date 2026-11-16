package dev.mangaflow.anime.player

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.dataDirectory
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** App-owned, immutable-by-version resources; no media/source identity enters paths. */
internal object DesktopAnime4KShaderStore {
    fun prepare(context: PlatformContext, mode: AnimeVideoEnhancementMode): List<String> {
        val shaders = Anime4KShaderLibrary.sources(mode)
        if (shaders.isEmpty()) return emptyList()
        val root = Path.of(dataDirectory(context).path).toAbsolutePath().normalize()
        val privateRoot = privateDirectory(root.resolve("anime4k"))
        val directory = privateDirectory(privateRoot.resolve(Anime4KShaderLibrary.UPSTREAM_COMMIT))
        check(shaders.map { it.name }.distinct().size == shaders.size) { "Duplicate enhancement resource" }
        return shaders.map { shader ->
            check(Regex("Anime4K_[A-Za-z0-9_]+\\.glsl").matches(shader.name)) { "Invalid enhancement resource" }
            val path = directory.resolve(shader.name)
            val bytes = shader.source.encodeToByteArray()
            val digest = sha256(bytes)
            if (!matches(path, bytes.size, digest)) {
                check(!Files.isSymbolicLink(path)) { "Enhancement resource must be an ordinary file" }
                val temporary = Files.createTempFile(directory, ".anime4k-", ".tmp")
                try {
                    privateFile(temporary)
                    FileChannel.open(temporary, WRITE).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    // An unsupported atomic move makes this optional enhancement
                    // unavailable; never expose a partially-written shader to mpv.
                    Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            check(matches(path, bytes.size, digest)) { "Enhancement resource is incomplete" }
            privateFile(path)
            path.toString()
        }
    }

    private fun privateDirectory(path: Path): Path {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            val attributes = if (Files.getFileStore(path.parent).supportsFileAttributeView("posix"))
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            else emptyArray()
            try { Files.createDirectory(path, *attributes) }
            catch (_: FileAlreadyExistsException) { /* Another player prepared the same pinned version. */ }
        }
        check(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Enhancement directory is unavailable" }
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }

    private fun privateFile(path: Path) {
        if (Files.getFileStore(path).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }

    private fun matches(path: Path, size: Int, digest: ByteArray): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == size.toLong() &&
            MessageDigest.isEqual(sha256(Files.readAllBytes(path)), digest)
}
