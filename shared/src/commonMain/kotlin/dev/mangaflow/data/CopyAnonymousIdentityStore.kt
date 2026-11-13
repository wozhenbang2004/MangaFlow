package dev.mangaflow.data

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.platform.storage.cacheDir

/** Installation-scoped public identity, separate from saved account credentials. */
object CopyAnonymousIdentityStore {
    private const val PREFERENCES = "copy_anonymous_identity"
    private const val DEVICE = "device"
    private const val DEVICE_INFO = "device_info"
    private const val PSEUDO_ID = "pseudo_id"
    private const val UM_STRING = "um_string"
    private var cached: CopyAnonymousIdentity? = null
    private var pendingSave: CopyAnonymousIdentity? = null

    private val identityLock = kotlinx.atomicfu.locks.SynchronizedObject()

    fun load(context: Context): CopyAnonymousIdentity = kotlinx.atomicfu.locks.synchronized(identityLock) {
        cached?.let { return@synchronized it }
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, 0)
        val values = try {
            listOf(DEVICE, DEVICE_INFO, PSEUDO_ID, UM_STRING).map { preferences.getString(it, null) }
        } catch (error: ClassCastException) {
            throw SourceException("保存的匿名来源标识无法读取，未更换身份。", cause = error)
        }
        val firstUse = values.all { it == null }
        val identity = pendingSave ?: if (firstUse) {
            CopyAnonymousIdentity.create()
        } else {
            if (values.any { it == null }) {
                throw SourceException("保存的匿名来源标识不完整，未更换身份。")
            }
            try {
                CopyAnonymousIdentity(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
            } catch (error: IllegalArgumentException) {
                throw SourceException("保存的匿名来源标识无效，未更换身份。", cause = error)
            }
        }
        if (firstUse || pendingSave != null) {
            // A failed commit can still update SharedPreferences' in-memory values.
            // Retain this identity and require a successful save before exposing it.
            pendingSave = identity
            if (!preferences.edit()
                .putString(DEVICE, identity.device)
                .putString(DEVICE_INFO, identity.deviceInfo)
                .putString(PSEUDO_ID, identity.pseudoId)
                .putString(UM_STRING, identity.umString)
                .commit()) {
                throw SourceException("设备无法保存来源设置，请检查存储空间后重试。")
            }
            pendingSave = null
        }
        cached = identity
        identity
    }
}
