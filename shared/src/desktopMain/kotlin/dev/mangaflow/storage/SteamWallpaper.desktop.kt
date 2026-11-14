package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.cacheDirectory
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

actual fun createSteamWallpaperClient(context: PlatformContext): SteamWallpaperClient =
    JvmSteamWallpaperClient(context, prepareNative = { _, _ ->
        SteamNative.load {
            require(System.getProperty("os.name").orEmpty().lowercase().contains("win"))
            val root = File(cacheDirectory(context).path, "steam-native").canonicalFile
            check(root.isDirectory || root.mkdirs())
            val temporary = File.createTempFile("steam-", ".dll.part", root)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val resource = SteamNative::class.java.getResourceAsStream("/steam/windows-x64/mangaflow_steam.dll")
                    ?: error("Steam component missing")
                resource.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 64L * 1024 * 1024)
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        require(total > 0)
                        output.fd.sync()
                    }
                }
                val expected = digest.digest()
                val identity = expected.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                val directory = File(root, identity)
                check(directory.isDirectory || directory.mkdir())
                check(directory.canonicalFile == directory.absoluteFile)
                val target = File(directory, "mangaflow_steam.dll")
                if (target.exists()) {
                    check(target.canonicalFile == target.absoluteFile && target.isFile &&
                        MessageDigest.isEqual(hashSteamLibrary(target), expected))
                } else check(temporary.renameTo(target))
                check(MessageDigest.isEqual(hashSteamLibrary(target), expected))
                System.load(target.canonicalPath)
            } finally { temporary.delete() }
        }
    })

private fun hashSteamLibrary(file: File): ByteArray {
    require(file.length() in 1..(64L * 1024 * 1024))
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest()
}
