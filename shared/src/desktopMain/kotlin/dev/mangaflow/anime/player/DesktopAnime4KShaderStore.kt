package dev.mangaflow.anime.player

import dev.mangaflow.enhancement.engine.DesktopArtCnn
import dev.mangaflow.enhancement.engine.EngineModel
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
    fun prepare(context: PlatformContext, mode: AnimeVideoEnhancementMode, scale: Int, model: EngineModel?,
        enhanceAtNativeSize: Boolean): List<String> {
        val optional = model?.takeIf { Anime4KOptionalModels.supports(it.engineId) }
        val dedicated = mode == AnimeVideoEnhancementMode.MODEL && optional == null
        val shaders = when {
            dedicated -> listOf(DesktopArtCnn.verifiedShader(checkNotNull(model)))
            optional != null -> Anime4KOptionalModels(context).shaders(optional, scale).map { it.name to it.source.encodeToByteArray() }
            else -> Anime4KShaderLibrary.sources(mode, scale).map { it.name to it.source.encodeToByteArray() }
        }
        if (shaders.isEmpty()) return emptyList()
        val root = Path.of(dataDirectory(context).path).toAbsolutePath().normalize()
        val privateRoot = privateDirectory(root.resolve(if (dedicated) "artcnn" else "anime4k"))
        val version = if (dedicated) DesktopArtCnn.VERSION else Anime4KShaderLibrary.UPSTREAM_COMMIT
        val manualUpscale = scale == 2 && enhanceAtNativeSize
        val directory = privateDirectory(privateRoot.resolve(if (manualUpscale) "$version-manual-2x" else version))
        check(shaders.map { it.first }.distinct().size == shaders.size) { "Duplicate enhancement resource" }
        return shaders.map { (name, source) ->
            check(Regex("(?:Anime4K|ArtCNN)_[A-Za-z0-9_]+\\.glsl").matches(name)) { "Invalid enhancement resource" }
            val bytes = if (manualUpscale && (dedicated || name.startsWith("Anime4K_Upscale_")))
                enableNativeSize(source, dedicated) else source
            val path = directory.resolve(name)
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

    private fun enableNativeSize(bytes: ByteArray, artCnn: Boolean): ByteArray {
        val plane = if (artCnn) "LUMA" else "MAIN"
        val threshold = if (artCnn) "1.3" else "1.200"
        val gate = "//!WHEN OUTPUT.w $plane.w / $threshold > OUTPUT.h $plane.h / $threshold > *"
        val source = bytes.decodeToString()
        check(gate in source) { "无法确认模型的放大条件" }
        // Keep the upstream display-size rule for large sources. A requested 2× graph
        // can also process up to 1080p before mpv resamples to the actual window size.
        val requested = "$gate $plane.w $plane.h * ${1920 * 1080} > ! +"
        return source.replace(gate, requested).encodeToByteArray()
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
