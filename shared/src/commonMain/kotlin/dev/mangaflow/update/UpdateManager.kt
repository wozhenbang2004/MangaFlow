package dev.mangaflow.update

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.flow.StateFlow

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

data class AppRelease(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val minSdk: Int,
    val sourceCommit: String,
)

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: AppRelease? = null,
    val downloaded: Long = 0,
    val total: Long = 0,
    val message: String = "检查漫流的新版本",
    val lastChecked: Long = 0,
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    val progress: Float get() = if (total > 0) (downloaded.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

expect class UpdateManager {
    val state: StateFlow<UpdateState>
    val automatic: Boolean
    fun start()
    fun setAutomatic(enabled: Boolean)
    fun check(manual: Boolean = true, onComplete: (() -> Unit)? = null)
    fun download()
    fun cancelDownload()
    fun install(context: PlatformContext)
    companion object {
        val REPOSITORY: String
        val MANIFEST_URL: String
        fun get(context: PlatformContext): UpdateManager
    }
}
