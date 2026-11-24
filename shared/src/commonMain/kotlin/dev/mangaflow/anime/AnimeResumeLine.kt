package dev.mangaflow.anime

import dev.mangaflow.anime.sources.animeContentIdentity
import dev.mangaflow.anime.sources.animeHash

internal val ANIME_RESUME_LINE_KEY = Regex("[a-f0-9]{64}\\.[a-f0-9]{64}")

private fun resumeDigest(vararg fields: Any?): String = animeHash(fields.joinToString("") { field ->
    val text = field?.toString()
    if (text == null) "-:" else "${text.length}:$text"
})

/** Scope the receipt to the reviewed source work and that episode's association, not display metadata. */
internal fun animeResumeBindingKey(binding: AnimeSourceBinding, episodeId: String, revision: String): String =
    resumeDigest(binding.subjectId, episodeId, binding.catalog.sourceId,
    binding.catalog.id, binding.catalog.url, binding.selectedGroup, binding.automatic,
    binding.episodeMappings[episodeId], revision)

/** A new catalogue, not the saved receipt, must supply the actual provider episode and fresh player ticket. */
internal fun animeResumeLineKey(bindingKey: String, sourceEpisode: AnimeSourceEpisode): String =
    "$bindingKey." + resumeDigest(sourceEpisode.id, sourceEpisode.group, sourceEpisode.kind, sourceEpisode.number,
        sourceEpisode.edition, animeContentIdentity(sourceEpisode.url))
