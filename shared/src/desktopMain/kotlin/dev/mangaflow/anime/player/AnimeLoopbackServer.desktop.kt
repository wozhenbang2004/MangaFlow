package dev.mangaflow.anime.player

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal actual class AnimeLoopbackServer actual constructor(
    private val scope: CoroutineScope,
    private val handler: suspend (AnimeLoopbackConnection) -> Unit,
) {
    private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val connections = Collections.synchronizedSet(mutableSetOf<DesktopConnection>())
    actual val port: Int = listener.localPort

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
        scope.launch(Dispatchers.IO) {
            while (isActive && !closed.get()) {
                val socket = try { listener.accept() } catch (failure: SocketException) {
                    if (closed.get()) break else throw failure
                }
                socket.soTimeout = 30_000
                socket.tcpNoDelay = true
                val connection = DesktopConnection(socket)
                val admitted = synchronized(connections) {
                    if (closed.get()) false else { connections.add(connection); true }
                }
                if (!admitted) { connection.close(); break }
                launch {
                    try { handler(connection) }
                    finally { connection.close(); connections.remove(connection) }
                }
            }
        }
    }

    actual fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { listener.close() }
        val active = synchronized(connections) { connections.toList() }
        active.forEach { it.close() }
        connections.clear()
    }
}

private class DesktopConnection(private val socket: Socket) : AnimeLoopbackConnection {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val closed = AtomicBoolean(false)
    override fun read(buffer: ByteArray): Int = input.read(buffer)
    override fun write(buffer: ByteArray, offset: Int, count: Int) { output.write(buffer, offset, count) }
    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { socket.close() }
    }
}
