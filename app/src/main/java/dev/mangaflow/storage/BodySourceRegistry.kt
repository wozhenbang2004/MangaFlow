package dev.mangaflow.storage

import android.content.Context
import dev.mangaflow.data.sources.BodySourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class BodySourceRegistryEntry(
    val source: BodySourceInfo,
    val enabled: Boolean = true,
    val removed: Boolean = false,
    val isProtected: Boolean = false,
)

/** Local availability for installed adapters; removing a source never deletes its reading data. */
class BodySourceRegistry(context: Context, sources: List<BodySourceInfo>) {
    private val disk = context.applicationContext.getSharedPreferences("mangaflow_library", Context.MODE_PRIVATE)
    private val installedSources = sources.distinctBy { it.id }.toList()
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
        entry.copy(enabled = true, removed = false)
    }

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
            val json = JSONObject()
            next.forEach { item ->
                json.put(item.source.id, JSONObject()
                    .put("enabled", item.enabled)
                    .put("removed", item.removed))
            }
            check(disk.edit().putString(KEY, json.toString()).commit()) {
                "来源设置未能保存，请检查存储空间后重试。"
            }
            mutableEntries.value = next
        }
    }

    private fun readEntries(): List<BodySourceRegistryEntry> {
        val saved = runCatching { JSONObject(disk.getString(KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        return installedSources.map { source ->
            val settings = saved.optJSONObject(source.id)
            val protectedSource = source.id == "copy"
            val removed = !protectedSource && settings?.optBoolean("removed", false) == true
            BodySourceRegistryEntry(
                source = source,
                enabled = !removed && (settings?.optBoolean("enabled", true) ?: true),
                removed = removed,
                isProtected = protectedSource,
            )
        }
    }

    private companion object {
        const val KEY = "body_source_registry"
    }
}
