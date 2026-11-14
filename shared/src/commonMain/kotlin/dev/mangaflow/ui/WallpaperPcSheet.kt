package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.WallpaperPcDevice
import dev.mangaflow.storage.createWallpaperPcClient
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress

/** Discovery and reception belong to this foreground panel, never to the wallpaper renderer. */
@Composable
internal fun WallpaperPcSheet(
    onDismiss: () -> Unit,
    visible: Boolean = true,
    backdrop: HazeState? = null,
) {
    val context = LocalMangaContext.current
    val client = remember(context) { createWallpaperPcClient(context) }
    val liveState by client.state.collectAsStateWithLifecycle()
    var retained by remember(client) { mutableStateOf(liveState) }
    SideEffect { if (visible) retained = liveState }
    val state = if (visible) liveState else retained
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var target by remember(client) { mutableStateOf<WallpaperPcDevice?>(null) }
    var pin by remember(client) { mutableStateOf("") }
    var manual by remember(client) { mutableStateOf(false) }
    var address by remember(client) { mutableStateOf("") }
    var paused by remember(client) { mutableStateOf(false) }
    val panelVisible by rememberUpdatedState(visible)

    DisposableEffect(client, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                pin = ""
                paused = true
                client.disconnect()
            } else if (event == Lifecycle.Event.ON_START && panelVisible) {
                paused = false
                client.discover()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            client.close()
        }
    }
    LaunchedEffect(client, visible) {
        pin = ""
        if (visible && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            target = null
            manual = false
            address = ""
            paused = false
            client.discover()
        } else client.disconnect()
    }
    LaunchedEffect(state.devices, target?.id) {
        // A computer can announce before its TCP pairing listener is ready.
        // Keep the row visible and promote it when the real ready announcement arrives.
        val selected = target
        if (selected != null && selected.port == 0) {
            state.devices.firstOrNull { it.id == selected.id && it.port > 0 }?.let { target = it }
        }
    }
    LaunchedEffect(state.connectedName) {
        if (state.connectedName != null) {
            pin = ""
            target = null
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    val dismiss = {
        pin = ""
        client.disconnect()
        onDismiss()
    }
    val connect = {
        val selected = target
        if (selected != null && pin.length == 4 && !state.connecting) {
            val entered = pin
            pin = ""
            paused = false
            focus.clearFocus(force = true)
            keyboard?.hide()
            client.connect(selected, entered)
        }
    }
    val scan = {
        pin = ""
        target = null
        manual = false
        paused = false
        client.discover()
    }
    MotionDialog(visible = visible, onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 624.dp) {
        val panel: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()
                .height(minOf(LocalMangaWindow.current.height * .84f, 520.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Computer, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text("连接电脑", Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (state.supported && state.connectedName == null && !state.connecting)
                        MotionIconButton(onClick = scan, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Refresh, "重新查找电脑", Modifier.size(20.dp))
                        }
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭电脑壁纸连接", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!state.supported) {
                        Text(state.message ?: "此平台暂不支持电脑配对。",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else if (state.connectedName != null) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = colors.onSurface)
                            Text(state.connectedName.orEmpty(), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            MotionIconButton(onClick = { pin = ""; client.disconnect() }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.LinkOff, "断开电脑", Modifier.size(20.dp))
                            }
                        }
                        Text("在电脑选择图片或视频壁纸，点击「发送到移动设备」。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        state.transferName?.let { name ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                val fraction = if (state.totalBytes > 0L)
                                    (state.receivedBytes.toDouble() / state.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = colors.onSurface, trackColor = colors.onSurface.copy(alpha = .08f))
                                Text(if (state.totalBytes > 0L && state.receivedBytes >= state.totalBytes) "正在加入壁纸库…"
                                    else "${pcByteLabel(state.receivedBytes)} / ${pcByteLabel(state.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    } else {
                        Text("同一局域网内，在电脑 Wallpaper Engine 打开「移动设备 → 连接新设备」。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        val selected = target
                        if (selected != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                MotionIconButton(onClick = { target = null; pin = ""; client.disconnect(); client.discover() },
                                    enabled = !state.connecting, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Outlined.ChevronLeft, "返回电脑列表", Modifier.size(20.dp))
                                }
                                Text(selected.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected.port == 0) {
                                Text("已找到电脑。请在电脑打开「连接新设备」，等待配对入口就绪。",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            } else {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(colors.onSurface.copy(alpha = .045f)).padding(start = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    BasicTextField(pin, onValueChange = { value ->
                                        pin = value.filter { it in '0'..'9' }.take(4)
                                    }, modifier = Modifier.weight(1f).padding(vertical = 14.dp)
                                        .semantics { contentDescription = "电脑显示的四位配对码" },
                                        enabled = !state.connecting, singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, letterSpacing = 7.sp),
                                        cursorBrush = SolidColor(colors.onSurface),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(onGo = { connect() }),
                                        decorationBox = { input -> Box {
                                            if (pin.isEmpty()) Text("四位配对码", style = MaterialTheme.typography.bodyMedium,
                                                color = colors.onSurfaceVariant)
                                            input()
                                        } })
                                    MotionTextButton(onClick = connect, enabled = pin.length == 4 && !state.connecting) {
                                        Text(if (state.connecting) "连接中…" else "连接")
                                    }
                                }
                            }
                            if (selected.paired && !state.connecting) Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End) {
                                MotionTextButton(onClick = {
                                    pin = ""; client.forget(selected); target = selected.copy(paired = false)
                                }) { Text("忘记配对", style = MaterialTheme.typography.labelMedium) }
                            }
                        } else {
                            if (state.devices.isEmpty()) Text(
                                if (paused) "接收已暂停，点击右上角重新查找。"
                                else if (state.discovering) "正在寻找附近电脑…"
                                else "未找到电脑，可重新查找或输入地址。",
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp))
                            else Column(Modifier.fillMaxWidth()) {
                                state.devices.forEach { device ->
                                    val interaction = remember(device.id, device.address) { MutableInteractionSource() }
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                                            onClickLabel = "连接 ${device.name}") {
                                            target = device; pin = ""; manual = false; paused = false
                                            if (device.paired && device.port > 0) client.connect(device, "")
                                        }.motionPress(interaction).padding(vertical = 10.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.Computer, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(device.name, style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(if (device.port == 0) "${device.address} · 连接未开放"
                                                else if (device.paired) "${device.address} · 已配对" else device.address,
                                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                        }
                                        if (device.paired) MotionIconButton(onClick = { target = device; pin = "" },
                                            modifier = Modifier.size(40.dp)) {
                                            Icon(Icons.Outlined.Link, "重新配对 ${device.name}", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                        } else Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                    }
                                }
                            }
                            if (manual) {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Link, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                    Spacer(Modifier.width(8.dp))
                                    BasicTextField(address, onValueChange = { value ->
                                        address = value.filter { it in '0'..'9' || it == '.' }.take(15)
                                    }, singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 14.dp)
                                        .semantics { contentDescription = "电脑的局域网 IPv4 地址" },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                        cursorBrush = SolidColor(colors.onSurface),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                        keyboardActions = KeyboardActions(onDone = {
                                            if (validPcAddress(address)) {
                                                target = WallpaperPcDevice("manual:$address", address, address)
                                                pin = ""; focus.clearFocus(force = true); keyboard?.hide()
                                            }
                                        }),
                                        decorationBox = { input -> Box {
                                            if (address.isEmpty()) Text("电脑 IP 地址", style = MaterialTheme.typography.bodyMedium,
                                                color = colors.onSurfaceVariant)
                                            input()
                                        } })
                                    MotionIconButton(enabled = validPcAddress(address), onClick = {
                                        target = WallpaperPcDevice("manual:$address", address, address)
                                        pin = ""; focus.clearFocus(force = true); keyboard?.hide()
                                    }, modifier = Modifier.size(44.dp)) {
                                        Icon(Icons.Outlined.ChevronRight, "使用电脑地址", Modifier.size(20.dp))
                                    }
                                }
                            } else MotionTextButton(onClick = { manual = true }) {
                                Text("用 IP 直连", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().height(64.dp * LocalMangaWindow.current.fontScale),
                    verticalArrangement = Arrangement.Center) {
                    val message = state.error ?: state.message?.takeIf { state.supported }
                        ?: if (state.importedCount > 0) "已接收并应用 " + state.importedCount + " 张"
                        else if (state.connectedName != null) "局域网接收中 · 关闭此页会断开"
                        else "图片 · 动态视频 · 局域网连接"
                    Text(message, style = MaterialTheme.typography.labelSmall, maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = if (state.error != null) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            if (backdrop != null) LiquidGlassSurface(state = backdrop, modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp, role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
        }
    }
}

private fun validPcAddress(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { part -> part.isEmpty() || part.length > 3 ||
        part.any { it !in '0'..'9' } || (part.toIntOrNull() ?: -1) !in 0..255 }) return false
    val first = parts[0].toInt()
    val second = parts[1].toInt()
    return first == 10 || first == 127 || (first == 192 && second == 168) ||
        (first == 172 && second in 16..31) || (first == 169 && second == 254)
}

private fun pcByteLabel(value: Long): String = when {
    value < 1024L -> "${value.coerceAtLeast(0)} B"
    value < 1024L * 1024L -> "${value / 1024L} KB"
    else -> "${value / (1024L * 1024L)} MB"
}
