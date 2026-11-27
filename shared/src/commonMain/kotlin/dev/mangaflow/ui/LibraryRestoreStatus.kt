package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
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
import dev.mangaflow.ui.motion.MotionBusyIndicator
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** Existing quiet loading/error treatment, without presenting unrestored data as an empty library. */
@Composable
internal fun LibraryRestoreStatus(library: LibraryStore, modifier: Modifier = Modifier) {
    val error by library.restoreError.collectAsStateWithLifecycle()
    if (platformKind == PlatformKind.DESKTOP) {
        val colors = MaterialTheme.colorScheme
        Box(modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 410.dp).fillMaxWidth()
                .background(colors.surfaceContainerLow, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState())
                .padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (error == null) {
                    MotionBusyIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    Text("正在恢复书架与阅读记录…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(22.dp), tint = colors.error)
                    Text("暂时无法载入书架", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant) }
                    TextButton(onClick = library::retryRestore) { Text("重新载入") }
                }
            }
        }
        return
    }
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

