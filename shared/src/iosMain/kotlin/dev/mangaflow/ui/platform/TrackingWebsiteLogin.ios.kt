package dev.mangaflow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import dev.mangaflow.tracking.MangaTrackingAuthorization

internal actual fun trackingWebsiteLoginSupported(): Boolean = false

@Composable
internal actual fun NativeTrackingWebsiteLogin(
    authorization: MangaTrackingAuthorization,
    onCancel: (String) -> Unit,
    onCredential: (String, String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    val cancel = rememberUpdatedState(onCancel)
    val loading = rememberUpdatedState(onLoading)
    val error = rememberUpdatedState(onError)
    LaunchedEffect(authorization.id) {
        try {
            loading.value(false)
            error.value("此设备暂不支持应用内漫画账号授权，请使用凭据导入。")
        } finally { cancel.value(authorization.id) }
    }
}
