package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.sources.lanerc.LanercBrowseSection
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A disposable public catalogue snapshot. No episode assets or source session data enter it. */
internal class AnimeSourceHomeCache(private val context: PlatformContext) {
    private val cache = AnimeMetadataCache.get(context)
    val generation: Long get() = cache.generation

    suspend fun read(source: AnimeBrowseSource, route: DownloadRouteSnapshot): AnimeSourceHomeFeed? {
        return try {
            val document = cache.read(key(source, route)) ?: return null
            withContext(Dispatchers.Default) {
                val value = JSONObject(document.text)
                val fetchedAt = value.getLong("fetchedAt")
                require(fetchedAt in 1..document.savedAt)
                val rows = value.getJSONArray("sections")
                val sections = (0 until rows.length()).map { index ->
                    val section = rows.getJSONObject(index)
                    val items = section.getJSONArray("items")
                    LanercBrowseSection(section.getString("key"), section.getString("title"),
                        (0 until items.length()).map { decodeTitle(source, items.getJSONObject(it)) })
                }
                require("latest" !in source.capabilities || sections.single().key == "latest")
                AnimeSourceHomeFeed(source, sections, fetchedAt = fetchedAt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun write(feed: AnimeSourceHomeFeed, route: DownloadRouteSnapshot, requestedGeneration: Long) {
        if (requestedGeneration != generation || !contentCachePreferences(context).enabled) return
        try {
            val text = withContext(Dispatchers.Default) {
                JSONObject().put("fetchedAt", requireNotNull(feed.fetchedAt)).put("sections",
                    JSONArray(feed.sections.map { section ->
                        JSONObject().put("key", section.key).put("title", section.title)
                            .put("items", JSONArray(section.items.map(::encodeTitle)))
                    })).toString()
            }
            cache.write(key(feed.source, route), text, requestedGeneration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unavailable disposable cache does not invalidate a successful catalogue response.
        }
    }

    private fun key(source: AnimeBrowseSource, route: DownloadRouteSnapshot): String {
        val document = JSONArray(listOf(source.id, source.revision, "latest" in source.capabilities, route.identity()))
        return "source-home:v1:${animeHash(document.toString())}"
    }

    private fun encodeTitle(item: AnimeSourceTitle): JSONObject = JSONObject()
        .put("id", item.id).put("title", item.title).put("url", item.url).put("cover", item.coverUrl)
        .put("year", item.year).put("count", item.episodeCount).put("externalIds", JSONObject(item.externalIds))
        .put("update", item.updateText).put("rating", item.ratingText)

    private fun decodeTitle(source: AnimeBrowseSource, row: JSONObject): AnimeSourceTitle {
        val id = row.getString("id")
        val title = row.getString("title")
        val url = row.getString("url")
        require(id.isNotBlank() && title.isNotBlank() && url.isNotBlank())
        val ids = row.getJSONObject("externalIds")
        return AnimeSourceTitle(
            sourceId = source.id, id = id, title = title, url = url, coverUrl = row.getString("cover"),
            year = if (row.isNull("year")) null else row.getInt("year"),
            episodeCount = if (row.isNull("count")) null else row.getInt("count"),
            externalIds = ids.keys().asSequence().associateWith(ids::getString),
            updateText = row.getString("update"), ratingText = row.getString("rating"), sourceRevision = source.revision,
        )
    }
}
