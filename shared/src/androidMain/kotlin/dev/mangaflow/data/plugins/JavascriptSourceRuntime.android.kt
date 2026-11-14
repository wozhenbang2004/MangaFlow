package dev.mangaflow.data.plugins

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore

/** Android keeps the existing optional native-runtime download and verified loader. */
internal actual suspend fun prepareJavascriptEngine(context: PlatformContext) {
    PanelRuntimeStore.get(context).ensureLoaded(PanelRuntimeId.QUICKJS)
}
