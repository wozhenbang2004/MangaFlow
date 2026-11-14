package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.data.sources.PicacgBodySource
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The existing source-account form now belongs to the shared account destination. */
@Composable
internal fun ContentProfileAccountScreen(
    picacg: PicacgBodySource,
    hasAccountSource: Boolean,
    active: Boolean,
    onSources: () -> Unit,
    onSessionChange: () -> Unit,
    contentTopPadding: Dp = 0.dp,
) {
    val colors = MaterialTheme.colorScheme
    val signedIn by picacg.signedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var loginUser by remember { mutableStateOf("") }
    var loginPass by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) { if (!active) loginPass = "" }
    Column(Modifier.fillMaxSize().padding(top = contentTopPadding)
        .verticalScroll(rememberScrollState()).imePadding()
        .padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (!hasAccountSource) {
            EmptyMessage("当前没有可设置的来源账号", "可在分区来源中管理已有来源。",
                "管理来源", onSources)
        } else {
            if (signedIn) Row(Modifier.fillMaxWidth().padding(horizontal = 0.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("哔咔账号已登录", Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant)
                MotionTextButton(onClick = { scope.launch { picacg.logout(); onSessionChange() } }) {
                    Text("退出", fontSize = 11.sp)
                }
            } else Column(Modifier.fillMaxWidth().padding(vertical = 7.dp),
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
                                loginPass = ""; onSessionChange()
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
    }
}
