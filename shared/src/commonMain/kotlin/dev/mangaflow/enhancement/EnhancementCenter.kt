package dev.mangaflow.enhancement

import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.StorageLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One application lifetime owns settings, engines, scheduling and measured outcomes. */
class EnhancementCenter private constructor(context: PlatformContext) {
    private val app = applicationContext(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val resources = ExperiencePolicyStore.get(app)
    val settings = EnhancementSettingsStore(app)
    val engine = createEnhancementEngine(app)
    val telemetry = EnhancementTelemetry(app, scope) { resources.usage.value.resources }
    val scheduler = EnhancementScheduler(scope, resources)
    private val mutableVideoPreparation = MutableStateFlow<VideoPreparationStore?>(null)
    val videoPreparation: StateFlow<VideoPreparationStore?> = mutableVideoPreparation.asStateFlow()
    internal fun attachVideoPreparation(store: VideoPreparationStore) { mutableVideoPreparation.value = store }
    val chapters by lazy { ReaderChapterEnhancementStore(app, this) }
    private val inventoryWriter = Mutex()
    private val installLock = StorageLock()
    private val installations = mutableMapOf<String, Job>()
    private val modelDownloads = mutableMapOf<String, Deferred<EngineModel>>()
    private val mutableInventory = MutableStateFlow(EngineInventory(emptyList()))
    val inventory: StateFlow<EngineInventory> = mutableInventory.asStateFlow()
    private val mutableEngineProgress = MutableStateFlow<Map<String, EngineProgress>>(emptyMap())
    val engineProgress: StateFlow<Map<String, EngineProgress>> = mutableEngineProgress.asStateFlow()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in writes) write() }
        refreshInventory()

        // Android runtime download() only enqueues the OS-managed transfer.
        scope.launch {
            var lastReadiness: Pair<Boolean, Boolean>? = null
            PanelRuntimeStore.get(app).states.collect { states ->
                val runtime = states[PanelRuntimeId.ONNX] ?: return@collect
                val readiness = runtime.installed to runtime.downloading
                if (lastReadiness != readiness) {
                    lastReadiness = readiness
                    refreshInventory()
                }
                if (mutableInventory.value.engines.any { it.id == EnhancementEngineIds.ANDROID_ONNX }) {
                    val status = when {
                        runtime.downloading -> EngineProgress(EngineStage.DOWNLOAD,
                            (runtime.progress?.let { (it * runtime.totalBytes).toLong() } ?: runtime.bytes)
                                .coerceAtLeast(0L), runtime.totalBytes.takeIf { it > 0L },
                            detail = "ONNX 运行库正在后台下载", bytes = runtime.bytes)
                        runtime.installed -> null
                        runtime.error != null -> EngineProgress(EngineStage.INSTALL, detail = runtime.error)
                        else -> null
                    }
                    mutableEngineProgress.update { current ->
                        if (status == null) current - EnhancementEngineIds.ANDROID_ONNX -
                            EnhancementEngineIds.ANDROID_VIDEO_ONNX
                        else current + (EnhancementEngineIds.ANDROID_ONNX to status) +
                            (EnhancementEngineIds.ANDROID_VIDEO_ONNX to status)
                    }
                }
            }
        }
    }

    fun refreshInventory(): Job = scope.launch {
        inventoryWriter.withLock {
            try {
                mutableInventory.value = engine.inspect()
                mutableEngineProgress.update { it - "inventory" }
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutableEngineProgress.update { it + ("inventory" to
                EngineProgress(EngineStage.VERIFY, detail = error.message ?: "无法读取增强引擎")) } }
        }
    }

    fun installEngine(id: String): Job = synchronized(installLock) {
        installations[id]?.takeIf { it.isActive } ?: scope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.install(id) { progress -> mutableEngineProgress.update { it + (id to progress) } }
                val inventory = inventoryWriter.withLock {
                    engine.inspect().also { mutableInventory.value = it }
                }
                val readiness = inventory.engines.firstOrNull { it.id == id }?.readiness
                mutableEngineProgress.update { current -> current + (id to
                    if (readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.NEEDS_MODELS,
                            EngineReadiness.READY_FOR_TRIAL))
                        EngineProgress(EngineStage.COMPLETE,
                            detail = "运行库已准备；仍须选择模型并通过设备试用。")
                    else EngineProgress(EngineStage.DOWNLOAD,
                        detail = "已加入后台下载，安装与校验尚未完成。")) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutableEngineProgress.update { it + (id to
                EngineProgress(EngineStage.INSTALL, detail = error.message ?: "安装未完成，请重试")) } }
        }.also { installations[id] = it; it.start() }
    }

    fun importModel(sourcePath: String, nativeScale: Int, content: EngineContent): Deferred<EngineModel> = scope.async {
        val model = engine.importModel(sourcePath, nativeScale, content)
        inventoryWriter.withLock { mutableInventory.value = engine.inspect() }
        model
    }


    /** Application-lifetime work is not cancelled when the sheet is dismissed. */
    fun downloadModel(offer: EnhancementModelOffer): Deferred<EngineModel> = synchronized(installLock) {
        modelDownloads[offer.id]?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
            val key = "model:${offer.id}"
            try {
                val model = engine.downloadModel(offer) { value ->
                    mutableEngineProgress.update { it + (key to value) }
                }
                inventoryWriter.withLock { mutableInventory.value = engine.inspect() }
                mutableEngineProgress.update { it + (key to EngineProgress(EngineStage.COMPLETE,
                    detail = "模型已校验安装；还需在本机试用。")) }
                model
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                mutableEngineProgress.update { it + (key to EngineProgress(EngineStage.INSTALL,
                    detail = failure.message ?: "模型下载未完成")) }
                throw failure
            }
        }.also { modelDownloads[offer.id] = it; it.start() }
    }

    /** Accepted settings edits survive a panel closing and are committed in click order. */
    fun savePolicy(content: EnhancementContent, policy: EnhancementPolicy, sessionKey: String? = null): Deferred<Boolean> {
        policy.checked()
        val receipt = CompletableDeferred<Boolean>()
        val sessionToken = sessionKey?.let(settings::sessionToken)
        writes.trySend {
            try {
                val activeTrial = telemetry.state.value.activeTrial
                val changesTrial = activeTrial?.takeIf { it.content == content }?.let { trial ->
                    (sessionKey == trial.sessionKey || sessionKey == null && settings.sessions.value[trial.sessionKey] == null) &&
                        settings.policy(content, trial.sessionKey) != policy
                } == true
                if (changesTrial && activeTrial != null) telemetry.finishTrial(activeTrial.id, "试用设置已改变")
                val saved = if (sessionKey == null) settings.update(content, policy)
                    else settings.setSessionOverrideIfCurrent(sessionKey, content, policy, checkNotNull(sessionToken))
                receipt.complete(saved)
            } catch (error: Exception) { receipt.completeExceptionally(error) }
        }.getOrThrow()
        return receipt
    }

    fun startTrial(content: EnhancementContent, sessionKey: String, durationSeconds: Int = 15): String {
        require(durationSeconds in 5..120) { "短时试用需要 5～120 秒" }
        val id = telemetry.beginTrial(content, sessionKey, settings.policy(content, sessionKey))
        scope.launch { delay(durationSeconds * 1000L); telemetry.finishTrial(id) }
        return id
    }

    fun resetSessionPolicy(sessionKey: String) {
        telemetry.state.value.activeTrial?.takeIf { it.sessionKey == sessionKey }?.let {
            telemetry.finishTrial(it.id, "本次设置已恢复为默认值")
        }
        settings.clearSession(sessionKey)
    }

    fun endSession(sessionKey: String) {
        scheduler.cancelSession(sessionKey)
        settings.clearSession(sessionKey)
        telemetry.endSession(sessionKey)
    }

    companion object {
        private val lock = StorageLock()
        private var instance: EnhancementCenter? = null
        fun get(context: PlatformContext): EnhancementCenter = synchronized(lock) {
            instance ?: EnhancementCenter(context).also { instance = it }
        }
    }
}
