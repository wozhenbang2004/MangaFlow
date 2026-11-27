package dev.mangaflow.sync

import dev.mangaflow.APP_DISPLAY_NAME
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal data class LanLocalAddress(
    val address: Inet4Address, val prefix: Int, val interfaceName: String,
    val bind: (Socket) -> Unit,
) {
    fun contains(destination: Inet4Address): Boolean {
        if (prefix !in 1..32 || destination.isAnyLocalAddress || destination.isLoopbackAddress || destination.isMulticastAddress)
            return false
        val source = address.address
        val target = destination.address
        var remaining = prefix
        for (index in source.indices) {
            if (remaining <= 0) return true
            val bits = minOf(8, remaining)
            val mask = (255 shl (8 - bits)) and 255
            if ((source[index].toInt() and mask) != (target[index].toInt() and mask)) return false
            remaining -= bits
        }
        return true
    }
}

internal data class LanReadingEndpoint(val id: String, val name: String, val address: Inet4Address, val port: Int) {
    val key: String get() = "$id@${address.hostAddress}:$port"
}

internal interface LanServiceDiscovery : AutoCloseable {
    fun start(port: Int, id: String, name: String, found: (LanReadingEndpoint) -> Unit, lost: (String) -> Unit,
        failed: (String) -> Unit)
}

/** TLS is provided by JSSE/Android; OkHttp TLS supplies the persisted self-signed device identity. */
internal class JvmLanReadingTransport(
    private val context: PlatformContext,
    private val owner: CoroutineScope,
    private val listener: LanReadingTransportListener,
    deviceName: String,
    private val localAddresses: () -> List<LanLocalAddress>,
    private val newDiscovery: () -> LanServiceDiscovery,
) : LanReadingTransport {
    override val supported = true
    private val name = cleanName(deviceName)
    private val disk = preferences(context, "mangaflow_lan_reading_peers")
    private val initialization = Mutex()
    private val trustChanges = Mutex()
    private val guard = Any()
    private val random = SecureRandom()
    @Volatile private var certificate: HeldCertificate? = null
    @Volatile private var ownId = ""
    @Volatile private var trusted: Map<String, String> = emptyMap()
    @Volatile private var runtime: CoroutineScope? = null
    @Volatile private var generation = 0L
    @Volatile private var pairingUntil = 0L
    @Volatile private var discoveryFailed = false
    private var discovery: LanServiceDiscovery? = null
    private val sockets = mutableMapOf<Closeable, String?>()
    private val endpoints = linkedMapOf<String, LanReadingEndpoint>()
    private val outgoing = mutableSetOf<String>()
    private val retries = mutableMapOf<String, Pair<Int, Long>>()
    private var pending: PendingPairing? = null

    override suspend fun identity(): String = initialization.withLock {
        if (certificate == null) {
            val saved = readSecret(context, "lan-reading-identity-v1")
            val held = if (saved == null) {
                val now = System.currentTimeMillis()
                HeldCertificate.Builder().commonName("MangaFlow LAN")
                    .validityInterval(now - 86_400_000L, now + 10L * 365 * 86_400_000L).ecdsa256().build().also {
                        writeSecret(context, "lan-reading-identity-v1", it.certificatePem() + it.privateKeyPkcs8Pem())
                    }
            } else HeldCertificate.decode(saved)
            held.certificate.checkValidity()
            val encodedPeers = disk.getString("peers", "[]") ?: "[]"
            val rows = JSONArray(encodedPeers)
            val peers = linkedMapOf<String, String>()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                require(ID.matches(id) && id !in peers) { "已配对设备的记录损坏，请检查本机存储。" }
                peers[id] = cleanName(row.getString("name"))
            }
            ownId = fingerprint(held.certificate)
            trusted = peers - ownId
            certificate = held
            publishDevices()
        }
        ownId
    }

    override suspend fun start() {
        identity()
        if (runtime != null) return
        val scope = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]) + Dispatchers.IO)
        val ticket = synchronized(guard) { generation++; runtime = scope; generation }
        try {
            val server = sslContext { id -> isTrusted(id) || pairingAllowed() }.serverSocketFactory
                .createServerSocket() as SSLServerSocket
            own(server, null, ticket)
            server.reuseAddress = true
            server.needClientAuth = true
            server.enabledProtocols = server.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            server.soTimeout = 1000
            startDiscovery(server.localPort, ticket)
            val admission = Semaphore(4)
            scope.launch {
                while (isActive && active(ticket)) {
                    val socket = try { server.accept() as SSLSocket }
                    catch (_: SocketTimeoutException) { continue }
                    catch (failure: Exception) {
                        if (active(ticket)) listener.activity(null, false, "局域网连接已中断，重新启用同步后可恢复。")
                        break
                    }
                    if (!onPhysicalLan(socket) || !admission.tryAcquire()) { socket.close(); continue }
                    if (!active(ticket)) { socket.close(); admission.release(); break }
                    own(socket, null, ticket)
                    scope.launch {
                        try { serve(socket, ticket) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            val peer = synchronized(guard) { sockets[socket] }
                            if (active(ticket) && peer != null && isTrusted(peer))
                                listener.activity(peer, false, readableFailure(failure))
                        } finally { release(socket) }
                    }.invokeOnCompletion { release(socket); admission.release() }
                }
            }
            scope.launch {
                var network = networkKey()
                while (isActive && active(ticket)) {
                    delay(15_000L)
                    if (pairingUntil > 0L && !pairingAllowed()) closePairing()
                    val now = networkKey()
                    if (now != network || discoveryFailed) {
                        network = now
                        restartDiscovery(server.localPort, ticket)
                    }
                    synchronize()
                }
            }
        } catch (failure: Exception) { stop(); throw failure }
    }

    override fun stop() {
        val closing = synchronized(guard) {
            generation++
            runtime?.cancel()
            runtime = null
            pending?.approval?.complete(false)
            pending = null
            pairingUntil = 0L
            endpoints.clear()
            outgoing.clear()
            retries.clear()
            sockets.keys.toList().also { sockets.clear() }
        }
        discovery?.let { runCatching { it.close() } }
        discovery = null
        closing.forEach { runCatching { it.close() } }
        listener.pairing(null)
        listener.pairingOpen(false)
        publishDevices()
    }

    override fun openPairing() {
        if (runtime == null) return
        pairingUntil = System.currentTimeMillis() + PAIRING_MILLIS
        listener.pairingOpen(true)
    }

    override fun closePairing() {
        pairingUntil = 0L
        val cancelled = synchronized(guard) { pending?.also { it.approval.complete(false) } }
        cancelled?.let { runCatching { it.socket.close() } }
        listener.pairingOpen(false)
    }

    override fun pair(deviceId: String) {
        val endpoint = endpoint(deviceId) ?: return listener.activity(deviceId, false, "设备已离开当前局域网。")
        if (isTrusted(deviceId)) { queueSync(endpoint, force = true); return }
        openPairing()
        launchConnection(endpoint, pairing = true, force = true)
    }

    override fun confirm(session: String) {
        synchronized(guard) { pending?.takeIf { it.session == session }?.let { pairing ->
            pairing.approval.complete(true)
            listener.pairing(pairing.view.copy(confirmedHere = true))
        } }
    }

    override fun reject(session: String) {
        val cancelled = synchronized(guard) {
            pending?.takeIf { it.session == session }?.also { it.approval.complete(false) }
        }
        cancelled?.let { runCatching { it.socket.close() } }
    }

    override fun synchronize(force: Boolean) {
        if (runtime == null) return
        val discovered = synchronized(guard) { endpoints.values.map { it.id }.distinct() }
        discovered.filter(::isTrusted).forEach { id -> endpoint(id)?.let { queueSync(it, force = force) } }
    }

    override suspend fun forget(deviceId: String) = trustChanges.withLock {
        val next = trusted - deviceId
        persistPeers(next)
        trusted = next
        val closing = synchronized(guard) { sockets.filterValues { it == deviceId }.keys.toList() }
        closing.forEach { runCatching { it.close() } }
        publishDevices()
    }

    override fun isTrusted(deviceId: String): Boolean = deviceId in trusted

    private fun queueSync(endpoint: LanReadingEndpoint, force: Boolean) {
        if (isTrusted(endpoint.id)) launchConnection(endpoint, pairing = false, force = force)
    }

    private fun launchConnection(endpoint: LanReadingEndpoint, pairing: Boolean, force: Boolean) {
        val scope = runtime ?: return
        val ticket = generation
        val admitted = synchronized(guard) {
            if (!active(ticket) || endpoint.id in outgoing || !force && (retries[endpoint.id]?.second ?: 0L) > System.currentTimeMillis()) false
            else { outgoing += endpoint.id; true }
        }
        if (!admitted) return
        listener.activity(endpoint.id, true)
        scope.launch {
            try {
                connect(endpoint, pairing, ticket)
                synchronized(guard) { if (active(ticket)) retries.remove(endpoint.id) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (active(ticket)) {
                    synchronized(guard) {
                        val count = ((retries[endpoint.id]?.first ?: 0) + 1).coerceAtMost(6)
                        retries[endpoint.id] = count to (System.currentTimeMillis() + minOf(60_000L, 1000L shl count))
                    }
                    listener.activity(endpoint.id, false, readableFailure(failure))
                }
            } finally {
                synchronized(guard) { if (active(ticket)) outgoing.remove(endpoint.id) }
                if (active(ticket)) listener.activity(endpoint.id, synchronized(guard) { outgoing.isNotEmpty() })
            }
        }
    }

    private suspend fun connect(endpoint: LanReadingEndpoint, pairing: Boolean, ticket: Long) {
        check(active(ticket)) { "局域网同步已暂停。" }
        val physical = route(endpoint.address) ?: error("该设备已不在当前可达的物理局域网。")
        val raw = own(Socket(), endpoint.id, ticket)
        var secure: SSLSocket? = null
        try {
            physical.bind(raw)
            raw.connect(InetSocketAddress(endpoint.address, endpoint.port), 8_000)
            raw.soTimeout = 15_000
            secure = sslContext { id -> id == endpoint.id && (pairing && pairingAllowed() || isTrusted(id)) }
                .socketFactory.createSocket(raw, endpoint.address.hostAddress, endpoint.port, true) as SSLSocket
            own(secure, endpoint.id, ticket)
            secure.useClientMode = true
            secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            secure.soTimeout = 15_000
            secure.startHandshake()
            check(peerId(secure) == endpoint.id && active(ticket) && onPhysicalLan(secure)) { "对端设备身份或局域网已改变。" }
            if (pairing) {
                val nonce = nonce()
                send(secure, JSONObject().put("op", "pair").put("version", 1).put("name", name).put("nonce", nonce).toString())
                val answer = JSONObject(receive(secure, HELLO_BYTES))
                check(answer.getString("op") == "pair-hello" && answer.getInt("version") == 1)
                finishPairing(secure, endpoint.id, cleanName(answer.getString("name")), nonce,
                    checkedNonce(answer.getString("nonce")), client = true, ticket = ticket)
            } else {
                check(isTrusted(endpoint.id)) { "此设备的配对已移除。" }
                send(secure, JSONObject().put("op", "sync").put("version", 1).toString())
                val document = listener.document(endpoint.id)
                check(active(ticket) && isTrusted(endpoint.id) && onPhysicalLan(secure) && document.stillAllowed())
                send(secure, document.payload)
                val response = receive(secure, LanReadingCodec.MAX_BYTES)
                check(active(ticket) && isTrusted(endpoint.id) && onPhysicalLan(secure))
                listener.merge(endpoint.id, response)
                check(active(ticket) && isTrusted(endpoint.id) && onPhysicalLan(secure))
                send(secure, JSONObject().put("op", "ack").toString())
                listener.synchronized(endpoint.id)
            }
        } finally { secure?.let(::release); release(raw) }
    }

    private suspend fun serve(socket: SSLSocket, ticket: Long) {
        socket.soTimeout = 15_000
        socket.useClientMode = false
        socket.needClientAuth = true
        socket.startHandshake()
        val id = peerId(socket)
        synchronized(guard) { if (sockets.containsKey(socket)) sockets[socket] = id }
        check(active(ticket) && onPhysicalLan(socket))
        val hello = JSONObject(receive(socket, HELLO_BYTES))
        require(hello.getInt("version") == 1) { "两台设备上的 $APP_DISPLAY_NAME 使用了不同的局域网协议版本。" }
        when (hello.getString("op")) {
            "pair" -> {
                check(pairingAllowed()) { "请先在另一台设备打开添加设备。" }
                val clientNonce = checkedNonce(hello.getString("nonce"))
                val serverNonce = nonce()
                send(socket, JSONObject().put("op", "pair-hello").put("version", 1).put("name", name)
                    .put("nonce", serverNonce).toString())
                finishPairing(socket, id, cleanName(hello.getString("name")), clientNonce, serverNonce,
                    client = false, ticket = ticket)
            }
            "sync" -> {
                check(isTrusted(id)) { "请先在两台设备确认配对。" }
                val document = receive(socket, LanReadingCodec.MAX_BYTES)
                check(active(ticket) && isTrusted(id) && onPhysicalLan(socket))
                val response = listener.merge(id, document)
                check(active(ticket) && isTrusted(id) && onPhysicalLan(socket) && response.stillAllowed())
                send(socket, response.payload)
                check(JSONObject(receive(socket, HELLO_BYTES)).getString("op") == "ack")
                listener.synchronized(id)
            }
            else -> error("不支持的局域网请求。")
        }
    }

    private suspend fun finishPairing(socket: SSLSocket, id: String, remoteName: String, clientNonce: String,
        serverNonce: String, client: Boolean, ticket: Long) {
        val clientId = if (client) ownId else id
        val serverId = if (client) id else ownId
        val code = digest("mangaflow-lan-pair-v1\n$clientId\n$serverId\n$clientNonce\n$serverNonce".toByteArray())
            .take(32).uppercase().chunked(4).joinToString(" ")
        val request = PendingPairing(UUID.randomUUID().toString(), remoteName, code, socket)
        synchronized(guard) {
            check(pending == null && pairingAllowed()) { "已有配对请求，请先处理当前请求。" }
            pending = request
        }
        listener.pairing(request.view)
        try {
            socket.soTimeout = PAIRING_MILLIS.toInt()
            val approved = withTimeout(PAIRING_MILLIS) { request.approval.await() }
            send(socket, JSONObject().put("op", "pair-confirm").put("approved", approved).toString())
            check(approved && active(ticket)) { "配对已取消。" }
            val confirmation = JSONObject(receive(socket, HELLO_BYTES))
            check(confirmation.getString("op") == "pair-confirm" && confirmation.getBoolean("approved")) { "另一台设备取消了配对。" }
            check(active(ticket) && onPhysicalLan(socket)) { "局域网已变化，请重新配对。" }
            trustChanges.withLock {
                check(active(ticket) && pairingAllowed() && !socket.isClosed &&
                    synchronized(guard) { pending === request }) { "配对已取消。" }
                val next = trusted + (id to remoteName)
                persistPeers(next)
                trusted = next
            }
            send(socket, JSONObject().put("op", "pair-ready").toString())
            check(JSONObject(receive(socket, HELLO_BYTES)).getString("op") == "pair-ready")
            pairingUntil = 0L
            listener.pairingOpen(false)
            publishDevices()
            runtime?.launch { delay(250L); endpoint(id)?.let { queueSync(it, force = true) } }
        } finally {
            val removed = synchronized(guard) { if (pending === request) { pending = null; true } else false }
            if (removed) listener.pairing(null)
        }
    }

    private fun sslContext(accept: (String) -> Boolean): SSLContext {
        val held = certificate ?: error("设备身份尚未准备好。")
        val keyManager = HandshakeCertificates.Builder().heldCertificate(held).build().keyManager
        val pins = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = checkChain(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = checkChain(chain)
            private fun checkChain(chain: Array<out X509Certificate>?) {
                if (chain == null || chain.size != 1) throw CertificateException("设备证书格式不正确。")
                val leaf = chain[0]
                leaf.checkValidity()
                leaf.verify(leaf.publicKey)
                if (!accept(fingerprint(leaf))) throw CertificateException("此设备尚未配对，或设备证书已改变。")
            }
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf<TrustManager>(pins), random) }
    }

    private fun peerId(socket: SSLSocket) = fingerprint(socket.session.peerCertificates.single() as X509Certificate)
    private fun active(ticket: Long) = runtime != null && generation == ticket
    private fun pairingAllowed() = runtime != null && System.currentTimeMillis() < pairingUntil
    private fun route(address: Inet4Address) = localAddresses().firstOrNull { it.contains(address) }
    private fun onPhysicalLan(socket: Socket): Boolean {
        val remote = socket.inetAddress as? Inet4Address ?: return false
        return localAddresses().any { it.address == socket.localAddress && it.contains(remote) }
    }
    private fun networkKey() = localAddresses().map { "${it.interfaceName}:${it.address.hostAddress}/${it.prefix}" }.sorted().joinToString("|")
    private fun endpoint(id: String) = synchronized(guard) { endpoints.values.firstOrNull { it.id == id && route(it.address) != null } }

    private fun startDiscovery(port: Int, ticket: Long) {
        val next = newDiscovery()
        synchronized(guard) {
            if (!active(ticket)) { next.close(); return }
            discovery = next
            discoveryFailed = false
        }
        next.start(port, ownId, name, found = { endpoint ->
            if (active(ticket) && endpoint.id != ownId && ID.matches(endpoint.id) && endpoint.port in 1..65535 && route(endpoint.address) != null) {
                synchronized(guard) {
                    if (active(ticket) && (endpoint.key in endpoints || endpoints.size < 128))
                        endpoints[endpoint.key] = endpoint.copy(name = cleanName(endpoint.name))
                }
                publishDevices()
                if (isTrusted(endpoint.id)) queueSync(endpoint, force = false)
            }
        }, lost = { key ->
            if (active(ticket)) { synchronized(guard) { endpoints.remove(key) }; publishDevices() }
        }, failed = { error -> if (active(ticket)) {
            discoveryFailed = true
            listener.activity(null, false, error)
        } })
        if (!active(ticket)) next.close()
    }

    private fun restartDiscovery(port: Int, ticket: Long) {
        discovery?.let { runCatching { it.close() } }
        synchronized(guard) { endpoints.clear(); retries.clear(); pending?.approval?.complete(false) }
        publishDevices()
        if (active(ticket)) runCatching { startDiscovery(port, ticket) }
            .onFailure {
                discoveryFailed = true
                listener.activity(null, false, "当前网络无法发现设备，将在网络恢复后重试。")
            }
    }

    private fun publishDevices() {
        val found = synchronized(guard) { endpoints.values.groupBy { it.id } }
        val devices = (trusted.keys + found.keys).map { id ->
            LanReadingDevice(id, trusted[id] ?: found.getValue(id).first().name, found[id]?.any { route(it.address) != null } == true,
                id in trusted)
        }.sortedWith(compareByDescending<LanReadingDevice> { it.trusted }.thenByDescending { it.online }.thenBy { it.name })
        listener.devices(devices)
    }

    private fun persistPeers(peers: Map<String, String>) {
        val json = JSONArray(peers.entries.sortedBy { it.key }.map { JSONObject().put("id", it.key).put("name", it.value) }).toString()
        check(disk.edit().putString("peers", json).commit()) { "无法保存设备配对，请检查存储空间。" }
    }

    private fun <T : Closeable> own(socket: T, id: String?, ticket: Long): T {
        val accepted = synchronized(guard) {
            if (active(ticket)) { sockets[socket] = id; true } else false
        }
        if (!accepted) { runCatching { socket.close() }; error("局域网同步已暂停。") }
        return socket
    }
    private fun release(socket: Closeable) { synchronized(guard) { sockets.remove(socket) }; runCatching { socket.close() } }
    private fun nonce() = ByteArray(32).also(random::nextBytes).let { Base64.getEncoder().encodeToString(it) }
    private fun checkedNonce(value: String): String {
        require(value.length == 44 && Base64.getDecoder().decode(value).size == 32) { "配对挑战无效。" }
        return value
    }

    private fun send(socket: SSLSocket, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..LanReadingCodec.MAX_BYTES) { "阅读同步数据过大。" }
        DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
    }

    private fun receive(socket: SSLSocket, limit: Int): String {
        val input = DataInputStream(socket.inputStream)
        val size = input.readInt()
        require(size in 1..limit) { "局域网数据长度无效。" }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private class PendingPairing(val session: String, val name: String, val code: String, val socket: SSLSocket) {
        val approval = CompletableDeferred<Boolean>()
        val view get() = LanReadingPairing(session, name, code)
    }

    private companion object {
        val ID = Regex("[a-f0-9]{64}")
        const val HELLO_BYTES = 16 * 1024
        const val PAIRING_MILLIS = 120_000L
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun fingerprint(certificate: X509Certificate) = digest(certificate.encoded)
        fun cleanName(value: String) = value.filterNot(Char::isISOControl).trim().take(80).ifBlank { "$APP_DISPLAY_NAME 设备" }
        fun readableFailure(failure: Exception): String = when (failure) {
            is SocketTimeoutException -> "设备暂未响应。请在两台设备上保持 $APP_DISPLAY_NAME 打开，连接恢复后会自动重试。"
            is java.net.SocketException, is java.io.EOFException -> "局域网连接已断开，恢复后会自动重试。"
            is javax.net.ssl.SSLException -> "无法核验设备；首次连接请在两端打开添加设备并确认同一校验码。"
            else -> failure.message ?: "局域网同步暂未完成，将自动重试。"
        }
    }
}
