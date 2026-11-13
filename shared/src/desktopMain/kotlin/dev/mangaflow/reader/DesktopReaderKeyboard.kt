package dev.mangaflow.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.ui.motion.MotionTextButton
import java.awt.event.KeyEvent as AwtKeyEvent

private data class DesktopChord(val code: Int, val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val meta: Boolean) {
    fun encode() = listOf(code, if (ctrl) 1 else 0, if (alt) 1 else 0, if (shift) 1 else 0, if (meta) 1 else 0).joinToString(":")
    fun label(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Win")
        add(AwtKeyEvent.getKeyText(code))
    }.joinToString(" + ")
    companion object {
        fun read(value: String?): DesktopChord? {
            val parts = value?.split(':')?.map { it.toIntOrNull() ?: return null } ?: return null
            if (parts.size != 5 || parts[0] <= 0 || parts.drop(1).any { it !in 0..1 }) return null
            return DesktopChord(parts[0], parts[1] == 1, parts[2] == 1, parts[3] == 1, parts[4] == 1)
        }
        fun from(event: KeyEvent) = DesktopChord(event.key.nativeKeyCode,
            event.isCtrlPressed, event.isAltPressed, event.isShiftPressed, event.isMetaPressed)
    }
}

private class DesktopKeyBindings(context: PlatformContext) {
    private val prefs = context.getSharedPreferences("mangaflow_desktop_reader_keys")
    var custom by mutableStateOf(ReaderKeyboardAction.entries.mapNotNull { action ->
        DesktopChord.read(prefs.getString(action.name))?.let { action to it }
    }.toMap())
        private set

    fun chord(action: ReaderKeyboardAction, rtl: Boolean): DesktopChord = custom[action] ?: DesktopChord(
        when (action) {
            ReaderKeyboardAction.PREVIOUS_PAGE -> if (rtl) AwtKeyEvent.VK_RIGHT else AwtKeyEvent.VK_LEFT
            ReaderKeyboardAction.NEXT_PAGE -> if (rtl) AwtKeyEvent.VK_LEFT else AwtKeyEvent.VK_RIGHT
            ReaderKeyboardAction.SCROLL_UP -> AwtKeyEvent.VK_UP
            ReaderKeyboardAction.SCROLL_DOWN -> AwtKeyEvent.VK_DOWN
            ReaderKeyboardAction.FIRST_PAGE -> AwtKeyEvent.VK_HOME
            ReaderKeyboardAction.LAST_PAGE -> AwtKeyEvent.VK_END
            ReaderKeyboardAction.TOGGLE_CONTROLS -> AwtKeyEvent.VK_ENTER
        }, false, false, false, false)

    fun action(event: KeyEvent, rtl: Boolean): ReaderKeyboardAction? {
        val pressed = DesktopChord.from(event)
        // An explicit user assignment wins over the conventional secondary keys.
        ReaderKeyboardAction.entries.firstOrNull { chord(it, rtl) == pressed }?.let { return it }
        if (pressed.ctrl || pressed.alt || pressed.meta) return null
        return when (pressed.code) {
            AwtKeyEvent.VK_PAGE_UP -> ReaderKeyboardAction.PREVIOUS_PAGE
            AwtKeyEvent.VK_PAGE_DOWN -> ReaderKeyboardAction.NEXT_PAGE
            AwtKeyEvent.VK_SPACE -> if (pressed.shift) ReaderKeyboardAction.PREVIOUS_PAGE else ReaderKeyboardAction.NEXT_PAGE
            else -> null
        }
    }

    fun assign(action: ReaderKeyboardAction, value: DesktopChord): String? {
        if (value.code in setOf(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.VK_CONTROL, AwtKeyEvent.VK_ALT,
                AwtKeyEvent.VK_ALT_GRAPH, AwtKeyEvent.VK_META, AwtKeyEvent.VK_WINDOWS)) return "请同时按一个普通按键"
        if (value.code in setOf(AwtKeyEvent.VK_ESCAPE, AwtKeyEvent.VK_F11, AwtKeyEvent.VK_TAB)) return "Esc、F11 与 Tab 保留给窗口和焦点操作"
        val conflict = ReaderKeyboardAction.entries.firstOrNull { other ->
            other != action && (chord(other, false) == value || chord(other, true) == value)
        }
        if (conflict != null) return "已用于“${conflict.label}”，请换一个按键"
        return try {
            check(prefs.edit().putString(action.name, value.encode()).commit())
            custom = custom + (action to value)
            null
        } catch (_: Exception) { "快捷键未能保存，请检查存储空间" }
    }

    fun reset(): String? = try {
        check(prefs.edit().clear().commit())
        custom = emptyMap()
        null
    } catch (_: Exception) { "默认快捷键未能保存，请重试" }
}

/** Instantiated once per real desktop window; no user keys enter account/history data. */
@Composable
fun ProvideDesktopReaderKeyboard(context: PlatformContext, content: @Composable () -> Unit) {
    val bindings = remember(context) { DesktopKeyBindings(context) }
    val configuration = remember(bindings) {
        ReaderKeyboardConfiguration(bindings::action) { rtl -> DesktopShortcutSettings(bindings, rtl) }
    }
    CompositionLocalProvider(LocalReaderKeyboard provides configuration, content = content)
}

@Composable
private fun DesktopShortcutSettings(bindings: DesktopKeyBindings, rtl: Boolean) {
    var recording by remember { mutableStateOf<ReaderKeyboardAction?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(recording) { if (recording != null) requester.requestFocus() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("键盘快捷键", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        Text("左右方向随阅读方向调整；PageUp / PageDown、空格也可翻页。",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        ReaderKeyboardAction.entries.forEach { action ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = "设置${action.label}快捷键") {
                    recording = action; message = null
                }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(action.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                Text(if (recording == action) "请按键…" else bindings.chord(action, rtl).label(),
                    color = if (recording == action) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp)
            }
        }
        if (recording != null) Text("按 Esc 取消录入", Modifier.fillMaxWidth()
            .focusRequester(requester).onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (event.key == Key.Escape) { recording = null; message = null }
                else recording?.let { action ->
                    message = bindings.assign(action, DesktopChord.from(event))
                    if (message == null) recording = null
                }
                true
            }.focusable(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (recording != null) MotionTextButton(onClick = { recording = null; message = null }) { Text("取消") }
            MotionTextButton(onClick = { recording = null; message = bindings.reset() }) { Text("恢复默认") }
        }
    }
}
