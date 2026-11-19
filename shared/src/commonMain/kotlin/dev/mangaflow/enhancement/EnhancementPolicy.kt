package dev.mangaflow.enhancement

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** These namespaces are identities, never inferred from a title, URL or image pixels. */
enum class EnhancementContent(val label: String, val directory: String) {
    MANGA("普通漫画", "manga"), ADULT_MANGA("成人漫画", "adult_manga"), ANIME("番剧", "anime");

    /** Settings follow media capabilities; storage and session identities keep their original namespace. */
    val settingsOwner: EnhancementContent get() = if (this == ADULT_MANGA) MANGA else this
    val settingsLabel: String get() = if (this == ANIME) "番剧" else "漫画"
}
enum class QualityTier(val label: String) {
    ORIGINAL("原画"), AUTO("自动"), LIGHT("轻量"), BALANCED("均衡"), FINE("精细"), ULTRA("极致")
}
enum class ImageStyle(val label: String) { AUTO("自动识别"), MONOCHROME("黑白漫画"), COLOR("彩色漫画"), WEBTOON("条漫") }
enum class PreparationMode(val label: String) { IMMEDIATE("即时"), SMART("智能预处理"), COMPLETE("完整预处理") }
enum class FrameMode(val label: String) {
    ORIGINAL("原始"), AUTO("自动"), DOUBLE("2×"), FPS60("60 FPS"), FPS120("120 FPS"), CUSTOM("自定义")
}
enum class FrameQuality(val label: String) { LIGHT("轻量"), BALANCED("均衡"), FINE("精细"), ULTRA("极致") }

/** User intent is retained even when the current engine or resource budget cannot execute it. */
data class EnhancementPolicy(
    val tier: QualityTier = QualityTier.ORIGINAL,
    val scale: Int = 1,
    val style: ImageStyle = ImageStyle.AUTO,
    val preparation: Set<PreparationMode> = setOf(PreparationMode.SMART),
    val locked: Boolean = false,
    val modelId: String? = null,
    val frameMode: FrameMode = FrameMode.ORIGINAL,
    val frameQuality: FrameQuality = FrameQuality.BALANCED,
    val customFps: Int = 60,
    val detectScenes: Boolean = true,
    val enhanceAfterDownload: Boolean = false,
    val cacheMiB: Int = 1024,
) {
    fun checked(): EnhancementPolicy = apply {
        require(scale > 0) { "放大倍率须大于零" }
        require(customFps > 0) { "目标帧率须大于零" }
        require(cacheMiB >= 0) { "缓存预算不能为负" }
        require(modelId == null || modelId.length in 1..256) { "模型身份无效" }
    }

    fun targetFps(sourceFps: Double?, refreshHz: Double?): Double? = when (frameMode) {
        FrameMode.ORIGINAL -> sourceFps?.takeIf { it.isFinite() && it > 0 }
        FrameMode.DOUBLE -> sourceFps?.takeIf { it.isFinite() && it > 0 }?.times(2)?.takeIf { it.isFinite() }
        FrameMode.FPS60 -> 60.0
        FrameMode.FPS120 -> 120.0
        FrameMode.CUSTOM -> customFps.toDouble()
        FrameMode.AUTO -> if (sourceFps != null && sourceFps.isFinite() && sourceFps > 0 &&
            refreshHz != null && refreshHz.isFinite() && refreshHz > 0)
            maxOf(sourceFps, minOf(sourceFps * 2, refreshHz)) else null
    }
}

data class EnhancementSessionPolicy(val content: EnhancementContent, val policy: EnhancementPolicy)

/** Serialized confirmed writes; a failed disk commit cannot masquerade as a saved preference. */
class EnhancementSettingsStore(context: PlatformContext) {
    private val disk = preferences(applicationContext(context), "mangaflow_enhancement_v1")
    private val writer = Mutex()
    private val sessionLock = StorageLock()
    /** Removing a token retires every accepted write which still carries that session identity. */
    private val sessionTokens = mutableMapOf<String, Any>()
    private val mutableState = MutableStateFlow(readPolicies())
    val state: StateFlow<Map<EnhancementContent, EnhancementPolicy>> = mutableState.asStateFlow()
    private val mutableSessions = MutableStateFlow<Map<String, EnhancementSessionPolicy>>(emptyMap())
    val sessions: StateFlow<Map<String, EnhancementSessionPolicy>> = mutableSessions.asStateFlow()
    private val mutableVisibleMetrics = MutableStateFlow(readVisibleMetrics())
    val visibleMetrics: StateFlow<Map<EnhancementContent, Set<EnhancementMetric>>> = mutableVisibleMetrics.asStateFlow()

    fun isConfigured(content: EnhancementContent): Boolean = policyKeys(content).any(disk::contains)

    fun policy(content: EnhancementContent, sessionKey: String? = null): EnhancementPolicy =
        sessionKey?.let { mutableSessions.value[it]?.takeIf { value -> value.content == content }?.policy }
            ?: mutableState.value.getValue(content)

    suspend fun update(content: EnhancementContent, policy: EnhancementPolicy): Boolean = withContext(Dispatchers.IO) {
        policy.checked()
        writer.withLock {
            val key = policyKeys(content).first()
            val before = disk.getString(key)
            val value = encode(policy)
            if (!runCatching { disk.edit().putString(key, value).commit() }.getOrDefault(false)) {
                // Android preferences can update memory even when commit reports failure.
                runCatching { disk.edit().apply { if (before == null) remove(key) else putString(key, before) }.commit() }
                return@withLock false
            }
            mutableState.value = mutableState.value.mapValues { (identity, current) ->
                if (identity.settingsOwner == content.settingsOwner) policy else current
            }
            true
        }
    }

    /** A display edit must not restart processing, end a trial or change offline asset identity. */
    suspend fun updateVisibleMetrics(content: EnhancementContent, metrics: Set<EnhancementMetric>): Boolean =
        withContext(Dispatchers.IO) {
            require(metrics.all { it.supports(content) }) { "显示项不适用于当前内容" }
            val selected = metrics.toSet()
            writer.withLock {
                val key = displayKeys(content).first()
                val before = disk.getString(key)
                val value = JSONObject().put("version", 1).put("metrics", JSONArray().apply {
                    selected.sortedBy { it.ordinal }.forEach { put(it.name) }
                }).toString()
                if (!runCatching { disk.edit().putString(key, value).commit() }.getOrDefault(false)) {
                    runCatching { disk.edit().apply { if (before == null) remove(key) else putString(key, before) }.commit() }
                    return@withLock false
                }
                mutableVisibleMetrics.value = mutableVisibleMetrics.value.mapValues { (identity, current) ->
                    if (identity.settingsOwner == content.settingsOwner) selected else current
                }
                true
            }
        }

    // Keep legacy values intact. With no timestamps, ordinary manga is the deterministic fallback;
    // an adult-only configuration is retained, including an explicitly empty set of display items.
    private fun policyKeys(content: EnhancementContent): List<String> = if (content.settingsOwner == EnhancementContent.MANGA)
        listOf("shared_manga", EnhancementContent.MANGA.directory, EnhancementContent.ADULT_MANGA.directory)
        else listOf(content.directory)

    private fun displayKeys(content: EnhancementContent): List<String> = if (content.settingsOwner == EnhancementContent.MANGA)
        listOf("overlay_shared_manga", "overlay_manga", "overlay_adult_manga") else listOf("overlay_${content.directory}")

    private fun readPolicies(): Map<EnhancementContent, EnhancementPolicy> {
        fun read(content: EnhancementContent) = policyKeys(content).firstNotNullOfOrNull {
            decode(disk.getString(it))
        } ?: EnhancementPolicy()
        val manga = read(EnhancementContent.MANGA)
        return mapOf(EnhancementContent.MANGA to manga, EnhancementContent.ADULT_MANGA to manga,
            EnhancementContent.ANIME to read(EnhancementContent.ANIME))
    }

    private fun readVisibleMetrics(): Map<EnhancementContent, Set<EnhancementMetric>> {
        fun read(content: EnhancementContent) = displayKeys(content).firstNotNullOfOrNull {
            decodeVisibleMetrics(content, disk.getString(it))
        } ?: EnhancementMetric.defaults(content)
        val manga = read(EnhancementContent.MANGA)
        return mapOf(EnhancementContent.MANGA to manga, EnhancementContent.ADULT_MANGA to manga,
            EnhancementContent.ANIME to read(EnhancementContent.ANIME))
    }

    private fun decodeVisibleMetrics(content: EnhancementContent, raw: String?): Set<EnhancementMetric>? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        val value = JSONObject(raw)
        require(value.optInt("version") == 1)
        val metrics = requireNotNull(value.optJSONArray("metrics"))
        // An explicit empty array means hide all. Unknown future names do not invalidate known choices.
        (0 until metrics.length()).mapNotNull { index -> EnhancementMetric.entries.firstOrNull {
            it.name == metrics.optString(index) && it.supports(content)
        } }.toSet()
    }.getOrNull()

    fun setSessionOverride(sessionKey: String, content: EnhancementContent, policy: EnhancementPolicy?) {
        require(sessionKey.isNotBlank())
        policy?.checked()
        synchronized(sessionLock) {
            if (policy == null) sessionTokens.remove(sessionKey) else sessionTokens.getOrPut(sessionKey) { Any() }
            mutableSessions.value = if (policy == null) mutableSessions.value - sessionKey
                else mutableSessions.value + (sessionKey to EnhancementSessionPolicy(content, policy))
        }
    }

    internal fun sessionToken(sessionKey: String): Any = synchronized(sessionLock) {
        require(sessionKey.isNotBlank())
        sessionTokens.getOrPut(sessionKey) { Any() }
    }

    internal fun setSessionOverrideIfCurrent(sessionKey: String, content: EnhancementContent,
        policy: EnhancementPolicy, token: Any): Boolean = synchronized(sessionLock) {
        if (sessionTokens[sessionKey] !== token) return@synchronized false
        policy.checked()
        mutableSessions.value = mutableSessions.value + (sessionKey to EnhancementSessionPolicy(content, policy))
        true
    }

    fun clearSession(sessionKey: String) = synchronized(sessionLock) {
        sessionTokens.remove(sessionKey)
        mutableSessions.value = mutableSessions.value - sessionKey
    }

    private fun encode(p: EnhancementPolicy): String = JSONObject().put("version", 1)
        .put("tier", p.tier.name).put("scale", p.scale).put("style", p.style.name)
        .put("preparation", JSONArray().apply { p.preparation.sortedBy { it.ordinal }.forEach { put(it.name) } })
        .put("locked", p.locked).put("modelId", p.modelId ?: "")
        .put("frameMode", p.frameMode.name).put("frameQuality", p.frameQuality.name)
        .put("customFps", p.customFps).put("detectScenes", p.detectScenes)
        .put("enhanceAfterDownload", p.enhanceAfterDownload).put("cacheMiB", p.cacheMiB).toString()

    private fun decode(raw: String?): EnhancementPolicy? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        val j = JSONObject(raw)
        require(j.optInt("version") == 1)
        val prep = j.optJSONArray("preparation")
        EnhancementPolicy(
            tier = QualityTier.entries.firstOrNull { it.name == j.optString("tier") } ?: QualityTier.ORIGINAL,
            scale = j.optInt("scale", 1),
            style = ImageStyle.entries.firstOrNull { it.name == j.optString("style") } ?: ImageStyle.AUTO,
            preparation = if (prep == null) setOf(PreparationMode.SMART) else (0 until prep.length()).mapNotNull { i ->
                PreparationMode.entries.firstOrNull { it.name == prep.optString(i) }
            }.toSet(),
            locked = j.optBoolean("locked"), modelId = j.optString("modelId").takeIf { it.isNotBlank() },
            frameMode = FrameMode.entries.firstOrNull { it.name == j.optString("frameMode") } ?: FrameMode.ORIGINAL,
            frameQuality = FrameQuality.entries.firstOrNull { it.name == j.optString("frameQuality") } ?: FrameQuality.BALANCED,
            customFps = j.optInt("customFps", 60), detectScenes = j.optBoolean("detectScenes", true),
            enhanceAfterDownload = j.optBoolean("enhanceAfterDownload"), cacheMiB = j.optInt("cacheMiB", 1024),
        ).checked()
    }.getOrNull()
}
