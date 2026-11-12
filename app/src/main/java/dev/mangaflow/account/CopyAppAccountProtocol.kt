package dev.mangaflow.account

import dev.mangaflow.data.CopyAnonymousIdentity
import java.security.SecureRandom
import java.util.Base64
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** App account wire protocol. Public requests never call this credential-bearing builder. */
internal class CopyAppAccountProtocol(private val identity: CopyAnonymousIdentity) {
    fun loginRequest(username: String, password: String): Request {
        val salt = (SecureRandom().nextInt(9000) + 1000).toString()
        val salted = "$password-$salt".toByteArray(Charsets.UTF_8)
        val encoded = try { Base64.getEncoder().encodeToString(salted) } finally { salted.fill(0) }
        val form = FormBody.Builder().add("username", username).add("password", encoded).add("salt", salt).build()
        return request(loginUrl, token = null, form = form)
    }

    fun memberRequest(url: HttpUrl, token: String, form: FormBody? = null): Request {
        require(allowed(url, if (form == null) "GET" else "POST")) { "该地址不属于个人账号接口。" }
        return request(url, token, form)
    }

    private fun request(url: HttpUrl, token: String?, form: FormBody?): Request {
        val builder = Request.Builder().url(url)
        identity.headers().forEach { (name, value) -> builder.header(name, value) }
        builder.header("Authorization", token?.let { "Token $it" } ?: "Token")
        if (form != null) {
            val common = identity.accountFormFields()
            val body = FormBody.Builder()
            for (index in 0 until form.size) {
                val name = form.name(index)
                if (name !in common && name != "authorization") body.add(name, form.value(index))
            }
            common.forEach { (name, value) -> body.add(name, value) }
            body.add("authorization", token?.let { "Token $it" } ?: "Token ")
            builder.post(body.build())
        }
        return builder.build()
    }

    companion object {
        // Fixed upstream App endpoints and form metadata are documented in:
        // https://github.com/rchdliang/CopyManga_Neo/tree/36ec484fb6ee166456f8025917963fcf8e9e2ce8
        // No endpoint fallback or credential replay occurs after a rejected submission.
        val loginUrl = "https://api.copy202602.com/api/v3/login?platform=3".toHttpUrl()
        val profileUrl = "https://api.copy202601.com/api/v3/member/info?platform=3".toHttpUrl()
        private val queryPath = Regex("/api/v3/comic2/[A-Za-z0-9_-]{1,160}/query")
        private val chapterPath = Regex("/api/v3/comic/[A-Za-z0-9_-]{1,160}/chapter2/[0-9a-fA-F-]{36}")

        fun allowed(url: HttpUrl, method: String): Boolean {
            if (url.scheme != "https" || url.host != profileUrl.host || url.port != 443 ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
            if (method == "POST") return url.encodedPath == "/api/v3/member/collect/comic"
            if (method != "GET") return false
            return url == profileUrl || url.encodedPath in setOf(
                "/api/v3/member/collect/comics", "/api/v3/member/browse/comics",
            ) || queryPath.matches(url.encodedPath) || chapterPath.matches(url.encodedPath)
        }
    }
}
