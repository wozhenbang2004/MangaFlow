@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.platform.storage

import dev.mangaflow.platform.PlatformContext
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.posix.*

actual fun dataDirectory(context: PlatformContext): StorageFile = StorageFile(
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/MangaFlow"
).also { check(it.isDirectory || it.mkdirs()) }
actual fun cacheDirectory(context: PlatformContext): StorageFile = StorageFile(
    (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String) + "/MangaFlow"
).also { check(it.isDirectory || it.mkdirs()) }
actual fun availableSpace(file: StorageFile): Long =
    (NSFileManager.defaultManager.attributesOfFileSystemForPath(file.path, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
actual fun syncStorageFile(file: StorageFile) {
    val descriptor = open(file.path, O_RDWR)
    if (descriptor < 0) throw okio.IOException("无法同步本地文件。")
    try { if (fsync(descriptor) != 0) throw okio.IOException("本地文件尚未写入存储设备。") }
    finally { close(descriptor) }
}
actual fun preferences(context: PlatformContext, name: String): Preferences {
    require(Regex("[a-zA-Z0-9_.-]{1,120}").matches(name))
    return FilePreferences.get(StorageFile(StorageFile(dataDirectory(context), "preferences"), "$name.json"))
}

private fun string(value: String): CFStringRef = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)!!
private inline fun <T> withQuery(key: String, block: (CFMutableDictionaryRef) -> T): T {
    require(key.length in 1..120)
    val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
    val service = string("dev.mangaflow")
    val account = string(key)
    try {
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, service)
        CFDictionarySetValue(query, kSecAttrAccount, account)
        return block(query)
    } finally { CFRelease(query); CFRelease(service); CFRelease(account) }
}

actual fun readSecret(context: PlatformContext, key: String): String? = withQuery(key) { query ->
    CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
    CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
    memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        if (status == errSecItemNotFound) return@withQuery null
        check(status == errSecSuccess) { "系统钥匙串不可用（$status），请解锁设备后重试。" }
        val item = result.value ?: error("系统钥匙串没有返回凭据。")
        try {
            val data: CFDataRef = item.reinterpret()
            val length = CFDataGetLength(data).toInt()
            require(length in 0..1024 * 1024)
            if (length == 0) "" else CFDataGetBytePtr(data)!!.readBytes(length).decodeToString()
        } finally { CFRelease(item) }
    }
}

actual fun writeSecret(context: PlatformContext, key: String, value: String) = withQuery(key) { query ->
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 1024 * 1024)
    val data = bytes.usePinned { CFDataCreate(null, if (bytes.isEmpty()) null else it.addressOf(0).reinterpret(), bytes.size.toLong()) }!!
    val attributes = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
    try {
        CFDictionarySetValue(attributes, kSecValueData, data)
        CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        var status = SecItemUpdate(query, attributes)
        if (status == errSecItemNotFound) {
            CFDictionarySetValue(query, kSecValueData, data)
            CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            status = SecItemAdd(query, null)
        }
        check(status == errSecSuccess) { "无法安全保存凭据（$status）。" }
    } finally { CFRelease(attributes); CFRelease(data) }
}

actual fun deleteSecret(context: PlatformContext, key: String) = withQuery(key) { query ->
    val status = SecItemDelete(query)
    check(status == errSecSuccess || status == errSecItemNotFound) { "无法删除系统钥匙串凭据（$status）。" }
}
