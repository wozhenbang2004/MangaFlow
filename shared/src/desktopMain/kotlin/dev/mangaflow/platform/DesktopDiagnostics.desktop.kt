package dev.mangaflow.platform

import dev.mangaflow.MangaApplicationServices
import dev.mangaflow.anime.player.AnimeNativeVideoStatistics

/** Read on the UI thread; observing diagnostics never creates an anime controller/player. */
fun peekDesktopNativeVideoStatistics(context: PlatformContext): AnimeNativeVideoStatistics? =
    MangaApplicationServices.get(context).peekAnimeNativeVideoStatistics()
