package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

/** No existing reliable default-path observer; do not infer it from installed software. */
actual fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment =
    DownloadNetworkEnvironment(null, null)
