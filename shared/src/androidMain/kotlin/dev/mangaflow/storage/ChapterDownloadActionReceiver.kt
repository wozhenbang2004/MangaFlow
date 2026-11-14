package dev.mangaflow.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.UUID

/** A stale notification can never pause a newer retry of the same chapter. */
class ChapterDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val chapter = intent.getStringExtra(KEY_CHAPTER)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return
        val work = intent.getStringExtra(KEY_WORK)?.takeIf { runCatching { UUID.fromString(it) }.isSuccess } ?: return
        DownloadStore.get(context.applicationContext).pause(chapter, expectedWorkId = work)
    }

    companion object {
        const val ACTION_PAUSE = "dev.mangaflow.action.PAUSE_CHAPTER_DOWNLOAD"
        const val KEY_CHAPTER = "chapter_download_id"
        const val KEY_WORK = "chapter_download_work"
    }
}
