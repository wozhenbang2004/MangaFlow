package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.platform.storage.PRIVATE_STORAGE
import dev.mangaflow.data.sources.BodySourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONObject

data class BodySourceRegistryEntry(
    val source: BodySourceInfo,
    val enabled: Boolean = true,
    val removed: Boolean = false,
    val isProtected: Boolean = false,
    val isInstalled: Boolean = true,
)

/** Local availability for installed adapters; removing a source never deletes its reading data. */
class BodySourceRegistry(context: Context, sources: List<BodySourceInfo>) {
    private val disk = context.applicationContext.getSharedPreferences("mangaflow_library", PRIVATE_STORAGE)
    private var installedSources = sources.distinctBy { it.id }.toList()
    private val mutex = Mutex()
    private val mutableEntries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<BodySourceRegistryEntry>> = mutableEntries.asStateFlow()

    val enabledSourceIds: Set<String>
        get() = mutableEntries.value.asSequence().filter { !it.removed && it.enabled }
            .map { it.source.id }.toSet()

    suspend fun setEnabled(sourceId: String, enabled: Boolean) = update(sourceId) { entry ->
        check(!enabled || !entry.removed) { "请先从添加来源中恢复这个来源。" }
        entry.copy(enabled = enabled)
    }

    suspend fun remove(sourceId: String) = update(sourceId) { entry ->
        check(!entry.isProtected) { "拷贝同时提供账号和标签目录，不能移除；可以关闭其搜索与自动合并。" }
        entry.copy(enabled = false, removed = true)
    }

    suspend fun restore(sourceId: String) = update(sourceId) { entry ->
        check(installedSources.any { it.id == sourceId }) { "此来源代码已卸载，请重新导入该源。" }
        entry.copy(enabled = true, removed = false)
    }

    suspend fun updateInstalledSources(sources: List<BodySourceInfo>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = sources.distinctBy { it.id }.toList()
            val saved = JSONObject(disk.getString(KEY, "{}") ?: "{}")
            mutableEntries.value.forEach { entry ->
                val value = saved.optJSONObject(entry.source.id) ?: JSONObject()
                    .put("enabled", entry.enabled).put("removed", entry.removed)
                value.put("source", encodeSource(entry.source))
                if (next.none { it.id == entry.source.id }) value.put("enabled", false).put("removed", true)
                saved.put(entry.source.id, value)
            }
            next.forEach { source ->
                val value = saved.optJSONObject(source.id) ?: JSONObject().put("enabled", true).put("removed", false)
                value.put("source", encodeSource(source))
                saved.put(source.id, value)
            }
            check(disk.edit().putString(KEY, saved.toString()).commit()) { "来源安装状态未能保存，请检查存储空间。" }
            installedSources = next
            mutableEntries.value = readEntries()
        }
    }

    suspend fun reload() = withContext(Dispatchers.IO) { mutex.withLock { mutableEntries.value = readEntries() } }

    private suspend fun update(
        sourceId: String,
        transform: (BodySourceRegistryEntry) -> BodySourceRegistryEntry,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = mutableEntries.value
            val index = current.indexOfFirst { it.source.id == sourceId }
            require(index >= 0) { "这个来源尚未安装，无法修改。" }
            val entry = transform(current[index])
            if (entry == current[index]) return@withLock
            val next = current.toMutableList().apply { set(index, entry) }.toList()
            val json = JSONObject(disk.getString(KEY, "{}") ?: "{}")
            val saved = json.optJSONObject(entry.source.id) ?: JSONObject()
            json.put(entry.source.id, saved.put("enabled", entry.enabled).put("removed", entry.removed)
                .put("source", encodeSource(entry.source)))
            check(disk.edit().putString(KEY, json.toString()).commit()) {
                "来源设置未能保存，请检查存储空间后重试。"
            }
            mutableEntries.value = next
        }
    }

    private fun readEntries(): List<BodySourceRegistryEntry> {
        val saved = runCatching { JSONObject(disk.getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val tombstones = saved.keys().asSequence().filter { id -> installedSources.none { it.id == id } }
            .mapNotNull { id -> saved.optJSONObject(id)?.optJSONObject("source")?.let { value ->
                BodySourceInfo(id, value.optString("name", id), value.optString("language"),
                    value.optString("homeUrl"), value.optString("iconUrl"), value.optString("revision"))
            } }.toList()
        return (installedSources + tombstones).map { source ->
            val settings = saved.optJSONObject(source.id)
            val protectedSource = source.id == "copy"
            val removed = !protectedSource && (settings?.optBoolean("removed", false) == true ||
                installedSources.none { it.id == source.id })
            BodySourceRegistryEntry(
                source = source,
                enabled = !removed && (settings?.optBoolean("enabled", true) ?: true),
                removed = removed,
                isProtected = protectedSource,
                isInstalled = installedSources.any { it.id == source.id },
            )
        }
    }

    private fun encodeSource(source: BodySourceInfo) = JSONObject().put("name", source.name)
        .put("language", source.language).put("homeUrl", source.homeUrl).put("iconUrl", source.iconUrl).put("revision", source.revision)

    private companion object {
        const val KEY = "body_source_registry"
    }
}
