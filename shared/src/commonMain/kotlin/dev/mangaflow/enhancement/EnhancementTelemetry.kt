package dev.mangaflow.enhancement

import dev.mangaflow.enhancement.engine.EngineOutput
import dev.mangaflow.experience.ExperienceResources
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Null means not observed. In particular submission throughput is never presentation FPS. */
data class EnhancementSessionMetrics(
    val sessionKey: String,
    val content: EnhancementContent,
    val updatedAtMillis: Long = 0L,
    val currentEnhanced: Boolean = false,
    val nextReady: Boolean = false,
    val reason: String? = null,
    val model: String? = null,
    val modelDigest: String? = null,
    val outputWidth: Int? = null,
    val outputHeight: Int? = null,
    val sourceFps: Double? = null,
    val targetFps: Double? = null,
    val presentedFps: Double? = null,
    val submittedFrames: Long? = null,
    val completedFrames: Long? = null,
    val presentedFrames: Long? = null,
    val droppedFrames: Long? = null,
    val repeatedFrames: Long? = null,
    val generatedFrames: Long? = null,
    val generationFps: Double? = null,
    val skippedFrames: Long? = null,
    val preparedSeconds: Double? = null,
    val imageSamples: Long = 0,
    val imageP50Millis: Double? = null,
    val imageP95Millis: Double? = null,
    val gpuP50Millis: Double? = null,
    val gpuP95Millis: Double? = null,
    val submitCpuP50Millis: Double? = null,
    val submitCpuP95Millis: Double? = null,
    val gpuSampleSequence: Long? = null,
    val gpuSubmittedAtNanos: Long? = null,
    val gpuCompletionObservedAtNanos: Long? = null,
    val presentedAtNanos: Long? = null,
    val cacheLookups: Long = 0,
    val cacheHits: Long = 0,
    val adjustments: List<String> = emptyList(),
) {
    val cacheHitRatio: Double? get() = if (cacheLookups > 0) cacheHits.toDouble() / cacheLookups else null
}

enum class TrialConfidence(val label: String) {
    NO_SAMPLES("没有有效样本"), CURRENT_CONTENT("仅当前内容的短时结果"), INTERRUPTED("试用已中断 · 部分样本")
}
data class EnhancementTrial(
    val id: String,
    val sessionKey: String,
    val content: EnhancementContent,
    val tier: QualityTier,
    val scale: Int,
    val startedAt: Long,
    val durationMillis: Long = 0,
    val samples: Long = 0,
    val outputLabel: String? = null,
    val gpuP95Millis: Double? = null,
    val processingP95Millis: Double? = null,
    val presentedFps: Double? = null,
    val droppedFrames: Long? = null,
    val thermalStart: Int? = null,
    val thermalEnd: Int? = null,
    val modelDigest: String? = null,
    val deviceFingerprint: String,
    val confidence: TrialConfidence = TrialConfidence.NO_SAMPLES,
    val reason: String? = null,
)
data class EnhancementTelemetryState(
    val sessions: Map<String, EnhancementSessionMetrics> = emptyMap(),
    val trials: List<EnhancementTrial> = emptyList(),
    val activeTrial: EnhancementTrial? = null,
    val persistenceErrors: Map<EnhancementContent, String> = emptyMap(),
)

/** Bounded current-session observations, with partitioned, local-only trial history. */
class EnhancementTelemetry(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val resources: () -> ExperienceResources,
) {
    private val lock = StorageLock()
    private val trialWriter = Mutex()
    private val imageTimes = mutableMapOf<String, MutableList<Double>>()
    private val gpuTimes = mutableMapOf<String, MutableList<Double>>()
    private val submitTimes = mutableMapOf<String, MutableList<Double>>()
    private var trialStartMono = 0L
    private var trialBase: EnhancementSessionMetrics? = null
    private val trialImageTimes = mutableListOf<Double>()
    private val trialGpuTimes = mutableListOf<Double>()
    private val mutableState = MutableStateFlow(EnhancementTelemetryState(trials = readTrials()))
    val state: StateFlow<EnhancementTelemetryState> = mutableState.asStateFlow()

    fun recordImage(sessionKey: String, content: EnhancementContent, output: EngineOutput, cacheHit: Boolean = false) = synchronized(lock) {
        val old = session(sessionKey, content)
        if (!cacheHit) {
            sample(imageTimes, sessionKey, output.elapsedMillis.toDouble())
            output.gpuExecutionMillis?.let { sample(gpuTimes, sessionKey, it) }
            if (mutableState.value.activeTrial?.sessionKey == sessionKey) {
                trialImageTimes.add(output.elapsedMillis.toDouble())
                output.gpuExecutionMillis?.let { trialGpuTimes.add(it) }
            }
        }
        // Ahead-of-reader work updates processing statistics, not the visible page's identity.
        val readerOwnsVisibleResult = old.currentEnhanced != null
        put(old.copy(updatedAtMillis = monotonicTimeMillis(),
            model = if (readerOwnsVisibleResult) old.model else output.engineId,
            modelDigest = if (readerOwnsVisibleResult) old.modelDigest else output.modelSha256,
            outputWidth = if (readerOwnsVisibleResult) old.outputWidth else output.width,
            outputHeight = if (readerOwnsVisibleResult) old.outputHeight else output.height,
            imageSamples = old.imageSamples + if (cacheHit) 0 else 1,
            imageP50Millis = percentile(imageTimes[sessionKey], .50), imageP95Millis = percentile(imageTimes[sessionKey], .95),
            gpuP50Millis = percentile(gpuTimes[sessionKey], .50), gpuP95Millis = percentile(gpuTimes[sessionKey], .95)))
    }

    fun recordCacheLookup(sessionKey: String, content: EnhancementContent, hit: Boolean) = synchronized(lock) {
        val old = session(sessionKey, content)
        put(old.copy(updatedAtMillis = monotonicTimeMillis(), cacheLookups = old.cacheLookups + 1,
            cacheHits = old.cacheHits + if (hit) 1 else 0))
    }
    fun setReaderState(sessionKey: String, content: EnhancementContent, currentEnhanced: Boolean,
        nextReady: Boolean, reason: String? = null, model: String? = null, modelDigest: String? = null,
        outputWidth: Int? = null, outputHeight: Int? = null) = synchronized(lock) {
        put(session(sessionKey, content).copy(updatedAtMillis = monotonicTimeMillis(), currentEnhanced = currentEnhanced,
            nextReady = nextReady, reason = reason, model = model, modelDigest = modelDigest,
            outputWidth = outputWidth, outputHeight = outputHeight))
    }

    /** Each argument must be supplied by the named native observation, not a configured target. */
    fun recordVideo(sessionKey: String, model: String?, sourceFps: Double?, targetFps: Double?,
        outputWidth: Int?, outputHeight: Int?, submittedFrames: Long?, submitCpuNanos: Long?,
        presentedFps: Double? = null, presentedFrames: Long? = null, droppedFrames: Long? = null,
        repeatedFrames: Long? = null, preparedSeconds: Double? = null, reason: String? = null,
        gpuSampleSequence: Long? = null, gpuExecutionNanos: Long? = null,
        gpuSubmittedAtNanos: Long? = null, gpuCompletionObservedAtNanos: Long? = null, presentedAtNanos: Long? = null,
    ) = synchronized(lock) {
        val old = session(sessionKey, EnhancementContent.ANIME)
        if (old.model != model || old.outputWidth != outputWidth || old.outputHeight != outputHeight) {
            val hadSamples = !gpuTimes[sessionKey].isNullOrEmpty() || !submitTimes[sessionKey].isNullOrEmpty()
            if (hadSamples) mutableState.value.activeTrial?.takeIf { it.sessionKey == sessionKey }?.let {
                finishTrial(it.id, "实际处理模型或输出尺寸已改变")
            }
            gpuTimes.remove(sessionKey)
            submitTimes.remove(sessionKey)
        }
        if (submittedFrames != old.submittedFrames) submitCpuNanos?.takeIf { it >= 0 }?.let {
            sample(submitTimes, sessionKey, it / 1_000_000.0)
        }
        if (gpuSampleSequence != old.gpuSampleSequence || gpuSubmittedAtNanos != old.gpuSubmittedAtNanos)
            gpuExecutionNanos?.takeIf { it >= 0 }?.let {
            val millis = it / 1_000_000.0
            sample(gpuTimes, sessionKey, millis)
            if (mutableState.value.activeTrial?.sessionKey == sessionKey) trialGpuTimes.add(millis)
        }
        put(old.copy(updatedAtMillis = monotonicTimeMillis(), model = model,
            sourceFps = sourceFps?.validPositive(), targetFps = targetFps?.validPositive(),
            outputWidth = outputWidth, outputHeight = outputHeight, submittedFrames = submittedFrames,
            presentedFps = presentedFps?.takeIf { it.isFinite() && it >= 0 }, presentedFrames = presentedFrames,
            droppedFrames = droppedFrames, repeatedFrames = repeatedFrames, preparedSeconds = preparedSeconds,
            submitCpuP50Millis = percentile(submitTimes[sessionKey], .50),
            submitCpuP95Millis = percentile(submitTimes[sessionKey], .95), reason = reason,
            gpuP50Millis = percentile(gpuTimes[sessionKey], .50), gpuP95Millis = percentile(gpuTimes[sessionKey], .95),
            gpuSampleSequence = gpuSampleSequence, gpuSubmittedAtNanos = gpuSubmittedAtNanos,
            gpuCompletionObservedAtNanos = gpuCompletionObservedAtNanos, presentedAtNanos = presentedAtNanos))
    }

    fun noteAdjustment(sessionKey: String, content: EnhancementContent, reason: String) = synchronized(lock) {
        val old = session(sessionKey, content)
        if (old.adjustments.lastOrNull() != reason) put(old.copy(adjustments = (old.adjustments + reason).takeLast(12)))
    }

    fun beginTrial(content: EnhancementContent, sessionKey: String, policy: EnhancementPolicy): String = synchronized(lock) {
        check(mutableState.value.activeTrial == null) { "已有性能试用正在记录" }
        val measured = resources()
        val trial = EnhancementTrial(randomUuid(), sessionKey, content, policy.tier, policy.scale, wallTimeMillis(),
            thermalStart = measured.thermalPressure,
            deviceFingerprint = sha256(measured.device.toString().encodeToByteArray()).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            })
        trialStartMono = monotonicTimeMillis()
        trialBase = mutableState.value.sessions[sessionKey]
        trialImageTimes.clear(); trialGpuTimes.clear()
        mutableState.value = mutableState.value.copy(activeTrial = trial)
        trial.id
    }

    fun finishTrial(id: String, reason: String? = null) = synchronized(lock) {
        val trial = mutableState.value.activeTrial?.takeIf { it.id == id } ?: return@synchronized
        val last = mutableState.value.sessions[trial.sessionKey]
        val images = ((last?.imageSamples ?: 0L) - (trialBase?.imageSamples ?: 0L)).coerceAtLeast(0L)
        // A renderer submission is not a presented-frame measurement. It still records processing activity.
        val frames = difference(last?.submittedFrames, trialBase?.submittedFrames) ?: 0L
        val samples = images + frames
        val result = trial.copy(durationMillis = monotonicTimeMillis() - trialStartMono, samples = samples,
            outputLabel = if (samples > 0 && last?.outputWidth != null && last.outputHeight != null)
                "${last.outputWidth} × ${last.outputHeight}" else null,
            gpuP95Millis = percentile(trialGpuTimes, .95), processingP95Millis = percentile(trialImageTimes, .95),
            presentedFps = last?.presentedFps.takeIf { samples > 0 }, droppedFrames = difference(last?.droppedFrames, trialBase?.droppedFrames),
            thermalEnd = resources().thermalPressure, modelDigest = last?.modelDigest,
            confidence = when {
                samples == 0L -> TrialConfidence.NO_SAMPLES
                reason != null -> TrialConfidence.INTERRUPTED
                else -> TrialConfidence.CURRENT_CONTENT
            },
            reason = reason ?: if (samples == 0L) "本次没有测到增强处理，请在真实阅读或播放中试用" else "短时样本不能证明长时间稳定",
        )
        mutableState.value = mutableState.value.copy(activeTrial = null,
            trials = (mutableState.value.trials + result).takeLast(120))
        scope.launch { persistTrials(result.content) }
    }

    fun endSession(sessionKey: String) = synchronized(lock) {
        mutableState.value.activeTrial?.takeIf { it.sessionKey == sessionKey }?.let { finishTrial(it.id, "内容或播放时间轴已改变") }
        imageTimes.remove(sessionKey); gpuTimes.remove(sessionKey); submitTimes.remove(sessionKey)
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions - sessionKey)
    }

    private fun session(key: String, content: EnhancementContent) = mutableState.value.sessions[key]
        ?.takeIf { it.content == content } ?: EnhancementSessionMetrics(key, content)
    private fun put(value: EnhancementSessionMetrics) {
        val values = mutableState.value.sessions + (value.sessionKey to value)
        mutableState.value = mutableState.value.copy(sessions = if (values.size > 24)
            values.entries.sortedByDescending { it.value.updatedAtMillis }.take(24).associate { it.toPair() } else values)
    }
    private fun sample(map: MutableMap<String, MutableList<Double>>, key: String, value: Double) {
        if (!value.isFinite() || value < 0) return
        map.getOrPut(key) { mutableListOf() }.apply { add(value); if (size > 240) removeAt(0) }
    }
    private fun Double.validPositive(): Double? = takeIf { it.isFinite() && it > 0 }
    private fun difference(value: Long?, base: Long?): Long? = value?.let { (it - (base ?: 0)).takeIf { n -> n >= 0 } }
    private fun percentile(values: List<Double>?, q: Double): Double? = values?.takeIf { it.isNotEmpty() }?.sorted()
        ?.let { it[(kotlin.math.ceil(it.size * q).toInt() - 1).coerceIn(0, it.lastIndex)] }

    private suspend fun persistTrials(content: EnhancementContent) = trialWriter.withLock {
        // Sample data stays local and adult history has its own preference file.
        val values = synchronized(lock) { mutableState.value.trials.filter { it.content == content }.takeLast(40) }
        val json = JSONArray()
        values.forEach { t -> json.put(JSONObject().put("id", t.id).put("tier", t.tier.name).put("scale", t.scale)
            .put("at", t.startedAt).put("duration", t.durationMillis).put("samples", t.samples)
            .put("output", t.outputLabel ?: "").put("gpu", t.gpuP95Millis ?: -1.0)
            .put("processing", t.processingP95Millis ?: -1.0).put("presented", t.presentedFps ?: -1.0)
            .put("dropped", t.droppedFrames ?: -1).put("thermalStart", t.thermalStart ?: -1)
            .put("thermalEnd", t.thermalEnd ?: -1).put("model", t.modelDigest ?: "")
            .put("device", t.deviceFingerprint).put("confidence", t.confidence.name).put("reason", t.reason ?: "")) }
        val saved = runCatching {
            preferences(context, "enhancement_trials_${content.directory}").edit().putString("trials_v1", json.toString()).commit()
        }.getOrDefault(false)
        synchronized(lock) {
            mutableState.value = mutableState.value.copy(persistenceErrors = if (saved)
                mutableState.value.persistenceErrors - content else mutableState.value.persistenceErrors +
                (content to "本次试用记录尚未保存到本机，请检查存储空间。"))
        }
    }
    private fun readTrials(): List<EnhancementTrial> = EnhancementContent.entries.flatMap { content -> runCatching {
        val raw = preferences(context, "enhancement_trials_${content.directory}").getString("trials_v1") ?: return@runCatching emptyList()
        require(raw.length <= 131_072)
        val rows = JSONArray(raw)
        (0 until minOf(rows.length(), 40)).mapNotNull { i -> runCatching {
            val j = rows.getJSONObject(i)
            EnhancementTrial(j.getString("id"), "", content, QualityTier.valueOf(j.getString("tier")), j.optInt("scale", 1),
                j.optLong("at"), j.optLong("duration"), j.optLong("samples"), j.optString("output").takeIf { it.isNotBlank() },
                j.optDouble("gpu", -1.0).takeIf { it.isFinite() && it >= 0 }, j.optDouble("processing", -1.0).takeIf { it.isFinite() && it >= 0 },
                j.optDouble("presented", -1.0).takeIf { it.isFinite() && it >= 0 }, j.optLong("dropped", -1).takeIf { it >= 0 },
                j.optInt("thermalStart", -1).takeIf { it >= 0 }, j.optInt("thermalEnd", -1).takeIf { it >= 0 },
                j.optString("model").takeIf { it.isNotBlank() }, j.getString("device"),
                TrialConfidence.entries.firstOrNull { it.name == j.optString("confidence") } ?: TrialConfidence.NO_SAMPLES,
                j.optString("reason").takeIf { it.isNotBlank() })
        }.getOrNull() }
    }.getOrDefault(emptyList()) }.sortedBy { it.startedAt }
}
