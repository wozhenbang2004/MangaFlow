package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Only installed sources with real account capabilities enter this shared destination. */
@Composable
internal fun ContentProfileAccountScreen(
    repository: UnifiedMangaRepository,
    sources: List<BodySourceInfo>,
    active: Boolean,
    onSources: () -> Unit,
    onSessionChange: () -> Unit,
    onOpen: (Manga) -> Unit,
    isLocalFavorite: (Manga) -> Boolean,
    onToggleLocalFavorite: (Manga) -> Unit,
    onInspect: (String) -> Unit,
    contentTopPadding: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val currentActive by rememberUpdatedState(active)
    var accountSources by remember { mutableStateOf<List<BodySourceInfo>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    val source = accountSources.firstOrNull { it.id == selectedId }
    var account by remember(selectedId) { mutableStateOf(SourceAccountState()) }
    var capabilities by remember(selectedId) { mutableStateOf(SourceCapabilities(emptySet())) }
    var user by remember(selectedId) { mutableStateOf("") }
    var password by remember(selectedId) { mutableStateOf("") }
    var cookieValues by remember(selectedId) { mutableStateOf<List<String>>(emptyList()) }
    var cookieMode by remember(selectedId) { mutableStateOf(false) }
    var busy by remember(selectedId) { mutableStateOf(false) }
    var message by remember(selectedId) { mutableStateOf<String?>(null) }
    var folders by remember(selectedId) { mutableStateOf<List<SourceFavoriteFolder>>(emptyList()) }
    var folderId by remember(selectedId) { mutableStateOf<String?>(null) }
    var books by remember(selectedId) { mutableStateOf<List<Manga>>(emptyList()) }
    var page by remember(selectedId) { mutableIntStateOf(0) }
    var more by remember(selectedId) { mutableStateOf(false) }
    var operation by remember(selectedId) { mutableStateOf<Job?>(null) }
    DisposableEffect(selectedId) { onDispose { operation?.cancel() } }
    LaunchedEffect(sources, active) {
        if (!active) { password = ""; cookieValues = emptyList(); operation?.cancel(); return@LaunchedEffect }
        val capable = mutableListOf<BodySourceInfo>()
        for (entry in sources) {
            try { if (repository.sourceCapabilities(entry.id).supports(SourceFeature.ACCOUNT)) capable.add(entry) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* A failed source does not hide the other accounts. */ }
        }
        accountSources = capable
        if (capable.none { it.id == selectedId }) selectedId = capable.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(selectedId, active) {
        if (!active || selectedId.isEmpty()) return@LaunchedEffect
        try { capabilities = repository.sourceCapabilities(selectedId); account = repository.sourceAccountState(selectedId) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = "账号状态未能读取，请重试。" }
    }
    fun operate(block: suspend () -> Unit) {
        if (busy || !active || source == null) return
        busy = true; message = null
        val owner = selectedId
        operation = scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                val failedMessage = repository.sourceHealth.value[owner]?.message?.takeIf { it.isNotBlank() }
                    ?: "来源操作未完成，请重试。"
                try {
                    val latest = repository.sourceAccountState(owner)
                    if (selectedId == owner && currentActive) {
                        val expired = account.signedIn && !latest.signedIn
                        account = latest
                        if (!latest.signedIn) {
                            books = emptyList(); folders = emptyList(); folderId = null; page = 0; more = false
                        }
                        if (expired) onSessionChange()
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Keep the previous state when its refresh also fails. */ }
                if (selectedId == owner && currentActive) message = failedMessage
            }
            finally { if (selectedId == owner) { busy = false; password = ""; cookieValues = emptyList() } }
        }
    }
    suspend fun loadFavorites(folder: String, next: Int) {
        val result = repository.sourceFavorites(selectedId, folder, next)
        books = (if (next == 0) result.items else books + result.items).distinctBy { it.id }
        folderId = folder; page = next; more = result.hasMore
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = contentTopPadding).imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accountSources.isEmpty()) item {
            EmptyMessage("当前没有可设置的来源账号", "来源支持登录时会显示在这里。", "管理来源", onSources)
        }
        if (accountSources.size > 1) item {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                accountSources.forEach { entry -> MotionTextButton(onClick = { if (!busy) selectedId = entry.id }, enabled = !busy) {
                    Text(entry.name, fontSize = 12.sp, color = if (selectedId == entry.id) colors.onSurface else colors.onSurfaceVariant)
                } }
            }
        }
        if (source != null) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (account.signedIn) account.displayName.ifBlank { source.name + " · 已登录" } else source.name,
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    MotionTextButton(onClick = { onInspect(source.id) }, enabled = !busy) { Text("来源设置", fontSize = 11.sp) }
                    if (account.signedIn) MotionTextButton(onClick = { operate {
                        repository.sourceLogout(source.id); account = repository.sourceAccountState(source.id)
                        books = emptyList(); folders = emptyList(); folderId = null; onSessionChange()
                    } }, enabled = !busy) { Text("退出", fontSize = 11.sp) }
                }
            }
            if (!account.signedIn) item {
                val useCookies = capabilities.cookieFields.isNotEmpty() && (!capabilities.passwordLogin || cookieMode)
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (capabilities.passwordLogin && capabilities.cookieFields.isNotEmpty()) Row {
                        MotionTextButton(onClick = { cookieMode = false; cookieValues = emptyList() }, enabled = !busy) { Text("账号密码", fontSize = 12.sp) }
                        MotionTextButton(onClick = { cookieMode = true; password = "" }, enabled = !busy) { Text("Cookie", fontSize = 12.sp) }
                    }
                    if (useCookies) {
                        Text("填写此来源的 Cookie，验证成功后保存登录状态。", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        capabilities.cookieFields.forEachIndexed { index, field ->
                            val value = cookieValues.getOrNull(index).orEmpty()
                            BasicTextField(value, { changed ->
                                if (changed.length <= 8_192 && changed.none { it == '\r' || it == '\n' || it == ';' })
                                    cookieValues = capabilities.cookieFields.indices.map { if (it == index) changed else cookieValues.getOrNull(it).orEmpty() }
                            }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
                                .background(colors.onSurface.copy(alpha = .045f)).padding(12.dp), singleLine = true, enabled = !busy,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                                decorationBox = { inner -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(field, fontSize = 11.sp, color = colors.onSurfaceVariant); inner()
                                } })
                        }
                        MotionButton(onClick = {
                            val submitted = capabilities.cookieFields.indices.map { cookieValues.getOrNull(it).orEmpty() }
                            cookieValues = emptyList()
                            operate { repository.sourceLoginCookies(source.id, submitted)
                                account = repository.sourceAccountState(source.id); onSessionChange() }
                        }, enabled = !busy && cookieValues.any(String::isNotBlank)) {
                            Text(if (busy) "验证中…" else "验证并登录", fontSize = 12.sp)
                        }
                    } else if (capabilities.passwordLogin) {
                    Text("使用来源账号登录，密码只用于这一次提交。", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    BasicTextField(user, { if (it.length <= 160) user = it }, modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp)).background(colors.onSurface.copy(alpha = .045f)).padding(12.dp),
                        singleLine = true, enabled = !busy, textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                        decorationBox = { inner -> Box { if (user.isEmpty()) Text("账号", fontSize = 12.sp, color = colors.onSurfaceVariant); inner() } })
                    BasicTextField(password, { if (it.length <= 512) password = it }, modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp)).background(colors.onSurface.copy(alpha = .045f)).padding(12.dp),
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                        decorationBox = { inner -> Box { if (password.isEmpty()) Text("密码", fontSize = 12.sp, color = colors.onSurfaceVariant); inner() } })
                    MotionButton(onClick = { val submittedUser = user; val submittedPassword = password; password = ""
                        operate { repository.sourceLogin(source.id, submittedUser, submittedPassword)
                            account = repository.sourceAccountState(source.id); onSessionChange() }
                    }, enabled = !busy && user.isNotBlank() && password.isNotBlank()) { Text(if (busy) "登录中…" else "登录", fontSize = 12.sp) }
                    }
                }
            }
            if (account.signedIn && capabilities.supports(SourceFeature.FAVORITES)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("站点收藏", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        MotionTextButton(onClick = { operate {
                            folders = repository.sourceFavoriteFolders(source.id)
                            val selected = folders.firstOrNull { it.id == folderId } ?: folders.firstOrNull()
                            if (selected != null) loadFavorites(selected.id, 0)
                        } }, enabled = !busy) { Text(if (folderId == null) "打开" else "刷新", fontSize = 12.sp) }
                    }
                    Text("与本机书架分别保存；移出站点收藏会保留本机记录。", fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                if (folders.size > 1) item {
                    Row(Modifier.horizontalScroll(rememberScrollState())) { folders.forEach { folder ->
                        MotionTextButton(onClick = { operate { loadFavorites(folder.id, 0) } }, enabled = !busy) {
                            Text(folder.title, fontSize = 12.sp, color = if (folder.id == folderId) colors.onSurface else colors.onSurfaceVariant)
                        }
                    } }
                }
                items(books, key = { it.id }) { manga ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MotionTextButton(onClick = { onOpen(manga) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 5.dp)) {
                            MangaCover(manga, repository, Modifier.width(38.dp).height(54.dp).clip(RoundedCornerShape(6.dp)))
                            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(manga.title, fontSize = 12.sp, maxLines = 2)
                                Text(source.name, fontSize = 10.sp, color = colors.onSurfaceVariant)
                            }
                        }
                        MotionIconButton(onClick = { onToggleLocalFavorite(manga) }, tooltip = if (isLocalFavorite(manga)) "移出本机书架" else "加入本机书架",
                            modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.BookmarkBorder, "本机书架", Modifier.size(18.dp),
                                tint = if (isLocalFavorite(manga)) colors.onSurface else colors.onSurfaceVariant) }
                        MotionIconButton(onClick = { val folder = folderId ?: return@MotionIconButton
                            operate { repository.sourceSetFavorite(source.id, manga.id, folder, false); books = books.filterNot { it.id == manga.id } }
                        }, enabled = !busy, tooltip = "移出站点收藏", modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, "移出站点收藏", Modifier.size(18.dp)) }
                    }
                }
                if (folderId != null && books.isEmpty() && !busy && message == null) item { Text("这个收藏夹暂无作品", fontSize = 12.sp, color = colors.onSurfaceVariant) }
                if (more) item { MotionTextButton(onClick = { val folder = folderId ?: return@MotionTextButton
                    operate { loadFavorites(folder, page + 1) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("更多收藏", fontSize = 12.sp) } }
            }
        }
        message?.let { item { Text(it, fontSize = 11.sp, color = colors.error) } }
    }
}
