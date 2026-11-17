package dev.mangaflow.anime

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

/** Public metadata only; playback URLs, cookies and tokens never enter this document. */
internal object AnimeDocumentCodec {
    fun subject(value: AnimeSubject): JSONObject = JSONObject().put("id", value.id)
        .put("title", value.title).put("original", value.originalTitle).put("cover", value.coverUrl)
        .put("description", value.description).put("count", value.episodeCount).put("year", value.year)
        .put("tags", JSONArray(value.tags)).put("externalIds", JSONObject(value.externalIds))
        .put("weekday", value.airWeekday).put("rating", value.rating).put("rank", value.rank)

    fun subject(json: JSONObject): AnimeSubject {
        val ids = json.optJSONObject("externalIds") ?: JSONObject()
        val id = json.getString("id")
        val nativeId = id.removePrefix("bangumi:")
        if (id.startsWith("native:")) {
            val source = ids.optString("native-source")
            // Native detail writers retain provider metadata IDs (for example AGE/girigiri).
            // These do not change the native namespace or establish a canonical association.
            require(Regex("anime-[a-z0-9-]+").matches(source) &&
                Regex("native:${Regex.escape(source)}:[a-f0-9]{64}").matches(id)) {
                "原始来源条目身份无效，不能作为标准番剧恢复。"
            }
        } else {
            require(id.startsWith("bangumi:") && nativeId.toIntOrNull()?.let { it > 0 && it.toString() == nativeId } == true) {
                "番剧记录缺少有效的条目身份。"
            }
            require(!ids.has("bangumi") || ids.getString("bangumi") == nativeId) { "番剧外部身份与主条目不一致。" }
            require(!ids.has("native-source")) { "标准番剧不能冒用原始来源身份。" }
        }
        return AnimeSubject(id = id, title = json.getString("title"),
            originalTitle = json.optString("original"), coverUrl = json.optString("cover"),
            description = json.optString("description"),
            episodeCount = json.optInt("count").takeIf { it > 0 },
            year = json.optInt("year").takeIf { it > 0 },
            tags = json.optJSONArray("tags")?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty(),
            externalIds = ids.keys().asSequence().associateWith { ids.getString(it) },
            airWeekday = json.optInt("weekday").takeIf { it in 1..7 },
            rating = if (json.has("rating") && !json.isNull("rating")) json.getDouble("rating").takeIf { it.isFinite() } else null,
            rank = json.optInt("rank").takeIf { it > 0 })
    }

    fun detail(value: AnimeDetail): JSONObject = JSONObject().put("subject", subject(value.subject))
        .put("episodes", JSONArray(value.episodes.map { episode ->
            JSONObject().put("id", episode.id).put("subject", episode.subjectId).put("title", episode.title)
                .put("number", episode.number).put("kind", episode.kind.name)
                .put("duration", episode.durationMs).put("airDate", episode.airDate)
        }))

    fun detail(json: JSONObject): AnimeDetail {
        val subject = subject(json.getJSONObject("subject"))
        val rows = json.getJSONArray("episodes")
        val episodes = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            AnimeEpisode(row.getString("id"), row.getString("subject"), row.getString("title"),
                row.getDouble("number"), AnimeEpisodeKind.valueOf(row.getString("kind")),
                if (row.isNull("duration")) null else row.getLong("duration"),
                row.optString("airDate", null))
        }
        require(episodes.all { it.subjectId == subject.id && it.number.isFinite() && it.id.isNotBlank() })
        require(episodes.map { it.id }.distinct().size == episodes.size)
        if (subject.id.startsWith("native:")) require(episodes.all {
            Regex("${Regex.escape(subject.id)}:episode:[a-f0-9]{64}").matches(it.id)
        }) { "原始剧集身份与所属条目不一致。" }
        return AnimeDetail(subject, episodes)
    }
}
