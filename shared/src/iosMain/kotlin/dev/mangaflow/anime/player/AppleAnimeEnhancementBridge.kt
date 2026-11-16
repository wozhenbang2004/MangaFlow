@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.anime.player

import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.UIKit.UIView

/**
 * The Swift host renders frames from the existing AVPlayer. It never owns a URL,
 * transport, audio clock, or a second playback session. All calls are on Main.
 * Strings keep this small Objective-C bridge independent of generated enum names.
 */
interface AppleAnimeEnhancementListener {
    fun enhancementChanged(
        mode: String,
        phase: String,
        detail: String?,
        inputWidth: Int,
        inputHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    )
}

interface AppleAnimeEnhancementRenderer {
    val view: UIView
    fun bind(item: AVPlayerItem?)
    fun configure(mode: String, shaderVersion: String, shaderSources: List<String>)
    fun setVideoScale(scale: String)
    fun setForeground(foreground: Boolean)
    fun setSurfaceAttached(attached: Boolean)
    fun seekStarted()
    fun refresh()
    fun dispose()
}

interface AppleAnimeEnhancementFactory {
    fun create(player: AVPlayer, listener: AppleAnimeEnhancementListener): AppleAnimeEnhancementRenderer
}

/** Registered by the iOS app before creating the shared application. */
object AppleAnimeEnhancementBridge {
    internal var factory: AppleAnimeEnhancementFactory? = null
        private set

    fun registerFactory(factory: AppleAnimeEnhancementFactory) {
        this.factory = factory
    }
}
