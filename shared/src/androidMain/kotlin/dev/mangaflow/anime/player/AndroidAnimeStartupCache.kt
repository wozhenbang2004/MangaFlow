@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong

/** A real decoder output surface, kept off-screen until the exact prepared player is adopted. */
internal class AndroidAnimeStartupSurface : AutoCloseable {
    private val texture = SurfaceTexture(false).apply { setDefaultBufferSize(WIDTH, HEIGHT) }
    private val surface = Surface(texture)
    private var closed = false

    fun attach(player: ExoPlayer) {
        player.setVideoSurface(surface)
        for (index in 0 until player.rendererCount) {
            if (player.getRendererType(index) == C.TRACK_TYPE_VIDEO)
                player.createMessage(player.getRenderer(index)).setType(Renderer.MSG_SET_VIDEO_OUTPUT_RESOLUTION)
                    .setPayload(Size(WIDTH, HEIGHT)).send()
        }
    }

    fun detach(player: ExoPlayer) = player.clearVideoSurface(surface)

    override fun close() {
        if (closed) return
        closed = true
        surface.release()
        texture.release()
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 36
    }
}

/** One byte allowance for the warm media source, including manifest and cached reads. */
internal class AndroidAnimeStartupGate(byteLimit: Long, private val validUntil: Long) {
    private val remaining = AtomicLong(byteLimit)
    private val monitor = Object()
    @Volatile private var promoted = false
    @Volatile private var parked = false
    @Volatile private var retired = false
    val allowsLoading: Boolean get() = !retired && (promoted || !parked)

    fun park() { parked = true }
    fun promote() = synchronized(monitor) {
        promoted = true
        monitor.notifyAll()
    }
    fun retire() = synchronized(monitor) {
        retired = true
        monitor.notifyAll()
    }

    fun wrap(upstream: DataSource): DataSource = object : DataSource by upstream {
        override fun open(dataSpec: DataSpec): Long {
            if (!promoted) awaitActiveRead()
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (promoted || length == 0) return upstream.read(buffer, offset, length)
            awaitActiveRead()
            val reserved = reserve(length)
            if (reserved == 0) return upstream.read(buffer, offset, length)
            var read = 0
            try {
                read = upstream.read(buffer, offset, reserved)
                return read
            } finally {
                remaining.addAndGet((reserved - read.coerceAtLeast(0)).toLong())
            }
        }
    }

    private fun awaitActiveRead() {
        synchronized(monitor) {
            while (!promoted && parked && !retired) {
                val left = validUntil - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                try { monitor.wait(left) }
                catch (cancel: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("下一集准备已停止")
                }
            }
            if (!promoted && (retired || SystemClock.elapsedRealtime() >= validUntil))
                throw IOException("下一集准备已停止或过期")
        }
    }

    private fun reserve(requested: Int): Int {
        while (true) {
            if (promoted) return 0
            if (SystemClock.elapsedRealtime() >= validUntil) throw IOException("下一集准备已过期")
            val available = remaining.get()
            if (available <= 0L) throw IOException("下一集预解码已使用允许的媒体数据")
            val granted = minOf(available, requested.toLong())
            if (remaining.compareAndSet(available, available - granted)) return granted.toInt()
        }
    }
}
