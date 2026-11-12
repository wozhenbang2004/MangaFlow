package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.ui.motion.rememberDelayedBusy

/** Existing quiet loading/error treatment, without presenting unrestored data as an empty library. */
@Composable
internal fun LibraryRestoreStatus(library: LibraryStore, modifier: Modifier = Modifier) {
    val error by library.restoreError.collectAsStateWithLifecycle()
    val showBusy = rememberDelayedBusy(error == null)
    Column(modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (error != null) {
            Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = library::retryRestore) { Text("重试") }
        } else if (showBusy) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

