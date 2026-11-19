package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.lanerc.LanercSourceKind
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.net.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.BodySourceRegistryEntry
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.flow.MutableStateFlow

private data class NetworkDirectorySource(val id: String, val name: String, val targetId: String,
    val category: DownloadCategory? = null, val chapterDownload: Boolean = false)

/** Configuration inventory only. Opening a group never probes a site or enables a source. */
@Composable
internal fun NetworkRouteDirectory(repository: UnifiedMangaRepository?, registry: BodySourceRegistry?,
    checks: NetworkAutoCheckController, active: Boolean, backdrop: HazeState?) {
    val context = LocalMangaContext.current
    val plugins = remember(context) { SourcePluginStore.get(context) }
    val packages by plugins.installed.collectAsStateWithLifecycle()
    val emptyRegistry = remember { MutableStateFlow(emptyList<BodySourceRegistryEntry>()) }
    val registryEntries by (registry?.entries ?: emptyRegistry).collectAsStateWithLifecycle()
    val anime = remember(context) { AnimeSourceRepository(context) }
    val animeEntries by anime.extensions.state.collectAsStateWithLifecycle()
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val schedule by checks.schedule.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val mangaSources = (repository?.sources.orEmpty().map { it.id to it.name } +
        registryEntries.map { it.source.id to it.source.name } +
        packages.map { it.sourceId to it.metadata.optString("name").ifBlank { it.sourceId } })
        .distinctBy { it.first }.map { (id, name) -> NetworkDirectorySource(id, name, "source.$id", chapterDownload = true) }
    val animeSources = (anime.providers.map { it.id to it.name } +
        animeEntries.filter { it.kind == LanercSourceKind.VIDEO || it.installed }.map { it.id to
            (it.name + if (!it.installed) " · 未安装" else if (!it.enabled) " · 未启用" else "") })
        .distinctBy { it.first }.map { (id, name) -> NetworkDirectorySource(id, name, "anime.$id", DownloadCategory.ANIME) }
    val ratingSources = ReviewProviders.all.map {
        NetworkDirectorySource(ReviewProviders.routingId(it.id), it.name, ReviewProviders.routingId(it.id))
    } + listOf("anime-bangumi" to "Bangumi 番剧资料", "anime-anilist" to "AniList 番剧资料",
        "anime-danmaku-dandanplay" to "弹弹play 弹幕").map { (id, name) ->
        NetworkDirectorySource(id, name, "anime.$id", DownloadCategory.ANIME)
    }
    val known = (mangaSources + animeSources + ratingSources).map { it.id }.toSet()
    val preserved = settings.sourceOverrides.keys.filterNot { it in known }.sorted().map {
        NetworkDirectorySource(it, it, if (it.startsWith("review.")) it else "source.$it")
    }
    @Composable fun lock(targetId: String) {
        val locked = targetId in schedule.lockedTargetIds
        MotionIconButton(onClick = {
            if (!checks.lockTarget(targetId, !locked)) notice = "锁定状态未保存，请重试"
        }, enabled = active, modifier = Modifier.size(44.dp),
            tooltip = if (locked) "已禁止自动改线；点击允许" else "允许自动改线；点击禁止") {
            Icon(if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                if (locked) "禁止自动改线" else "允许自动改线", Modifier.size(16.dp),
                tint = if (locked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    @Composable fun sources(rows: List<NetworkDirectorySource>) {
        rows.forEach { item -> key(item.id) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    SourceNetworkSettingsControl(item.id, active, item.category, item.name,
                        showChapterDownload = item.chapterDownload)
                }
                lock(item.targetId)
            }
        } }
        if (rows.isEmpty()) Text("暂无来源", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    @Composable fun category(category: DownloadCategory, title: String = "默认线路") {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            DownloadRouteControl(category, enabled = active, backdrop = backdrop, compact = true)
            if (category != DownloadCategory.CHAPTERS && category != DownloadCategory.ANIME)
                lock("category.${category.name}")
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("使用默认会跟随默认线路；单独设置只用于该来源。锁图标控制自动改线：锁定后仍可检测和手动改线。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsDisclosure("漫画来源", "${mangaSources.size} 源", Icons.Outlined.AutoStories,
            expanded == "manga", { if (active) expanded = if (expanded == "manga") null else "manga" }, compact = true) {
            category(DownloadCategory.CHAPTERS, "章节下载默认线路")
            sources(mangaSources)
        }
        SettingsDisclosure("动漫来源", "${animeSources.size} 源", Icons.Outlined.Movie,
            expanded == "anime", { if (active) expanded = if (expanded == "anime") null else "anime" }, compact = true) {
            category(DownloadCategory.ANIME)
            sources(animeSources)
        }
        SettingsDisclosure("评分、资料与弹幕", "${ratingSources.size} 项", Icons.Outlined.StarOutline,
            expanded == "ratings", { if (active) expanded = if (expanded == "ratings") null else "ratings" }, compact = true) {
            sources(ratingSources)
        }
        val categories = DownloadCategory.entries.filter { it != DownloadCategory.CHAPTERS && it != DownloadCategory.ANIME }
        SettingsDisclosure("下载与同步", "${categories.size} 类", Icons.Outlined.Download,
            expanded == "downloads", { if (active) expanded = if (expanded == "downloads") null else "downloads" }, compact = true) {
            categories.forEach { category(it, downloadCategoryLabel(it)) }
        }
        if (preserved.isNotEmpty()) SettingsDisclosure("其他已保存来源", "${preserved.size} 项", Icons.Outlined.Settings,
            expanded == "preserved", { if (active) expanded = if (expanded == "preserved") null else "preserved" }, compact = true) {
            sources(preserved)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}
