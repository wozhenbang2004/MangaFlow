@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.anime.player

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.*

internal actual class AnimeLoopbackServer actual constructor(
    private val scope: CoroutineScope,
    private val handler: suspend (AnimeLoopbackConnection) -> Unit,
) {
    private val closed = atomic(false)
    private val lock = SynchronizedObject()
    private val connections = mutableSetOf<AppleConnection>()
    private val descriptor: Int = socket(AF_INET, SOCK_STREAM, 0).also { require(it >= 0) { "无法建立本机播放连接" } }
    actual val port: Int

    init {
        try {
            port = memScoped {
                val address = alloc<sockaddr_in>()
                memset(address.ptr, 0, sizeOf<sockaddr_in>().convert())
                address.sin_len = sizeOf<sockaddr_in>().convert()
                address.sin_family = AF_INET.convert()
                address.sin_port = 0u
                address.sin_addr.s_addr = inet_addr("127.0.0.1")
                require(bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) {
                    "无法绑定本机播放连接"
                }
                require(listen(descriptor, 16) == 0) { "无法启动本机播放连接" }
                val length = alloc<socklen_tVar>()
                length.value = sizeOf<sockaddr_in>().convert()
                require(getsockname(descriptor, address.ptr.reinterpret(), length.ptr) == 0)
                ntohs(address.sin_port).toInt()
            }
        } catch (failure: Throwable) {
            platform.posix.close(descriptor)
            throw failure
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
        scope.launch(Dispatchers.IO) {
            while (isActive && !closed.value) {
                val accepted = accept(descriptor, null, null)
                if (accepted < 0) {
                    if (closed.value) break
                    if (errno == EINTR) continue
                    error("本机播放连接已中断")
                }
                val connection = AppleConnection(accepted)
                val admitted = synchronized(lock) {
                    if (closed.value) false else { connections.add(connection); true }
                }
                if (!admitted) { connection.close(); break }
                launch {
                    try { handler(connection) }
                    finally { connection.close(); synchronized(lock) { connections.remove(connection) } }
                }
            }
        }
    }

    actual fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        shutdown(descriptor, SHUT_RDWR)
        platform.posix.close(descriptor)
        val active = synchronized(lock) { connections.toList().also { connections.clear() } }
        active.forEach { it.close() }
    }
}

private class AppleConnection(private val descriptor: Int) : AnimeLoopbackConnection {
    private val closed = atomic(false)
    init {
        memScoped {
            val enabled = alloc<IntVar>(); enabled.value = 1
            setsockopt(descriptor, SOL_SOCKET, SO_NOSIGPIPE, enabled.ptr, sizeOf<IntVar>().convert())
            val timeout = alloc<timeval>(); timeout.tv_sec = 30; timeout.tv_usec = 0
            setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            setsockopt(descriptor, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        }
    }
    override fun read(buffer: ByteArray): Int = buffer.usePinned { pinned ->
        while (true) {
            val result = recv(descriptor, pinned.addressOf(0), buffer.size.convert(), 0).toInt()
            if (result < 0 && errno == EINTR) continue
            return@usePinned result
        }
        @Suppress("UNREACHABLE_CODE")
        -1
    }
    override fun write(buffer: ByteArray, offset: Int, count: Int) {
        buffer.usePinned { pinned ->
            var written = 0
            while (written < count) {
                val result = send(descriptor, pinned.addressOf(offset + written), (count - written).convert(), 0).toInt()
                if (result < 0 && errno == EINTR) continue
                require(result > 0) { "本机播放连接已关闭" }
                written += result
            }
        }
    }
    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            shutdown(descriptor, SHUT_RDWR)
            platform.posix.close(descriptor)
        }
    }
}
