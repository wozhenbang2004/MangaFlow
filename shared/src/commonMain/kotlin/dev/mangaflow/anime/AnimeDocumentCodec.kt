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
        .put("ratingCount", value.ratingCount).put("format", value.format?.name)
        .put("aliases", JSONArray(value.aliases))

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
        } else if (id.startsWith("douban:")) {
            val doubanId = id.removePrefix("douban:")
            require(doubanId.toLongOrNull()?.let { it > 0L && it.toString() == doubanId } == true &&
                ids.optString("douban") == doubanId && !ids.has("native-source")) {
                "豆瓣作品身份与主条目不一致。"
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
            rank = json.optInt("rank").takeIf { it > 0 },
            ratingCount = json.optLong("ratingCount", -1L).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
            format = json.optString("format", null)?.let { AnimeDiscoveryFormat.valueOf(it) },
            aliases = json.optJSONArray("aliases")?.let { array ->
                require(array.length() <= 64) { "作品别名记录过多。" }
                (0 until array.length()).map(array::getString).also { names ->
                    require(names.all { it.isNotBlank() && it.length <= 1000 }) { "作品别名记录无效。" }
                }.distinct()
            }.orEmpty())
    }

    fun detail(value: AnimeDetail): JSONObject = JSONObject().put("subject", subject(value.subject))
        .put("episodes", JSONArray(value.episodes.map { episode ->
            JSONObject().put("id", episode.id).put("subject", episode.subjectId).put("title", episode.title)
                .put("number", episode.number).put("kind", episode.kind.name)
                .put("duration", episode.durationMs).put("airDate", episode.airDate)
                .put("numberInSubject", episode.numberInSubject)
                .put("metadataEpisode", episode.metadataEpisodeId).put("metadataSubject", episode.metadataSubjectId)
        })).put("metadataSubject", value.metadataSubject?.let(::subject))

    fun detail(json: JSONObject): AnimeDetail {
        val subject = subject(json.getJSONObject("subject"))
        val rows = json.getJSONArray("episodes")
        val episodes = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            AnimeEpisode(row.getString("id"), row.getString("subject"), row.getString("title"),
                row.getDouble("number"), AnimeEpisodeKind.valueOf(row.getString("kind")),
                if (row.isNull("duration")) null else row.getLong("duration"),
                row.optString("airDate", null),
                row.optDouble("numberInSubject").takeIf { it.isFinite() && it > 0.0 },
                row.optString("metadataEpisode", null), row.optString("metadataSubject", null))
        }
        val metadata = json.optJSONObject("metadataSubject")?.let { AnimeDocumentCodec.subject(it) }
        return AnimeDetail(subject, episodes, metadata).also(::validateDetail)
    }

    fun validateDetail(value: AnimeDetail) {
        val subject = value.subject
        val episodes = value.episodes
        val metadata = value.metadataSubject
        require(episodes.all { it.subjectId == subject.id && it.number.isFinite() && it.id.isNotBlank() })
        require(episodes.map { it.id }.distinct().size == episodes.size)
        if (subject.id.startsWith("native:")) require(episodes.all {
            Regex("${Regex.escape(subject.id)}:episode:[a-f0-9]{64}").matches(it.id)
        }) { "原始剧集身份与所属条目不一致。" }
        if (subject.canonicalDoubanId() != null) require(episodes.all { animeCatalogEpisodeOwnedBy(subject, it) }) {
            "豆瓣本地剧集身份与所属条目不一致。"
        }
        require(metadata == null || metadata.canonicalBangumiId() != null &&
            (subject.canonicalBangumiId() == null || subject.id == metadata.id)) { "补充资料缺少有效的 Bangumi 身份。" }
        // A download snapshot may contain one referenced episode without the full metadata subject.
        // The paired remote IDs remain exact; a complete detail additionally checks their owner.
        require(episodes.all { episode ->
            if (episode.metadataEpisodeId == null && episode.metadataSubjectId == null) true
            else animeBangumiReferenceId(episode.metadataEpisodeId) != null &&
                animeBangumiReferenceId(episode.metadataSubjectId) != null &&
                (metadata == null || episode.metadataSubjectId == metadata.id)
        }) { "剧集补充资料身份不完整或属于其他作品。" }
        val references = episodes.mapNotNull { it.metadataEpisodeId }
        require(references.distinct().size == references.size &&
            episodes.mapNotNull { it.metadataSubjectId }.distinct().size <= 1) { "剧集补充资料存在重复或不同作品身份。" }
    }
}
