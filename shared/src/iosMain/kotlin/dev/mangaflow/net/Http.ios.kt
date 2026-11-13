package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSHTTPCookieAcceptPolicyNever

internal actual fun platformHttpClient(http1Only: Boolean, configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever
            HTTPCookieStorage = null
            URLCache = null
        }
    }
    configure(this)
}
