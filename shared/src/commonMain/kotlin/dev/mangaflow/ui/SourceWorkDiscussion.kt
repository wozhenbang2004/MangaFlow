package dev.mangaflow.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import androidx.compose.ui.platform.LocalDensity
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.data.SourceFailureKind
import dev.mangaflow.data.SourceStage
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sourceFailureSummary
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.SourceComment
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class SourceCommentWork(val manga: Manga, val source: BodySourceInfo, val pagedReplies: Boolean)

private class WorkDiscussionLane {
    val position = LazyListState()
    var session: String? = null
    var rows by mutableStateOf<List<SourceComment>>(emptyList())
    var nextPage by mutableStateOf<Int?>(null)
    var initialized by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var failure by mutableStateOf<Pair<Int, String>?>(null)
    var needsAccount by mutableStateOf(false)
    var request: Job? = null

    fun cancel() {
        request?.cancel()
        request = null
        loading = false
    }

    fun reset() {
        cancel()
        rows = emptyList()
        nextPage = null
        initialized = false
        failure = null
        needsAccount = false
    }
}

@Composable
internal fun SourceCapabilityComments(
    repository: UnifiedMangaRepository,
    works: List<SourceCommentWork>,
    modifier: Modifier,
    bottomPadding: Dp,
    active: Boolean,
    accountRevision: Int,
    onAccount: (String) -> Unit,
) {
    if (works.isEmpty()) return
    var selected by rememberSaveable(works.first().manga.id) { mutableStateOf(works.first().manga.id) }
    val work = works.firstOrNull { it.manga.id == selected } ?: works.first()
    val manga = work.manga
    val provider = work.source
    val identity = Triple(manga.id, provider.revision, accountRevision)
    val lanes = remember(accountRevision) { mutableMapOf<Triple<String, String, Int>, WorkDiscussionLane>() }
    val lane = remember(lanes, identity) { lanes.getOrPut(identity, ::WorkDiscussionLane) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    fun load(page: Int) {
        if (!active || lane.loading) return
        lane.loading = true
        lane.failure = null
        lane.needsAccount = false
        lane.request = scope.launch {
            try {
                val owner = repository.sourceProbeIdentity(provider.id)
                if (lane.session != owner) {
                    lane.session = owner
                    lane.rows = emptyList()
                    lane.nextPage = null
                    lane.initialized = false
                    if (page != 0) {
                        lane.failure = 0 to "账号已变化，请重新读取作品评论。"
                        return@launch
                    }
                }
                val result = repository.sourceComments(provider.id, manga.id, page)
                currentCoroutineContext().ensureActive()
                if (repository.sourceProbeIdentity(provider.id) != owner || lane.session != owner) {
                    lane.rows = emptyList()
                    lane.nextPage = null
                    lane.initialized = false
                    lane.failure = 0 to "账号已变化，请重新读取作品评论。"
                    return@launch
                }
                lane.rows = (if (page == 0) result.items else lane.rows + result.items)
                    .filter { it.id.isNotBlank() }.distinctBy { it.id }
                lane.initialized = true
                lane.nextPage = (page + 1).takeIf { result.hasMore }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                val summary = sourceFailureSummary(SourceStage.COMMENTS, failure)
                lane.failure = page to summary.message
                lane.needsAccount = summary.kind == SourceFailureKind.LOGIN
            } finally {
                if (currentCoroutineContext().isActive) lane.loading = false
            }
        }
    }

    DisposableEffect(lane, active) { onDispose { lane.cancel() } }
    LaunchedEffect(lane, active) {
        if (!active) return@LaunchedEffect
        val session = try { repository.sourceProbeIdentity(provider.id) }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            lane.reset()
            lane.failure = 0 to "评论来源暂不可用，请重试。"
            return@LaunchedEffect
        }
        if (lane.session != session) {
            lane.reset()
            lane.session = session
        }
        if (!lane.initialized && lane.failure == null) load(0)
    }
    Column(modifier.motionInputGate(active)) {
        val desktop = platformKind == PlatformKind.DESKTOP
        if (works.size > 1) BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            val labels = works.map { candidate ->
                if (works.count { it.source.id == candidate.source.id } == 1) candidate.source.name
                else "${candidate.source.name} · ${candidate.manga.title}"
            }
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val width = maxWidth.coerceAtLeast(92.dp * fontScale * labels.size)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                MotionTabs(selected = works.indexOf(work), labels = labels,
                    onSelect = { index -> if (active) selected = works[index].manga.id },
                    modifier = Modifier.width(width), controlHeight = 44.dp, compact = true,
                    treatment = MotionTabTreatment.Underline, tooltips = labels)
            }
        }
        val source = remember(work, lane.session) {
            ReviewSource(provider.id, provider.name, ReviewStatus.READY, "", subjectId = manga.id,
                supportsReplies = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = lane.position, userScrollEnabled = active,
                contentPadding = PaddingValues(start = 20.dp, end = if (desktop) 28.dp else 20.dp, bottom = bottomPadding + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "work-comments-scope") {
                    Text(if (works.size == 1) "${provider.name} · 作品评论" else "作品评论",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant)
                }
                if (lane.rows.isEmpty() && (lane.loading || !lane.initialized && lane.failure == null))
                    item(key = "work-comments-loading") { QuietLoading("正在读取作品评论…") }
                if (lane.initialized && lane.rows.isEmpty() && !lane.loading && lane.failure == null)
                    item(key = "work-comments-empty") { Text("暂无作品评论", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant) }
                items(lane.rows, key = { "comment:${it.id}" }) { row ->
                    key(identity, lane.session) {
                        CommunityDiscussionComment(source, row.toReview(work.pagedReplies), loadReplies = { _, review, page ->
                            val owner = lane.session
                            if (owner == null || repository.sourceProbeIdentity(provider.id) != owner || review.id != row.id) {
                                ReviewPage(emptyList(), error = "站点账号已变化，请重新打开评论。")
                            } else if (row.inlineReplies.isNotEmpty()) {
                                ReviewPage(if (page == 0) row.inlineReplies.filter { it.id.isNotBlank() }.map { it.toReview() } else emptyList())
                            } else if (work.pagedReplies) {
                                try {
                                    val result = repository.sourceCommentReplies(provider.id, manga.id, row.id, page)
                                    currentCoroutineContext().ensureActive()
                                    if (repository.sourceProbeIdentity(provider.id) != owner)
                                        ReviewPage(emptyList(), error = "站点账号已变化，请重新打开评论。")
                                    else ReviewPage(result.items.filter { it.id.isNotBlank() }.map { it.toReview() },
                                        nextPage = (page + 1).takeIf { result.hasMore })
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) {
                                    ReviewPage(emptyList(), error = sourceFailureSummary(SourceStage.COMMENTS, error).message)
                                }
                            } else ReviewPage(emptyList())
                        }, foreground = colors.onSurface, muted = colors.onSurfaceVariant, comfortable = true)
                    }
                }
                lane.failure?.let { (failedPage, message) -> item(key = "work-comments-error") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                        MotionTextButton(onClick = { if (lane.needsAccount) onAccount(provider.id) else load(failedPage) },
                            enabled = active && !lane.loading) { Text(if (lane.needsAccount) "登录" else "重试") }
                    }
                } }
                lane.nextPage?.takeIf { lane.failure == null }?.let { page -> item(key = "work-comments-more") {
                    MotionTextButton(onClick = { load(page) }, enabled = active && !lane.loading,
                        modifier = Modifier.fillMaxWidth()) { Text(if (lane.loading) "正在读取…" else "更多评论") }
                } }
            }
            if (desktop && active) DesktopVerticalScrollbar(lane.position, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private fun SourceComment.toReview(pagedReplies: Boolean = false) = MangaReview(
    id = id, author = author.ifBlank { "未署名" }, summary = content, url = "", date = time,
    replyCount = replyCount ?: inlineReplies.size.takeIf { it > 0 }, avatarUrl = avatar,
    upVotes = likes.takeIf { it > 0 }, canLoadReplies = pagedReplies,
)
