package dev.mangaflow.reader

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.mangaflow.data.Manga
import dev.mangaflow.storage.LibraryStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only a resumed reader owns the minute ticker; returning from the background refreshes now. */
@Composable
internal fun rememberReaderClock(active: Boolean): String {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val format = remember { DateTimeFormatter.ofPattern("HH:mm") }
    fun currentTime(): String = format.format(Instant.now().atZone(ZoneId.systemDefault()))
    var clock by remember { mutableStateOf(currentTime()) }
    LaunchedEffect(lifecycle, active) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                clock = currentTime()
                val now = System.currentTimeMillis()
                delay(60_000L - now.mod(60_000L))
            }
        }
    }
    return clock
}

/** Flush monotonic foreground intervals; cancellation also settles the unfinished tail. */
@Composable
internal fun RecordReaderActivity(
    manga: Manga, eligible: Boolean, library: LibraryStore,
    chapterId: String = "", onReadingConfirmed: (String, String) -> Unit = { _, _ -> },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val confirm by rememberUpdatedState(onReadingConfirmed)
    LaunchedEffect(lifecycle, manga.id, chapterId, eligible, library) {
        if (eligible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val confirmation = launch { delay(3_000L); confirm(manga.id, chapterId) }
            var last = SystemClock.elapsedRealtime()
            fun flush() {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - last).coerceAtLeast(0L)
                last = now
                if (elapsed > 0L) library.recordReadingTime(manga, elapsed, System.currentTimeMillis())
            }
            try {
                while (true) {
                    delay(15_000L)
                    flush()
                }
            } finally {
                confirmation.cancel()
                flush()
            }
        }
    }
}
