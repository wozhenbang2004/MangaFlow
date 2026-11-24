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
import androidx.compose.ui.semantics.heading
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
        val detail = state.detail
        val reviewBlock = detail?.let { trackingReviewBlock(it, state, provider) }
        AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (detail == null) "追番账号" else "追番同步", Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭追番账号") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        MotionTabs(providerIndex, listOf("Bangumi", "AniList"), onSelect = {
                            providerIndex = it; requestedReview = false
                        }, modifier = Modifier.fillMaxWidth().motionInputGate(!state.syncing), controlHeight = 44.dp)
                    }
                    item {
                        Text("同步追番状态与看完的正片。看到第几分钟保存在这台设备。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (reviewBlock != null && account == null) item {
                        AnimeSectionTitle(reviewBlock.first, reviewBlock.second)
                    }
                    if (account == null) {
                        item {
                            AnimeSectionTitle("连接 $accountName", if (detail == null)
                                "连接后，可从支持的番剧详情核对进度" else null)
                        }
                        item {
                            AnimeTextField(token, { token = it }, accountName + " 访问令牌", secret = true,
                                enabled = !state.syncing)
                        }
                        item {
                            AnimeQuietLabel("在 $accountName 生成访问令牌后，粘贴到这里。")
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
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f))
                                    .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(Icons.Outlined.AccountCircle, null, Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(account.displayName.ifBlank { account.ownerId }, style = MaterialTheme.typography.titleSmall)
                                    AnimeQuietLabel(accountName + " · 已连接")
                                }
                                MotionTextButton(onClick = { controller.disconnectTracking(provider) },
                                    enabled = !state.syncing, modifier = Modifier.heightIn(min = 44.dp)) { Text("断开") }
                            }
                        }
                        if (detail == null) item {
                            AnimeSectionTitle("同步在番剧详情中进行", "打开一部番剧，再选择核对进度。")
                        } else if (reviewBlock != null) item {
                            AnimeSectionTitle(reviewBlock.first, reviewBlock.second)
                        } else if (state.detailLoading || detail.episodes.isEmpty()) item {
                            AnimeSectionTitle(if (state.detailLoading) "正在载入剧集目录" else "还没有可核对的剧集",
                                detail.subject.title)
                        } else {
                            item { AnimeSectionTitle("核对这部番剧", detail.subject.title) }
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
                                }, enabled = !state.syncing,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text(if (state.syncing) "正在读取远端进度" else "查看进度差异")
                                }
                            }
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

/** Show only deterministic limitations; the controller still admits every real review. */
private fun trackingReviewBlock(detail: AnimeDetail, state: AnimeScreenState, provider: String): Pair<String, String>? {
    if (state.libraryReadUnavailable)
        return "本机进度暂不可读取" to "本地文件仍保留。恢复记录后再核对。"
    if (detail.subject.externalIds["native-source"] != null)
        return "这部番的进度仅保存在本机" to "当前是片源独有条目，还不能同追番平台核对。"
    if (detail.subject.canonicalDoubanId() == null) return null
    if (provider == "anime-anilist")
        return "这部番暂不支持 AniList 核对" to "豆瓣条目可以使用已确认同季资料的 Bangumi 记录。"
    val saved = state.library.firstOrNull { it.subject.id == detail.subject.id }
    val reference = saved?.nativeMetadata ?: detail.metadataSubject.takeUnless { saved?.metadataAutoMatchDisabled == true }
    return if (reference?.canonicalBangumiId() == null)
        "先确认同季的 Bangumi 资料" to "返回番剧详情，核对 Bangumi 的作品与季度后再同步。"
    else null
}

@Composable
internal fun AnimeProgressReviewPanel(visible: Boolean, state: AnimeScreenState, controller: AnimeController,
    backdrop: HazeState, onDismiss: () -> Unit, onConnect: () -> Unit,
    onRecheck: (AnimeTrackingReview) -> Unit) {
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 760.dp) {
        AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("核对追番进度", style = MaterialTheme.typography.titleLarge)
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
                        Text("选择要保留的追番状态和已看集数，然后应用到本机或同时同步到远端。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.syncing) item { AnimeQuietLabel("正在核对所选内容，请稍候。") }
                    if (state.reviews.isEmpty() && !state.syncing) item {
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
        if (shown != null) AnimePanelSurface(backdrop) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val position = animeTime(shown.previousPositionMs)
                Text("从哪里继续？", style = MaterialTheme.typography.titleLarge)
                Text(animeEpisodeLabel(shown.episode), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnimeQuietLabel("上次看到 $position · ${animeSourceName(shown.previousSourceId)}")
                    Text("切换至 ${shown.option.sourceName}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("不同线路的片头或剪辑可能有差异，原时间点可能偏移。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MotionButton(onClick = { onChoose(true) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("从 $position 继续")
                }
                MotionTextButton(onClick = { onChoose(false) }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text("从头播放")
                }
                MotionTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("取消") }
            }
        }
    }
}
