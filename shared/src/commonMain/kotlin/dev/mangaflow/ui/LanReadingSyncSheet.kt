package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.sync.LanReadingSyncController
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

@Composable
internal fun LanReadingSyncSheet(controller: LanReadingSyncController, onDismiss: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var removing by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val paired = state.devices.filter { it.trusted }
    val nearby = state.devices.filter { !it.trusted && it.online }

    // Leaving the sheet closes first-contact admission, while established sync remains enabled.
    DisposableEffect(controller) { onDispose { controller.closePairing() } }
    MotionBottomSheet(onDismissRequest = onDismiss) { dismiss ->
        Column(Modifier.fillMaxWidth()
            .heightIn(max = (LocalMangaWindow.current.height - 64.dp).coerceAtLeast(200.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("局域网阅读同步", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                MotionIconButton(onClick = dismiss, tooltip = "关闭", modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(18.dp))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "scope") {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        SettingsToggle("自动同步阅读数据", state.enabled, Icons.Outlined.Link,
                            enabled = state.supported, onCheckedChange = controller::setEnabled)
                        Text("同步在线普通漫画的收藏、阅读位置、页码书签和图片收藏位置。两端需打开漫流，并连接同一 Wi-Fi 或有线局域网。",
                            fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Text("本地导入的漫画和 PDF 不在本次同步范围；图片与文件不会复制，阅读布局仍由每台设备自己设置。",
                            fontSize = 11.sp, color = colors.onSurfaceVariant)
                        Text(when {
                            !state.supported -> "当前支持 Android 与 Windows 之间同步。"
                            !state.enabled -> "开启后，配对设备在同一局域网中会自动重连。"
                            !state.active -> "等待漫流在前台启动局域网连接。"
                            state.lastSyncAt > 0L -> "已完成同步，后续阅读变化会自动合并。"
                            paired.isEmpty() -> "先添加一台设备，在两端核对校验码。"
                            else -> "等待已配对设备连接。"
                        }, fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
                if (state.busy) item(key = "busy") { QuietLoading("正在连接设备并合并阅读数据…") }
                state.error?.let { error -> item(key = "error") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(error, fontSize = 12.sp, color = colors.error)
                        MotionTextButton(onClick = controller::syncNow, enabled = state.enabled && state.supported,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("重试", fontSize = 12.sp)
                        }
                    }
                } }
                val pairing = state.pairing
                if (pairing != null) item(key = "pairing:${pairing.session}") {
                    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("与 ${pairing.name} 配对", style = MaterialTheme.typography.titleSmall)
                        Text("请面对面核对两端校验码完全相同。双方确认后，才开始交换阅读数据。",
                            fontSize = 12.sp, color = colors.onSurfaceVariant)
                        Text(pairing.code.split(' ').chunked(4).joinToString("\n") { it.joinToString(" ") },
                            fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 25.sp)
                        if (pairing.confirmedHere) Text("本机已确认，等待另一台设备确认。", fontSize = 12.sp,
                            color = colors.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically) {
                            MotionTextButton(onClick = { controller.rejectPairing(pairing.session) }) {
                                Text("取消", fontSize = 12.sp)
                            }
                            MotionButton(onClick = { controller.confirmPairing(pairing.session) }, enabled = !pairing.confirmedHere) {
                                Text("两端校验码相同", fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (paired.isNotEmpty()) item(key = "paired-title") {
                    Text("已配对设备", style = MaterialTheme.typography.titleSmall)
                }
                items(paired, key = { "paired:${it.id}" }) { device ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(device.name, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (device.online) "已在局域网发现" else "等待设备上线", fontSize = 11.sp,
                                    color = colors.onSurfaceVariant)
                            }
                            MotionTextButton(onClick = { removing = if (removing == device.id) null else device.id }) {
                                Text("移除配对", fontSize = 12.sp)
                            }
                        }
                        if (removing == device.id) {
                            Text("移除后停止与此设备交换数据；已保存的阅读记录会保留。", fontSize = 11.sp,
                                color = colors.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                MotionTextButton(onClick = { removing = null }) { Text("取消", fontSize = 12.sp) }
                                MotionTextButton(onClick = { controller.forgetDevice(device.id); removing = null }) {
                                    Text("确认移除", fontSize = 12.sp, color = colors.error)
                                }
                            }
                        }
                    }
                }
                if (state.active && pairing == null) item(key = "add") {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.pairingOpen) MotionTextButton(onClick = controller::closePairing) {
                                Text("停止添加", fontSize = 12.sp)
                            } else MotionButton(onClick = controller::beginPairing) {
                                Text("添加设备", fontSize = 12.sp)
                            }
                            if (paired.isNotEmpty()) MotionTextButton(onClick = controller::syncNow, enabled = !state.busy) {
                                Text("立即同步", fontSize = 12.sp)
                            }
                        }
                        if (state.pairingOpen) Text("请在另一台设备也打开「添加设备」，然后任选一端发起配对。添加窗口 2 分钟后自动关闭。",
                            fontSize = 12.sp, color = colors.onSurfaceVariant)
                        if (state.pairingOpen && nearby.isEmpty()) Text("正在寻找附近的漫流设备…", fontSize = 12.sp,
                            color = colors.onSurfaceVariant)
                    }
                }
                if (state.pairingOpen && pairing == null) items(nearby, key = { "nearby:${it.id}" }) { device ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionTextButton(onClick = { controller.pair(device.id) }, enabled = !state.busy) {
                            Text("配对", fontSize = 12.sp)
                        }
                    }
                }
                if (state.conflicts.isNotEmpty()) item(key = "conflicts-title") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${state.conflicts.size} 处阅读差异", style = MaterialTheme.typography.titleSmall)
                        Text("不同设备离线修改过同一记录。当前结果已合并，其他版本仍保留；删除与修改冲突时优先保留删除，也可以手动选择恢复。",
                            fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
                items(state.conflicts, key = { "conflict:${it.key}" }) { conflict ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(conflict.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        MotionTextButton(onClick = { expanded = if (expanded == conflict.key) null else conflict.key },
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("${conflict.kind} · ${if (expanded == conflict.key) "收起版本" else "选择版本"}", fontSize = 12.sp)
                        }
                        if (expanded == conflict.key) conflict.choices.forEach { choice ->
                            MotionTextButton(onClick = { controller.chooseConflict(conflict.key, choice.id) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                    .semantics { selected = choice.selected }) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(choice.device + if (choice.selected) " · 当前采用" else "", fontSize = 12.sp)
                                    Text(choice.position, fontSize = 11.sp, color = colors.onSurfaceVariant)
                                }
                                if (choice.selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
