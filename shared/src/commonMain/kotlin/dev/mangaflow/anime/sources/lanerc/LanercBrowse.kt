package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteSnapshot

data class LanercBrowseSection(val key: String, val title: String, val items: List<AnimeSourceTitle>)
data class LanercFilterOption(val value: String, val label: String)
data class LanercFilter(val key: String, val title: String, val options: List<LanercFilterOption>)
data class LanercCategory(val key: String, val title: String, val filters: List<LanercFilter>)

internal suspend fun LanercSourceStore.readHomeSections(id: String, expectedRevision: String? = null): List<LanercBrowseSection> {
    val descriptor = browseDescriptor(id)
    val reply = runtime.call(id, "homeSections", expectedRevision = expectedRevision)
    val result = reply.value as? JSONArray
        ?: throw LanercCapabilityException("来源分区格式不兼容，请更新来源。")
    require(result.length() <= 64) { "来源分区数量过多。" }
    return (0 until result.length()).map { index ->
        val section = result.optJSONObject(index)
            ?: throw LanercCapabilityException("来源分区格式不兼容，请更新来源。")
        val title = section.optString("title").ifBlank { section.optString("name") }
        val items = section.optJSONArray("items")
            ?: throw LanercCapabilityException("来源分区缺少条目列表，请更新来源。")
        if (title.isBlank()) throw LanercCapabilityException("来源分区缺少名称，请更新来源。")
        LanercBrowseSection(section.optString("key"), title, lanercCards(descriptor, items, reply.revision))
    }
}

internal suspend fun LanercSourceStore.readCategories(id: String, expectedRevision: String? = null): List<LanercCategory> {
    browseDescriptor(id)
    val result = runtime.call(id, "categories", expectedRevision = expectedRevision).value as? JSONArray
        ?: throw LanercCapabilityException("来源分类格式不兼容，请更新来源。")
    require(result.length() <= 128)
    return (0 until result.length()).map { index ->
        val item = result.optJSONObject(index)
            ?: throw LanercCapabilityException("来源分类格式不兼容，请更新来源。")
        val key = item.optString("key")
        val title = item.optString("title").ifBlank { key }
        if (title.isBlank()) throw LanercCapabilityException("来源分类缺少名称，请更新来源。")
        LanercCategory(key, title, parseFilters(item.opt("filters")))
    }.distinctBy { it.key }
}

internal suspend fun LanercSourceStore.readFiltered(id: String, category: String, filtersJson: String, page: Int,
    expectedRevision: String? = null): List<AnimeSourceTitle> {
    val descriptor = browseDescriptor(id)
    require(category.length <= 1000 && filtersJson.length <= 16_384 && page >= 1)
    val filters = JSONObject(filtersJson).toString()
    val reply = runtime.call(id, "searchFiltered", JSONArray(listOf(category, filters, page)), expectedRevision)
    return lanercCards(descriptor, reply.value, reply.revision)
}

internal suspend fun LanercSourceStore.readRelated(id: String, titleId: String, expectedRevision: String? = null,
    expectedRoute: DownloadRouteSnapshot? = null): List<AnimeSourceTitle> {
    val descriptor = browseDescriptor(id)
    require(titleId.length in 1..16_384)
    val reply = runtime.call(id, "related", JSONArray(listOf(titleId)), expectedRevision, expectedRoute)
    return lanercCards(descriptor, reply.value, reply.revision)
}

private fun LanercSourceStore.browseDescriptor(id: String): LanercSourceDescriptor = descriptor(id).also {
    require(it.kind in setOf(LanercSourceKind.VIDEO, LanercSourceKind.BROWSE)) { "该来源不提供视频内容浏览。" }
}

private fun parseFilters(raw: Any?): List<LanercFilter> {
    val entries = when (raw) {
        is JSONArray -> { require(raw.length() <= 32); (0 until raw.length()).mapNotNull(raw::optJSONObject) }
        is JSONObject -> {
            require(raw.length() <= 32)
            raw.keys().asSequence().map { JSONObject().put("key", it).put("name", filterLabel(it)).put("values", raw.opt(it)) }.toList()
        }
        else -> emptyList()
    }
    return entries.mapNotNull { item ->
        val title = item.optString("name")
        val key = item.optString("key").ifBlank { title }
        val options = item.optJSONArray("value") ?: item.optJSONArray("options") ?: item.optJSONArray("values")
            ?: return@mapNotNull null
        require(options.length() <= 300)
        val mapped = (0 until options.length()).map { index ->
            val option = options.optJSONObject(index)
            if (option == null) LanercFilterOption(options.optString(index), options.optString(index))
            else {
                val label = option.optString("n").ifBlank { option.optString("name") }
                LanercFilterOption(option.optString("v", option.optString("value", label)), label)
            }
        }
        if (key.isBlank() || mapped.isEmpty()) null else LanercFilter(key, title.ifBlank { filterLabel(key) }, mapped)
    }.distinctBy { it.key }
}
private fun filterLabel(key: String): String = when (key.lowercase()) {
    "year" -> "年份"; "area", "region" -> "地区"; "type", "class", "cat", "genre" -> "类型"
    "sort", "by", "orderby" -> "排序"; "language", "lang" -> "语言"; "status" -> "状态"; "letter" -> "字母"
    else -> key
}
