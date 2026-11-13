package dev.mangaflow.platform.storage

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same preference keys/types and immediate memory updates as Android SharedPreferences. */
internal class FilePreferences private constructor(private val file: StorageFile) : Preferences {
    private val lock = StorageLock()
    private val diskLock = StorageLock()
    private var loaded = false
    private var values = emptyMap<String, Any?>()
    private val listeners = mutableSetOf<PreferenceChangeListener>()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        writer.launch {
            for (ignored in writes) {
                // A newer complete snapshot includes all earlier applies; older writes never win.
                synchronized(diskLock) { persist(synchronized(lock) { load(); values.toMap() }) }
            }
        }
    }
    private fun load() {
        if (loaded) return
        values = if (!file.exists() && !StorageFile(file.path + ".bak").exists()) emptyMap() else {
            val json = JSONObject(StorageAtomicFile(file).openRead().bufferedReader().use { it.readText() })
            check(json.optString("format") == "mangaflow-preferences-v1") { "本地设置格式无法读取，请从阅读备份恢复。" }
            val entries = json.getJSONObject("values")
            entries.keys().asSequence().associateWith { key ->
                val entry = entries.getJSONObject(key)
                when (entry.getString("type")) {
                    "string" -> entry.getString("value")
                    "boolean" -> entry.getBoolean("value")
                    "int" -> entry.getInt("value")
                    "long" -> entry.getLong("value")
                    "float" -> entry.getDouble("value").toFloat()
                    "stringSet" -> entry.getJSONArray("value").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }
                    else -> error("本地设置字段类型无法读取。")
                }
            }
        }
        loaded = true
    }
    override val all: Map<String, Any?> get() = synchronized(lock) { load(); values.toMap() }
    override fun contains(key: String): Boolean = synchronized(lock) { load(); key in values }
    override fun getString(key: String, fallback: String?): String? = synchronized(lock) { load(); values[key] as? String ?: fallback }
    override fun getBoolean(key: String, fallback: Boolean): Boolean = synchronized(lock) { load(); values[key] as? Boolean ?: fallback }
    override fun getInt(key: String, fallback: Int): Int = synchronized(lock) { load(); (values[key] as? Number)?.toInt() ?: fallback }
    override fun getLong(key: String, fallback: Long): Long = synchronized(lock) { load(); (values[key] as? Number)?.toLong() ?: fallback }
    override fun getFloat(key: String, fallback: Float): Float = synchronized(lock) { load(); (values[key] as? Number)?.toFloat() ?: fallback }
    override fun getStringSet(key: String, fallback: Set<String>?): Set<String>? = synchronized(lock) {
        load()
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: fallback
    }
    override fun edit(): PreferencesEditor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: PreferenceChangeListener) { synchronized(lock) { listeners.add(listener) } }
    override fun unregisterOnSharedPreferenceChangeListener(listener: PreferenceChangeListener) { synchronized(lock) { listeners.remove(listener) } }

    private fun persist(snapshot: Map<String, Any?>): Boolean {
        val atomic = StorageAtomicFile(file)
        var output: StorageOutput? = null
        return try {
            output = atomic.startWrite()
            val entries = JSONObject()
            snapshot.forEach { (key, value) ->
                val entry = JSONObject()
                when (value) {
                    is String -> entry.put("type", "string").put("value", value)
                    is Boolean -> entry.put("type", "boolean").put("value", value)
                    is Int -> entry.put("type", "int").put("value", value)
                    is Long -> entry.put("type", "long").put("value", value)
                    is Float -> entry.put("type", "float").put("value", value.toDouble())
                    is Set<*> -> entry.put("type", "stringSet").put("value", JSONArray(value.filterIsInstance<String>().sorted()))
                    else -> error("无法保存本地设置字段 $key")
                }
                entries.put(key, entry)
            }
            output.write(JSONObject().put("format", "mangaflow-preferences-v1").put("values", entries).toString().encodeToByteArray())
            atomic.finishWrite(output)
            true
        } catch (_: Exception) { output?.let(atomic::failWrite); false }
    }
    private fun flush(): Boolean = synchronized(diskLock) {
        val snapshot = synchronized(lock) { if (loaded) values.toMap() else null }
        snapshot == null || persist(snapshot)
    }
    private inner class Editor : PreferencesEditor {
        private var clearAll = false
        private val changes = linkedMapOf<String, Any?>()
        override fun putString(key: String, value: String?): PreferencesEditor = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean): PreferencesEditor = apply { changes[key] = value }
        override fun putInt(key: String, value: Int): PreferencesEditor = apply { changes[key] = value }
        override fun putLong(key: String, value: Long): PreferencesEditor = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float): PreferencesEditor = apply { changes[key] = value }
        override fun putStringSet(key: String, value: Set<String>?): PreferencesEditor = apply { changes[key] = value?.toSet() }
        override fun remove(key: String): PreferencesEditor = apply { changes[key] = null }
        override fun clear(): PreferencesEditor = apply { clearAll = true }
        private fun updateMemory(): Pair<List<String>, List<PreferenceChangeListener>> = synchronized(lock) {
            load()
            val next = (if (clearAll) emptyMap() else values).toMutableMap()
            changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            val changed = (values.keys + next.keys).filter { values[it] != next[it] }
            values = next
            changed to listeners.toList()
        }
        private fun notifyListeners(changes: Pair<List<String>, List<PreferenceChangeListener>>) {
            changes.first.forEach { key -> changes.second.forEach { it.onChanged(this@FilePreferences, key) } }
        }
        override fun commit(): Boolean {
            val changes = updateMemory()
            val saved = synchronized(diskLock) { persist(synchronized(lock) { values.toMap() }) }
            notifyListeners(changes)
            return saved
        }
        override fun apply() {
            val changes = updateMemory()
            notifyListeners(changes)
            writes.trySend(Unit)
        }
    }
    companion object {
        private val instances = mutableMapOf<String, FilePreferences>()
        private val lock = StorageLock()
        fun get(file: StorageFile): Preferences = synchronized(lock) { instances.getOrPut(file.absolutePath) { FilePreferences(file) } }
        fun flushAll(): Boolean = synchronized(lock) { instances.values.toList() }.map { it.flush() }.all { it }
    }
}

/** Desktop close and Apple scene-background transitions can await pending apply() writes. */
suspend fun flushLocalPreferences(): Boolean = withContext(Dispatchers.IO) { FilePreferences.flushAll() }
