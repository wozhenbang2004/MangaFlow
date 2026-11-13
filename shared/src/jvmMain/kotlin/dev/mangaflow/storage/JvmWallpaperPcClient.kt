package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.storage.deleteSecret
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One foreground pairing panel owns its discovery, connection and serial imports. */
internal class JvmWallpaperPcClient(
    context: PlatformContext,
    private val deviceName: String,
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val acquireDiscovery: () -> (() -> Unit) = { {} },
) : WallpaperPcClient {
    private val app = applicationContext(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val generation = AtomicLong()
    private val scanGeneration = AtomicLong()
    private val _state = MutableStateFlow(WallpaperPcState())
    override val state = _state.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var scan: WallpaperEngineConnection? = null
    @Volatile private var connection: WallpaperEngineConnection? = null
    @Volatile private var currentDevice: WallpaperPcDevice? = null
    @Volatile private var pairedIds: Set<String> = emptySet()
    private var scanJob: Job? = null
    private var scanTimeout: Job? = null
    private var connectionJob: Job? = null

    override fun discover() {
        if (closed || _state.value.connecting || _state.value.connectedName != null) return
        stopDiscovery()
        val token = scanGeneration.incrementAndGet()
        _state.update { it.copy(discovering = true, devices = emptyList(), error = null, message = null) }
        val transport = WallpaperEngineConnection(File(cacheDirectory(app).path, "wallpaper-pc"))
        scan = transport
        scanJob = scope.launch {
            var release: (() -> Unit)? = null
            try {
                pairedIds = preferences(app, PREFS).getStringSet("paired", emptySet()).orEmpty()
                release = acquireDiscovery()
                if (closed || scanGeneration.get() != token) return@launch
                transport.discover { found ->
                    if (!closed && scanGeneration.get() == token) _state.update { previous ->
                        val device = found.copy(paired = found.id in pairedIds)
                        previous.copy(devices = (previous.devices.filterNot { it.id == device.id } + device)
                            .take(64).sortedBy { it.name.lowercase() })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                if (scanGeneration.get() == token) _state.update {
                    it.copy(error = "局域网权限不可用，请在系统设置允许附近设备后重试。")
                }
            } catch (_: Exception) {
                if (scanGeneration.get() == token) _state.update {
                    it.copy(error = "未能搜索电脑，可刷新或手动输入局域网地址。")
                }
            } finally {
                transport.close()
                runCatching { release?.invoke() }
                if (!closed && scanGeneration.get() == token) _state.update {
                    it.copy(discovering = false)
                }
            }
        }
        scanTimeout = scope.launch {
            delay(15_000)
            if (scanGeneration.get() == token) transport.close()
        }
    }

    override fun connect(device: WallpaperPcDevice, pin: String) {
        if (closed || _state.value.connecting || _state.value.connectedName != null) return
        if (pin.isNotEmpty() && (pin.length != 4 || pin.any { it !in '0'..'9' })) {
            _state.update { it.copy(error = "请输入电脑显示的四位配对码。") }
            return
        }
        stopDiscovery()
        val token = generation.incrementAndGet()
        connection?.close()
        connectionJob?.cancel()
        currentDevice = device
        val transport = WallpaperEngineConnection(File(cacheDirectory(app).path, "wallpaper-pc"))
        connection = transport
        _state.update { it.copy(connecting = true, connectedName = null, discovering = false,
            transferName = null, receivedBytes = 0, totalBytes = 0, error = null, message = null) }
        connectionJob = scope.launch {
            try {
                val disk = preferences(app, PREFS)
                val saved = if (pin.isEmpty()) savedPairing(device) else null
                val actualPin = if (pin.isEmpty()) saved?.text("pin").orEmpty() else pin
                require(actualPin.length == 4 && actualPin.all { it in '0'..'9' }) {
                    "配对信息已失效，请在电脑打开“连接新设备”后重新配对。"
                }
                val identity = saved?.text("identity")
                    ?: disk.getString("identity", null)?.takeIf { it.length in 1..80 }
                    ?: randomUuid().also { check(disk.edit().putString("identity", it).commit()) {
                        "未能保存配对设备身份，请检查剩余空间。"
                    } }
                var serverKey: String? = null
                transport.receive(device, actualPin, identity, deviceName, displayWidth, displayHeight,
                    onConnected = {
                        requireCurrentConnection(token)
                        var savedMessage: String? = null
                        try {
                            val record = buildJsonObject {
                                put("pin", actualPin)
                                put("identity", identity)
                                put("server_key", checkNotNull(serverKey))
                            }
                            writeSecret(app, credentialKey(device), record.toString())
                            pairedIds = (disk.getStringSet("paired", emptySet()).orEmpty() + device.id)
                            check(disk.edit().putStringSet("paired", pairedIds).commit())
                        } catch (_: Exception) {
                            savedMessage = "已连接；本次配对未能保存，下次需重新输入配对码。"
                        }
                        update(token) { it.copy(connecting = false, connectedName = device.name,
                            devices = it.devices.map { found ->
                                if (found.id == device.id) found.copy(paired = device.id in pairedIds) else found
                            }, message = savedMessage, error = null) }
                    },
                    onProgress = { title, received, total ->
                        update(token) { it.copy(transferName = title, receivedBytes = received,
                            totalBytes = total, message = null, error = null) }
                    },
                    onPackage = { source, title ->
                        currentCoroutineContext().ensureActive()
                        requireCurrentConnection(token)
                        update(token) { it.copy(message = "正在加入壁纸库…") }
                        var media: File? = null
                        try {
                            val extracted = WallpaperEnginePackage.extractMedia(source, source.parentFile!!, title)
                            media = extracted
                            currentCoroutineContext().ensureActive()
                            requireCurrentConnection(token)
                            WallpaperStore.get(app).importMedia(extracted.toURI().toString(), displayName = title)
                            update(token) { it.copy(importedCount = it.importedCount + 1,
                                transferName = null, receivedBytes = 0, totalBytes = 0,
                                message = "已加入壁纸库", error = null) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            update(token) { it.copy(transferName = null, receivedBytes = 0, totalBytes = 0,
                                message = null, error = failure.message ?: "这份壁纸未能加入，请重新发送图片或视频。") }
                        } finally {
                            media?.delete()
                        }
                    },
                    onServerKey = { fingerprint ->
                        requireCurrentConnection(token)
                        if (saved != null) require(saved.text("server_key") == fingerprint) {
                            "电脑的配对身份已改变，请重新输入电脑显示的配对码。"
                        }
                        serverKey = fingerprint
                    })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                update(token) { it.copy(error = "局域网权限不可用，请允许附近设备后重试。") }
            } catch (failure: Exception) {
                update(token) { it.copy(error = failure.message ?: "电脑连接已断开，请重新连接。") }
            } finally {
                transport.close()
                update(token) { it.copy(connecting = false, connectedName = null,
                    transferName = null, receivedBytes = 0, totalBytes = 0) }
            }
        }
    }

    override fun forget(device: WallpaperPcDevice) {
        if (closed) return
        if (currentDevice?.id == device.id) disconnect()
        scope.launch {
            try {
                deleteSecret(app, credentialKey(device))
                val disk = preferences(app, PREFS)
                pairedIds = disk.getStringSet("paired", emptySet()).orEmpty() - device.id
                check(disk.edit().putStringSet("paired", pairedIds).commit())
                _state.update { it.copy(devices = it.devices.map { found ->
                    if (found.id == device.id) found.copy(paired = false) else found
                }, message = "已忘记配对", error = null) }
            } catch (_: Exception) {
                _state.update { it.copy(error = "配对信息未能移除，请重试。") }
            }
        }
    }

    override fun disconnect() {
        generation.incrementAndGet()
        stopDiscovery()
        connection?.close()
        connection = null
        connectionJob?.cancel()
        connectionJob = null
        currentDevice = null
        _state.update { it.copy(discovering = false, connecting = false, connectedName = null,
            transferName = null, receivedBytes = 0, totalBytes = 0, message = null) }
    }

    override fun close() {
        if (closed) return
        closed = true
        disconnect()
        scope.cancel()
    }

    private fun stopDiscovery() {
        scanGeneration.incrementAndGet()
        scan?.close()
        scan = null
        scanJob?.cancel()
        scanTimeout?.cancel()
        scanJob = null
        scanTimeout = null
    }

    private fun update(token: Long, transform: (WallpaperPcState) -> WallpaperPcState) {
        _state.update { if (!closed && generation.get() == token) transform(it) else it }
    }

    private fun requireCurrentConnection(token: Long) {
        if (closed || generation.get() != token) throw CancellationException("Wallpaper pairing changed")
    }

    private fun savedPairing(device: WallpaperPcDevice): JsonObject? = try {
        readSecret(app, credentialKey(device))?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (_: Exception) {
        error("已保存的配对信息不可用，请重新配对。")
    }

    private fun credentialKey(device: WallpaperPcDevice): String =
        "wallpaper.pc." + sha256(device.id.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object { private const val PREFS = "mangaflow_wallpaper_pc" }
}
