package dev.mangaflow.data.plugins

import dev.mangaflow.json.JSONArray
import dev.mangaflow.platform.PlatformContext

expect class JavascriptSourceRuntime(context: PlatformContext) {
    /** Compiles and inspects actual source exports; no HTTP request is allowed during validation. */
    suspend fun validate(bundle: ScriptSourcePackage)
    /** Actual JSON result and source headers. Unsupported platforms fail explicitly. */
    suspend fun call(bundle: ScriptSourcePackage, method: String, args: JSONArray = JSONArray()): String
}
