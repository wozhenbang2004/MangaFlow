package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.SourceAccountState
import dev.mangaflow.data.sources.SourceCapabilities
import dev.mangaflow.data.sources.SourceFeature
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class ProfileAccountSource(val source: BodySourceInfo, val capabilities: SourceCapabilities?)

/** Credentials are managed here; source collections stay in the bookshelf. */
@Composable
internal fun ContentProfileAccountScreen(
    repository: UnifiedMangaRepository,
    sources: List<BodySourceInfo>,
    active: Boolean,
    onSources: () -> Unit,
    onSessionChange: (String) -> Unit,
    onInspect: (String) -> Unit,
    contentTopPadding: Dp = 0.dp,
    initialSourceId: String? = null,
    onOpenShelf: (String) -> Unit = {},
) {
    var known by remember(sources) { mutableStateOf<List<ProfileAccountSource>?>(null) }
    var retryCapabilities by remember { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(initialSourceId) { mutableStateOf(initialSourceId) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(sources, active, retryCapabilities) {
        if (!active) return@LaunchedEffect
        val slots = Semaphore(3)
        known = supervisorScope {
            sources.map { source -> async {
                slots.withPermit {
                    val capabilities = try { repository.sourceCapabilities(source.id) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                    ProfileAccountSource(source, capabilities)
                }
            } }.awaitAll()
        }
    }
    val accountSources = known.orEmpty().filter { entry ->
        entry.capabilities?.let { it.supports(SourceFeature.ACCOUNT) || it.browserAccount != null } == true
    }
    val requested = known.orEmpty().firstOrNull { it.source.id == selectedId }
    val requestedUnavailable = requested != null && accountSources.none { it.source.id == selectedId }
    val choice = if (requestedUnavailable) null else accountSources.firstOrNull { it.source.id == selectedId }
        ?: accountSources.firstOrNull()
    val unavailableCount = known.orEmpty().count { it.capabilities == null }

    Box(Modifier.fillMaxSize().padding(top = contentTopPadding).imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MangaLayout.ListWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (known == null) SourceAccountLoading("正在读取来源账号…")
            if (accountSources.size > 1 || requestedUnavailable && accountSources.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MotionTabs(selected = accountSources.indexOfFirst { it == choice }.coerceAtLeast(0),
                        labels = accountSources.map { it.source.name }, treatment = MotionTabTreatment.Underline,
                        compact = true, controlHeight = 44.dp, selectionVisible = choice != null,
                        modifier = Modifier.weight(1f), onSelect = { index ->
                            if (active && !busy) selectedId = accountSources.getOrNull(index)?.source?.id
                        })
                    MotionIconButton(onClick = onSources, enabled = active && !busy, tooltip = "管理来源",
                        modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Tune, "管理来源", Modifier.size(18.dp)) }
                }
            }
            if (choice != null) key(choice.source.id, choice.source.revision) {
                val source = choice.source
                val capabilities = requireNotNull(choice.capabilities)
                var account by remember { mutableStateOf<SourceAccountState?>(null) }
                var accountReadError by remember { mutableStateOf(false) }
                var refreshAccount by remember { mutableIntStateOf(0) }
                LaunchedEffect(active, refreshAccount) {
                    if (!active) return@LaunchedEffect
                    accountReadError = false
                    try { account = repository.sourceAccountState(source.id) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { accountReadError = true }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChapterSourceLogo(source.id, source.name, Modifier.size(32.dp))
                    Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = { onInspect(source.id) }, enabled = active && !busy,
                        modifier = Modifier.size(44.dp), tooltip = "${source.name}设置") {
                        Icon(Icons.Outlined.Tune, "${source.name}设置", Modifier.size(19.dp))
                    }
                }
                val current = account
                when {
                    accountReadError -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("账号状态未能读取", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MotionTextButton(onClick = { refreshAccount++ }, enabled = active && !busy) { Text("重试") }
                    }
                    current == null -> SourceAccountLoading("正在读取账号状态…")
                    else -> SourceAccountControls(source, repository, capabilities, current, enabled = active && !busy,
                        onBusy = { busy = it }, onAccountChanged = { updated ->
                            account = updated
                            onSessionChange(source.id)
                        }, onOpenShelf = if (capabilities.supports(SourceFeature.FAVORITES)) {
                            { onOpenShelf(source.id) }
                        } else null, initiallyShowLogin = initialSourceId == source.id, active = active)
                }
            } else if (known != null) {
                Spacer(Modifier.height(12.dp))
                when {
                    requestedUnavailable && requested != null -> EmptyMessage(
                        if (requested.capabilities == null) "暂时无法读取 ${requested.source.name} 的账号能力"
                        else "${requested.source.name} 未提供应用内账号入口",
                        if (requested.capabilities == null) "查看来源是否已准备好。" else "可在来源设置中查看可用功能。",
                        action = "查看来源", onAction = { onInspect(requested.source.id) },
                    )
                    unavailableCount > 0 -> EmptyMessage("暂时无法读取来源账号", "来源运行组件或账号状态尚未就绪。", action = "重试",
                        onAction = { retryCapabilities++ })
                    else -> EmptyMessage("当前没有可设置的来源账号", "来源提供账号功能时会在这里显示。", action = "管理来源", onAction = onSources)
                }
            }
            if (choice != null && unavailableCount > 0) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("有 ${unavailableCount} 个来源的账号能力暂未读取", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MotionTextButton(onClick = { retryCapabilities++ }, enabled = active && !busy) { Text("重试") }
                }
            }
        }
    }
}
