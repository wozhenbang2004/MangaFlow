package dev.mangaflow.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mangaflow.tracking.MangaTrackingAuthorization

/** A disposable official authorization page; only a reviewed token leaves its native host. */
@Composable
internal expect fun NativeTrackingWebsiteLogin(
    authorization: MangaTrackingAuthorization,
    onCancel: (String) -> Unit,
    onCredential: (String, String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)

internal expect fun trackingWebsiteLoginSupported(): Boolean
