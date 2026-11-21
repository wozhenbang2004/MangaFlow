package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Protocol

internal actual fun platformHttpClient(http1Only: Boolean, route: DownloadRouteSnapshot?, observeRelayTraffic: Boolean,
    publicMediaOnly: Boolean, configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            if (http1Only) protocols(listOf(Protocol.HTTP_1_1))
            applyDownloadRoute(route, observeRelayTraffic, publicMediaOnly)
            if (publicMediaOnly) applyPublicMediaNetwork(route)
        }
    }
    configure(this)
}

// Preserve the former Android network-security domain exceptions for ordinary
// clients. Script transports additionally enforce their exact endpoint grants.
private val EXISTING_SOURCE_HTTP_HOSTS = setOf(
    "app2.dsx.ac", "91.110.236.125", "23.224.111.70", "198.16.32.138",
    "23.224.53.35", "118.184.173.5", "frontend-static.kurte.cn",
)

internal fun isDefaultCleartextAllowed(host: String): Boolean = host in EXISTING_SOURCE_HTTP_HOSTS
