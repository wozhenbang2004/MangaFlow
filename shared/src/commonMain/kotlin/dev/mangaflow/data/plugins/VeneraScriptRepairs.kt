package dev.mangaflow.data.plugins

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

/** Narrow, reviewable upstream fixes; new upstream versions are never patched by a broad regex. */
internal fun repairVeneraScript(code: String, metadata: JSONObject): Pair<String, JSONArray> {
    val repairs = JSONArray()
    var result = code
    if (metadata.optString("key") == "nhentai" && metadata.optString("version") == "1.1.0") {
        val original = "let currentPage = page || 1;"
        if (result.split(original).size == 2 && result.contains("type: \"mixed\"")) {
            result = result.replace(original, "let currentPage = Number(page ?? 0) + 1;")
            repairs.put(JSONObject().put("id", "nhentai-1.1.0-mixed-page-index")
                .put("reason", "The ComicSource mixed loader receives a zero-based index; convert it once for the native catalogue cursor."))
        }
    }
    if (metadata.optString("key") == "jm" && metadata.optString("version") == "1.4.0") {
        val original = "else if (epId > 421926)"
        if (result.split(original).size == 2 && result.contains("fillImageRangeAt")) {
            result = result.replace(original, "else if (epId >= 421926)")
            repairs.put(JSONObject().put("id", "jm-1.4.0-segment-boundary")
                .put("reason", "Align the exact 421926 strip-count boundary with the pinned JM image decoder; retain the actual script URL and source identity."))
        }
    }
    if (metadata.optString("key") == "jm" && metadata.optString("version") == "1.4.0") {
        val signature = "addOrDelFavorite: async (comicId, folderId, isAdding, favoriteId) => {"
        val start = result.indexOf(signature)
        val end = if (start >= 0) result.indexOf("\n        },", start) else -1
        if (start >= 0 && end > start && result.substring(start, end).contains("if (isAdding)")) {
            val replacement = """
                addOrDelFavorite: async (comicId, folderId, isAdding, favoriteId) => {
                    const current = await this.comic.loadInfo(comicId)
                    if (typeof current.isFavorite !== 'boolean') throw new Error('Missing favorite state')
                    if (current.isFavorite !== isAdding) await this.post(`${'$'}{this.baseUrl}/favorite`, `aid=${'$'}{encodeURIComponent(comicId)}`)
                    if (isAdding && folderId != null && String(folderId) !== '0') {
                        const moved = JSON.parse(await this.post(`${'$'}{this.baseUrl}/favorite_folder`, `type=move&folder_id=${'$'}{encodeURIComponent(folderId)}&aid=${'$'}{encodeURIComponent(comicId)}`))
                        if (String(moved.status).toLowerCase() !== 'ok') throw new Error('Favorite folder move failed')
                    }
                    const confirmed = await this.comic.loadInfo(comicId)
                    if (confirmed.isFavorite !== isAdding) throw new Error('Favorite change was not confirmed')
                    return 'ok'
            """.trimIndent()
            result = result.replaceRange(start, end, replacement)
            result = result.replace("isFavorite: data.is_favorite ?? false,", "isFavorite: typeof data.is_favorite === 'boolean' ? data.is_favorite : null,")
            repairs.put(JSONObject().put("id", "jm-1.4.0-idempotent-favorites")
                .put("reason", "Read actual membership before the toggle, move folders independently, and confirm the requested membership."))
        }
    }
    if (metadata.optString("key") == "jm" && metadata.optString("version") == "1.4.0") {
        val original = "if (json.uid) {\n                this.saveData(\"uid\", json.uid)\n            }\n            return \"ok\""
        if (result.split(original).size == 2) {
            result = result.replace(original, "if (!json.uid) throw new Error('Source did not confirm login')\n            this.saveData(\"uid\", json.uid)\n            return \"ok\"")
            repairs.put(JSONObject().put("id", "jm-1.4.0-confirm-login")
                .put("reason", "An HTTP-success response must contain the real account id before a source session is considered signed in."))
        }
    }
    if (metadata.optString("key") == "wnacg" && metadata.optString("version") == "1.0.6") {
        val original = "cover = 'https:' + cover\n            cover = cover.substring(0, 6) + cover.substring(8)"
        if (result.split(original).size == 2) {
            result = result.replace(original, "cover = new URL(cover, this.baseUrl).href")
            repairs.put(JSONObject().put("id", "wnacg-1.0.6-cover-url")
                .put("reason", "Resolve the real protocol-relative cover instead of removing its HTTPS slashes."))
        }
    }
    return result to repairs
}
