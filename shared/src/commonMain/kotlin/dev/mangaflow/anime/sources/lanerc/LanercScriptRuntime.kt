package dev.mangaflow.anime.sources.lanerc

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.data.plugins.JavascriptHttpSession
import dev.mangaflow.data.plugins.prepareJavascriptEngine
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class LanercInvocation(
    val value: Any?, val route: DownloadRouteSnapshot, val revision: String, val digest: String,
)

/** A synchronous compatibility protocol in an isolated, cancellable background QuickJS context. */
internal class LanercScriptRuntime(private val context: PlatformContext, private val store: LanercSourceStore) {
    private val lock = SynchronizedObject()
    private val sourceLocks = mutableMapOf<String, Mutex>()
    private val running = mutableMapOf<String, () -> Unit>()
    private val slots = Semaphore(2)

    fun invalidate(id: String) {
        val cancel = synchronized(lock) { running[id] }
        cancel?.invoke()
    }

    suspend fun validate(value: LanercInstalledPackage, descriptor: LanercSourceDescriptor) {
        execute(value, descriptor, "__validate", JSONArray(), false)
    }

    suspend fun call(id: String, method: String, args: JSONArray = JSONArray(), expectedRevision: String? = null): LanercInvocation {
        require(method in METHODS) { "此动漫来源操作不受支持。" }
        require(args.toString().length <= 2 * 1024 * 1024) { "来源操作参数过大。" }
        store.ready.await()
        val sourceLock = synchronized(lock) { sourceLocks.getOrPut(id) { Mutex() } }
        return sourceLock.withLock {
            val value = store.snapshot(id) ?: throw LanercCapabilityException("该来源未安装或已停用，请先启用来源。")
            if (expectedRevision != null && value.revision != expectedRevision)
                throw LanercCapabilityException("来源版本或参数已改变，请刷新剧集后重新选择。")
            execute(value, store.descriptor(id), method, args, true)
        }
    }

    private suspend fun execute(bundle: LanercInstalledPackage, descriptor: LanercSourceDescriptor,
        method: String, args: JSONArray, networkAllowed: Boolean): LanercInvocation = withContext(Dispatchers.Default) {
        try {
            withTimeout(60_000L) { slots.withPermit {
                prepareJavascriptEngine(context)
                val operationContext = currentCoroutineContext()
                operationContext.ensureActive()
                val route = DownloadRoutingStore.get(context).sourceSnapshot(bundle.id, DownloadCategory.ANIME)
                val js = QuickJs.create(Dispatchers.Default)
                val cleartextEndpoints = lanercCleartextEndpoints(bundle.id, bundle.digest)
                val network = JavascriptHttpSession(networkAllowed, route, 8 * 1024 * 1024,
                    cleartextEndpoints = cleartextEndpoints)
                val sessionRouteKey = animeHash(route.toString())
                var sourceLoaded = false
                val sdEvidence = SdSearchResponseEvidence.forCall(bundle.id, bundle.digest, method, args.optString(0))
                var hostFailure: Exception? = null
                var responseStatus: Int? = null
                var changed = false
                fun checkCurrent() {
                    operationContext.ensureActive()
                    if (networkAllowed && (!store.isCurrent(bundle) || DownloadRoutingStore.get(context).sourceSnapshot(bundle.id, DownloadCategory.ANIME) != route)) {
                        changed = true
                        throw LanercCapabilityException("来源或网络路由已改变，请刷新后重试。")
                    }
                }
                val cancel: () -> Unit = {
                    operationContext[Job]?.cancel(CancellationException("Anime source changed"))
                    network.close()
                    runCatching { if (!js.isClosed) js.interruptEvaluation() }
                }
                if (networkAllowed) synchronized(lock) { running[bundle.id] = cancel }
                try {
                    checkCurrent()
                    val preferences = LanercPreferences(context, bundle.id, bundle.revision, networkAllowed, ::checkCurrent)
                    js.memoryLimit = 48L * 1024 * 1024
                    js.maxStackSize = 256L * 1024
                    // Unlike asynchronous manga scripts, a Lanerc call waits inside its JS callback.
                    // The whole operation and each HTTP hop have independent cancellation budgets.
                    js.evaluationTimeoutMillis = 60_000L
                    js.defineBinding("__mlHttp", FunctionBinding<String> { values ->
                        try {
                            checkCurrent()
                            check(networkAllowed) { "来源验证不能访问网络。" }
                            val request = argument(values)
                            sdEvidence?.beforeRequest(request)
                            lanercBlocking(operationContext) {
                                http(request, network, preferences, cleartextEndpoints, ::checkCurrent, sdEvidence).also {
                                    responseStatus = JSONObject(it).optInt("statusCode").takeIf { code -> code in 400..599 }
                                }
                            }
                        } catch (failure: Exception) {
                            hostFailure = failure
                            throw IllegalStateException("来源网络请求未完成。")
                        }
                    })
                    js.defineBinding("__mlPrefs", FunctionBinding<String> { values ->
                        try { preferences.call(argument(values)) }
                        catch (failure: Exception) { hostFailure = failure; throw IllegalStateException("来源会话操作未完成。") }
                    })
                    js.defineBinding("__mlCodec", FunctionBinding<String> { values ->
                        try {
                            checkCurrent()
                            val request = argument(values)
                            lanercCodec(request).also { sdEvidence?.afterCodec(request, it) }
                        }
                        catch (failure: Exception) { hostFailure = failure; throw IllegalStateException("来源编解码未完成。") }
                    })
                    js.defineBinding("__mlUnsupported", FunctionBinding<String> { _ ->
                        val failure = LanercMediaSniffingUnsupported()
                        hostFailure = failure
                        throw failure
                    })
                    js.evaluate<Unit>(LANERC_JAVASCRIPT_HOST, "anime-host.js")
                    js.evaluate<Unit>("globalThis.ext = ${bundle.optionsJson}; void 0;", "anime-options.js")
                    js.evaluate<Unit>(js.compile(bundle.code + "\n;void 0;\n", "anime-source.js"))
                    sourceLoaded = true
                    if (networkAllowed) lanercSessionRestore(bundle.id, bundle.digest, sessionRouteKey)?.let {
                        js.evaluate<Unit>(it, "anime-session-restore.js")
                    }
                    checkCurrent()
                    val encoded = if (method == "__validate") {
                        val config = js.evaluate<String>("__mlInvoke('config', [], ${JSONObject.quote(bundle.id)})", "anime-config.js")
                        val configResult = JSONObject(config).optJSONObject("result")
                        if (descriptor.kind == LanercSourceKind.VIDEO && (configResult?.optBoolean("browseOnly") == true ||
                                configResult?.optString("kind") in setOf("comic", "danmaku", "browse")))
                            throw LanercCapabilityException("该脚本已改为其他内容协议，不能启用为视频来源。")
                        val required = when (descriptor.kind) {
                            LanercSourceKind.VIDEO -> listOf("search", "detail", "play")
                            LanercSourceKind.BROWSE -> listOf("search", "homeSections")
                            LanercSourceKind.DANMAKU -> listOf("danmu")
                            else -> throw LanercCapabilityException("该来源的内容协议尚未接入。")
                        }
                        js.evaluate<String>("JSON.stringify({result: ${JSONArray(required)}.every(function (key) { return typeof globalThis[key] === 'function'; })})", "anime-validate.js")
                    } else js.evaluate<String>("__mlInvoke(${JSONObject.quote(method)}, $args, ${JSONObject.quote(bundle.id)})", "anime-call.js")
                    require(encoded.length <= 8 * 1024 * 1024) { "来源返回内容过大。" }
                    val envelope = JSONObject(encoded)
                    when {
                        envelope.optBoolean("missing") -> throw LanercCapabilityException("该来源当前版本没有提供这项功能。")
                        envelope.optBoolean("selectionChanged") -> throw LanercCapabilityException("所选线路已没有这集资源，请刷新并重新选择；不会自动替换线路。")
                        envelope.optBoolean("selectionUnsupported") -> throw LanercCapabilityException("该来源的线路协议已改变，无法核实所选资源，请更新兼容适配。")
                        envelope.optBoolean("asyncUnsupported") -> throw LanercCapabilityException("该来源改用了异步脚本协议，当前兼容版本尚未支持。")
                        envelope.optBoolean("invalid") || !envelope.has("result") -> throw IllegalStateException("来源返回格式无效。")
                    }
                    val result = envelope.opt("result")
                    if (method == "__validate" && result != true)
                        throw LanercCapabilityException("脚本缺少此类来源必需的方法，未启用该包。")
                    if (emptyResult(method, result) && (hostFailure != null || responseStatus != null)) {
                        val failure = hostFailure
                        if (failure is LanercCapabilityException) throw failure
                        if (responseStatus in setOf(401, 403)) throw LanercCapabilityException("此来源需要验证或拒绝了访问，请检查来源账号与设置。")
                        throw IllegalStateException("来源请求或解析未完成，请检查网络路由与来源版本。")
                    }
                    if (result is JSONArray && result.length() == 0 && sdEvidence?.twoUnresolvedMainAttempts() == true)
                        throw LanercCapabilityException("闪电主搜索响应解析未完成，未将其当作没有匹配作品。")
                    checkCurrent()
                    LanercInvocation(result, route, bundle.revision, bundle.digest)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    operationContext.ensureActive()
                    if (changed) throw LanercCapabilityException("来源或网络路由已改变，请刷新后重试。")
                    if (failure is LanercCapabilityException) throw failure
                    if (hostFailure is LanercCapabilityException) throw hostFailure as LanercCapabilityException
                    // Never surface a JS exception, response body, signed URL or credential in UI/logs.
                    throw IllegalStateException(if (hostFailure != null)
                        "来源请求、会话或编解码未完成，请检查网络路由、来源版本及兼容能力。"
                    else "来源脚本未能完成解析，请更新来源或选择其他来源。")
                } finally {
                    // Persist only the audited anonymous protocol state, under the same revision/route
                    // fence. A cancelled or superseded invocation cannot publish old credentials.
                    if (sourceLoaded && networkAllowed && !js.isClosed) runCatching {
                        checkCurrent()
                        lanercSessionSave(bundle.id, bundle.digest, sessionRouteKey)?.let {
                            js.evaluate<Unit>(it, "anime-session-save.js")
                        }
                        checkCurrent()
                    }
                    if (networkAllowed) synchronized(lock) { if (running[bundle.id] === cancel) running.remove(bundle.id) }
                    network.close()
                    runCatching { if (!js.isClosed) js.interruptEvaluation() }
                    js.close()
                }
            } }
        } catch (_: TimeoutCancellationException) {
            throw LanercCapabilityException("来源响应超时，已停止本次请求，可重试或切换来源。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: LanercCapabilityException) { throw known }
        catch (_: Throwable) { throw IllegalStateException("来源运行环境或脚本解析未完成，请检查组件、来源版本与网络设置。") }
    }

    private suspend fun http(raw: String, network: JavascriptHttpSession, preferences: LanercPreferences,
        cleartextEndpoints: Set<String>, checkCurrent: () -> Unit, sdEvidence: SdSearchResponseEvidence? = null): String {
        val input = JSONObject(raw)
        var url = lanercRequestUrl(input.getString("url"), cleartextEndpoints)
        var headers = input.optJSONObject("headers") ?: JSONObject()
        var method = input.optString("method", "GET").uppercase()
        var body: Any? = input.opt("body")
        val bodyEncoding = input.optString("bodyEncoding", "utf8").lowercase()
        if (body != null && body !== JSONObject.NULL && bodyEncoding !in setOf("utf8", "utf-8", "text")) {
            val encodedBody = body as? String
                ?: throw IllegalArgumentException("二进制来源请求体必须使用文本编码。")
            body = byteArrayJson(decodeLanercBytes(encodedBody, bodyEncoding))
        }
        val useCookies = !input.optBoolean("noCookie", false)
        val follow = input.optBoolean("followRedirects", true)
        val timeout = input.optLong("timeout", 15_000).coerceIn(1_000, 20_000)
        repeat(4) { redirect ->
            checkCurrent()
            val sentHeaders = JSONObject(headers.toString())
            if (url.isHttps && useCookies && sentHeaders.keys().asSequence().none { it.equals("Cookie", true) }) {
                preferences.header(url).takeIf(String::isNotBlank)?.let { sentHeaders.put("Cookie", it) }
            }
            val hop = JSONObject().put("url", url.toString()).put("method", method).put("headers", sentHeaders)
                .put("responseEncoding", input.optString("responseEncoding", "utf8")).put("followRedirects", false)
            if (body != null && body !== JSONObject.NULL) hop.put("body", body)
            val response = JSONObject(withTimeout(timeout) { network.request(hop.toString()) })
            checkCurrent()
            val responseHeaders = response.optJSONObject("headers") ?: JSONObject()
            if (url.isHttps && useCookies) responseHeaders.keys().asSequence().firstOrNull { it.equals("Set-Cookie", true) }
                ?.let { preferences.receive(url, responseHeaders.optString(it)) }
            val status = response.optInt("statusCode")
            // Only the first actual hop is eligible: redirects can change POST into GET.
            if (redirect == 0 && method == "POST" && status == 200)
                sdEvidence?.afterResponse(hop.toString(), response.toString())
            if (!follow || status !in setOf(301, 302, 303, 307, 308)) return response.toString()
            require(redirect < 3) { "来源重定向次数过多。" }
            val location = responseHeaders.keys().asSequence().firstOrNull { it.equals("Location", true) }
                ?.let(responseHeaders::optString) ?: error("来源重定向缺少目标。")
            val target = lanercRequestUrl(url.resolve(location)?.toString() ?: error("来源重定向地址无效。"),
                cleartextEndpoints)
            require(!url.isHttps || target.isHttps) { "不能把 HTTPS 来源请求降级到 HTTP。" }
            if (url.host != target.host || url.port != target.port || url.scheme != target.scheme) {
                require(body == null || body === JSONObject.NULL || body.toString().isEmpty()) { "不能跨站重放来源请求正文。" }
                headers = JSONObject(headers.keys().asSequence().filter { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                    .associateWith { headers.optString(it) })
            }
            if (status == 303 || method == "POST" && status in setOf(301, 302)) { method = "GET"; body = null }
            url = target
        }
        error("来源重定向次数过多。")
    }

    private fun emptyResult(method: String, result: Any?): Boolean = when (result) {
        null, JSONObject.NULL -> true
        is JSONArray -> result.length() == 0
        is JSONObject -> when (method) {
            "play" -> result.optString("url").isBlank() && (result.optJSONArray("resolutions")?.length() ?: 0) == 0
            "detail" -> (result.optJSONArray("episodes")?.length() ?: 0) == 0
            else -> result.length() == 0
        }
        else -> false
    }

    private companion object {
        val METHODS = setOf("config", "search", "detail", "play", "homeSections", "categories", "searchFiltered", "related", "danmu")
        fun argument(values: Array<Any?>): String = (values.singleOrNull() as? String)
            ?.also { require(it.length <= 2 * 1024 * 1024) }
            ?: throw IllegalArgumentException("来源宿主参数无效。")
    }
}
