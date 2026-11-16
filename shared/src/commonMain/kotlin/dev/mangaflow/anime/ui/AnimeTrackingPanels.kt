package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

@Composable
internal fun AnimeTrackingPanel(visible: Boolean, state: AnimeScreenState, controller: AnimeController,
    backdrop: HazeState, onDismiss: () -> Unit, onReview: () -> Unit) {
    var providerIndex by remember { mutableIntStateOf(0) }
    val provider = if (providerIndex == 0) "anime-bangumi" else "anime-anilist"
    val account = state.accounts.firstOrNull { it.providerId == provider }
    var token by remember(provider) { mutableStateOf("") }
    var remoteId by remember(provider, state.detail?.subject?.id) { mutableStateOf("") }
    var requestedReview by remember { mutableStateOf(false) }
    var previousReviewIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val accountName = animeProviderName(provider)
    LaunchedEffect(visible) { if (!visible) { token = ""; requestedReview = false } }
    LaunchedEffect(state.syncing, state.reviews) {
        if (requestedReview && !state.syncing) {
            requestedReview = false
            if (state.reviews.any { it.id !in previousReviewIds &&
                    it.providerId == provider && it.subjectId == state.detail?.subject?.id }) {
                onDismiss()
                onReview()
            }
        }
    }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 540.dp) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("追番账号", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭追番账号") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        MotionTabs(providerIndex, listOf("Bangumi", "AniList"), onSelect = {
                            providerIndex = it; requestedReview = false
                        }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp)
                    }
                    item {
                        Text("同步追番状态与正片已完成集；播放到第几分保存在本机。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (account == null) {
                        item {
                            AnimeTextField(token, { token = it }, accountName + " 访问令牌", secret = true,
                                enabled = !state.syncing)
                        }
                        item {
                            AnimeQuietLabel("使用该账号生成的访问令牌连接。连接后，先查看差异，再决定是否回写远端。")
                            MotionButton(onClick = {
                                controller.connectTracking(provider, token.trim())
                                token = ""
                            }, enabled = token.isNotBlank() && !state.syncing,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)) {
                                Text(if (state.syncing) "正在连接" else "连接 " + accountName)
                            }
                        }
                    } else {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(account.displayName.ifBlank { account.ownerId }, style = MaterialTheme.typography.titleSmall)
                                    AnimeQuietLabel(accountName + " · 已连接")
                                }
                                MotionTextButton(onClick = { controller.disconnectTracking(provider) },
                                    enabled = !state.syncing, modifier = Modifier.heightIn(min = 44.dp)) { Text("断开") }
                            }
                        }
                        if (state.detail != null) {
                            item { AnimeSectionTitle("核对这部番剧", state.detail.subject.title) }
                            if (providerIndex == 1) item {
                                AnimeTextField(remoteId, { remoteId = it.filter(Char::isDigit) }, "AniList 动画条目 ID（首次关联必填）")
                                AnimeQuietLabel("只关联你指定的动画条目。已关联后可留空沿用；请先核对季度和版本。",
                                    Modifier.padding(top = 8.dp))
                            }
                            item {
                                MotionButton(onClick = {
                                    previousReviewIds = state.reviews.mapTo(mutableSetOf()) { it.id }
                                    requestedReview = true
                                    controller.reviewTracking(provider, remoteId.trim().takeIf { it.isNotBlank() })
                                }, enabled = !state.syncing && !state.detailLoading && state.detail.episodes.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (state.syncing) "正在读取远端进度" else "查看进度差异")
                                }
                            }
                        } else item {
                            AnimeMessage("先打开一部番剧", "从详情里核对这部番剧的追番进度，避免批量误认同名作品。")
                        }
                    }
                    if (state.notice != null) item {
                        Text(state.notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnimeProgressReviewPanel(visible: Boolean, state: AnimeScreenState, controller: AnimeController,
    backdrop: HazeState, onDismiss: () -> Unit, onConnect: () -> Unit,
    onRecheck: (AnimeTrackingReview) -> Unit) {
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 760.dp) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("进度差异", style = MaterialTheme.typography.titleMedium)
                        AnimeQuietLabel("逐项选择，再应用")
                    }
                    if (state.canUndo) MotionIconButton(onClick = controller::undo, modifier = Modifier.size(44.dp),
                        tooltip = "撤销本机改动") {
                        Icon(Icons.Outlined.Undo, "撤销本机改动", Modifier.size(19.dp))
                    }
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭进度差异") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item {
                        Text("这里只处理追番状态与正片已完成集。来源和集数映射在播放来源里确认；远端读取失败会保留本机记录。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.syncing) item { AnimeQuietLabel("正在核对所选内容，请稍候。") }
                    if (state.reviews.isEmpty()) item {
                        AnimeMessage("目前没有待复核项",
                            if (state.accounts.isEmpty()) "连接追番账号后，从番剧详情发起核对。"
                            else "从番剧详情读取远端进度后，差异会出现在这里。读取失败不表示远端已删除。",
                            action = if (state.accounts.isEmpty()) "连接账号" else "追番账号", onAction = onConnect)
                    }
                    items(state.reviews, key = { it.id }) { review ->
                        AnimeReviewItem(review, !state.syncing, onRecheck = { onRecheck(review) }, onApply = { status, episodes, publish ->
                            controller.applyReview(review.id, status, episodes, publish)
                        })
                    }
                    if (state.notice != null) item {
                        Text(state.notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeReviewItem(review: AnimeTrackingReview, enabled: Boolean, onRecheck: () -> Unit,
    onApply: (AnimeSyncChoice, AnimeSyncChoice, Boolean) -> Unit) {
    // A refreshed evidence set clears the draft. A transient network failure does not fabricate another row.
    var status by remember(review.id, review.localRevision, review.localStatus, review.remoteStatus) {
        mutableStateOf<AnimeSyncChoice?>(null)
    }
    var episodes by remember(review.id, review.localRevision, review.localNumbers, review.remoteNumbers) {
        mutableStateOf<AnimeSyncChoice?>(null)
    }
    var expandedNumbers by remember(review.id) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.onSurface.copy(alpha = .035f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(review.subjectTitle, style = MaterialTheme.typography.titleSmall)
            AnimeQuietLabel(animeProviderName(review.providerId) + " · 条目 " + review.remoteSubjectId)
        }
        AnimeQuietLabel("追番状态")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                AnimeSelection("本机 · " + animeStatusLabel(review.localStatus), selected = status == AnimeSyncChoice.LOCAL,
                    onClick = { status = AnimeSyncChoice.LOCAL }, enabled = enabled)
            }
            Box(Modifier.weight(1f)) {
                AnimeSelection("远端 · " + animeStatusLabel(review.remoteStatus), selected = status == AnimeSyncChoice.REMOTE,
                    onClick = { status = AnimeSyncChoice.REMOTE }, enabled = enabled)
            }
        }
        AnimeQuietLabel("正片已完成集")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                AnimeSelection("本机 · " + review.localNumbers.size + " 集",
                    "本机独有 " + (review.localNumbers - review.remoteNumbers).size + " 集",
                    selected = episodes == AnimeSyncChoice.LOCAL,
                    onClick = { episodes = AnimeSyncChoice.LOCAL }, enabled = enabled)
            }
            Box(Modifier.weight(1f)) {
                AnimeSelection("远端 · " + review.remoteNumbers.size + " 集",
                    "远端独有 " + (review.remoteNumbers - review.localNumbers).size + " 集",
                    selected = episodes == AnimeSyncChoice.REMOTE,
                    onClick = { episodes = AnimeSyncChoice.REMOTE }, enabled = enabled)
            }
        }
        AnimeSelection("合并正片已完成集", "保留两边已完成的集数，共 " +
            (review.localNumbers + review.remoteNumbers).size + " 集；这不会决定追番状态。",
            selected = episodes == AnimeSyncChoice.MERGE,
            onClick = { episodes = AnimeSyncChoice.MERGE }, enabled = enabled)
        MotionTextButton(onClick = { expandedNumbers = !expandedNumbers }, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(if (expandedNumbers) "收起集号" else "查看具体集号")
        }
        if (expandedNumbers) {
            Text("本机：" + animeNumbers(review.localNumbers), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            Text("远端：" + animeNumbers(review.remoteNumbers), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        val selectedStatus = if (status == AnimeSyncChoice.REMOTE) review.remoteStatus else review.localStatus
        if (status != null && selectedStatus == null) AnimeQuietLabel("所选状态为未追番；应用后保留本机观看记录。")
        val canApply = enabled && status != null && episodes != null
        if (status != null && episodes != null) Text("将采用" +
            (if (status == AnimeSyncChoice.LOCAL) "本机状态" else "远端状态") + "与" +
            when (episodes) { AnimeSyncChoice.LOCAL -> "本机正片已完成集"
                AnimeSyncChoice.REMOTE -> "远端正片已完成集"; else -> "两边合并的正片已完成集" } + "。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        MotionButton(onClick = { status?.let { s -> episodes?.let { e -> onApply(s, e, false) } } },
            enabled = canApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用到本机") }
        MotionTextButton(onClick = { status?.let { s -> episodes?.let { e -> onApply(s, e, true) } } },
            enabled = canApply, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text("应用并回写 " + animeProviderName(review.providerId))
        }
        MotionTextButton(onClick = onRecheck, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("重新核对这部番剧")
        }
        AnimeQuietLabel("撤销只恢复本机的上一步；已回写远端的内容需重新核对后再调整。")
    }
}

private fun animeNumbers(numbers: Set<Double>): String =
    if (numbers.isEmpty()) "没有已完成集" else numbers.sorted().joinToString("、", transform = ::animeEpisodeNumber)

@Composable
internal fun AnimeResumePanel(choice: AnimePlaybackChoice?, backdrop: HazeState,
    onChoose: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var retained by remember { mutableStateOf<AnimePlaybackChoice?>(null) }
    SideEffect { if (choice != null) retained = choice }
    MotionDialog(choice != null, onDismissRequest = onDismiss, maximumWidth = 480.dp,
        onClosed = { retained = null }) {
        val shown = choice ?: retained
        if (shown != null) LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("切换版本，确认续看位置", style = MaterialTheme.typography.titleMedium)
                Text(animeEpisodeLabel(shown.episode) + " · " + shown.option.episode.title,
                    style = MaterialTheme.typography.bodyMedium)
                AnimeQuietLabel("上次在 " + shown.previousSourceId + " 看到 " + animeTime(shown.previousPositionMs) +
                    "；这次使用 " + shown.option.sourceName + "。")
                Text("不同剪辑、片头或合并集可能让时间位置偏移。你可以沿用时间，也可以从头播放。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MotionButton(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("沿用 " + animeTime(shown.previousPositionMs))
                }
                MotionTextButton(onClick = { onChoose(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text("从头播放")
                }
                MotionTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("取消切源") }
            }
        }
    }
}
