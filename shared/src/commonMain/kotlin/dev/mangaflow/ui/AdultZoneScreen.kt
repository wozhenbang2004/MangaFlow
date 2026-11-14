package dev.mangaflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.PicacgBodySource
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private enum class AdultPage(val label: String) { DISCOVER("发现"), FAVORITES("收藏"), DOWNLOADS("离线") }

/** Own browse/search state, separate from global discovery and its persistent query cache. */
@Composable
internal fun AdultZoneScreen(
    confirmed: Boolean, onConfirm: () -> Unit, onExit: () -> Unit,
    registry: BodySourceRegistry, plugins: SourcePluginStore, picacg: PicacgBodySource,
    repository: UnifiedMangaRepository, downloads: DownloadStore,
    favorites: List<Manga>, onOpen: (Manga) -> Unit, onOpenOffline: (DownloadEntry) -> Unit,
    onToggleFavorite: (Manga) -> Unit,
    backdrop: HazeState, contentTopPadding: Dp,
) {
    val colors = MaterialTheme.colorScheme
    if (!confirmed) {
        Box(Modifier.fillMaxSize().padding(top = contentTopPadding), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(29.dp), tint = colors.onSurfaceVariant)
                Text("仅限成年人", style = MaterialTheme.typography.titleMedium)
                Text("进入后才加载本专区的漫画封面。仅阅读你有权访问、且在所在地合法的内容。",
                    fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
                MotionButton(onClick = onConfirm) { Text("我已年满 18 周岁", fontSize = 13.sp) }
                MotionTextButton(onClick = onExit) { Text("返回发现", fontSize = 12.sp) }
            }
        }
        return
    }

    val entries by registry.entries.collectAsStateWithLifecycle()
    val installed by plugins.installed.collectAsStateWithLifecycle()
    val signedIn by picacg.signedIn.collectAsStateWithLifecycle()
    val storedDownloads by downloads.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sources = entries.filter { it.adult && it.isInstalled && !it.removed }
    val enabled = sources.filter { it.enabled }
    val adultIds = entries.filter { it.adult }.map { it.source.id }.toSet()
    val candidates = entries.filter { it.source.id.startsWith("js_") && it.isInstalled &&
        !it.adult && !it.removed && installed.any { pkg -> pkg.sourceId == it.source.id } }
    var importing by remember { mutableStateOf(false) }
    var assignExisting by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<String?>(null) }
    var loginUser by remember { mutableStateOf("") }
    var loginPass by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var pageName by rememberSaveable { mutableStateOf(AdultPage.DISCOVER.name) }
    val pageKind = AdultPage.valueOf(pageName)
    var preferred by rememberSaveable { mutableStateOf("") }
    val selected = enabled.firstOrNull { it.source.id == preferred } ?: enabled.firstOrNull()
    var query by rememberSaveable { mutableStateOf("") }
    var appliedQuery by rememberSaveable { mutableStateOf("") }
    var index by remember { mutableIntStateOf(0) }
    var nextIndex by remember { mutableIntStateOf(1) }
    var hasNext by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Manga>>(emptyList()) }
    fun search() {
        val term = query.trim()
        if (term != appliedQuery) { appliedQuery = term; index = 0; items = emptyList() }
        else revision++
    }
    LaunchedEffect(selected?.source?.id, appliedQuery, index, revision, signedIn) {
        val sourceId = selected?.source?.id ?: run {
            items = emptyList(); hasNext = false; loading = false; return@LaunchedEffect
        }
        if (sourceId == "picacg" && !signedIn) {
            items = emptyList(); hasNext = false; loading = false; error = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val result = withTimeout(20_000L) {
                if (appliedQuery.isEmpty()) repository.browseSource(sourceId, index)
                else repository.searchSource(sourceId, appliedQuery, index, forceRefresh = revision > 0)
            }
            items = if (index == 0) result.items else (items + result.items).distinctBy { it.id }
            hasNext = result.hasMore
            nextIndex = result.nextPage?.takeIf { it > index } ?: index + 1
        } catch (failure: TimeoutCancellationException) {
            error = "请求超时，可重试或更换来源。"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "来源暂时无法读取。"
        } finally { loading = false }
    }
    val saved = favorites.filter { it.sourceId in adultIds }
    val offline = storedDownloads.filter { it.manga.sourceId in adultIds }
        .map { it.manga }.distinctBy { it.id }
    val visible = when (pageKind) {
        AdultPage.DISCOVER -> items
        AdultPage.FAVORITES -> saved
        AdultPage.DOWNLOADS -> offline
    }
    val columns = when {
        LocalMangaWindow.current.width < 340.dp -> 2
        LocalMangaWindow.current.width >= 600.dp -> 4
        else -> 3
    }
    Column(Modifier.fillMaxSize().padding(top = contentTopPadding)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("独立浏览 · 不参与全站搜索", Modifier.weight(1f), fontSize = 11.sp,
                color = colors.onSurfaceVariant)
            if (platformKind == PlatformKind.ANDROID) MotionTextButton(onClick = { importing = true }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                Text("导入", fontSize = 12.sp)
            }
            if (candidates.isNotEmpty()) MotionTextButton(onClick = { assignExisting = !assignExisting }) {
                Text(if (assignExisting) "收起" else "已有源", fontSize = 12.sp)
            }
        }
        if (assignExisting) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                candidates.forEach { item ->
                    MotionTextButton(enabled = operation == null, onClick = {
                        operation = item.source.id
                        scope.launch {
                            try { registry.setAdult(item.source.id, true); operationError = null }
                            catch (failure: Exception) { operationError = failure.message ?: "移动来源失败" }
                            finally { operation = null }
                        }
                    }) { Text(item.source.name, fontSize = 12.sp) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AdultPage.entries.forEach { target ->
                MotionTextButton(onClick = { pageName = target.name }) {
                    Text(target.label + when (target) {
                        AdultPage.FAVORITES -> if (saved.isEmpty()) "" else " ${saved.size}"
                        AdultPage.DOWNLOADS -> if (offline.isEmpty()) "" else " ${offline.size}"
                        else -> ""
                    }, fontSize = 13.sp,
                        fontWeight = if (pageKind == target) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (pageKind == target) colors.onSurface else colors.onSurfaceVariant)
                }
            }
        }
        if (pageKind == AdultPage.DISCOVER && sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                sources.forEach { entry ->
                    MotionTextButton(enabled = operation == null, onClick = {
                        if (entry.enabled) { preferred = entry.source.id; index = 0; items = emptyList() }
                        else {
                            operation = entry.source.id
                            scope.launch {
                                try { registry.setEnabled(entry.source.id, true); operationError = null }
                                catch (failure: Exception) { operationError = failure.message ?: "启用来源失败" }
                                finally { operation = null }
                            }
                        }
                    }) {
                        Text(entry.source.name + if (entry.enabled) "" else " · 启用",
                            fontSize = 12.sp,
                            color = if (selected?.source?.id == entry.source.id) colors.onSurface
                            else colors.onSurfaceVariant)
                        if (selected?.source?.id == entry.source.id)
                            Icon(Icons.Outlined.Check, null, Modifier.size(12.dp))
                    }
                }
            }
        }
        if (pageKind == AdultPage.DISCOVER && selected != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)
                .clip(RoundedCornerShape(12.dp)).background(colors.onSurface.copy(alpha = .045f))
                .heightIn(min = 44.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                BasicTextField(query, { if (it.length <= 120) query = it },
                    modifier = Modifier.weight(1f), singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    decorationBox = { inner ->
                        Box {
                            if (query.isBlank()) Text("搜索当前来源", fontSize = 13.sp,
                                color = colors.onSurfaceVariant)
                            inner()
                        }
                    })
                MotionTextButton(onClick = { search() }) { Text("搜索", fontSize = 12.sp) }
            }
        }

        if (pageKind == AdultPage.DISCOVER && selected?.source?.id == "picacg") {
            if (signedIn) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("哔咔账号已登录", Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant)
                MotionTextButton(onClick = { scope.launch { picacg.logout(); items = emptyList() } }) {
                    Text("退出", fontSize = 11.sp)
                }
            } else Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("使用哔咔账号登录后浏览，密码只用于登录请求，不保存在本机。",
                    fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(colors.onSurface.copy(alpha = .045f)).heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(loginUser, { if (it.length <= 160) loginUser = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.onSurface),
                        decorationBox = { inner -> Box {
                            if (loginUser.isBlank()) Text("账号", fontSize = 12.sp, color = colors.onSurfaceVariant)
                            inner()
                        } })
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                    .background(colors.onSurface.copy(alpha = .045f)).heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(loginPass, { if (it.length <= 512) loginPass = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.onSurface),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        decorationBox = { inner -> Box {
                            if (loginPass.isBlank()) Text("密码", fontSize = 12.sp, color = colors.onSurfaceVariant)
                            inner()
                        } })
                }
                loginError?.let { Text(it, fontSize = 11.sp, color = colors.error) }
                MotionButton(enabled = !loggingIn && loginUser.isNotBlank() && loginPass.isNotBlank(),
                    onClick = {
                        loggingIn = true; loginError = null
                        scope.launch {
                            try {
                                picacg.login(loginUser, loginPass)
                                loginPass = ""; index = 0; revision++
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) {
                                loginError = failure.message ?: "登录失败，请检查账号与网络。"
                            } finally { loggingIn = false }
                        }
                    }) {
                    Text(if (loggingIn) "登录中…" else "登录", fontSize = 12.sp)
                }
            }
        }
        operationError?.let { Text(it, Modifier.padding(horizontal = 20.dp),
            fontSize = 11.sp, color = colors.error) }
        if (pageKind == AdultPage.DISCOVER && sources.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("尚未添加成人来源", style = MaterialTheme.typography.titleSmall)
                Text("这里不预装未经授权的站点。可以导入信任的 JS 来源，或者将已有脚本源移入专区。",
                    fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant)
                if (platformKind != PlatformKind.ANDROID) Text("目前脚本源运行仅支持 Android。",
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
                else MotionTextButton(onClick = { importing = true }) { Text("导入来源", fontSize = 12.sp) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(columns = GridCells.Fixed(columns), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(visible, key = { it.id }) { manga ->
                    AdultWorkTile(manga, repository, saved.any { it.id == manga.id },
                        onClick = {
                            if (pageKind == AdultPage.DOWNLOADS) {
                                storedDownloads.firstOrNull { it.manga.id == manga.id && it.status == DownloadStatus.COMPLETED }
                                    ?.let(onOpenOffline) ?: onOpen(manga)
                            } else onOpen(manga)
                        }, onFavorite = { onToggleFavorite(manga) })
                }
                if (pageKind == AdultPage.DISCOVER && loading)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            MotionBusyIndicator(Modifier.size(18.dp))
                        }
                    }
                if (pageKind == AdultPage.DISCOVER && error != null)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error.orEmpty(), fontSize = 11.sp, color = colors.error)
                            MotionTextButton(onClick = { revision++ }) { Text("重试", fontSize = 12.sp) }
                        }
                    }
                if (pageKind == AdultPage.DISCOVER && !loading && error == null && hasNext)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MotionTextButton(onClick = { index = nextIndex }) { Text("加载更多", fontSize = 12.sp) }
                        }
                    }
                if (visible.isEmpty() && !loading && error == null &&
                    (pageKind != AdultPage.DISCOVER || selected != null &&
                        (selected.source.id != "picacg" || signedIn)))
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyMessage(when (pageKind) {
                            AdultPage.DISCOVER -> "暂时没有作品"
                            AdultPage.FAVORITES -> "还没有收藏"
                            AdultPage.DOWNLOADS -> "还没有离线作品"
                        }, when (pageKind) {
                            AdultPage.DISCOVER -> "可切换来源或输入关键词。"
                            AdultPage.FAVORITES -> "长按作品封面可以收藏。"
                            AdultPage.DOWNLOADS -> "从作品详情下载后在此查看。"
                        })
                    }
            }
        }
        Text("来源请求仍由对应网站处理。本专区不会加入普通发现、搜索及自动搜源。",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp),
            fontSize = 10.sp, color = colors.onSurfaceVariant)
    }
    if (importing && platformKind == PlatformKind.ANDROID) SourceImportSheet(
        plugins, registry, backdrop, onDismiss = { importing = false },
        onImported = { operationError = null }, markAdult = true)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdultWorkTile(
    manga: Manga, repository: UnifiedMangaRepository, favorite: Boolean,
    onClick: () -> Unit, onFavorite: () -> Unit,
) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().combinedClickable(
        interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = "打开${manga.title}",
        onLongClickLabel = if (favorite) "取消收藏" else "收藏",
        onClick = onClick, onLongClick = onFavorite).motionPress(interaction)) {
        MangaCover(manga, repository, Modifier.fillMaxWidth().aspectRatio(2f / 3f).mangaCoverCard(11.dp))
        Text(manga.title, Modifier.padding(start = 2.dp, end = 2.dp, top = 7.dp),
            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        if (favorite) Text("已收藏", Modifier.padding(start = 2.dp), fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
