package dev.mangaflow.storage

import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.networkFailureKind
import dev.mangaflow.data.SourceException
import okio.IOException

/** Live transfer facts never count as a saved page until validation and atomic publication finish. */
enum class DownloadPhase(val label: String) {
    QUEUED("等待下载"), PREPARING("核对本地图片"), WAITING("等待恢复"),
    CONNECTING("连接来源"), FIRST_BYTE_WAIT("等待来源响应"), TRANSFERRING("正在接收"),
    VERIFYING("验证图片"), COMMITTING("保存图片"), COMPLETED("已保存"), PAUSED("已暂停"), FAILED("下载未完成"),
}

enum class DownloadWaitReason(val label: String) {
    NO_NETWORK("等待网络连接"), STORAGE_LOW("等待可用空间"), THERMAL("设备温度较高"),
    MEMORY_PRESSURE("等待释放内存"), BACKGROUND_RESTRICTED("后台下载受限"),
    READING_PRIORITY("优先当前阅读"), SOURCE_BACKOFF("来源限制请求"), SOURCE_LIMIT("等待来源空闲"),
    GLOBAL_LIMIT("等待下载空闲"), CHAPTER_LIMIT("等待前方章节"), FAIR_QUEUE("等待下载顺序"),
    NETWORK_RETRY("等待网络重试"), SYSTEM_SUSPENDED("等待系统恢复下载"),
}

enum class DownloadFailureReason(val label: String) {
    ROUTE("线路不可用"), AUTH("来源拒绝访问"), SOURCE_EXPIRED("图片地址已过期"),
    SOURCE_UNAVAILABLE("来源暂不可用"), STORAGE("保存失败"), IMAGE("图片验证失败"),
    TLS("连接证书无效"), TIMEOUT("连接或传输超时"), NETWORK("连接或传输中断"),
}

data class DownloadProgress(
    val phase: DownloadPhase = DownloadPhase.QUEUED,
    /** Zero based; only a single page's exact total can produce a byte percentage. */
    val pageIndex: Int? = null,
    val activePages: Int = 0,
    /** Bytes in uncommitted pages, including safe retained partials; separate from downloadedBytes. */
    val receivedBytes: Long = 0L,
    /** Actual newly received bytes in this execution generation; retained partials do not inflate speed. */
    val transferredBytes: Long = 0L,
    val expectedBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val updatedAt: Long = 0L,
    val phaseStartedAt: Long = 0L,
    val lastProgressAt: Long? = null,
    val waitingReason: DownloadWaitReason? = null,
    val retryAt: Long? = null,
    val routeLabel: String = "",
    val failureReason: DownloadFailureReason? = null,
    val httpStatus: Int? = null,
    val workId: String = "",
    val generation: String = "",
    /** One actual page request; automatic retries can retain a work generation. */
    val transferId: String = "",
)

/** Transport callbacks are synchronous; the store coalesces their publication without disk writes. */
internal data class DownloadPageProgress(
    val phase: DownloadPhase,
    val receivedBytes: Long = 0L,
    val retainedBytes: Long = 0L,
    val expectedBytes: Long? = null,
)

internal fun Exception.downloadHttpStatus(): Int? = when (this) {
    is DownloadHttpException -> code
    is SourceException -> httpStatus
    else -> null
}

internal fun Exception.downloadRetryDelayMillis(): Long = when (this) {
    is DownloadHttpException -> retryAfterMillis ?: 0L
    is SourceException -> retryAfterSeconds.coerceIn(0L, 86_400L) * 1_000L
    else -> 0L
}

internal fun Exception.downloadNeedsSourceRefresh(): Boolean =
    (this as? PermanentDownloadException)?.needsSourceRefresh == true || downloadHttpStatus() in setOf(404, 410)

internal fun Exception.downloadFailureReason(): DownloadFailureReason = when {
    this is DownloadRouteException -> DownloadFailureReason.ROUTE
    this is DownloadStorageException -> DownloadFailureReason.STORAGE
    this is DownloadValidationException -> DownloadFailureReason.IMAGE
    this is PermanentDownloadException -> if (needsSourceRefresh) DownloadFailureReason.SOURCE_EXPIRED else DownloadFailureReason.IMAGE
    downloadHttpStatus() != null -> when (downloadHttpStatus()) {
        401, 403 -> DownloadFailureReason.AUTH
        404, 410 -> DownloadFailureReason.SOURCE_EXPIRED
        else -> DownloadFailureReason.SOURCE_UNAVAILABLE
    }
    else -> when (networkFailureKind(this)) {
        NetworkFailureKind.TLS -> DownloadFailureReason.TLS
        NetworkFailureKind.CONNECT_TIMEOUT, NetworkFailureKind.RESPONSE_TIMEOUT,
        NetworkFailureKind.TRANSFER_TIMEOUT, NetworkFailureKind.REQUEST_TIMEOUT -> DownloadFailureReason.TIMEOUT
        else -> DownloadFailureReason.NETWORK
    }
}

internal fun Exception.canRetryDownload(): Boolean = when {
    this is DownloadRouteException || this is DownloadStorageException || this is PermanentDownloadException -> false
    this is DownloadValidationException -> true
    downloadHttpStatus() != null -> downloadHttpStatus()?.let { it == 408 || it == 429 || it in 500..599 } == true
    networkFailureKind(this) == NetworkFailureKind.TLS -> false
    networkFailureKind(this) != null -> true
    else -> this is IOException || this is kotlinx.io.IOException || this is SourceException
}
