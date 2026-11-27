package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A status stays in the reading column even when there are no rows beneath its header. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CatalogueState(title: String, detail: String, modifier: Modifier = Modifier,
    selectableDetail: Boolean = false, actions: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, Modifier.widthIn(max = 600.dp).semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        val description: @Composable () -> Unit = {
            Text(detail, Modifier.widthIn(max = 600.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selectableDetail) SelectionContainer { description() } else description()
        if (actions != null) FlowRow(Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) { actions() }
    }
}
