package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.data.plugins.PublicPlaybackParserGrant
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.decodeUrlComponent
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class LanercPlaybackSelection(val catalogId: String, val group: String, val flag: String)

/**
 * Pinned AppV7 detail() packs parse_api/extra_parse_api into each av7 episode flag;
 * play() GETs that exact prefix plus the episode token, optionally refreshed by its API.
 * Keep this declaration in memory under the original source/revision/route/flag identity.
 */
internal class LanercPlaybackParsers {
    private data class Scope(
        val revision: String, val digest: String, val route: DownloadRouteSnapshot,
        // Fingerprints only: parser URLs and opaque episode tokens are not retained here.
        val flags: LinkedHashMap<String, Long> = linkedMapOf(),
    )
    private val lock = SynchronizedObject()
    private val sources = mutableMapOf<String, Scope>()

    fun invalidate(id: String) = synchronized(lock) { sources.remove(id); Unit }

    fun remember(bundle: LanercInstalledPackage, route: DownloadRouteSnapshot, catalogId: String, result: Any?) {
        if (!reviewed(bundle) || result !is JSONObject || result.optString("id") != catalogId) return
        val episodes = result.optJSONArray("episodes") ?: return
        if (episodes.length() > MAX_CATALOG_EPISODES) return
        val declarations = linkedSetOf<String>()
        for (index in 0 until episodes.length()) {
            val episode = episodes.optJSONObject(index) ?: continue
            val flag = episode.optString("url")
            if (flag.isBlank() || flag.length > 65_536) continue
            declarations += selectionKey(LanercPlaybackSelection(catalogId,
                episode.optString("route").trim().ifBlank { "默认线路" }, flag))
            while (declarations.size > MAX_FLAGS) declarations.remove(declarations.first())
        }
        rememberKeys(bundle, route, declarations)
    }

    fun rememberSelection(bundle: LanercInstalledPackage, route: DownloadRouteSnapshot,
        selection: LanercPlaybackSelection, result: Any?) {
        if (!reviewed(bundle)) return
        val detail = result as? JSONObject
        val episodes = detail?.optJSONArray("episodes")
        require(detail?.optString("id") == selection.catalogId && episodes != null &&
            episodes.length() <= MAX_CATALOG_EPISODES && (0 until episodes.length()).any { index ->
                val episode = episodes.optJSONObject(index) ?: return@any false
                episode.optString("url") == selection.flag &&
                    episode.optString("route").trim().ifBlank { "默认线路" } == selection.group
            }) { "所选剧集或线路已变化，请刷新剧集后重新选择。" }
        rememberKeys(bundle, route, setOf(selectionKey(selection)))
    }

    private fun rememberKeys(bundle: LanercInstalledPackage, route: DownloadRouteSnapshot, keys: Set<String>) {
        synchronized(lock) {
            val now = monotonicTimeMillis()
            val scope = sources[bundle.id]?.takeIf { it.matches(bundle, route) }
                ?: Scope(bundle.revision, bundle.digest, route).also { sources[bundle.id] = it }
            scope.flags.entries.removeAll { it.value <= now }
            keys.forEach { flag ->
                scope.flags.remove(flag)
                scope.flags[flag] = now + MAX_AGE_MILLIS
            }
            while (scope.flags.size > MAX_FLAGS) scope.flags.remove(scope.flags.keys.first())
        }
    }

    fun needsRefresh(bundle: LanercInstalledPackage, route: DownloadRouteSnapshot, selection: LanercPlaybackSelection): Boolean =
        reviewed(bundle) && declaredParsers(selection.flag).isNotEmpty() && !hasCurrent(bundle, route, selection)

    fun forPlay(bundle: LanercInstalledPackage, route: DownloadRouteSnapshot, flag: String,
        selection: LanercPlaybackSelection?): List<PublicPlaybackParserGrant> {
        if (!reviewed(bundle)) return emptyList()
        val grants = declaredParsers(flag)
        if (grants.isEmpty()) return emptyList()
        if (selection == null || selection.flag != flag || !hasCurrent(bundle, route, selection))
            throw LanercCapabilityException("此线路的解析声明已变化，请刷新剧集后重试。")
        return grants
    }

    private fun hasCurrent(bundle: LanercInstalledPackage, route: DownloadRouteSnapshot,
        selection: LanercPlaybackSelection): Boolean = synchronized(lock) {
            val scope = sources[bundle.id]
            if (scope == null || !scope.matches(bundle, route)) {
                sources.remove(bundle.id)
                return@synchronized false
            }
            val now = monotonicTimeMillis()
            scope.flags.entries.removeAll { it.value <= now }
            selectionKey(selection) in scope.flags
        }

    private fun selectionKey(selection: LanercPlaybackSelection): String =
        animeHash(JSONObject().put("catalogId", selection.catalogId).put("group", selection.group).put("flag", selection.flag).toString())

    private fun Scope.matches(bundle: LanercInstalledPackage, currentRoute: DownloadRouteSnapshot): Boolean =
        revision == bundle.revision && digest == bundle.digest && route == currentRoute

    private fun reviewed(bundle: LanercInstalledPackage): Boolean = REVIEWED[bundle.id] == bundle.digest

    @OptIn(ExperimentalEncodingApi::class)
    private fun declaredParsers(flag: String): List<PublicPlaybackParserGrant> = runCatching {
        require(flag.length <= 65_536)
        val decoded = when {
            flag.startsWith("av7:") -> Base64.Default.decode(flag.removePrefix("av7:")).decodeToString(throwOnInvalidSequence = true)
            flag.startsWith("av7u:") -> decodeUrlComponent(flag.removePrefix("av7u:"))
            else -> return emptyList()
        }
        val value = JSONObject(decoded)
        if (value.optString("url").isBlank()) return emptyList()
        listOf(value.optString("parse"), value.optString("extra")).distinct()
            .mapNotNull { PublicPlaybackParserGrant.fromCataloguePrefix(it) }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_CATALOG_EPISODES = 20_000
        const val MAX_FLAGS = 4096
        const val MAX_AGE_MILLIS = 30 * 60_000L
        val REVIEWED = mapOf(
            "anime-lanerc-manguo" to "177ecf39473b12aa042cdbbad203af47e3cfeacb54520d55da9a88b704dd546c",
            "anime-lanerc-gugu" to "323079886b1ffb0b7023545471360cb2851c19af524a0f33bcb655f133376e1f",
            "anime-lanerc-07" to "7505abdae0dcdab1fad683e8c028bb41e4c5986c2c2ebafff88640e83ce3ecd6",
        )
    }
}
