package dev.mangaflow.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.CodingErrorAction
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Independent receiver for the observed Wallpaper Engine mobile wire format.
 * Protocol reference: MirageWallpaper e66f14b9, MobilePairingService.swift.
 * This is not a port of its server implementation or a scene renderer.
 * Legacy per-frame CBC/IV reuse is required by that protocol, not a new
 * cryptographic design. The caller pins the computer key before sending secrets.
 */
internal class WallpaperEngineConnection(private val temporaryDirectory: File) : AutoCloseable {
    private val guard = Any()
    private val sockets = mutableSetOf<Closeable>()
    @Volatile private var closed = false

    fun discover(onDevice: (WallpaperPcDevice) -> Unit) {
        val socket = own(MulticastSocket(null))
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(DISCOVERY_PORT))
            socket.soTimeout = 1000
            val group = InetAddress.getByAddress(byteArrayOf(239.toByte(), 100, 0, 1))
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (network in interfaces) {
                if (closed) return
                try {
                    if (network.isUp && !network.isLoopback && network.supportsMulticast()) {
                        socket.joinGroup(InetSocketAddress(group, DISCOVERY_PORT), network)
                    }
                } catch (_: IOException) {
                    // Directed/broadcast discovery can still work on this interface.
                } catch (_: SecurityException) {
                    // Other interfaces may remain available.
                }
            }
            val bytes = ByteArray(1024)
            val packet = DatagramPacket(bytes, bytes.size)
            val reported = mutableSetOf<String>()
            while (!closed && !Thread.currentThread().isInterrupted) {
                packet.length = bytes.size
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val address = packet.address as? Inet4Address ?: continue
                if (!isLocal(address)) continue
                val payload = decodeUtf8(bytes.copyOfRange(packet.offset, packet.offset + packet.length))
                    ?: continue
                val parts = payload.trim('\u0000', '\r', '\n').split(':')
                if (parts.size !in 4..5 || !parts[0].equals("WallpaperEngine/Ping", true)) continue
                val port = parts[1].takeIf { it.length in 1..5 && it.all(Char::isDigit) }
                    ?.toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
                val version = if (parts.size == 5) parts[3].toIntOrNull() ?: continue else 4
                if (version != 4) continue
                val identifier = parts.last().takeIf { it.isNotBlank() && it.length <= 128 } ?: continue
                val host = address.hostAddress ?: continue
                val id = identifier + "@" + host + ":" + port
                if (id in reported || reported.size >= 64) continue
                val name = visibleText(parts[2], 96).ifBlank { host }
                reported.add(id)
                onDevice(WallpaperPcDevice(identifier, name, host, port, version))
            }
        } catch (error: SocketException) {
            if (!closed) throw error
        } finally {
            release(socket)
        }
    }

    suspend fun receive(
        device: WallpaperPcDevice,
        pin: String,
        identity: String,
        deviceName: String,
        displayWidth: Int,
        displayHeight: Int,
        onConnected: () -> Unit,
        onProgress: (title: String, received: Long, total: Long) -> Unit,
        onPackage: suspend (file: File, title: String) -> Unit,
        onServerKey: (fingerprint: String) -> Unit = {}
    ): Unit = withContext(Dispatchers.IO) {
        require(pin.length == 4 && pin.all { it in '0'..'9' }) { "请输入电脑显示的四位配对码。" }
        require(device.port in 1..65535 && device.version == 4) { "不支持此 Wallpaper Engine 配对版本。" }
        val address = parseLocalAddress(device.address)
        val context = currentCoroutineContext()
        checkOpen(context)
        val socket = own(Socket())
        var ownedDirectory: File? = null
        var current: Upload? = null
        try {
            socket.connect(InetSocketAddress(address, device.port), AUTH_TIMEOUT)
            socket.tcpNoDelay = true
            socket.soTimeout = AUTH_TIMEOUT
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 4096)
            val exponent = BigInteger(1, readBytes(input, readWord(input, context, 1, 8), context))
            val modulus = BigInteger(1, readBytes(input, readWord(input, context, 128, 1024), context))
            if (exponent < BigInteger.valueOf(3) || !exponent.testBit(0) ||
                modulus.bitLength() !in 1024..8192 || !modulus.testBit(0)) {
                throw IOException("电脑返回的配对公钥无效。")
            }
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(publicKey.encoded).joinToString("") {
                    (it.toInt() and 255).toString(16).padStart(2, '0')
                }
            checkOpen(context)
            onServerKey(fingerprint)
            val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey,
                OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
            val identityBytes = identity.toByteArray(Charsets.UTF_8)
            val maximumIdentity = (modulus.bitLength() + 7) / 8 - 42
            require(identityBytes.isNotEmpty() && identityBytes.size <= maximumIdentity) { "配对设备身份无效。" }
            val random = SecureRandom()
            val iv = ByteArray(16).also(random::nextBytes)
            val key = ByteArray(32).also(random::nextBytes)
            for (plain in listOf(identityBytes, iv, key)) {
                checkOpen(context)
                writeFrame(output, rsa.doFinal(plain))
            }
            val session = Session(key, iv)
            val request = buildJsonObject {
                put("version", 4)
                put("pin", pin)
                put("name", visibleText(deviceName, 96).ifBlank { "MangaFlow" })
                put("deviceUserName", "漫流")
                put("displayWidth", displayWidth.coerceIn(128, 16384))
                put("displayHeight", displayHeight.coerceIn(128, 16384))
            }
            writeFrame(output, session.encrypt(request.toString().toByteArray(Charsets.UTF_8)))
            output.flush()
            val auth = readJson(input, socket, session, false, AUTH_TIMEOUT, context)
            when (auth.text("status")) {
                "AuthOK" -> Unit
                "AuthFailed" -> throw IOException("配对码不正确，请在电脑打开“连接新设备”后重试。")
                "AuthFailedVersion" -> throw IOException("Wallpaper Engine 的配对协议版本不兼容。")
                else -> throw IOException("电脑返回了无法识别的配对结果。")
            }
            checkOpen(context)
            onConnected()
            var announced: Description? = null
            while (true) {
                checkOpen(context)
                val command = readJson(input, socket, session, current == null && announced == null, TRANSFER_TIMEOUT, context)
                when (val operation = command.text("command")) {
                    "beginUpload" -> {
                        if (current != null || announced != null) throw IOException("电脑重复开始了壁纸传输。")
                        announced = description(command)
                    }
                    "transmissionStart" -> {
                        if (current != null) throw IOException("上一个壁纸尚未接收完成。")
                        val before = announced ?: throw IOException("缺少壁纸传输信息。")
                        val startSize = command.number("size") ?: before.size
                        val startFile = command.text("file") ?: before.remoteName
                        if (startSize != before.size || startFile != before.remoteName) {
                            throw IOException("电脑返回的壁纸传输信息不一致。")
                        }
                        checkOpen(context)
                        val directory = ownedDirectory ?: File(
                            temporaryDirectory, "wallpaper-pc-" + UUID.randomUUID().toString()
                        ).also {
                            if (!it.mkdirs() && !it.isDirectory) throw IOException("无法创建壁纸接收目录。")
                            ownedDirectory = it
                        }
                        val file = File(directory, UUID.randomUUID().toString() + ".bin")
                        val upload = Upload(before, file, FileOutputStream(file))
                        current = upload
                        onProgress(before.title, 0, before.size)
                    }
                    "transmissionContinue" -> {
                        val upload = current ?: throw IOException("电脑尚未开始传输壁纸。")
                        val count = command.number("size")
                            ?.takeIf { it in 1..MAX_CHUNK.toLong() && it <= upload.description.size - upload.received }
                            ?.toInt() ?: throw IOException("壁纸数据块大小无效。")
                        val encrypted = readFrame(input, socket, MAX_FRAME, false, TRANSFER_TIMEOUT, context)
                        val plain = session.decrypt(encrypted)
                        if (plain.size != count) throw IOException("壁纸数据块不完整。")
                        checkOpen(context)
                        upload.output.write(plain)
                        upload.received += plain.size
                        onProgress(upload.description.title, upload.received, upload.description.size)
                        if (upload.received == upload.description.size) {
                            upload.output.flush()
                            upload.output.fd.sync()
                            upload.output.close()
                            current = null
                            announced = null
                            checkOpen(context)
                            try {
                                onPackage(upload.file, upload.description.title)
                            } finally {
                                upload.file.delete()
                            }
                        }
                    }
                    else -> throw IOException("Wallpaper Engine 使用了暂不支持的传输指令：" +
                        visibleText(operation.orEmpty(), 48).ifBlank { "空指令" })
                }
            }
        } finally {
            try { current?.output?.close() } catch (_: IOException) { }
            release(socket)
            ownedDirectory?.deleteRecursively()
        }
    }

    override fun close() {
        val owned = synchronized(guard) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        for (resource in owned) {
            try { resource.close() } catch (_: IOException) { }
        }
    }

    private fun <T : Closeable> own(resource: T): T = synchronized(guard) {
        if (closed) {
            try { resource.close() } catch (_: IOException) { }
            throw CancellationException("壁纸连接已关闭。")
        }
        sockets.add(resource)
        resource
    }

    private fun release(resource: Closeable) {
        synchronized(guard) { sockets.remove(resource) }
        try { resource.close() } catch (_: IOException) { }
    }

    private fun checkOpen(context: CoroutineContext) {
        context.ensureActive()
        if (closed) throw CancellationException("壁纸连接已关闭。")
    }

    private fun description(json: JsonObject): Description {
        val size = json.number("size")?.takeIf { it in 1..MAX_PACKAGE }
            ?: throw IOException("壁纸文件大小无效或超过 512 MB。")
        val remoteName = json.text("file")?.takeIf { it.isNotBlank() && it.length <= 1024 }
            ?: throw IOException("电脑没有提供有效的壁纸文件信息。")
        val title = visibleText(json.text("title").orEmpty(), 256).ifBlank { "Wallpaper Engine 壁纸" }
        return Description(title, remoteName, size)
    }

    private fun readJson(
        input: InputStream,
        socket: Socket,
        session: Session,
        idle: Boolean,
        timeout: Int,
        context: CoroutineContext
    ): JsonObject {
        val plain = session.decrypt(readFrame(input, socket, MAX_COMMAND, idle, timeout, context))
        val text = decodeUtf8(plain) ?: throw IOException("电脑返回的传输指令编码无效。")
        return try {
            Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("电脑返回的传输指令无效。")
        } catch (error: IllegalArgumentException) {
            throw IOException("电脑返回的传输指令格式无效。", error)
        }
    }

    private fun readFrame(
        input: InputStream,
        socket: Socket,
        limit: Int,
        idle: Boolean,
        timeout: Int,
        context: CoroutineContext
    ): ByteArray {
        socket.soTimeout = if (idle) 1000 else timeout
        var first: Int
        while (true) {
            checkOpen(context)
            try {
                first = input.read()
                break
            } catch (error: SocketTimeoutException) {
                if (!idle) throw IOException("壁纸传输超时，请重新连接。", error)
            }
        }
        if (first < 0) throw EOFException("电脑已断开壁纸连接。")
        // Once a frame has started, a timeout is fatal: resuming at a new
        // header would misinterpret a partial payload as the next length.
        socket.soTimeout = timeout
        val rest = readBytes(input, 3, context)
        val length = first.toLong() or
            ((rest[0].toLong() and 255) shl 8) or
            ((rest[1].toLong() and 255) shl 16) or
            ((rest[2].toLong() and 255) shl 24)
        if (length <= 0 || length > limit || length % 16L != 0L) {
            throw IOException("电脑返回的加密数据帧大小无效。")
        }
        return readBytes(input, length.toInt(), context)
    }

    private fun readWord(input: InputStream, context: CoroutineContext, minimum: Int, maximum: Int): Int {
        val bytes = readBytes(input, 4, context)
        var value = 0L
        for (index in bytes.indices) value = value or ((bytes[index].toLong() and 255) shl (index * 8))
        if (value !in minimum.toLong()..maximum.toLong()) throw IOException("电脑返回的配对公钥大小无效。")
        return value.toInt()
    }

    private fun readBytes(input: InputStream, count: Int, context: CoroutineContext): ByteArray {
        val bytes = ByteArray(count)
        var position = 0
        while (position < count) {
            checkOpen(context)
            val read = input.read(bytes, position, count - position)
            if (read < 0) throw EOFException("电脑已断开壁纸连接。")
            if (read == 0) continue
            position += read
        }
        return bytes
    }

    private fun writeFrame(output: OutputStream, bytes: ByteArray) {
        val size = bytes.size
        repeat(4) { index -> output.write((size ushr (index * 8)) and 255) }
        output.write(bytes)
    }

    private class Session(key: ByteArray, iv: ByteArray) {
        private val secret = SecretKeySpec(key, "AES")
        private val vector = IvParameterSpec(iv)
        fun encrypt(plain: ByteArray): ByteArray = transform(plain, Cipher.ENCRYPT_MODE)
        fun decrypt(encrypted: ByteArray): ByteArray = transform(encrypted, Cipher.DECRYPT_MODE)
        private fun transform(bytes: ByteArray, mode: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(mode, secret, vector)
            return cipher.doFinal(bytes)
        }
    }

    private data class Description(val title: String, val remoteName: String, val size: Long)
    private class Upload(val description: Description, val file: File, val output: FileOutputStream) {
        var received = 0L
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        null
    }

    private fun visibleText(value: String, limit: Int): String =
        value.filterNot { Character.isISOControl(it) }.take(limit).trim()

    private fun parseLocalAddress(value: String): Inet4Address {
        val parts = value.split('.')
        require(parts.size == 4 && parts.all { part ->
            part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() in 0..255
        }) { "请输入电脑的局域网 IPv4 地址。" }
        val address = InetAddress.getByAddress(ByteArray(4) { parts[it].toInt().toByte() }) as Inet4Address
        require(isLocal(address)) { "只能连接本机或同一局域网内的电脑。" }
        return address
    }

    private fun isLocal(address: Inet4Address): Boolean =
        !address.isAnyLocalAddress && !address.isMulticastAddress &&
            (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress)

    private companion object {
        const val DISCOVERY_PORT = 7884
        const val AUTH_TIMEOUT = 10_000
        const val TRANSFER_TIMEOUT = 30_000
        const val MAX_COMMAND = 4096
        const val MAX_CHUNK = 1024 * 1024
        const val MAX_FRAME = MAX_CHUNK + 16
        const val MAX_PACKAGE = 512L * 1024 * 1024
    }
}
