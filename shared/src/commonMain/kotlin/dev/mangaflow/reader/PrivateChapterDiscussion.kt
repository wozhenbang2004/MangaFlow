package dev.mangaflow.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewKind
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.PrivateChapterComment
import dev.mangaflow.storage.PrivateChapterDiscussionStore
import dev.mangaflow.storage.PrivateChapterKey
import dev.mangaflow.storage.PrivateChapterThread
import dev.mangaflow.storage.PrivateDiscussionSpace
import dev.mangaflow.storage.WebDavBackupConfig
import dev.mangaflow.ui.CommunityDiscussionComment
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.QuietLoading
import dev.mangaflow.ui.SourceAccountField
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PrivateChapterDiscussionState(
    private val key: PrivateChapterKey,
    private val store: PrivateChapterDiscussionStore,
    val config: WebDavBackupConfig,
    private val scope: CoroutineScope,
) {
    val canConnect = store.canConnect(config)
    val subject = ReviewSource("mangaflow-webdav", "MangaFlow 私有讨论", ReviewStatus.READY, "",
        subjectId = key.digest, supportsReplies = true)
    var space by mutableStateOf(store.selectedSpace(config))
        private set
    var thread by mutableStateOf<PrivateChapterThread?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf("")
    var parent by mutableStateOf<PrivateChapterComment?>(null)
    var composing by mutableStateOf(false)
    private var pending: PrivateChapterComment? = null

    fun selectSpace(room: String, nickname: String, onSelected: () -> Unit) = operate {
        val selected = store.selectSpace(config, room, nickname)
        space = selected
        thread = null
        draft = ""
        parent = null
        pending = null
        composing = false
        onSelected()
        load(selected)
    }

    fun leaveSpace() = operate {
        store.leaveSpace()
        space = null
        thread = null
        draft = ""
        parent = null
        pending = null
        composing = false
    }

    fun refresh() {
        val selected = space ?: return
        operate { load(selected) }
    }

    fun send() {
        val selected = space ?: return
        val text = draft.trim()
        if (thread?.canPost != true || text.isBlank()) return
        operate {
            val parentId = parent?.id
            val message = pending?.takeIf { it.nickname == selected.nickname && it.text == text && it.parentId == parentId }
                ?: PrivateChapterComment(randomUuid(), selected.nickname, text, wallTimeMillis(), parentId)
            pending = message
            thread = store.append(key, config, selected, message)
            finishPost()
        }
    }

    private suspend fun load(selected: PrivateDiscussionSpace) {
        val current = store.read(key, config, selected)
        thread = current
        if (pending?.let { it in current.rows } == true) finishPost()
    }

    private fun finishPost() {
        pending = null
        draft = ""
        parent = null
        composing = false
    }

    private fun operate(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "讨论暂不可用，请重试。" }
            finally { busy = false }
        }
    }
}

@Composable
internal fun rememberPrivateChapterDiscussionState(key: PrivateChapterKey): PrivateChapterDiscussionState {
    val context = LocalMangaContext.current
    val store = remember(context) { PrivateChapterDiscussionStore(context) }
    val config by store.configuration.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    return remember(key, store, store.connectionKey(config)) { PrivateChapterDiscussionState(key, store, config, scope) }
}

@Composable
internal fun PrivateChapterDiscussionButton(backdrop: HazeState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    LiquidGlassSurface(backdrop, modifier.size(48.dp), cornerRadius = 24.dp, trackPointer = true) {
        MotionIconButton(onClick = onOpen, modifier = Modifier.size(48.dp), tooltip = "本话私有讨论") {
            Icon(Icons.Outlined.Forum, "本话私有讨论", Modifier.size(22.dp))
        }
    }
}

/** Read witnesses and in-flight drafts are owned by the exact reader chapter, independent of panel visibility. */
@Composable
internal fun PrivateChapterDiscussion(chapterTitle: String, sourceName: String, state: PrivateChapterDiscussionState,
    background: Color, foreground: Color, onClose: () -> Unit) {
    val muted = foreground.copy(alpha = .62f)
    val activeSpace = state.space
    var editing by remember(state) { mutableStateOf(false) }
    var room by remember(state, activeSpace) { mutableStateOf(activeSpace?.room.orEmpty()) }
    var nickname by remember(state, activeSpace) { mutableStateOf(activeSpace?.nickname.orEmpty()) }
    val position = rememberLazyListState()
    LaunchedEffect(state) {
        if (state.thread == null) state.refresh()
    }
    MotionBottomSheet(onDismissRequest = onClose, containerColor = background, contentColor = foreground) { dismiss ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).imePadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MangaFlow 私有讨论", style = MaterialTheme.typography.titleMedium, color = foreground)
                    Text(listOfNotNull(chapterTitle, sourceName, activeSpace?.room).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (activeSpace != null && !editing) {
                    MotionIconButton(onClick = state::refresh, enabled = !state.busy, modifier = Modifier.size(44.dp), tooltip = "刷新本话讨论") {
                        Icon(Icons.Outlined.Refresh, "刷新本话讨论", Modifier.size(18.dp), tint = muted)
                    }
                    MotionIconButton(onClick = { editing = true }, enabled = !state.busy,
                        modifier = Modifier.size(44.dp), tooltip = "讨论区设置") {
                        Icon(Icons.Outlined.Settings, "讨论区设置", Modifier.size(18.dp), tint = muted)
                    }
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭讨论") {
                    Icon(Icons.Outlined.Close, "关闭讨论", Modifier.size(20.dp), tint = muted)
                }
            }
            if (!state.canConnect || activeSpace == null || editing) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                    if (!state.canConnect) item {
                        Text("在设置的 WebDAV 阅读备份中保存你有权限使用的 HTTPS 服务器、账号和密码，再连接讨论区。",
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    } else {
                        item {
                            Text("有目录权限、使用同一讨论区名的成员可以看到消息。显示昵称由各成员填写。",
                                style = MaterialTheme.typography.bodySmall, color = muted)
                            Text(state.config.endpoint + " · " + state.config.remotePath.substringBeforeLast('/', "").ifBlank { "/" },
                                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = muted)
                        }
                        item { SourceAccountField("讨论区名", room, !state.busy, secret = false) { room = it.take(64) } }
                        item { SourceAccountField("昵称", nickname, !state.busy, secret = false) { nickname = it.take(32) } }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (activeSpace != null) MotionTextButton(onClick = { editing = false }, enabled = !state.busy) { Text("返回") }
                                if (activeSpace != null) MotionTextButton(onClick = { state.leaveSpace(); editing = false }, enabled = !state.busy) {
                                    Text("断开")
                                }
                                Spacer(Modifier.weight(1f))
                                MotionButton(onClick = { state.selectSpace(room, nickname) { editing = false } },
                                    enabled = !state.busy && room.isNotBlank() && nickname.isNotBlank()) { Text("连接讨论区") }
                            }
                        }
                    }
                    state.error?.let { issue -> item { Text(issue, style = MaterialTheme.typography.bodySmall, color = muted) } }
                    if (state.busy) item { QuietLoading("正在读取本话讨论…") }
                }
            } else {
                val thread = state.thread
                val rows = thread?.rows.orEmpty()
                val replies = remember(rows) { rows.filter { it.parentId != null }.groupBy { it.parentId } }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = position,
                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
                    if (thread == null && state.busy) item("loading") { QuietLoading("正在读取本话讨论…") }
                    if (thread != null && rows.isEmpty()) item("empty") {
                        Text("暂未读到本话消息。", Modifier.padding(vertical = 14.dp),
                            style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    items(rows.filter { it.parentId == null }.asReversed(), key = { it.id }) { row ->
                        val children = replies[row.id].orEmpty()
                        key(children) {
                            CommunityDiscussionComment(state.subject, row.toReview(children.size),
                                loadReplies = { source, review, page ->
                                    ReviewPage(if (source.subjectId == state.subject.subjectId && page == 0 && review.id == row.id)
                                        children.map { it.toReview() } else emptyList())
                                }, foreground = foreground, muted = muted)
                        }
                        if (thread?.canPost == true) MotionTextButton(onClick = {
                            state.parent = row
                            state.composing = true
                        }, modifier = Modifier.padding(start = 36.dp), enabled = !state.busy) { Text("回复") }
                    }
                }
                state.error?.let { issue -> Text(issue, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = muted) }
                if (thread?.canPost == false) Text("本话暂为只读。服务器需提供可核对的记录版本，且讨论需有剩余容量。",
                    Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = muted)
                if (state.composing) {
                    val label = state.parent?.let { "回复 ${it.nickname}" } ?: "本话评论"
                    SourceAccountField(label, state.draft, !state.busy, secret = false, singleLine = false) {
                        state.draft = it.take(PrivateChapterDiscussionStore.MAX_TEXT)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                        MotionTextButton(onClick = { state.composing = false }, enabled = !state.busy) { Text("收起") }
                        MotionButton(onClick = state::send, enabled = thread?.canPost == true && !state.busy && state.draft.isNotBlank()) {
                            Text(if (state.busy) "正在处理…" else "发送")
                        }
                    }
                } else if (thread?.canPost == true) {
                    MotionTextButton(onClick = { state.composing = true }, enabled = !state.busy,
                        modifier = Modifier.align(Alignment.End).padding(bottom = 4.dp)) { Text(if (state.draft.isNotBlank()) "继续编辑" else "写评论") }
                }
            }
        }
    }
}

private fun PrivateChapterComment.toReview(replyCount: Int? = null) = MangaReview(
    id = id, author = nickname, summary = text, url = "", kind = ReviewKind.CHAPTER,
    date = formatInstantMillis(time, "yyyy-MM-dd HH:mm:ss"), replyCount = replyCount,
)
