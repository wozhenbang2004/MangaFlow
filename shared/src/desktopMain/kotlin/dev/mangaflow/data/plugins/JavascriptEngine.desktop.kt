package dev.mangaflow.data.plugins

import dev.mangaflow.platform.PlatformContext

/** JVM QuickJS loads its matching packaged native library; no Android AAR is installed. */
internal actual suspend fun prepareJavascriptEngine(context: PlatformContext) = Unit
