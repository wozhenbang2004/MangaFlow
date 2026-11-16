package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimeEpisodeKind
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext

/** Official Bangumi v0 contract: bangumi/api/open-api/v0.yaml, read 2026-11-16. */
class AnimeMetadataRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }

    suspend fun discover(): List<AnimeSubject> {
        val http = AnimeHttp(BANGUMI_PROVIDER)
        return try {
            http.json(BANGUMI_API.newBuilder().addPathSegment("subjects")
                .addQueryParameter("type", "2").addQueryParameter("sort", "rank")
                .addQueryParameter("limit", "24").addQueryParameter("offset", "0").build(),
                AnimeSourceStage.METADATA)
                .requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
                .mapNotNull(::bangumiSubject)
        } finally { http.close() }
    }

    suspend fun search(query: String): List<AnimeSubject> {
        val keyword = query.trim()
        require(keyword.isNotEmpty()) { "请输入番剧名称。" }
        val http = AnimeHttp(BANGUMI_PROVIDER)
        return try {
            http.json(BANGUMI_API.newBuilder().addPathSegments("search/subjects")
                .addQueryParameter("limit", "50").addQueryParameter("offset", "0").build(),
                AnimeSourceStage.METADATA, "POST", JSONObject().put("keyword", keyword).put("sort", "match")
                    .put("filter", JSONObject().put("type", JSONArray(listOf(2)))))
                .requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
                .mapNotNull(::bangumiSubject)
        } finally { http.close() }
    }

    suspend fun detail(subject: AnimeSubject): AnimeDetail {
        val id = animeNumericId(subject.externalIds["bangumi"] ?: subject.id.removePrefix("bangumi:"))
        val http = AnimeHttp(BANGUMI_PROVIDER)
        return try {
            val canonical = bangumiSubject(http.json(BANGUMI_API.newBuilder().addPathSegment("subjects")
                .addPathSegment(id.toString()).build(), AnimeSourceStage.METADATA))
                ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "该条目不是动画，请重新选择正确的番剧或季度。")
            AnimeDetail(canonical, bangumiEpisodes(http, id).map { episode ->
                val number = episode.finiteNumber("sort")
                    ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "番剧目录缺少剧集编号，暂时无法建立准确映射。")
                val episodeId = episode.positiveInt("id")
                    ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "番剧目录缺少剧集身份，请刷新后重试。")
                AnimeEpisode(
                    id = "bangumi:$episodeId", subjectId = canonical.id,
                    title = episode.text("name_cn", "name").ifBlank { "第 ${animeEpisodeLabel(number)} 集" },
                    number = number, kind = bangumiEpisodeKind(episode.optInt("type", -1)),
                    durationMs = bangumiDuration(episode.text("duration")),
                    airDate = episode.text("airdate").takeIf { it.isNotBlank() && it != "0000-00-00" },
                )
            }.sortedWith(compareBy<AnimeEpisode> { it.kind.ordinal }.thenBy { it.number }.thenBy { it.id }))
        } finally { http.close() }
    }
}

internal const val BANGUMI_PROVIDER = "anime-bangumi"
internal const val ANILIST_PROVIDER = "anime-anilist"
internal val BANGUMI_API = animePublicUrl("https://api.bgm.tv/v0/")
internal val ANILIST_API = animePublicUrl("https://graphql.anilist.co/")

internal fun bangumiSubject(json: JSONObject): AnimeSubject? {
    if (json.optInt("type") != 2) return null
    val id = json.positiveInt("id") ?: return null
    val name = json.text("name_cn", "name")
    if (name.isBlank()) return null
    val images = json.optJSONObject("images")
    return AnimeSubject(
        id = "bangumi:$id", title = name, originalTitle = json.text("name"),
        coverUrl = sourceCover("https://bgm.tv/", images?.text("large", "common", "medium").orEmpty()),
        description = Ksoup.parse(json.text("summary")).text(),
        episodeCount = json.positiveInt("eps"),
        year = json.text("date").take(4).toIntOrNull()?.takeIf { it in 1900..9999 },
        tags = json.optJSONArray("tags")?.objects().orEmpty().map { it.text("name") }.filter { it.isNotBlank() }.distinct(),
        externalIds = mapOf("bangumi" to id.toString()),
    )
}

internal suspend fun bangumiEpisodes(http: AnimeHttp, subjectId: Int, token: String? = null): List<JSONObject> {
    val output = mutableListOf<JSONObject>()
    val identities = mutableSetOf<Int>()
    var offset = 0
    while (true) {
        val data = http.json(BANGUMI_API.newBuilder().addPathSegment("episodes")
            .addQueryParameter("subject_id", subjectId.toString()).addQueryParameter("limit", "200")
            .addQueryParameter("offset", offset.toString()).build(), AnimeSourceStage.METADATA, token = token)
        val rows = if (data.optInt("total", -1) == 0 && data.isNull("data")) emptyList()
            else data.requiredArray("data", BANGUMI_PROVIDER, AnimeSourceStage.METADATA).objects()
        val total = data.optInt("total", -1)
        if (rows.isEmpty()) {
            if (total > offset) throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "远端目录分页不完整，请刷新后重试。")
            break
        }
        for (row in rows) {
            val id = row.positiveInt("id")
                ?: throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "远端目录缺少剧集身份。")
            if (row.optInt("subject_id", subjectId) != subjectId || !identities.add(id))
                throw AnimeSourceException(BANGUMI_PROVIDER, AnimeSourceStage.METADATA, "远端目录在读取过程中发生变化，请刷新后重试。")
            output += row
        }
        offset += rows.size
        if (total >= 0 && offset >= total) break
        if (total < 0 && rows.size < 200) break
    }
    return output
}

internal fun bangumiEpisodeKind(type: Int): AnimeEpisodeKind = when (type) {
    0 -> AnimeEpisodeKind.MAIN
    1 -> AnimeEpisodeKind.SPECIAL
    2 -> AnimeEpisodeKind.OPENING
    3 -> AnimeEpisodeKind.ENDING
    4 -> AnimeEpisodeKind.TRAILER
    else -> AnimeEpisodeKind.OTHER
}

private fun bangumiDuration(text: String): Long? {
    val parts = text.split(':')
    if (parts.size !in 2..3) return null
    val values = parts.map { it.toLongOrNull() ?: return null }
    if (values.any { it < 0 } || values.drop(1).any { it >= 60 }) return null
    return (if (values.size == 3) values[0] * 3600 + values[1] * 60 + values[2]
        else values[0] * 60 + values[1]).takeIf { it in 1..86_400 }?.times(1_000L)
}
