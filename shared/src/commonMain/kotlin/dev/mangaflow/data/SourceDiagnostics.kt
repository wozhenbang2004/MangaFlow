package dev.mangaflow.data

import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SourceStage(val label: String) { CATALOGUE("目录"), SEARCH("搜索"), DETAIL("详情"), PAGES("图片清单"), IMAGE_RESOLVE("图片地址"), IMAGE_READ("图片读取"), IMAGE_DECODE("图片解码"), ACCOUNT("账号"), FAVORITES("站点收藏"), COMMENTS("评论") }
enum class SourceFailureKind(val label: String) { LOGIN("需要登录"), NETWORK("连接失败"), RESPONSE("站点响应"), PARSING("解析失败"), UNSUPPORTED("能力不兼容"), UNKNOWN("读取失败") }
data class SourceHealth(val sourceId: String, val stage: SourceStage, val lastAttemptAt: Long = 0,
    val lastSuccessAt: Long = 0, val checking: Boolean = false, val failure: SourceFailureKind? = null,
    val message: String = "", val operationId: Long = 0)

/** Contains only redacted operation summaries, never request bodies, URLs, credentials or cookies. */
internal class SourceDiagnostics {
    private val sequence = atomic(0L)
    private val mutable = MutableStateFlow<Map<String, SourceHealth>>(emptyMap())
    val state = mutable.asStateFlow()
    fun imageResult(id: String, stage: SourceStage, success: Boolean, at: Long) {
        val token = sequence.incrementAndGet()
        mutable.update { old ->
            val previous = old[id]
            if (previous != null && previous.lastAttemptAt > at) old else old + (id to SourceHealth(id, stage,
                lastAttemptAt = at, lastSuccessAt = if (success) at else previous?.lastSuccessAt ?: 0,
                failure = if (success) null else SourceFailureKind.RESPONSE,
                message = if (success) "" else stage.label + " · 读取失败，请重试或检查来源设置", operationId = token))
        }
    }
    suspend fun <T> track(id: String, stage: SourceStage, operation: suspend () -> T): T {
        val at = wallTimeMillis()
        val token = sequence.incrementAndGet()
        mutable.update { old -> old + (id to SourceHealth(id, stage, at, old[id]?.lastSuccessAt ?: 0, true, operationId = token)) }
        try {
            return operation().also {
                mutable.update { old -> if (old[id]?.operationId == token)
                    old + (id to SourceHealth(id, stage, at, wallTimeMillis(), operationId = token)) else old }
            }
        } catch (failure: Exception) {
            if (failure is CancellationException && failure !is TimeoutCancellationException) {
                mutable.update { old -> if (old[id]?.operationId == token) old + (id to old.getValue(id).copy(checking = false)) else old }
                throw failure
            }
            val type = when {
                (failure as? SourceException)?.httpStatus == 401 || failure.message.orEmpty().contains("登录") -> SourceFailureKind.LOGIN
                failure is TimeoutCancellationException || Regex("(?i)timeout|connect|dns|network|超时|连接|网络").containsMatchIn(failure.message.orEmpty()) -> SourceFailureKind.NETWORK
                (failure as? SourceException)?.httpStatus != null -> SourceFailureKind.RESPONSE
                Regex("不支持|不兼容|能力").containsMatchIn(failure.message.orEmpty()) -> SourceFailureKind.UNSUPPORTED
                Regex("解析|格式|JSON|目录为空").containsMatchIn(failure.message.orEmpty()) -> SourceFailureKind.PARSING
                else -> SourceFailureKind.UNKNOWN
            }
            val code = (failure as? SourceException)?.httpStatus?.let { " · HTTP $it" }.orEmpty()
            val message = stage.label + " · " + type.label + code
            mutable.update { old -> if (old[id]?.operationId == token)
                old + (id to old.getValue(id).copy(checking = false, failure = type, message = message)) else old }
            throw failure
        }
    }
}
