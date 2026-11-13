package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Protocol

// OkHttp follows the Windows ProxySelector and the same owner transport policy.
internal actual fun platformHttpClient(http1Only: Boolean, configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            if (http1Only) protocols(listOf(Protocol.HTTP_1_1))
        }
    }
    configure(this)
}
