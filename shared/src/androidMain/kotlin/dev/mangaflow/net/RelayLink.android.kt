package dev.mangaflow.net

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.UUID

private const val SERVICE_CLASS = "dev.relay.android.RelayLinkService"
private const val CONSENT_CLASS = "dev.relay.android.RelayLinkConsentActivity"
private val PROVIDERS = listOf("dev.relay.android.network", "dev.relay.android")

@Volatile private var activeRelayLink: AndroidRelayLink? = null
internal actual fun platformRelayLinkController(context: PlatformContext): RelayLinkController =
    synchronized(AndroidRelayLink::class.java) {
        (activeRelayLink ?: AndroidRelayLink(context.applicationContext).also { activeRelayLink = it }).controller
    }

actual fun currentRelayBinding(mode: DownloadRouteMode): RelayRouteBinding? =
    if (mode == DownloadRouteMode.SYSTEM || DownloadRoutingStore.activeProxyProvider() == DownloadProxyProvider.CUSTOM) null else activeRelayLink?.binding()

actual fun resolveRelayEndpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint =
    if (DownloadRoutingStore.activeProxyProvider() == DownloadProxyProvider.CUSTOM)
        throw DownloadRouteException("已切换到自定义代理，原 Relay 任务需按当前设置重新开始。")
    else activeRelayLink?.endpoint(binding, mode)
        ?: throw DownloadRouteException("Relay 联动尚未恢复，原任务暂不能继续。")

actual fun recordRelayRequest(binding: RelayRouteBinding, mode: DownloadRouteMode, observationEpoch: String) {
    activeRelayLink?.recordResponse(binding, mode, observationEpoch)
}

/** These functions are called only by MangaFlow's nonexported result-forwarding Activity. */
fun relayAuthorizationIntent(context: Context, provider: String, certificate: String, nonce: String): Intent? =
    (RelayLinkController.get(context).let { activeRelayLink })?.authorizationIntent(provider, certificate, nonce)

fun completeRelayAuthorization(context: Context, provider: String, certificate: String, nonce: String, approved: Boolean) {
    RelayLinkController.get(context)
    activeRelayLink?.completeAuthorization(provider, certificate, nonce, approved)
}

private data class RelayProvider(val packageName: String, val certificate: String, val uid: Int, val compatible: Boolean)
private data class Authorization(val provider: RelayProvider, val nonce: String)
private data class RelayObservation(val binding: RelayRouteBinding, val mode: DownloadRouteMode,
    val epoch: String, val observedElapsed: Long, val observedAt: Long)

private class AndroidRelayLink(private val app: Context) : RelayPlatformLink {
    private val preferences = app.getSharedPreferences("mangaflow_relay_link", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(RelayLinkState(enabled = preferences.getBoolean("enabled", false)))
    override val state = mutableState.asStateFlow()
    val controller = RelayLinkController(this)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var scope: CoroutineScope? = null
    @Volatile private var enabled = preferences.getBoolean("enabled", false)
    @Volatile private var identity = readBinding()
    @Volatile private var direct: RelayManagedEndpoint? = null
    @Volatile private var proxy: RelayManagedEndpoint? = null
    @Volatile private var receiptAt = 0L
    @Volatile private var coreEpoch = -1L
    @Volatile private var coreSession: String? = null
    @Volatile private var pendingObservation: RelayObservation? = null
    @Volatile private var sourceUrls: Map<String, String> = emptyMap()
    @Volatile private var sourcesKnown = false
    @Volatile private var authorization: Authorization? = null
    private var worker: Job? = null
    private var transport: RelayMessenger? = null
    private var acknowledgedRules = preferences.getString("rules", null)
    private var expectedRevision = preferences.getLong("revision", -1)

    override fun start(scope: CoroutineScope) {
        synchronized(this) {
            if (worker != null) return
            this.scope = scope
            worker = scope.launch(Dispatchers.IO) {
                for (signal in signals) {
                    delay(180)
                    mutex.withLock { reconcile() }
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            DownloadRoutingStore.get(app).state.collect { refresh() }
        }
        scope.launch(Dispatchers.IO) {
            while (true) { delay(if (enabled) 15_000L else 60_000L); refresh() }
        }
        val packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.data?.schemeSpecificPart in PROVIDERS) {
                    invalidate(RelayLinkPhase.CONNECTING)
                    transport?.close()
                    transport = null
                    refresh()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(packageReceiver, filter)
        observeDownloadNetworkEnvironment(app) {
            invalidate(RelayLinkPhase.CONNECTING)
            refresh()
        }
        refresh()
    }

    override fun refresh() { signals.trySend(Unit) }
    override fun updateSources(sources: Map<String, String>) {
        val next = sources.toMap()
        if (!sourcesKnown || sourceUrls != next) { sourceUrls = next; sourcesKnown = true; refresh() }
    }

    override fun enable() {
        if (identity == null) { requestAuthorization(); return }
        enabled = true
        invalidate(RelayLinkPhase.CONNECTING)
        launchAction { saveEnabled(true); reconcile() }
    }

    override fun disable() {
        enabled = false
        invalidate(RelayLinkPhase.SYNCING, "正在清除 Relay 中的联动规则。")
        launchAction {
            saveEnabled(false)
            check(preferences.edit().putBoolean("pendingClear", true).commit())
            reconcile()
        }
    }

    override fun revoke() {
        enabled = false
        invalidate(RelayLinkPhase.DISABLED)
        launchAction {
            saveEnabled(false)
            check(preferences.edit().putBoolean("pendingRevoke", true).commit())
            reconcile()
        }
    }

    override fun openProvider() {
        val target = mutableState.value.providerPackage ?: identity?.providerPackage ?: return
        main.post { runCatching { app.packageManager.getLaunchIntentForPackage(target)?.let {
            app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } } }
    }

    override fun requestAuthorization() {
        launchAction {
            val provider = findProvider() ?: run { reconcile(); return@launchAction }
            if (!provider.compatible) { reconcile(); return@launchAction }
            val pending = Authorization(provider, UUID.randomUUID().toString())
            authorization = pending
            invalidate(RelayLinkPhase.AUTHORIZING)
            withContext(Dispatchers.Main) {
                try {
                    app.startActivity(Intent().setClassName(app.packageName, "dev.mangaflow.RelayLinkAuthorizationActivity")
                        .putExtra("provider", provider.packageName).putExtra("certificate", provider.certificate)
                        .putExtra("nonce", pending.nonce).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    authorization = null
                    invalidate(RelayLinkPhase.ERROR, "无法打开 Relay 授权界面，请重试。")
                }
            }
        }
    }

    fun authorizationIntent(provider: String, certificate: String, nonce: String): Intent? {
        val pending = authorization ?: return null
        if (pending.nonce != nonce || pending.provider.packageName != provider || pending.provider.certificate != certificate) return null
        return Intent().setComponent(ComponentName(provider, CONSENT_CLASS))
            .putExtra("protocol", 1)
    }

    fun completeAuthorization(provider: String, certificate: String, nonce: String, approved: Boolean) {
        val pending = authorization ?: return
        if (pending.nonce != nonce || pending.provider.packageName != provider || pending.provider.certificate != certificate) return
        authorization = null
        launchAction {
            if (!approved || readProvider(provider)?.certificate != certificate) {
                invalidate(RelayLinkPhase.AUTHORIZATION_REQUIRED)
                return@launchAction
            }
            val response = connection(pending.provider).request("status")
            val grant = response.optString("grantId")
            check(response.optBoolean("ok") && grant.matches(Regex("[A-Za-z0-9_-]{1,128}")))
            check(readProvider(provider) == pending.provider)
            identity = RelayRouteBinding(provider, certificate, grant)
            expectedRevision = response.optLong("savedRevision", -1)
            acknowledgedRules = null
            check(expectedRevision >= 0)
            check(preferences.edit().putString("package", provider).putString("certificate", certificate)
                .putString("grantId", grant).putLong("revision", expectedRevision).remove("rules")
                .putBoolean("enabled", true).putBoolean("pendingClear", false).putBoolean("pendingRevoke", false).commit())
            enabled = true
            reconcile()
        }
    }

    fun binding(): RelayRouteBinding? = if (enabled) identity
        ?: throw DownloadRouteException("Relay 联动身份未能恢复，请重新授权。") else null

    @Synchronized fun endpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint {
        val snapshot = mutableState.value
        if (!enabled || identity != binding || snapshot.phase != RelayLinkPhase.APPLIED || !snapshot.vpnConnected ||
            !snapshot.authorized || SystemClock.elapsedRealtime() - receiptAt !in 0..30_000L) {
            refresh()
            throw DownloadRouteException("Relay 联动未应用或连接已中断，请恢复联动后重试原任务。")
        }
        return (if (mode == DownloadRouteMode.DIRECT) direct else if (mode == DownloadRouteMode.PROXY) proxy else null)
            ?: throw DownloadRouteException("Relay 没有提供这个任务需要的安全入口。")
    }

    @Synchronized fun recordResponse(binding: RelayRouteBinding, mode: DownloadRouteMode, observationEpoch: String) {
        val entry = runCatching { endpoint(binding, mode) }.getOrNull() ?: return
        if (entry.observationEpoch != observationEpoch) return
        val previous = mutableState.value
        if (previous.realRequestVerified && previous.verifiedMode == mode) return
        if (pendingObservation == null) pendingObservation = RelayObservation(binding, mode, observationEpoch,
            SystemClock.elapsedRealtime(), wallTimeMillis())
        refresh()
    }

    private fun launchAction(action: suspend () -> Unit) {
        val active = scope ?: return
        active.launch(Dispatchers.IO) {
            mutex.withLock {
                try { action() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { invalidate(RelayLinkPhase.ERROR, "Relay 联动暂不可用，请重试或打开 Relay。") }
            }
        }
    }

    private suspend fun reconcile() {
        try {
            val provider = findProvider()
            if (provider == null) {
                invalidate(RelayLinkPhase.NOT_INSTALLED)
                mutableState.value = mutableState.value.copy(installed = false, supported = false, authorized = false)
                return
            }
            mutableState.value = mutableState.value.copy(installed = true, supported = provider.compatible,
                providerPackage = provider.packageName, providerCertificate = provider.certificate, enabled = enabled)
            if (!provider.compatible) { invalidate(RelayLinkPhase.INCOMPATIBLE); return }
            val binding = identity
            if (binding != null && (binding.providerPackage != provider.packageName || binding.certificate != provider.certificate)) {
                invalidate(RelayLinkPhase.AUTHORIZATION_REQUIRED, "Relay 安装身份已改变，需要重新授权。")
                mutableState.value = mutableState.value.copy(authorized = false)
                return
            }
            val hello = connection(provider).request("hello")
            if (!hello.optBoolean("ok") || hello.optInt("protocol") != 1) { invalidate(RelayLinkPhase.INCOMPATIBLE); return }
            if (binding == null) { invalidate(RelayLinkPhase.AUTHORIZATION_REQUIRED); return }
            var receiptRequestedAt = SystemClock.elapsedRealtime()
            var response = connection(provider).request("status", JSONObject().put("grantId", binding.grantId))
            if (!response.optBoolean("ok") || response.optString("grantId") != binding.grantId) {
                invalidate(RelayLinkPhase.AUTHORIZATION_REQUIRED, "Relay 授权已失效，请重新授权。")
                mutableState.value = mutableState.value.copy(authorized = false)
                return
            }
            if (preferences.getBoolean("pendingRevoke", false)) {
                response = connection(provider).request("revoke", JSONObject().put("grantId", binding.grantId))
                check(response.optBoolean("ok"))
                check(preferences.edit().clear().commit())
                identity = null; expectedRevision = -1; acknowledgedRules = null
                invalidate(RelayLinkPhase.AUTHORIZATION_REQUIRED)
                mutableState.value = mutableState.value.copy(authorized = false, grantId = null)
                return
            }
            val desired = if (enabled) rules() else JSONArray()
            val serialized = canonicalRules(desired)
            val saved = response.optLong("savedRevision", -1)
            mutableState.value = mutableState.value.copy(authorized = true, grantId = binding.grantId,
                savedRevision = saved.coerceAtLeast(0), appliedRevision = response.optLong("appliedRevision"),
                vpnConnected = response.optBoolean("vpnConnected"), ruleCount = desired.length())
            // A timed-out replace may have committed remotely. Only the same canonical content
            // can reconcile that unknown outcome without overwriting Relay-side edits.
            val remoteRules = response.optJSONArray("rules")?.let(::canonicalRules)
            val pendingRules = preferences.getString("pendingRules", null)
            if (saved != expectedRevision && pendingRules != null && remoteRules == pendingRules) {
                expectedRevision = saved
                acknowledgedRules = remoteRules
                check(preferences.edit().putLong("revision", saved).putString("rules", remoteRules)
                    .putBoolean("pendingClear", !enabled && !cleanupConfirmed(response)).remove("pendingRules").commit())
            } else if (saved != expectedRevision) {
                invalidate(RelayLinkPhase.CONFLICT, "Relay 中的联动规则已变更，请打开 Relay 核对后重新授权。")
                return
            }
            if (acknowledgedRules != serialized || preferences.getBoolean("pendingClear", false)) {
                invalidate(RelayLinkPhase.SYNCING)
                check(preferences.edit().putString("pendingRules", serialized).commit())
                receiptRequestedAt = SystemClock.elapsedRealtime()
                response = connection(provider).request("replace", JSONObject().put("grantId", binding.grantId)
                    .put("expectedRevision", expectedRevision).put("rules", desired))
                if (!response.optBoolean("ok")) {
                    invalidate(if (response.optString("error").contains("revision", true)) RelayLinkPhase.CONFLICT
                        else RelayLinkPhase.ERROR, "Relay 未应用本次规则，请重试或核对联动权限。")
                    return
                }
                expectedRevision = response.optLong("savedRevision", -1)
                check(expectedRevision >= 0)
                acknowledgedRules = serialized
                check(preferences.edit().putLong("revision", expectedRevision).putString("rules", serialized)
                    .putBoolean("pendingClear", !enabled && !cleanupConfirmed(response)).remove("pendingRules").commit())
            }
            if (!enabled && preferences.getBoolean("pendingClear", false) && cleanupConfirmed(response))
                check(preferences.edit().putBoolean("pendingClear", false).commit())
            check(readProvider(provider.packageName) == provider)
            applyReceipt(response, provider, binding, desired.length(), receiptRequestedAt)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { invalidate(RelayLinkPhase.ERROR, if (!enabled &&
            (preferences.getBoolean("pendingClear", false) || preferences.getBoolean("pendingRevoke", false)))
            "已暂停新请求；Relay 中的旧规则尚未清除，恢复连接后继续处理。"
            else "Relay 联动已中断，原任务等待恢复。") }
    }

    @Synchronized private fun applyReceipt(response: JSONObject, provider: RelayProvider, binding: RelayRouteBinding,
        count: Int, receiptRequestedAt: Long) {
        val saved = response.optLong("savedRevision", -1)
        val applied = response.optLong("appliedRevision", -1)
        val vpn = response.optBoolean("vpnConnected")
        val nextCoreEpoch = response.optLong("coreEpoch", -1)
        val nextCoreSession = response.optString("coreSession").takeIf { it.matches(Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9]{1,20}")) }
        val conflicts = response.optJSONArray("conflicts")?.let { array ->
            (0 until array.length()).map { array.optString(it).take(200) }.filter(String::isNotBlank)
        }.orEmpty()
        val endpoints = response.optJSONObject("endpoints")
        val nextDirect = nextCoreSession?.let { endpointFrom(endpoints?.optJSONObject("direct"), it) }
        val nextProxy = nextCoreSession?.let { endpointFrom(endpoints?.optJSONObject("proxy"), it) }
        val phase = when {
            response.optString("state") == "ERROR" -> RelayLinkPhase.ERROR
            conflicts.isNotEmpty() -> RelayLinkPhase.CONFLICT
            !enabled && preferences.getBoolean("pendingClear", false) -> RelayLinkPhase.SAVED
            !enabled -> RelayLinkPhase.DISABLED
            !vpn -> RelayLinkPhase.WAITING_FOR_VPN
            saved < 0 || applied != saved -> RelayLinkPhase.SAVED
            response.optString("state") != "APPLIED" -> RelayLinkPhase.SAVED
            nextCoreEpoch < 0 -> RelayLinkPhase.SAVED
            nextDirect == null || nextProxy == null -> RelayLinkPhase.SAVED
            else -> RelayLinkPhase.APPLIED
        }
        val old = mutableState.value
        val sameReceipt = old.phase == RelayLinkPhase.APPLIED && phase == RelayLinkPhase.APPLIED &&
            coreEpoch == nextCoreEpoch && coreSession == nextCoreSession &&
            old.savedRevision == saved && old.appliedRevision == applied && old.grantId == binding.grantId &&
            sameEndpoint(direct, nextDirect) && sameEndpoint(proxy, nextProxy)
        direct = if (phase == RelayLinkPhase.APPLIED) (if (sameReceipt) direct else nextDirect) else null
        proxy = if (phase == RelayLinkPhase.APPLIED) (if (sameReceipt) proxy else nextProxy) else null
        receiptAt = if (phase == RelayLinkPhase.APPLIED) SystemClock.elapsedRealtime() else 0
        coreEpoch = nextCoreEpoch
        coreSession = nextCoreSession
        val observation = pendingObservation
        val observationMatches = phase == RelayLinkPhase.APPLIED && observation != null && observation.binding == binding &&
            observation.epoch == nextCoreSession &&
            (if (observation.mode == DownloadRouteMode.DIRECT) direct else proxy)?.observationEpoch == observation.epoch
        val newlyVerified = observationMatches && observation!!.observedElapsed <= receiptRequestedAt
        if (newlyVerified || !observationMatches) pendingObservation = null
        else if (observation != null) refresh()
        mutableState.value = RelayLinkState(true, true, true, enabled, provider.packageName, provider.certificate,
            binding.grantId, saved.coerceAtLeast(0), applied.coerceAtLeast(0), vpn, phase, count, conflicts,
            error = when {
                phase == RelayLinkPhase.ERROR -> "Relay 核心未能应用网络规则，请打开 Relay 重试。"
                !enabled && phase == RelayLinkPhase.SAVED -> "规则已保存，等待 Relay 清除旧规则。"
                else -> null
            },
            realRequestVerified = newlyVerified || (sameReceipt && old.realRequestVerified),
            verifiedAtMillis = if (newlyVerified) observation!!.observedAt else old.verifiedAtMillis.takeIf { sameReceipt },
            verifiedMode = if (newlyVerified) observation!!.mode else old.verifiedMode.takeIf { sameReceipt })
    }

    private fun rules(): JSONArray {
        val settings = DownloadRoutingStore.get(app).state.value
        if (settings.proxyProvider != DownloadProxyProvider.RELAY) return JSONArray()
        val rules = sortedMapOf<String, DownloadRouteMode>()
        fun add(id: String, mode: DownloadRouteMode) {
            if (mode != DownloadRouteMode.SYSTEM) rules[id] = mode
        }
        add("default", settings.defaultMode)
        settings.categoryOverrides.forEach { (category, mode) -> add("category." + category.name, mode) }
        val reviewOwners = dev.mangaflow.data.ReviewProviders.ids.map(dev.mangaflow.data.ReviewProviders::routingId).toSet()
        settings.sourceOverrides.forEach { (id, source) ->
            if (!sourcesKnown || id in sourceUrls || id in reviewOwners) add("source." + digest(id.toByteArray()).take(40), source.mode)
        }
        check(rules.size <= 256) { "Relay link rule limit" }
        return JSONArray().apply {
            rules.forEach { (id, mode) ->
                // Application/domain rules cannot distinguish an explicit SYSTEM task or shared CDN.
                // The frozen request selects the authenticated entry; this is its versioned inventory.
                put(JSONObject().put("id", id).put("mode", mode.name).put("domains", JSONArray()))
            }
        }
    }

    private fun endpointFrom(value: JSONObject?, epoch: String): RelayManagedEndpoint? {
        if (value == null || value.optString("host") != "127.0.0.1" || value.optInt("port") !in 1..65535) return null
        val user = value.optString("username"); val password = value.optString("password")
        if (user.length !in 8..255 || password.length !in 16..255 ||
            user.any { it.code !in 33..126 || it == ':' } || password.any { it.code !in 33..126 }) return null
        return RelayManagedEndpoint("127.0.0.1", value.optInt("port"), user, password, epoch)
    }

    @Synchronized private fun invalidate(phase: RelayLinkPhase, error: String? = null) {
        direct = null; proxy = null; receiptAt = 0
        coreEpoch = -1; coreSession = null; pendingObservation = null
        mutableState.value = mutableState.value.copy(enabled = enabled, phase = phase, error = error,
            realRequestVerified = false, verifiedAtMillis = null, verifiedMode = null)
    }

    private fun connection(provider: RelayProvider): RelayMessenger {
        val previous = transport
        if (previous != null && previous.provider == provider) return previous
        previous?.close()
        return RelayMessenger(app, provider) {
            invalidate(RelayLinkPhase.ERROR, "Relay 服务已中断，等待恢复。")
            refresh()
        }.also { transport = it }
    }

    private fun readBinding(): RelayRouteBinding? {
        val pkg = preferences.getString("package", null) ?: return null
        val cert = preferences.getString("certificate", null) ?: return null
        val grant = preferences.getString("grantId", null) ?: return null
        return if (pkg in PROVIDERS && cert.isNotBlank() && grant.isNotBlank()) RelayRouteBinding(pkg, cert, grant) else null
    }
    private fun saveEnabled(value: Boolean) { check(preferences.edit().putBoolean("enabled", value).commit()) }
    private fun cleanupConfirmed(response: JSONObject): Boolean = !response.optBoolean("vpnConnected") ||
        (response.optString("state") == "APPLIED" && response.optLong("savedRevision", -1) >= 0 &&
            response.optLong("savedRevision", -1) == response.optLong("appliedRevision", -2))
    private fun findProvider(): RelayProvider? = (listOfNotNull(identity?.providerPackage) + PROVIDERS).distinct()
        .firstNotNullOfOrNull(::readProvider)

    @Suppress("DEPRECATION")
    private fun readProvider(packageName: String): RelayProvider? = runCatching {
        if (packageName !in PROVIDERS) return@runCatching null
        val pm = app.packageManager
        val info = pm.getPackageInfo(packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val fingerprints = signatures?.map { digest(it.toByteArray()) }?.sorted()?.takeIf { it.isNotEmpty() }
            ?: return@runCatching null
        val certificate = if (fingerprints.size == 1) fingerprints.single() else digest(fingerprints.joinToString(":").toByteArray())
        val application = info.applicationInfo ?: return@runCatching null
        val service = runCatching { pm.getServiceInfo(ComponentName(packageName, SERVICE_CLASS), PackageManager.GET_META_DATA) }.getOrNull()
        val consent = runCatching { pm.getActivityInfo(ComponentName(packageName, CONSENT_CLASS), 0) }.getOrNull()
        RelayProvider(packageName, certificate, application.uid, application.enabled && service?.enabled == true &&
            service.exported && service.metaData?.getInt("dev.relay.android.LINK_PROTOCOL") == 1 &&
            consent?.enabled == true && consent.exported)
    }.getOrNull()
}

private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun sameEndpoint(a: RelayManagedEndpoint?, b: RelayManagedEndpoint?): Boolean = a != null && b != null &&
    a.host == b.host && a.port == b.port && a.username == b.username && a.password == b.password

private fun canonicalRules(array: JSONArray): String = JSONArray().apply {
    (0 until array.length()).map { array.getJSONObject(it) }.sortedBy { it.getString("id") }.forEach { rule ->
        val domains = rule.getJSONArray("domains")
        put(JSONObject().put("id", rule.getString("id")).put("mode", rule.getString("mode"))
            .put("domains", JSONArray((0 until domains.length()).map { domains.getString(it) }.sorted())))
    }
}.toString()

/** A pinned explicit Messenger binding. Each native binding has its own generation. */
private class RelayMessenger(private val app: Context, val provider: RelayProvider, private val lost: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var pendingId: String? = null
    private var pending: CompletableDeferred<JSONObject>? = null
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != 1 || message.sendingUid != provider.uid) return@Handler true
        val text = runCatching { message.data.getString("payload") }.getOrNull() ?: return@Handler true
        if (text.length > 131_072) return@Handler true
        val result = runCatching { JSONObject(text) }.getOrNull() ?: return@Handler true
        if (result.optInt("protocol") == 1 && result.optString("requestId") == pendingId) pending?.complete(result)
        true
    })

    private inner class Session : ServiceConnection {
        val ready = CompletableDeferred<Messenger>()
        var registered = false
        var messenger: Messenger? = null
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { main.post { disconnect(this) } }
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (session !== this) { retire(); return }
            if (name != ComponentName(provider.packageName, SERVICE_CLASS) || service == null) {
                disconnect(this); return
            }
            try {
                binder = service
                service.linkToDeath(death, 0)
                val connected = Messenger(service)
                messenger = connected
                ready.complete(connected)
            } catch (_: Exception) { disconnect(this) }
        }
        override fun onServiceDisconnected(name: ComponentName?) = disconnect(this)
        override fun onBindingDied(name: ComponentName?) = disconnect(this)
        override fun onNullBinding(name: ComponentName?) = disconnect(this)
        fun retire() {
            binder?.let { runCatching { it.unlinkToDeath(death, 0) } }; binder = null
            if (registered) runCatching { app.unbindService(this) }
            registered = false
            messenger = null
            ready.completeExceptionally(IllegalStateException("Relay unavailable"))
        }
    }

    suspend fun request(op: String, arguments: JSONObject = JSONObject()): JSONObject {
        val id = UUID.randomUUID().toString()
        var selected: Session? = null
        var completed = false
        try {
            val result = withTimeoutOrNull(5_000L) {
                val messenger = withContext(Dispatchers.Main) {
                    val active = session ?: Session().also { created ->
                        selected = created
                        session = created
                        val intent = Intent().setComponent(ComponentName(provider.packageName, SERVICE_CLASS))
                        created.registered = app.bindService(intent, created, Context.BIND_AUTO_CREATE)
                        check(created.registered)
                    }
                    selected = active
                    active.messenger ?: active.ready.await()
                }
                val response = CompletableDeferred<JSONObject>()
                withContext(Dispatchers.Main) {
                    check(session === selected)
                    pendingId = id; pending = response
                    arguments.put("protocol", 1).put("op", op).put("requestId", id)
                    messenger.send(Message.obtain(null, 1).apply {
                        data = Bundle().apply { putString("payload", arguments.toString()) }; replyTo = replies
                    })
                }
                response.await()
            } ?: throw IllegalStateException("Relay request timed out")
            completed = true
            return result
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (pendingId == id) { pendingId = null; pending = null }
                if (!completed) selected?.let(::disconnect)
            }
        }
    }

    private fun disconnect(stale: Session) {
        if (session !== stale) { stale.retire(); return }
        session = null
        stale.retire()
        pending?.completeExceptionally(IllegalStateException("Relay unavailable")); pending = null; pendingId = null
        lost()
    }
    fun close() { main.post { session?.let(::disconnect) } }
}
