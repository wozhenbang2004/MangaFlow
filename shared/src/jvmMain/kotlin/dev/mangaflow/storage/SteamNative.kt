package dev.mangaflow.storage

/** JNI is loaded lazily when Workshop is opened, never from the application initializer. */
internal object SteamNative {
    @Volatile private var loaded = false

    @Synchronized
    fun load(loader: () -> Unit): Boolean {
        if (loaded) return true
        return try {
            loader()
            loaded = true
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    @JvmStatic external fun nativeStart(requestJson: String): Long
    @JvmStatic external fun nativePoll(job: Long): String?
    @JvmStatic external fun nativeCancel(job: Long)
    @JvmStatic external fun nativeFree(job: Long)
}
