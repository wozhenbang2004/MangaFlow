package dev.mangaflow.data.plugins

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.sha256
import dev.mangaflow.data.SourceException
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class StarterSourceState(val running: Boolean = false, val phase: String = "",
    val completed: Int = 0, val total: Int = 3, val progress: Int? = null,
    val preparingEngine: Boolean = false, val error: String? = null)

/** Tiny pinned references, not redistributed source code, covers, manga or native libraries. */
internal class StarterSourceInstaller(private val context: PlatformContext,
    private val store: SourcePluginStore, private val scope: CoroutineScope) {
    private val control = SynchronizedObject()
    private var attempted = false
    private var job: Job? = null
    private val mutableState = MutableStateFlow(StarterSourceState())
    val state = mutableState.asStateFlow()

    fun start(retry: Boolean = false): Unit = synchronized(control) {
        if (job?.isCompleted == false || attempted && !retry) return@synchronized
        attempted = true
        val next = scope.launch(start = CoroutineStart.LAZY) { install(prepareExistingEngine = retry) }
        job = next
        next.start()
        Unit
    }

    fun cancel() {
        synchronized(control) { job?.cancel() }
    }

    private suspend fun install(prepareExistingEngine: Boolean) {
        mutableState.value = StarterSourceState(running = true, phase = "正在准备精选来源")
        try {
            store.ready.await()
            val pending = STARTERS.filterNot { store.hasSourceHistory(it.sourceId) }
            val repairs = store.pendingHostRepairs()
            mutableState.value = mutableState.value.copy(completed = STARTERS.size - pending.size, total = STARTERS.size + repairs.size)
            if (pending.isEmpty() && repairs.isEmpty()) return
            // Existing-source maintenance can validate an installed engine, but never restores
            // an explicitly removed optional component without the user's retry action.
            if (pending.isNotEmpty() || prepareExistingEngine) prepareEngine()
            val failed = mutableListOf<String>()
            for (source in pending) {
                currentCoroutineContext().ensureActive()
                // A manual import/removal while another source downloaded takes precedence.
                if (!store.hasSourceHistory(source.sourceId)) {
                    mutableState.value = mutableState.value.copy(phase = "正在安装 ${source.title}",
                        progress = null, preparingEngine = false)
                    try {
                        withTimeout(30_000L) { withContext(Dispatchers.IO) {
                            val pinnedUrl = "$PINNED_BASE/${source.file}"
                            val code = fetchPluginText(context, pinnedUrl)
                            val bytes = code.encodeToByteArray()
                            require(bytes.size == source.bytes && digest(bytes) == source.sha256) { "精选源脚本校验失败。" }
                            val liveUrl = "$UPDATE_BASE/${source.file}"
                            val candidate = store.preview(code, pinnedUrl).single { it.metadata.optString("key") == source.key }
                                .copy(codeUrl = liveUrl)
                            val prepared = store.prepare(candidate)
                            require(prepared.sourceId == source.sourceId && prepared.metadata.optBoolean("isAdult", false)) {
                                "精选源身份或分区不一致。"
                            }
                            store.installIfUnseen(prepared)
                        } }
                    } catch (_: TimeoutCancellationException) { failed += source.title + " · 下载或校验超时" }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { failed += source.title + " · " + failureReason(failure) }
                }
                mutableState.value = mutableState.value.copy(completed = mutableState.value.completed + 1)
            }
            for (source in repairs) {
                currentCoroutineContext().ensureActive()
                val name = source.metadata.optString("name").ifBlank { "来源" }
                mutableState.value = mutableState.value.copy(phase = "正在修复 $name", progress = null, preparingEngine = false)
                try {
                    withTimeout(30_000L) { store.applyHostRepair(source.sourceId, source.digest) }
                } catch (_: TimeoutCancellationException) { failed += name + " · 兼容修复超时" }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) {
                    val engine = engineMaintenanceIssue()
                    if (engine != null) { failed += engine; break }
                    failed += name + " · 兼容修复未完成"
                }
                mutableState.value = mutableState.value.copy(completed = mutableState.value.completed + 1)
            }
            mutableState.value = mutableState.value.copy(phase = "来源准备完成",
                error = failed.takeIf { it.isNotEmpty() }?.joinToString("；"))
        } catch (_: TimeoutCancellationException) {
            mutableState.value = mutableState.value.copy(error = "脚本组件准备超时，可重试或在来源管理中查看。")
        } catch (cancel: CancellationException) {
            mutableState.value = mutableState.value.copy(error = "精选来源安装已暂停，已装来源保留。")
            throw cancel
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "精选来源暂未准备完成，请检查来源存储与脚本组件后重试。")
        } finally {
            mutableState.value = mutableState.value.copy(running = false, preparingEngine = false, progress = null)
        }
    }

    private suspend fun prepareEngine() {
        if (platformKind != PlatformKind.ANDROID) return
        val runtimes = PanelRuntimeStore.get(context)
        val id = PanelRuntimeId.QUICKJS
        check(runtimes.states.value[id]?.pendingRemoval != true) { "脚本组件等待移除，请重启后再准备。" }
        if (runtimes.states.value[id]?.installed == true && runtimes.states.value[id]?.downloading != true) return
        mutableState.value = mutableState.value.copy(phase = "正在准备漫画源脚本组件", preparingEngine = true)
        runtimes.download(id)
        val ready = withTimeout(120_000L) {
            runtimes.states.first { states ->
                val status = states[id]
                mutableState.value = mutableState.value.copy(progress = status?.progress?.let { (it * 100).toInt().coerceIn(0, 100) })
                status != null && !status.downloading
            }.getValue(id)
        }
        check(ready.installed && !ready.pendingRemoval) { "漫画源脚本组件尚未就绪。" }
        mutableState.value = mutableState.value.copy(preparingEngine = false, progress = null)
    }

    private fun engineMaintenanceIssue(): String? {
        if (platformKind != PlatformKind.ANDROID) return null
        val engine = PanelRuntimeStore.get(context).states.value[PanelRuntimeId.QUICKJS]
        return when {
            engine?.pendingRemoval == true -> "脚本组件等待移除，请重启后重试。"
            engine?.downloading == true -> "脚本组件正在准备，完成后可重试兼容修复。"
            engine?.installed != true -> "脚本组件未就绪，重试可准备并修复已有来源。"
            else -> null
        }
    }

    private data class Starter(val key: String, val title: String, val file: String,
        val sourceId: String, val bytes: Int, val sha256: String)

    private companion object {
        const val PINNED_BASE = "https://raw.githubusercontent.com/venera-app/venera-configs/a04e05591f7832f7166d6660546613c114d6738a"
        const val UPDATE_BASE = "https://raw.githubusercontent.com/venera-app/venera-configs/main"
        val STARTERS = listOf(
            Starter("nhentai", "nhentai", "nhentai.js", "js_4121890e56c894baf221b16aaf5ec8adb85c", 58_970,
                REVIEWED_VENERA_CODE_DIGESTS.getValue("nhentai@1.1.0")),
            Starter("wnacg", "WNACG", "wnacg.js", "js_ac54e53a451f4f84f635c7c6cfac9ecd2cdb", 32_774,
                REVIEWED_VENERA_CODE_DIGESTS.getValue("wnacg@1.0.6")),
            Starter("ehentai", "E-Hentai", "ehentai.js", "js_d0e1410e9d2b0f26739feea876b7b82d11e9", 61_741,
                REVIEWED_VENERA_CODE_DIGESTS.getValue("ehentai@1.2.0")),
        )
        fun digest(bytes: ByteArray): String = sha256(bytes).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        fun failureReason(failure: Exception): String = when {
            failure is DownloadRouteException -> "下载路由不可用"
            failure is SourceException && failure.httpStatus != null -> "HTTP ${failure.httpStatus}"
            failure.message.orEmpty().contains("校验") -> "文件校验失败"
            Regex("网络|连接|下载|DNS", RegexOption.IGNORE_CASE).containsMatchIn(failure.message.orEmpty()) -> "下载失败"
            Regex("脚本|声明|宿主|不兼容").containsMatchIn(failure.message.orEmpty()) -> "脚本不兼容"
            else -> "安装未完成"
        }
    }
}
