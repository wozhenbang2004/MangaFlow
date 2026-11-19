package dev.mangaflow.enhancement

import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.experience.ExperiencePolicyStore
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
    private val mutableInventory = MutableStateFlow(EngineInventory(emptyList()))
    val inventory: StateFlow<EngineInventory> = mutableInventory.asStateFlow()
    private val mutableEngineProgress = MutableStateFlow<Map<String, EngineProgress>>(emptyMap())
    val engineProgress: StateFlow<Map<String, EngineProgress>> = mutableEngineProgress.asStateFlow()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (write in writes) write() }
        refreshInventory()
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
                // Re-read under the same writer: another import may have completed during installation.
                inventoryWriter.withLock { mutableInventory.value = engine.inspect() }
                mutableEngineProgress.update { it + (id to EngineProgress(EngineStage.COMPLETE,
                    detail = "安装已完成；实际输出与性能请在当前设备试用。")) }
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
