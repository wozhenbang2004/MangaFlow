package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException

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
    val installed = remember(sources) { sources.distinctBy { it.id } }
    var selectedId by rememberSaveable(initialSourceId) { mutableStateOf(initialSourceId) }
    var busy by remember { mutableStateOf(false) }
    val selected = if (selectedId == null) installed.firstOrNull() else installed.firstOrNull { it.id == selectedId }
    val enabled = active && !busy
    val densityScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

    AccountWorkspace(Modifier.fillMaxSize().padding(top = contentTopPadding).imePadding(),
        mobileWidth = MangaLayout.ListWidth, top = 12.dp, bottom = 12.dp, sideInset = 20.dp,
        allowSplit = installed.isNotEmpty(), detailKey = selected?.id, overview = { wide ->
            if (wide) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("来源账号", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        MotionIconButton(onClick = onSources, enabled = enabled, tooltip = "管理来源",
                            modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Tune, "管理来源", Modifier.size(17.dp)) }
                    }
                    installed.forEach { source ->
                        val current = source.id == selected?.id
                        MotionTextButton(onClick = { if (enabled) selectedId = source.id }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { this.selected = current },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                containerColor = if (current) MaterialTheme.colorScheme.surface else Color.Transparent),
                        ) {
                            ChapterSourceLogo(source.id, source.name, Modifier.size(22.dp))
                            Text(source.name, Modifier.weight(1f).padding(horizontal = 8.dp),
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            if (current) Icon(Icons.Outlined.Check, null, Modifier.size(15.dp))
                        }
                    }
                }
            } else if (installed.size > 1 || selected == null && installed.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val tabsWidth = maxWidth.coerceAtLeast(78.dp * densityScale * installed.size)
                        Box(Modifier.horizontalScroll(rememberScrollState(), enabled = enabled)) {
                            MotionTabs(selected = installed.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0),
                                labels = installed.map { it.name }, treatment = MotionTabTreatment.Underline,
                                controlHeight = 44.dp, selectionVisible = selected != null,
                                modifier = Modifier.width(tabsWidth).motionInputGate(enabled),
                                tooltips = installed.map { it.name }, onSelect = { index ->
                                    if (enabled) selectedId = installed.getOrNull(index)?.id
                                })
                        }
                    }
                    MotionIconButton(onClick = onSources, enabled = enabled, tooltip = "管理来源",
                        modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Tune, "管理来源", Modifier.size(18.dp)) }
                }
            }
        }) {
            if (selected != null) key(selected.id, selected.revision) {
                val source = selected
                var capabilities by remember { mutableStateOf<SourceCapabilities?>(null) }
                var capabilityUnavailable by remember { mutableStateOf(false) }
                var retryCapabilities by remember { mutableIntStateOf(0) }
                val uriHandler = LocalUriHandler.current
                val website = repository.reviewedProfileBrowserAccount(source)
                var websiteError by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(active, retryCapabilities) {
                    if (!active) return@LaunchedEffect
                    capabilityUnavailable = false
                    try { capabilities = repository.sourceCapabilities(source.id) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { capabilities = null; capabilityUnavailable = true }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ChapterSourceLogo(source.id, source.name, Modifier.size(44.dp))
                    PreferenceHeading(source.name, Modifier.weight(1f))
                    MotionIconButton(onClick = { onInspect(source.id) }, enabled = enabled,
                        modifier = Modifier.size(44.dp), tooltip = "${source.name}设置") {
                        Icon(Icons.Outlined.Tune, "${source.name}设置", Modifier.size(19.dp))
                    }
                }
                val offered = capabilities
                var accountReadError by remember { mutableStateOf(false) }
                if (offered != null && offered.supports(SourceFeature.ACCOUNT)) {
                    var account by remember { mutableStateOf<SourceAccountState?>(null) }
                    var refreshAccount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(active, refreshAccount) {
                        if (!active) return@LaunchedEffect
                        accountReadError = false
                        try { account = repository.sourceAccountState(source.id) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { account = null; accountReadError = true }
                    }
                    val current = account
                    when {
                        accountReadError -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("账号状态未能读取", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MotionTextButton(onClick = { refreshAccount++ }, enabled = enabled) { Text("重试") }
                        }
                        current == null -> SourceAccountLoading("正在读取账号状态…")
                        else -> SourceAccountControls(source, repository, offered, current, enabled = enabled,
                            onBusy = { busy = it }, onAccountChanged = { updated ->
                                account = updated
                                onSessionChange(source.id)
                            }, onOpenShelf = if (offered.supports(SourceFeature.FAVORITES)) {
                                { onOpenShelf(source.id) }
                            } else null, initiallyShowLogin = initialSourceId == source.id, active = active)
                    }
                } else if (offered != null) {
                    Text("此来源未提供漫流内账号登录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (capabilityUnavailable) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("暂时无法核对这个来源的账号能力", Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MotionTextButton(onClick = { retryCapabilities++ }, enabled = enabled) { Text("重试") }
                    }
                } else SourceAccountLoading("正在核对来源账号…")

                if (website != null && (offered?.supports(SourceFeature.ACCOUNT) != true || accountReadError)) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("网页登录不会把账号或登录状态带回漫流。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MotionTextButton(onClick = {
                            val current = repository.reviewedProfileBrowserAccount(source)
                            websiteError = if (current == null || current != website) "此来源已变化，请重新核对。"
                            else try { uriHandler.openUri(current.loginUrl); null }
                            catch (_: Exception) { "无法打开浏览器，请稍后重试。" }
                        }, enabled = enabled) { Text("在浏览器打开官网登录") }
                        websiteError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } else {
                Spacer(Modifier.height(12.dp))
                if (installed.isEmpty()) EmptyMessage("还没有已启用的来源", "先添加或启用来源，再查看它的账号入口。",
                    action = "管理来源", onAction = onSources)
                else EmptyMessage("原来源已不在当前账号列表", "可选择另一来源，或在来源管理中查看原来源。",
                    action = "管理来源", onAction = onSources)
            }
    }
}
