package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeDiscoveryProvider
import dev.mangaflow.anime.AnimeDiscoveryTag
import dev.mangaflow.anime.AnimeTagDirectoryPage
import dev.mangaflow.anime.animeDiscoveryGenreLabels
import dev.mangaflow.anime.animeDoubanGenreLabels
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.NetworkTransferException
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Provider-owned directories; their cursors never share the work-results pagination. */
internal class AnimeTagDirectoryRepository(context: PlatformContext) {
    init { DownloadRoutingStore.get(context) }
    private val cache = AnimeMetadataCache.get(context)

    suspend fun read(provider: AnimeDiscoveryProvider, offset: Int = 0,
        forceRefresh: Boolean = false): AnimeTagDirectoryPage = withContext(Dispatchers.Default) {
        require(offset >= 0) { "标签目录分页位置无效。" }
        require(provider == AnimeDiscoveryProvider.BANGUMI || offset == 0) { "这个标签目录不使用分页。" }
        currentCoroutineContext().ensureActive()
        if (provider == AnimeDiscoveryProvider.DOUBAN) {
            val tags = animeDoubanGenreLabels.map { AnimeDiscoveryTag(it, it) }
            return@withContext AnimeTagDirectoryPage(tags, nextOffset = null, total = tags.size)
        }

        val source = sourceId(provider)
        val route = DownloadRoutingStore.activeSourceSnapshot(source, DownloadCategory.ANIME)
        val generation = cache.generation
        val key = "anime-tag-directory:v1:${provider.name}:$offset:${animeHash(route.identity())}"
        fun checkScope() {
            if (DownloadRoutingStore.activeSourceSnapshot(source, DownloadCategory.ANIME) != route)
                throw DownloadRouteException("标签目录的网络线路已更改，请按当前线路重新读取。")
            if (cache.generation != generation) throw AnimeSourceException(source, AnimeSourceStage.METADATA,
                "标签缓存已清理，请重新读取目录。")
        }
        checkScope()
        if (!forceRefresh) {
            val document = cache.read(key)
            checkScope()
            if (document != null && cache.isFresh(document, ContentCacheKind.DISCOVERY)) {
                val restored = try { decode(provider, JSONObject(document.text), offset) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                if (restored != null) {
                    currentCoroutineContext().ensureActive()
                    checkScope()
                    return@withContext restored
                }
            }
        }

        val http = AnimeHttp(source, route)
        try {
            checkScope()
            val response = withTimeoutOrNull(READ_TIMEOUT_MILLIS) {
                when (provider) {
                    AnimeDiscoveryProvider.BANGUMI -> http.json(
                        animePublicUrl("https://next.bgm.tv/p1/channels/2/tags").newBuilder()
                            .addQueryParameter("limit", BANGUMI_PAGE_SIZE.toString())
                            .addQueryParameter("offset", offset.toString()).build(), AnimeSourceStage.METADATA)
                    AnimeDiscoveryProvider.ANILIST -> http.json(ANILIST_API, AnimeSourceStage.METADATA, "POST",
                        JSONObject().put("query", ANILIST_DIRECTORY_QUERY))
                    AnimeDiscoveryProvider.DOUBAN -> error("豆瓣标签由现有目录提供。")
                }
            } ?: throw AnimeSourceException(source, AnimeSourceStage.METADATA,
                "读取标签目录超时，请稍后重试。",
                cause = NetworkTransferException(NetworkFailureKind.RESPONSE_TIMEOUT, "读取标签目录超时"))
            currentCoroutineContext().ensureActive()
            checkScope()
            val result = decode(provider, response, offset)
            val encoded = response.toString()
            currentCoroutineContext().ensureActive()
            checkScope()
            cache.write(key, encoded, generation)
            checkScope()
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: DownloadRouteException) {
            throw failure
        } catch (failure: AnimeSourceException) {
            throw failure
        } catch (failure: Exception) {
            throw AnimeSourceException(source, AnimeSourceStage.METADATA,
                "标签目录没有返回完整的预期数据，请稍后重试。", cause = failure)
        } finally { http.close() }
    }

    private fun decode(provider: AnimeDiscoveryProvider, document: JSONObject, offset: Int): AnimeTagDirectoryPage =
        when (provider) {
            AnimeDiscoveryProvider.BANGUMI -> decodeBangumi(document, offset)
            AnimeDiscoveryProvider.ANILIST -> decodeAniList(document)
            AnimeDiscoveryProvider.DOUBAN -> error("豆瓣标签由现有目录提供。")
        }

    private fun decodeBangumi(document: JSONObject, offset: Int): AnimeTagDirectoryPage {
        val provider = AnimeDiscoveryProvider.BANGUMI
        val rows = document.optJSONArray("data") ?: throw invalidDirectory(provider)
        val total = nonNegativeInt(document.opt("total")) ?: throw invalidDirectory(provider)
        if (rows.length() == 0 && offset < total) throw invalidDirectory(provider)
        if (rows.length() > BANGUMI_PAGE_SIZE || rows.length().toLong() > (total.toLong() - offset).coerceAtLeast(0L))
            throw invalidDirectory(provider)
        val tags = List(rows.length()) { index ->
            val row = rows.optJSONObject(index) ?: throw invalidDirectory(provider)
            val name = directoryName(row.opt("name")) ?: throw invalidDirectory(provider)
            val count = nonNegativeInt(row.opt("count")) ?: throw invalidDirectory(provider)
            AnimeDiscoveryTag(key = name, name = name, count = count)
        }.distinctBy { it.key }
        // Bangumi applies SQL limit/offset before deduplicating each page by name.
        // A short returned page therefore does not mean the native directory has ended.
        val next = offset.toLong() + BANGUMI_PAGE_SIZE
        return AnimeTagDirectoryPage(tags, next.takeIf { it < total.toLong() }?.toInt(), total)
    }

    private fun decodeAniList(document: JSONObject): AnimeTagDirectoryPage {
        val provider = AnimeDiscoveryProvider.ANILIST
        if (!document.isNull("errors")) {
            val errors = document.optJSONArray("errors") ?: throw invalidDirectory(provider)
            if (errors.length() > 0) throw AnimeSourceException(ANILIST_PROVIDER, AnimeSourceStage.METADATA,
                "AniList 标签目录暂时无法读取，请稍后重试。")
        }
        val data = document.optJSONObject("data") ?: throw invalidDirectory(provider)
        val genres = data.optJSONArray("GenreCollection") ?: throw invalidDirectory(provider)
        val mediaTags = data.optJSONArray("MediaTagCollection") ?: throw invalidDirectory(provider)
        val tags = buildList {
            for (index in 0 until genres.length()) {
                val name = directoryName(genres.opt(index)) ?: throw invalidDirectory(provider)
                if (name.equals("Hentai", ignoreCase = true)) continue
                add(AnimeDiscoveryTag(key = name, name = animeDiscoveryGenreLabels[name] ?: name, aliases = listOf(name)))
            }
            for (index in 0 until mediaTags.length()) {
                val row = mediaTags.optJSONObject(index) ?: throw invalidDirectory(provider)
                val name = directoryName(row.opt("name")) ?: throw invalidDirectory(provider)
                val adult = row.opt("isAdult")
                if (!row.has("isAdult") || !row.isNull("isAdult") && adult !is Boolean)
                    throw invalidDirectory(provider)
                if (adult == true) continue
                add(AnimeDiscoveryTag(key = "tag:$name", name = name))
            }
        }.distinctBy { it.key }
        return AnimeTagDirectoryPage(tags, nextOffset = null, total = tags.size)
    }

    private fun directoryName(value: Any?): String? = (value as? String)?.takeIf {
        it.isNotBlank() && it.none { character -> character.isISOControl() }
    }

    private fun nonNegativeInt(value: Any?): Int? {
        val number = value as? Number ?: return null
        val integer = number.toLong()
        return integer.takeIf { it in 0..Int.MAX_VALUE.toLong() && number.toDouble() == it.toDouble() }?.toInt()
    }

    private fun invalidDirectory(provider: AnimeDiscoveryProvider) = AnimeSourceException(sourceId(provider),
        AnimeSourceStage.METADATA, "标签目录的数据结构不完整，请稍后重试。")

    private fun sourceId(provider: AnimeDiscoveryProvider): String = when (provider) {
        AnimeDiscoveryProvider.BANGUMI -> BANGUMI_PROVIDER
        AnimeDiscoveryProvider.ANILIST -> ANILIST_PROVIDER
        AnimeDiscoveryProvider.DOUBAN -> DOUBAN_PROVIDER
    }

    private companion object {
        const val BANGUMI_PAGE_SIZE = 100
        const val READ_TIMEOUT_MILLIS = 8_000L
        const val ANILIST_DIRECTORY_QUERY = "query AnimeTagDirectory { GenreCollection MediaTagCollection { name isAdult } }"
    }
}
