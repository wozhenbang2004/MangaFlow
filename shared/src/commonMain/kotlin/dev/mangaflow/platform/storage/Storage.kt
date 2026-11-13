package dev.mangaflow.platform.storage

import dev.mangaflow.platform.PlatformContext
import okio.FileSystem
import okio.SYSTEM
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use as useOkio
import kotlinx.atomicfu.locks.SynchronizedObject

typealias StorageLock = SynchronizedObject

const val PRIVATE_STORAGE = 0

expect fun dataDirectory(context: PlatformContext): StorageFile
expect fun cacheDirectory(context: PlatformContext): StorageFile
expect fun preferences(context: PlatformContext, name: String): Preferences
expect fun readSecret(context: PlatformContext, key: String): String?
expect fun writeSecret(context: PlatformContext, key: String, value: String)
expect fun deleteSecret(context: PlatformContext, key: String)
expect fun availableSpace(file: StorageFile): Long
expect fun syncStorageFile(file: StorageFile)

val PlatformContext.filesDir: StorageFile get() = dataDirectory(this)
val PlatformContext.cacheDir: StorageFile get() = cacheDirectory(this)
val PlatformContext.applicationContext: PlatformContext get() = dev.mangaflow.platform.applicationContext(this)
fun PlatformContext.getSharedPreferences(name: String, mode: Int = PRIVATE_STORAGE): Preferences = preferences(this, name)

interface Preferences {
    val all: Map<String, Any?>
    fun contains(key: String): Boolean
    fun getString(key: String, fallback: String? = null): String?
    fun getBoolean(key: String, fallback: Boolean = false): Boolean
    fun getInt(key: String, fallback: Int = 0): Int
    fun getLong(key: String, fallback: Long = 0L): Long
    fun getFloat(key: String, fallback: Float = 0f): Float
    fun getStringSet(key: String, fallback: Set<String>? = null): Set<String>?
    fun edit(): PreferencesEditor
    fun registerOnSharedPreferenceChangeListener(listener: PreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: PreferenceChangeListener)
}

fun interface PreferenceChangeListener { fun onChanged(preferences: Preferences, key: String?) }

interface PreferencesEditor {
    fun putString(key: String, value: String?): PreferencesEditor
    fun putBoolean(key: String, value: Boolean): PreferencesEditor
    fun putInt(key: String, value: Int): PreferencesEditor
    fun putLong(key: String, value: Long): PreferencesEditor
    fun putFloat(key: String, value: Float): PreferencesEditor
    fun putStringSet(key: String, value: Set<String>?): PreferencesEditor
    fun remove(key: String): PreferencesEditor
    fun clear(): PreferencesEditor
    fun commit(): Boolean
    fun apply() { check(commit()) { "无法保存本地设置，请检查存储空间。" } }
}

/** A path into the platform's real filesystem, preserving the app's existing on-disk layout. */
class StorageFile(val nativePath: Path) {
    constructor(path: String) : this(path.toPath(normalize = true))
    constructor(parent: StorageFile?, child: String) : this(parent?.nativePath?.resolve(child) ?: child.toPath(normalize = true))
    val path: String get() = nativePath.toString()
    val absolutePath: String get() = if (nativePath.isAbsolute) path else (FileSystem.SYSTEM.canonicalize(".".toPath()) / nativePath).toString()
    val canonicalPath: String get() = canonicalFile.path
    val canonicalFile: StorageFile get() = StorageFile(runCatching { FileSystem.SYSTEM.canonicalize(nativePath) }.getOrElse {
        val parent = nativePath.parent
        if (parent != null && FileSystem.SYSTEM.exists(parent)) FileSystem.SYSTEM.canonicalize(parent) / nativePath.name
        else absolutePath.toPath(normalize = true)
    })
    val name: String get() = nativePath.name
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.', name)
    val parentFile: StorageFile? get() = nativePath.parent?.let(::StorageFile)
    val parent: String? get() = nativePath.parent?.toString()
    val isFile: Boolean get() = FileSystem.SYSTEM.metadataOrNull(nativePath)?.isRegularFile == true
    val isDirectory: Boolean get() = FileSystem.SYSTEM.metadataOrNull(nativePath)?.isDirectory == true
    val usableSpace: Long get() = availableSpace(this)
    fun exists(): Boolean = FileSystem.SYSTEM.exists(nativePath)
    fun length(): Long = FileSystem.SYSTEM.metadataOrNull(nativePath)?.size ?: 0L
    fun lastModified(): Long = FileSystem.SYSTEM.metadataOrNull(nativePath)?.lastModifiedAtMillis ?: 0L
    fun mkdirs(): Boolean = runCatching { FileSystem.SYSTEM.createDirectories(nativePath); true }.getOrDefault(false)
    fun mkdir(): Boolean = runCatching { FileSystem.SYSTEM.createDirectory(nativePath); true }.getOrDefault(false)
    fun delete(): Boolean = runCatching { FileSystem.SYSTEM.delete(nativePath); true }.getOrDefault(false)
    fun deleteRecursively(): Boolean = runCatching { FileSystem.SYSTEM.deleteRecursively(nativePath); true }.getOrDefault(false)
    fun listFiles(): Array<StorageFile>? = runCatching { FileSystem.SYSTEM.list(nativePath).map(::StorageFile).toTypedArray() }.getOrNull()
    fun renameTo(destination: StorageFile): Boolean = runCatching { FileSystem.SYSTEM.atomicMove(nativePath, destination.nativePath); true }.getOrDefault(false)
    fun readBytes(): ByteArray = FileSystem.SYSTEM.read(nativePath) { readByteArray() }
    fun readText(): String = FileSystem.SYSTEM.read(nativePath) { readUtf8() }
    fun writeBytes(bytes: ByteArray) { FileSystem.SYSTEM.write(nativePath) { write(bytes) } }
    fun writeText(text: String) { FileSystem.SYSTEM.write(nativePath) { writeUtf8(text) } }
    fun appendText(text: String) { FileSystem.SYSTEM.appendingSink(nativePath).buffer().useOkio { it.writeUtf8(text) } }
    fun inputStream(): StorageInput = StorageInput(FileSystem.SYSTEM.source(nativePath).buffer())
    fun outputStream(): StorageOutput = StorageOutput(FileSystem.SYSTEM.sink(nativePath).buffer())
    fun copyTo(destination: StorageFile, overwrite: Boolean = false): StorageFile {
        require(overwrite || !destination.exists()) { "目标文件已存在。" }
        FileSystem.SYSTEM.copy(nativePath, destination.nativePath)
        return destination
    }
    /** Returned as a string because file URIs are the common reader/image-loader contract. */
    fun toURI(): String {
        val absolute = absolutePath.replace("\\", "/").let { if (it.startsWith('/')) it else "/$it" }
        val encoded = buildString {
            for (byte in absolute.encodeToByteArray()) {
                val value = byte.toInt() and 255
                val character = value.toChar()
                if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~/:" ) append(character)
                else { append('%'); append(value.toString(16).uppercase().padStart(2, '0')) }
            }
        }
        return "file://$encoded"
    }
    override fun equals(other: Any?): Boolean = other is StorageFile && nativePath == other.nativePath
    override fun hashCode(): Int = nativePath.hashCode()
    override fun toString(): String = path
}

class StorageInput internal constructor(internal val source: okio.BufferedSource) : AutoCloseable {
    fun read(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int = source.read(bytes, offset, length)
    fun read(): Int = if (source.exhausted()) -1 else source.readByte().toInt() and 255
    fun readBytes(): ByteArray = source.readByteArray()
    fun bufferedReader(): StorageTextReader = StorageTextReader(this)
    override fun close() = source.close()
}

class StorageTextReader(private val input: StorageInput) : AutoCloseable {
    fun readText(): String = input.source.readUtf8()
    override fun close() = input.close()
}

class StorageOutput internal constructor(internal val sink: okio.BufferedSink) : AutoCloseable {
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) { sink.write(bytes, offset, length) }
    fun write(value: Int) { sink.writeByte(value) }
    fun flush() = sink.flush()
    override fun close() = sink.close()
}

/** Write a sibling and publish by atomic rename; a failed write leaves the previous file intact. */
class StorageAtomicFile(val baseFile: StorageFile) {
    private val pending get() = StorageFile(baseFile.path + ".new")
    private val legacy get() = StorageFile(baseFile.path + ".bak")
    private fun restoreLegacy() {
        // Android AtomicFile's legacy backup is authoritative after an interrupted old write.
        if (legacy.exists()) check(legacy.renameTo(baseFile)) { "无法恢复上次保存的本地文件。" }
    }
    fun openRead(): StorageInput {
        restoreLegacy()
        return baseFile.inputStream()
    }
    fun startWrite(): StorageOutput {
        restoreLegacy()
        baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        return pending.outputStream()
    }
    fun finishWrite(stream: StorageOutput) {
        stream.flush(); stream.close()
        syncStorageFile(pending)
        check(pending.renameTo(baseFile)) { "无法原子保存本地文件。" }
        check(!legacy.exists() || legacy.delete()) { "无法清理旧版本的本地文件备份。" }
    }
    fun failWrite(stream: StorageOutput) { runCatching { stream.close() }; pending.delete() }
    fun delete() { baseFile.delete(); pending.delete(); legacy.delete() }
}
