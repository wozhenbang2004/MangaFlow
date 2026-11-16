@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.net

import dev.mangaflow.anime.sources.AnimeHttp
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.AnimeSourceStage
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.ConnectionProbeBudget
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.data.probeReviewNetwork
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.publicPluginUrl
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.platformVersion
import dev.mangaflow.platform.releaseAssetSuffixes
import dev.mangaflow.reader.panels.PANEL_MODEL_SPECS
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.WebDavBackupStore
import dev.mangaflow.storage.probeSteamNetwork
import dev.mangaflow.typography.MangaFontCatalog
import dev.mangaflow.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64

internal class AutoProbeFailure(val status: NetworkAutoRouteStatus, val summary: String, val code: Int? = null) : Exception(summary)
internal data class AutoSample(val message: String, val code: Int? = null, val responseMillis: Long? = null)
internal data class AutoCandidate(val route: DownloadRouteSnapshot?, val unavailable: NetworkAutoRouteResult? = null)
internal class AutoTarget(
    val id: String, val label: String, val category: DownloadCategory? = null, val sourceId: String? = null,
    val coverage: String, val timeoutMillis: Long = 70_000,
    val unsupported: NetworkAutoRouteResult? = null,
    val ratingId: String? = null,
    val groupOverride: NetworkAutoGroup? = null,
    val parallelRoutes: Boolean = true,
    val probe: suspend (DownloadRouteSnapshot) -> AutoSample = { throw AutoProbeFailure(NetworkAutoRouteStatus.UNSUPPORTED, "此项没有可独立验证的目标") },
) {
    val group: NetworkAutoGroup get() = groupOverride ?: when {
        ratingId != null -> NetworkAutoGroup.RATING_SITES
        sourceId != null -> NetworkAutoGroup.MANGA_SOURCES
        else -> NetworkAutoGroup.DOWNLOADS
    }
    val ownerId: String? get() = sourceId ?: ratingId?.let { "review.$it" }
}

internal fun autoCurrentRoute(settings: DownloadRoutingSettings, target: AutoTarget): DownloadRouteSnapshot? = runCatching {
    val source = target.ownerId?.let { settings.sourceOverrides[it] }
    val mode = source?.mode ?: target.category?.let { settings.categoryOverrides[it] } ?: settings.defaultMode
    val endpoint = if (source != null) source.proxy else settings.proxy
    val binding = if (mode != DownloadRouteMode.SYSTEM && (mode != DownloadRouteMode.PROXY || endpoint == null)) currentRelayBinding(mode) else null
    DownloadRouteSnapshot(mode, endpoint.takeIf { mode == DownloadRouteMode.PROXY }, binding).checked()
}.getOrNull()

internal fun autoCandidate(settings: DownloadRoutingSettings, target: AutoTarget, mode: DownloadRouteMode): AutoCandidate = try {
    val source = target.ownerId?.let { settings.sourceOverrides[it] }
    val endpoint = if (mode == DownloadRouteMode.PROXY) {
        if (source?.mode == DownloadRouteMode.PROXY) source.proxy else settings.proxy
    } else null
    val binding = if (endpoint == null) currentRelayBinding(mode) else null
    if (mode == DownloadRouteMode.PROXY && endpoint == null && binding == null) AutoCandidate(null,
        NetworkAutoRouteResult(NetworkAutoRouteStatus.NOT_CONFIGURED, message = "未配置可用代理或 Relay 联动"))
    else AutoCandidate(DownloadRouteSnapshot(mode, endpoint, binding).checked())
} catch (_: Exception) {
    AutoCandidate(null, NetworkAutoRouteResult(NetworkAutoRouteStatus.NOT_CONFIGURED, message = "此线路配置尚不可用"))
}

internal suspend fun autoTargets(context: PlatformContext, repository: UnifiedMangaRepository?, registry: BodySourceRegistry?,
    settings: DownloadRoutingSettings, webdav: WebDavBackupStore): List<AutoTarget> {
    val targets = mutableListOf<AutoTarget>()
    val plugins = SourcePluginStore.get(context)
    plugins.ready.await()
    val sourcePackages = (plugins.repositories.value.map { it.url } + plugins.installed.value.mapNotNull {
        it.metadata.optString("sourceCodeUrl").ifBlank { it.origin }.takeIf { address -> address.startsWith("https://") }
    }).distinct()
    val fontAssets = MangaFontCatalog.options.flatMap { it.assets }.distinctBy { it.fileName to it.gitBlobSha1 }
    val version = platformVersion(context).takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }
    val abi = networkAutoRuntimeAbi()
    DownloadCategory.entries.forEach { category ->
        val id = "category." + category.name
        val label = when (category) {
            DownloadCategory.CHAPTERS -> "漫画章节"
            DownloadCategory.STEAM_WALLPAPERS -> "Steam 连接与壁纸"
            DownloadCategory.STEAM_COMPONENT -> "Steam 连接组件"
            DownloadCategory.FONTS -> "字体"
            DownloadCategory.AI_MODELS -> "AI 模型"
            DownloadCategory.COMPONENTS -> "运行组件"
            DownloadCategory.SOURCE_PACKAGES -> "扩展源安装"
            DownloadCategory.UPDATES -> "应用更新"
            DownloadCategory.WEBDAV -> "WebDAV 同步"
            DownloadCategory.ANIME -> "动漫播放与下载"
        }
        val target = when (category) {
            DownloadCategory.CHAPTERS, DownloadCategory.ANIME -> null // Individual sources are verified, not a misleading aggregate.
            DownloadCategory.STEAM_WALLPAPERS -> AutoTarget(id, label, category,
                coverage = "Steam 匿名 CM 连接；不代表账号、所有权或壁纸资源可下载", timeoutMillis = 115_000) { route ->
                val result = probeSteamNetwork(context, route)
                if (!result.supported) throw AutoProbeFailure(NetworkAutoRouteStatus.UNSUPPORTED, "此平台不支持 Steam 连接检查")
                if (result.requiresPreparation) throw AutoProbeFailure(NetworkAutoRouteStatus.NOT_CONFIGURED, result.message.take(160))
                if (!result.usable) throw AutoProbeFailure(NetworkAutoRouteStatus.FAILED, "Steam CM 连接未完成")
                AutoSample("匿名 CM 握手可用；壁纸内容未验证")
            }
            DownloadCategory.STEAM_COMPONENT, DownloadCategory.COMPONENTS -> AutoTarget(id, label, category,
                coverage = "当前版本、架构的目录和组件资源连接；安装器仍需验签及完整性校验",
                unsupported = if (platformKind != PlatformKind.ANDROID || abi == null || version == null)
                    NetworkAutoRouteResult(NetworkAutoRouteStatus.UNSUPPORTED, message = "此平台没有可独立下载的对应 Android 组件") else null) { route ->
                val address = "https://github.com/wozhenbang2004/MangaFlow/releases/download/v$version/runtime-catalog.json"
                withProbeClient(route) { client ->
                    val root = decodedEnvelope(readJson(client, address, ::githubAsset))
                    if (root.optString("versionName") != version || root.optString("packageName") != "dev.mangaflow") invalidResponse()
                    val runtimes = root.optJSONArray("runtimes") ?: invalidResponse()
                    val wanted = if (category == DownloadCategory.STEAM_COMPONENT) setOf("STEAM") else setOf("LITERT", "ONNX", "QUICKJS")
                    val selected = (0 until runtimes.length()).map { runtimes.getJSONObject(it) }
                        .filter { it.optString("abi") == abi && it.optString("id") in wanted }
                    if (selected.map { it.optString("id") }.toSet() != wanted) invalidResponse()
                    parallelChecks(selected) { entry ->
                        val url = entry.getJSONObject("archive").getString("url").toHttpUrl()
                        if (url.host != "github.com" || !url.encodedPath.startsWith("/wozhenbang2004/MangaFlow/releases/download/v$version/")) invalidResponse()
                        resourceHeader(client, url.toString(), ::githubAsset)
                    }
                    AutoSample("版本目录与组件资源可连接")
                }
            }
            DownloadCategory.FONTS -> AutoTarget(id, label, category,
                coverage = "已声明的 ${fontAssets.size} 个字体文件资源头；按原顺序回退镜像，不下载字体") { route ->
                withProbeClient(route) { client ->
                    parallelChecks(fontAssets) { asset ->
                        var success = false
                        var failure: AutoProbeFailure? = null
                        for (url in asset.urls.distinct()) {
                            try { resourceHeader(client, url) { next -> next.host in setOf("raw.githubusercontent.com", "cdn.jsdelivr.net") }; success = true; break }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (error: AutoProbeFailure) { failure = error }
                            catch (error: Exception) { val safe = autoFailure(error); failure = AutoProbeFailure(safe.status, safe.message, safe.httpStatus) }
                        }
                        if (!success) throw failure ?: AutoProbeFailure(NetworkAutoRouteStatus.FAILED, "字体资源未能响应")
                    }
                    AutoSample("字体资源头可用；非完整文件或测速")
                }
            }
            DownloadCategory.AI_MODELS -> AutoTarget(id, label, category,
                coverage = "${PANEL_MODEL_SPECS.size} 个固定版本模型的资源连接；不下载权重、不验证推理") { route ->
                withProbeClient(route) { client ->
                    parallelChecks(PANEL_MODEL_SPECS.values.toList()) { model -> resourceHeader(client, model.downloadUrl) { next ->
                        next.host == "huggingface.co" || next.host.endsWith(".huggingface.co") || next.host.endsWith(".hf.co")
                    } }
                    AutoSample("模型资源连接可用；未下载完整权重")
                }
            }
            DownloadCategory.SOURCE_PACKAGES -> AutoTarget(id, label, category,
                coverage = "${sourcePackages.size} 个已保存仓库／脚本地址的资源连接；不安装或启用来源",
                unsupported = if (sourcePackages.isEmpty()) NetworkAutoRouteResult(NetworkAutoRouteStatus.NOT_CONFIGURED,
                    message = "尚无已保存的扩展仓库或远端脚本地址") else null) { route ->
                withProbeClient(route) { client ->
                    parallelChecks(sourcePackages) { address ->
                        publicPluginUrl(address)
                        resourceHeader(client, address) { next -> runCatching { publicPluginUrl(next.toString()); true }.getOrDefault(false) }
                    }
                    AutoSample("已配置源包资源连接可用")
                }
            }
            DownloadCategory.UPDATES -> AutoTarget(id, label, category,
                coverage = if (platformKind == PlatformKind.IOS) "发布元数据；签名发行渠道未验证"
                    else "本平台真实更新元数据及发布资源连接；不下载或安装应用") { route ->
                withProbeClient(route) { client ->
                    val root = readJson(client, UpdateManager.MANIFEST_URL, ::githubAsset)
                    if (platformKind == PlatformKind.ANDROID) {
                        val data = decodedEnvelope(root)
                        if (data.optString("packageName") != "dev.mangaflow" || data.optLong("versionCode") <= 0) invalidResponse()
                        val url = data.getJSONObject("apk").getString("url").toHttpUrl()
                        if (url.host != "github.com" || !url.encodedPath.startsWith("/wozhenbang2004/MangaFlow/releases/download/")) invalidResponse()
                        resourceHeader(client, url.toString(), ::githubAsset)
                    } else {
                        if (root.optString("tag_name").isBlank()) invalidResponse()
                        if (platformKind == PlatformKind.IOS) throw AutoProbeFailure(NetworkAutoRouteStatus.PARTIAL,
                            "发布元数据已响应；iOS 签名发行渠道不由本次线路验证")
                        val assets = root.optJSONArray("assets") ?: invalidResponse()
                        val listed = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                        val asset = releaseAssetSuffixes().firstNotNullOfOrNull { suffix ->
                            listed.firstOrNull { it.optString("name").endsWith(suffix, true) }
                        } ?: throw AutoProbeFailure(NetworkAutoRouteStatus.UNSUPPORTED, "此版本没有当前平台的安装资源")
                        if (asset.optLong("size") <= 0 || !asset.optString("digest").removePrefix("sha256:").matches(Regex("[a-fA-F0-9]{64}"))) invalidResponse()
                        val url = asset.getString("browser_download_url").toHttpUrl()
                        if (url.host != "github.com" || !url.encodedPath.startsWith("/wozhenbang2004/MangaFlow/releases/download/")) invalidResponse()
                        resourceHeader(client, url.toString(), ::githubAsset)
                    }
                    AutoSample("更新元数据与所列资源连接可用")
                }
            }
            DownloadCategory.WEBDAV -> {
                val request = if (webdav.connectionProbeConfigured()) runCatching { webdav.connectionProbeRequest() }.getOrNull() else null
                AutoTarget(id, label, category, coverage = "保存的 WebDAV 集合 Depth:0 只读查询；不传输或覆盖备份",
                    unsupported = if (request == null) NetworkAutoRouteResult(NetworkAutoRouteStatus.NOT_CONFIGURED,
                        message = "WebDAV 地址或凭据尚未配置完整") else null) { route ->
                    withProbeClient(route) { client ->
                        val response = boundedRequest(client, requireNotNull(request), headersOnly = false) { next ->
                            next.scheme == "https" && next.host == request.url.host && next.port == request.url.port
                        }
                        if (response.code != 207 || !(response.text().contains("multistatus", true))) invalidResponse()
                        AutoSample("WebDAV 集合只读查询可用", response.code)
                    }
                }
            }
        }
        if (target != null) targets += target
    }
    val sources = repository?.sources.orEmpty() + registry?.entries?.value.orEmpty().map { it.source } +
        plugins.installed.value.map { bundle -> BodySourceInfo(bundle.sourceId,
            bundle.metadata.optString("name").ifBlank { "扩展漫画源" }, bundle.metadata.optString("lang"),
            bundle.metadata.optString("baseUrl"), "", revision = bundle.digest) }
    val known = sources.distinctBy { it.id }
    known.forEach { source ->
        targets += AutoTarget("source." + source.id, source.name, sourceId = source.id,
            coverage = "当前安装版本的真实目录响应；不更改来源开关，不代表图片 CDN",
            timeoutMillis = 55_000,
            parallelRoutes = repository?.sourceProbeParallelRoutes(source.id) == true,
            unsupported = when {
                source.id == "local" -> NetworkAutoRouteResult(NetworkAutoRouteStatus.UNSUPPORTED, message = "本地来源无需联网")
                repository == null || repository.sources.none { it.id == source.id } -> NetworkAutoRouteResult(NetworkAutoRouteStatus.UNSUPPORTED, message = "该来源适配器尚未就绪")
                else -> null
            }) { route ->
            if (repository?.sourceProbeObserved(source.id, route) != true) throw AutoProbeFailure(NetworkAutoRouteStatus.PARTIAL, "只取得缓存目录，尚无真实网络响应")
            AutoSample("目录响应可用；图片未验证")
        }
    }

    // The anime category is inherited by both source discovery and frozen playback/download
    // assets. Only real JSON API schema evidence can trigger an automatic source-route change.
    val animeProviders = AnimeSourceRepository(context).providers
        .distinctBy { it.id }
    animeProviders.forEach { provider ->
        targets += AutoTarget(id = "anime." + provider.id, label = provider.name,
            category = DownloadCategory.ANIME, sourceId = provider.id,
            groupOverride = NetworkAutoGroup.ANIME_SOURCES,
            coverage = if (provider.id in AUTO_VERIFIED_ANIME_IDS)
                "动漫来源真实匿名目录／搜索 API 的结构验证；不代表全部剧集可播"
            else "第三方脚本的站点连通性；未验证解析结果，不自动修改路线",
            timeoutMillis = 45_000L, parallelRoutes = true) { route ->
            probeAnimeSource(provider.id, provider.homeUrl, route)
        }
    }
    val animeOwnerIds = animeProviders.map { it.id }.toSet() + setOf(
        "anime-bangumi", "anime-anilist", "anime-danmaku-dandanplay")
    for ((id, label) in listOf(
        "anime-bangumi" to "Bangumi 番剧资料",
        "anime-anilist" to "AniList 番剧资料",
        "anime-danmaku-dandanplay" to "弹弹play 弹幕")) {
        targets += AutoTarget("anime.$id", label, category = DownloadCategory.ANIME,
            sourceId = id, groupOverride = NetworkAutoGroup.ANIME_SOURCES,
            coverage = "资料／弹幕服务的站点可达性；认证／完整 API 未验证，不自动改线路",
            timeoutMillis = 25_000L) { route ->
            val address = when (id) {
                "anime-bangumi" -> "https://api.bgm.tv/"
                "anime-anilist" -> "https://graphql.anilist.co/"
                else -> "https://api.dandanplay.net/"
            }
            val http = AnimeHttp(id, route)
            try {
                http.request(animePublicUrl(address), AnimeSourceStage.METADATA)
                throw AutoProbeFailure(NetworkAutoRouteStatus.PARTIAL,
                    "站点已响应，未完成资料或弹幕接口校验，保留当前线路")
            } finally { http.close() }
        }
    }
    ReviewProviders.all.forEach { provider ->
        targets += AutoTarget(ReviewProviders.routingId(provider.id), provider.name, ratingId = provider.id,
            coverage = "实际评分／映射服务的匿名榜单或漫画搜索及结构校验；不写评分缓存或关联", timeoutMillis = 35_000) { route ->
            val result = probeReviewNetwork(context, provider.id, route)
            if (!result.observed || !result.valid) throw AutoProbeFailure(NetworkAutoRouteStatus.PARTIAL,
                "未取得可确认的漫画服务结果；首页或空查询不计可用")
            AutoSample("真实漫画服务响应与解析可用", responseMillis = result.responseMillis)
        }
    }
    val knownReviewOwners = ReviewProviders.ids.map(ReviewProviders::routingId).toSet()
    settings.sourceOverrides.keys.filter { id -> known.none { it.id == id } &&
        id !in knownReviewOwners && id !in animeOwnerIds }.sorted().forEach { id ->
        val rating = id.removePrefix("review.").takeIf { id.startsWith("review.") }
        targets += AutoTarget(if (rating == null) "source.$id" else id,
            if (rating == null) "未安装来源" else "未注册评分站", sourceId = id.takeIf { rating == null }, ratingId = rating,
            coverage = "仅保留的来源设置", unsupported = NetworkAutoRouteResult(NetworkAutoRouteStatus.UNSUPPORTED,
                message = "来源已移除或未安装，本次不修改其配置"))
    }
    return targets
}


private val AUTO_VERIFIED_ANIME_IDS = setOf("anime-tvtfun", "anime-age", "anime-girigiri", "anime-lmm")

/** Probes never call a provider through its currently selected route instead of the candidate. */
private suspend fun probeAnimeSource(id: String, home: String, route: DownloadRouteSnapshot): AutoSample {
    if (id !in AUTO_VERIFIED_ANIME_IDS) {
        val http = AnimeHttp(id, route)
        try {
            http.request(animePublicUrl(home), AnimeSourceStage.SEARCH)
            throw AutoProbeFailure(NetworkAutoRouteStatus.PARTIAL,
                "站点可达，但脚本搜索／视频解析未验证，保留原线路")
        } finally { http.close() }
    }
    val address = when (id) {
        "anime-tvtfun" -> "https://www.tvtfun.net/api/client/anime?keyword=anime&page=1&pageSize=1"
        "anime-age" -> "https://api.agedm.io/v2/catalog?genre=all&label=all&letter=all&order=time&region=all&resource=all&season=all&status=all&year=all&page=1&size=1"
        "anime-girigiri" -> "https://m3u8.girigirilove.com/api.php/Vod/get_list?vod_name=anime&offset=0&limit=1"
        else -> "https://www.lmm22.com/index.php/ajax/suggest?mid=1&wd=anime"
    }
    val http = AnimeHttp(id, route)
    try {
        val root = http.json(animePublicUrl(address), AnimeSourceStage.SEARCH, referer = home)
        val valid = when (id) {
            "anime-tvtfun" -> root.optJSONObject("data")?.optJSONArray("items") != null
            "anime-age" -> root.optJSONArray("videos") != null
            "anime-girigiri" -> root.optJSONObject("info")?.optJSONArray("rows") != null
            else -> root.optJSONArray("list") != null
        }
        if (!valid) throw AutoProbeFailure(NetworkAutoRouteStatus.FAILED,
            "动漫搜索响应结构不匹配，不能判定线路可用")
        return AutoSample("动漫匿名目录响应结构有效；不代表播放器资源可用")
    } finally { http.close() }
}

internal suspend fun <T> withProbeClient(route: DownloadRouteSnapshot, action: suspend (NetworkClient) -> T): T {
    val client = NetworkClient(connectTimeoutMillis = 5_000, readTimeoutMillis = 7_000, callTimeoutMillis = 9_000,
        acceptCompression = false, route = route, observeRelayTraffic = false)
    try { return action(client) } finally { client.close() }
}

private fun githubAsset(url: HttpUrl): Boolean = url.host in setOf("github.com", "api.github.com", "raw.githubusercontent.com",
    "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

private fun decodedEnvelope(root: JSONObject): JSONObject {
    val payload = root.optString("payload")
    if (payload.length !in 1..262_144 || root.optString("signature").isBlank()) invalidResponse()
    return try { JSONObject(Base64.Default.decode(payload).decodeToString()) }
    catch (_: Exception) { invalidResponse() }
}

private suspend fun readJson(client: NetworkClient, address: String, allowed: (HttpUrl) -> Boolean): JSONObject {
    val request = Request.Builder().url(address).header("Accept", "application/json")
        .header("User-Agent", "MangaFlow-Network-Check").header("Cache-Control", "no-cache").build()
    val response = boundedRequest(client, request, false, allowed)
    return try { JSONObject(response.text()) }
    catch (_: Exception) { invalidResponse() }
}

private suspend fun resourceHeader(client: NetworkClient, address: String, allowed: (HttpUrl) -> Boolean): NetworkResponse {
    val request = Request.Builder().url(address).method("HEAD", null).header("Cache-Control", "no-cache")
        .header("User-Agent", "MangaFlow-Network-Check").build()
    val response = boundedRequest(client, request, true, allowed)
    if (response.header("Content-Type")?.contains("text/html", true) == true) invalidResponse()
    return response
}

private suspend fun boundedRequest(client: NetworkClient, initial: Request, headersOnly: Boolean,
    allowed: (HttpUrl) -> Boolean): NetworkResponse {
    var request = initial
    repeat(6) {
        currentCoroutineContext().ensureActive()
        val address = request.url
        if (address.scheme != "https" || address.username.isNotEmpty() || address.password.isNotEmpty() || !allowed(address))
            throw AutoProbeFailure(NetworkAutoRouteStatus.UNSUPPORTED, "服务跳转超出本次安全检查范围")
        val response = try { ConnectionProbeBudget.requests.withPermit { withTimeout(10_000L) {
            if (headersOnly) client.downloadWithHeaders(request, 0, onResponse = { false }, onChunk = { _, _ -> })
            else client.execute(request, 256L * 1024)
        } } } catch (_: TimeoutCancellationException) { throw AutoProbeFailure(NetworkAutoRouteStatus.FAILED, "等待服务响应超时") }
        if (response.code in setOf(301, 302, 303, 307, 308)) {
            // Authentication belongs to its configured origin. WebDAV redirects are not followed.
            if (request.method == "PROPFIND") throw AutoProbeFailure(NetworkAutoRouteStatus.UNSUPPORTED, "WebDAV 地址发生跳转，请在设置中使用最终地址", response.code)
            val next = response.header("Location")?.let { address.resolve(it) }
                ?: throw AutoProbeFailure(NetworkAutoRouteStatus.FAILED, "服务返回无效跳转", response.code)
            val sameOrigin = next.scheme == address.scheme && next.host == address.host && next.port == address.port
            val builder = Request.Builder().url(next).method(request.method, request.body)
            request.headers.forEach { (name, value) ->
                if (sameOrigin || name.lowercase() !in setOf("authorization", "cookie", "proxy-authorization")) builder.header(name, value)
            }
            request = builder.build()
        } else {
            if (response.code !in 200..299) throw httpProbeFailure(response.code)
            return response
        }
    }
    throw AutoProbeFailure(NetworkAutoRouteStatus.FAILED, "服务跳转次数过多")
}

/** Concurrency is bounded at each actual HTTP request by ConnectionProbeBudget, not by a whole file group. */
private suspend fun <T> parallelChecks(values: List<T>, check: suspend (T) -> Unit): Unit = coroutineScope {
    val window = Semaphore(3)
    values.map { value -> async { window.withPermit { check(value) } } }.awaitAll()
}

private fun httpProbeFailure(code: Int) = AutoProbeFailure(when (code) {
    209, 210, 401, 403 -> NetworkAutoRouteStatus.LOGIN_REQUIRED
    405, 501 -> NetworkAutoRouteStatus.UNSUPPORTED
    else -> NetworkAutoRouteStatus.FAILED
}, when (code) {
    209, 210 -> "来源账号或客户端验证受限，本轮不据此判断换线"
    401, 403 -> "服务拒绝访问，需要账号、验证或权限"
    429 -> "服务限流，本轮不据此判断换线"
    503 -> "服务暂时繁忙，本轮不据此判断换线"
    405, 501 -> "服务不支持本次只读检查方式"
    else -> "服务响应 HTTP $code"
}, code)

private fun invalidResponse(): Nothing = throw AutoProbeFailure(NetworkAutoRouteStatus.FAILED, "响应内容与所选服务不符")

internal fun autoFailure(error: Exception): NetworkAutoRouteResult {
    if (error is AutoProbeFailure) return NetworkAutoRouteResult(error.status, message = error.summary, httpStatus = error.code)
    if (error is DownloadRouteException) return NetworkAutoRouteResult(NetworkAutoRouteStatus.FAILED, message = "线路或 Relay 联动尚不可用")
    val status = (error as? SourceException)?.httpStatus
    if (status != null) { val safe = httpProbeFailure(status); return NetworkAutoRouteResult(safe.status, message = safe.summary, httpStatus = status) }
    val message = error.message.orEmpty()
    if ("登录" in message || "登入" in message) return NetworkAutoRouteResult(NetworkAutoRouteStatus.LOGIN_REQUIRED, message = "来源需要登录或账号验证")
    if ("组件" in message || "不支持" in message || "未安装" in message) return NetworkAutoRouteResult(NetworkAutoRouteStatus.UNSUPPORTED, message = "此来源的检查能力或组件尚未就绪")
    return NetworkAutoRouteResult(NetworkAutoRouteStatus.FAILED, message = networkFailureKind(error)?.message ?: "服务连接或响应读取失败")
}
