package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

/** No existing reliable default-path observer; do not infer it from interface names. */
actual fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment =
    DownloadNetworkEnvironment(null, null)
