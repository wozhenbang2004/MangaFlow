package dev.mangaflow.ui

import dev.mangaflow.platform.*

import dev.mangaflow.ui.platform.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mangaflow.ui.motion.MotionBusyIndicator

/** One account-password entry: the real official form, within the account page. */
@Composable
internal fun WebsiteAccountLogin(
    modifier: Modifier,
    onBegin: () -> Long,
    onCancel: (Long) -> Unit,
    onCredential: (Long, String) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val begin by rememberUpdatedState(onBegin)
    val cancel by rememberUpdatedState(onCancel)
    val credential by rememberUpdatedState(onCredential)
    var retry by remember { mutableStateOf(0) }
    Box(modifier) {
        if (started) key(retry) {
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            NativeWebsiteLogin(begin, cancel, credential, { loading = it }, { error = it }, Modifier.fillMaxSize())
            if (loading) MotionBusyIndicator(Modifier.align(Alignment.TopCenter).padding(12.dp))
            error?.let { message ->
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }) { Text("重新打开登录") }
                }
            }
        }
    }
}
