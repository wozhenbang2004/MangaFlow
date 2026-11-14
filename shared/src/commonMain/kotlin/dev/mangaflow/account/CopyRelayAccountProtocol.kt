@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.account

import dev.mangaflow.net.FormBody
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.Request
import dev.mangaflow.platform.secureRandomBytes
import kotlin.io.encoding.Base64

/** Relay's web-login fields on the current official site; no host fallback or request replay. */
internal object CopyRelayAccountProtocol {
    // The official login bundle builds its API from document.location.host.
    // Keep the endpoint and browser-origin headers on the same current site.
    private const val LOGIN_ORIGIN = "https://mangacopy.com"
    val loginUrl = "$LOGIN_ORIGIN/api/kb/web/login".toHttpUrl()
    val profileUrl = "https://api.copy202601.com/api/v3/member/info".toHttpUrl()

    class Submission(val request: Request, val passwordField: String)

    fun loginRequest(username: String, password: String): Submission {
        val salt = freshSalt()
        val passwordField = Base64.Default.encode("$password-$salt".encodeToByteArray())
        val body = FormBody.Builder().add("username", username).add("password", passwordField)
            .add("salt", salt).add("platform", "2").add("version", "2025.12.10")
            .add("source", "freeSite").build()
        val request = Request.Builder().url(loginUrl)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("platform", "2")
            .header("Origin", LOGIN_ORIGIN)
            .header("Referer", "$LOGIN_ORIGIN/web/login/loginByAccount?url=person%2Fhome")
            .header("sec-ch-ua", "\"Microsoft Edge\";v=\"155\", \"Chromium\";v=\"155\", \"Not(A:Brand\";v=\"24\"")
            .header("sec-ch-ua-mobile", "?0").header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-site", "same-origin").header("sec-fetch-mode", "cors")
            .header("sec-fetch-dest", "empty").header("Cookie", "webp=1")
            .post(body).build()
        return Submission(request, passwordField)
    }

    fun memberRequest(url: HttpUrl, token: String, form: FormBody? = null): Request {
        require((form == null && url == profileUrl) ||
            CopyAppAccountProtocol.allowed(url, if (form == null) "GET" else "POST")) {
            "该地址不属于个人账号接口。"
        }
        val builder = Request.Builder().url(url)
            .header("User-Agent", "COPY/3.0.9").header("Accept", "application/json")
            .header("source", "copyApp").header("platform", "3")
            .header("version", "3.0.9").header("webp", "1")
            .header("Authorization", "Token $token")
        if (form != null) builder.post(form)
        return builder.build()
    }

    private fun freshSalt(): String {
        while (true) {
            val bytes = secureRandomBytes(3)
            val value = ((bytes[0].toInt() and 0xff) shl 16) or
                ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
            // Rejection sampling keeps all 900,000 six-digit salts equally likely.
            if (value < 16_200_000) return (100_000 + value % 900_000).toString()
        }
    }
}
