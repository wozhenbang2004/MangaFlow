package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.storage.LibraryStore
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** Existing quiet loading/error treatment, without presenting unrestored data as an empty library. */
@Composable
internal fun LibraryRestoreStatus(library: LibraryStore, modifier: Modifier = Modifier) {
    val error by library.restoreError.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (error != null) {
            Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = library::retryRestore) { Text("重试") }
        } else {
            Text("正在恢复书架与阅读记录…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

