package dev.mangaflow.data.plugins

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import dev.mangaflow.data.ChapterDownloadTransport
import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.PRIVATE_STORAGE
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One bounded QuickJS host on Android, desktop JVM and iOS Native. No browser or filesystem bridge. */
class JavascriptSourceRuntime(context: PlatformContext) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("mangaflow_script_source_preferences", PRIVATE_STORAGE)

    suspend fun validate(bundle: ScriptSourcePackage) {
        execute(bundle, reading = false, allowNetwork = false) { js ->
            val method = if (bundle.isVenera) "__mfVeneraValidate" else "__mfValidate"
            js.evaluate<String>("$method(globalThis.__mfProvider)", "validate.js")
        }
    }

    suspend fun call(bundle: ScriptSourcePackage, method: String, args: JSONArray = JSONArray()): String {
        require(method in METHODS) { "不支持的漫画源操作。" }
        require(args.toString().length <= MAX_ARGUMENT_CHARS) { "漫画源操作参数过大。" }
        return execute(bundle, reading = method == "getPageList" || method == "resolveImage", allowNetwork = true, allowRedirects = method != "login") { js ->
            val invoke = if (bundle.isVenera) "__mfVeneraInvoke" else "__mfInvoke"
            val result = js.evaluate<String>("$invoke(globalThis.__mfProvider, ${JSONObject.quote(method)}, $args)", "operation.js")
            require(result.length <= MAX_RESPONSE_BYTES) { "漫画源返回内容过大。" }
            val envelope = JSONObject(result)
            require(envelope.has("result")) { "漫画源操作没有返回结果。" }
            result
        }
    }

    private suspend fun <T> execute(bundle: ScriptSourcePackage, reading: Boolean, allowNetwork: Boolean,
        allowRedirects: Boolean = true, block: suspend (QuickJs) -> T): T = withContext(Dispatchers.Default) {
        require(bundle.code.length in 1..MAX_SCRIPT_CHARS) { "漫画源脚本大小无效。" }
        withTimeout(20_000L) {
            (if (reading) readingContexts else otherContexts).withPermit {
                prepareJavascriptEngine(application)
                currentCoroutineContext().ensureActive()
                val js = QuickJs.create(Dispatchers.Default)
                val dom = JavascriptDomBridge(bundle.metadata.optString("baseUrl"))
                var requests: JavascriptHttpSession? = null
                var hostFailure: Exception? = null
                var responseStatus: Int? = null
                val sourceEpoch = scriptSourceImageEpoch(application, bundle.sourceId)
                val preferenceScope = sha256(bundle.sourceId.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
                var preferenceCalls = 0
                try {
                    val network = JavascriptHttpSession(allowNetwork,
                        currentCoroutineContext()[ChapterDownloadTransport]?.route, MAX_RESPONSE_BYTES, allowRedirects)
                    requests = network
                    js.memoryLimit = 48L * 1024 * 1024
                    js.maxStackSize = 256L * 1024
                    js.evaluationTimeoutMillis = 2_000L
                    js.defineBinding("__mfHttp", AsyncFunctionBinding<String> { values ->
                        try { network.request(argument(values)).also { result ->
                            responseStatus = JSONObject(result).optInt("statusCode", 0).takeIf { it in 400..599 }
                        } }
                        catch (failure: Exception) { hostFailure = failure; throw failure }
                    })
                    js.defineBinding("__mfDom", FunctionBinding<String> { values -> dom.call(argument(values)) })
                    js.defineBinding("__mfPrefs", FunctionBinding<String> { values ->
                        require(++preferenceCalls <= 128) { "漫画源偏好操作过多。" }
                        val raw = argument(values)
                        if (JSONObject(raw).optString("op") == "get")
                            preference(preferenceScope, raw, allowNetwork, bundle.isVenera)
                        else withScriptSourceImageEpoch(application, bundle.sourceId, sourceEpoch) {
                            preference(preferenceScope, raw, allowNetwork, bundle.isVenera)
                        }
                    })
                    js.defineBinding("__mfCodec", FunctionBinding<String> { values -> javascriptCodec(argument(values)) })
                    js.defineBinding("__mfUrl", FunctionBinding<String> { values -> javascriptUrlInfo(argument(values)) })
                    val metadata = JSONObject(bundle.metadata.toString()).apply { if (!has("id")) put("id", bundle.sourceId) }
                    js.evaluate<Unit>("globalThis.__mfMetadata = $metadata; void 0;", "metadata.js")
                    js.evaluate<Unit>(MANGAYOMI_SOURCE_HOST, "source-host.js")
                    val constructor = if (bundle.isVenera) {
                        js.evaluate<Unit>(VENERA_SOURCE_BRIDGE, "venera-host.js")
                        metadata.getString("entryClass").also { require(it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*"))) }
                    } else "DefaultExtension"
                    js.evaluate<Unit>(js.compile(bundle.code + "\n;globalThis.__mfProvider = new $constructor(); void 0;\n", "source.js"))
                    block(js)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: DownloadRouteException) { throw failure }
                catch (failure: Exception) {
                    // Script errors can contain server bodies, account values or signed URLs.
                    // Keep them out of UI diagnostics and persisted logs.
                    val native = hostFailure
                    if (native is DownloadRouteException) throw native
                    val status = (native as? SourceException)?.httpStatus ?: responseStatus
                    val unsupported = Regex("Unsupported source capability: ([A-Za-z -]{1,100})")
                        .find(failure.message.orEmpty())?.groupValues?.get(1)
                    val message = when {
                        status == 401 || Regex("(?i)not[ _-]?logged|login required|login expired|session expired|需要登录|未登录")
                            .containsMatchIn(failure.message.orEmpty()) -> "此来源需要登录，请先设置来源账号。"
                        unsupported != null -> when {
                            unsupported.contains("image", ignoreCase = true) -> "此来源使用了当前宿主不支持的图片处理。"
                            unsupported.contains("GBK", ignoreCase = true) -> "此来源使用了当前宿主不支持的文字编码。"
                            else -> "此来源使用了当前宿主不支持的能力。"
                        }
                        status != null -> "来源拒绝了当前请求，请检查来源响应与设置。"
                        native != null -> "漫画源网络请求失败，请检查网络、来源响应或当前路由。"
                        else -> "漫画源解析或宿主能力不兼容，请检查来源版本与设置。"
                    }
                    throw SourceException(message, httpStatus = status,
                        retryAfterSeconds = (native as? SourceException)?.retryAfterSeconds ?: 0)
                } finally {
                    runCatching { if (!js.isClosed) js.interruptEvaluation() }
                    try { js.close() } finally { dom.close(); requests?.close() }
                }
            }
        }
    }

    private fun preference(scope: String, raw: String, writable: Boolean, secure: Boolean): String = synchronized(preferenceLock) {
        val request = JSONObject(raw)
        val key = request.getString("key")
        require(key.length in 1..160) { "漫画源偏好名称无效。" }
        val secretKey = "script-source:$scope"
        val values = JSONObject(if (secure) readSecret(application, secretKey) ?: "{}" else preferences.getString(scope, "{}") ?: "{}")
        when (request.getString("op")) {
            "get" -> scriptJsonValue(values.opt(key))
            "clearSession" -> {
                check(writable && secure) { "验证漫画源时不允许修改会话。" }
                values.keys().asSequence().toList().filter { it.startsWith("data:") || it.startsWith("cookie:") || it.startsWith("session:") }.forEach(values::remove)
                writeSecret(application, secretKey, values.toString())
                "true"
            }
            "set", "remove" -> {
                check(writable) { "验证漫画源时不允许修改偏好。" }
                if (request.getString("op") == "remove") values.remove(key)
                else values.put(key, request.opt("value") ?: JSONObject.NULL)
                val encoded = values.toString()
                require(values.length() <= 128 && encoded.length <= 65_536) { "漫画源偏好存储超出限制。" }
                if (secure) writeSecret(application, secretKey, encoded)
                else check(preferences.edit().putString(scope, encoded).commit()) { "漫画源偏好未能保存。" }
                "true"
            }
            else -> throw IllegalArgumentException("不支持的漫画源偏好操作。")
        }
    }

    private companion object {
        const val MAX_SCRIPT_CHARS = 1024 * 1024
        const val MAX_ARGUMENT_CHARS = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        val METHODS = setOf("search", "getDetail", "getPageList", "getPopular", "getLatestUpdates", "getFilterList", "getSourcePreferences", "getHeaders",
            "capabilities", "feeds", "browse", "searchFilters", "accountState", "login", "logout", "favoriteFolders", "favorites", "setFavorite", "comments", "settings", "saveSetting", "resolveImage", "feedFilters")
        val readingContexts = Semaphore(1)
        val otherContexts = Semaphore(1)
        val preferenceLock = SynchronizedObject()
        fun argument(values: Array<Any?>): String = (values.singleOrNull() as? String)
            ?.also { require(it.length <= MAX_RESPONSE_BYTES + 4096) { "漫画源宿主参数过大。" } }
            ?: throw IllegalArgumentException("漫画源宿主参数无效。")
    }
}

private val ScriptSourcePackage.isVenera: Boolean get() = metadata.optString("protocol") == "venera"
internal expect suspend fun prepareJavascriptEngine(context: PlatformContext)
internal expect fun javascriptCodec(raw: String): String
internal expect class JavascriptHttpSession(enabled: Boolean, route: DownloadRouteSnapshot?, maximumBytes: Int, allowRedirects: Boolean = true) {
    suspend fun request(raw: String): String
    fun close()
}
internal expect suspend fun fetchPluginText(context: PlatformContext, url: String): String

internal fun javascriptUrlInfo(raw: String): String {
    val value = JSONObject(raw)
    val base = value.optString("base").takeIf(String::isNotBlank)?.let(::publicPluginUrl)
    val url = base?.resolve(value.getString("url")) ?: publicPluginUrl(value.getString("url"))
    publicPluginUrl(url.toString())
    return JSONObject().put("href", url.toString()).put("origin", "${url.scheme}://${url.host}" + if (url.port == 443) "" else ":${url.port}")
        .put("hostname", url.host).put("host", url.host + if (url.port == 443) "" else ":${url.port}")
        .put("pathname", url.encodedPath).put("search", url.query?.let { "?$it" }.orEmpty())
        .put("hash", url.fragment?.let { "#$it" }.orEmpty()).toString()
}

/** Original script image bytes use the same checked public-network boundary as script HTTP. */
internal expect suspend fun fetchScriptImageBytes(context: PlatformContext, url: String,
    headers: Map<String, String>, route: DownloadRouteSnapshot?, onBytes: ((Long) -> Unit)? = null): ByteArray
