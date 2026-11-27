package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionIconButton

@Composable
internal fun CatalogueKeywordField(value: String, onChange: (String) -> Unit, hint: String,
    enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val desktop = platformKind == PlatformKind.DESKTOP
    val accent = colors.primary
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp)
        .background(colors.onSurface.copy(alpha = .045f), RoundedCornerShape(12.dp))
        .then(if (desktop) Modifier.desktopFieldFocusLine(focused && enabled, accent) else Modifier).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        BasicTextField(value, onValueChange = { if (enabled) onChange(it) }, enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(requester).onFocusChanged { focused = it.isFocused }
                .padding(horizontal = 10.dp, vertical = 12.dp).semantics { contentDescription = hint },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
            cursorBrush = SolidColor(accent), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
            decorationBox = { input ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
            })
        if (desktop) DesktopFieldClear(value.isNotEmpty(), enabled, "清除关键词", 40.dp) {
            onChange("")
            requester.requestFocus()
        } else if (value.isNotEmpty()) MotionIconButton(onClick = { onChange("") }, enabled = enabled,
            modifier = Modifier.size(40.dp), tooltip = "清除关键词") {
            Icon(Icons.Outlined.Close, "清除关键词", Modifier.size(16.dp))
        } else Spacer(Modifier.width(40.dp))
    }
}
