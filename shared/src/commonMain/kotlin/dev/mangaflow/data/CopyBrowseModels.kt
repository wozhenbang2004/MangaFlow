package dev.mangaflow.data

/** Native Copy filter identifiers. These remain separate from editor-curated topics. */
data class CopyFilterDirectory(
    val themes: List<MangaTag> = emptyList(),
    val scopes: List<MangaTag> = emptyList(),
    val orderings: List<MangaTag> = emptyList(),
)

data class CopyCatalogSelection(
    val theme: MangaTag? = null,
    val scope: MangaTag? = null,
    val ordering: String = "-popular",
    val original: MangaTag? = null,
)

data class CopyTopic(
    val path: String,
    val title: String,
    val coverUrl: String = "",
    val brief: String = "",
    val intro: String = "",
    val period: String = "",
    val date: String = "",
    val type: Int = 1,
)

data class CopyTopicPage(
    val items: List<CopyTopic>,
    val hasMore: Boolean,
    val nextPage: Int? = null,
)
