package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton

data class DesktopPerformanceMetric(val label: String, val value: String)

/** A disclosed developer tool, retaining the application's existing panel motion and typography. */
@Composable
fun DesktopPerformancePanel(
    visible: Boolean,
    metrics: List<DesktopPerformanceMetric>,
    recording: Boolean,
    status: String,
    onDismiss: () -> Unit,
    onToggleRecording: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    MotionDialog(visible, onDismiss, maximumWidth = 620.dp,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // This separate diagnostic window has no captured backdrop. Use the same
        // readable panel fallback as existing dialogs, rather than sampling itself.
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Windows 性能", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = onToggleRecording, tooltip = if (recording) "结束采集" else "采集性能日志与 JFR") {
                        Icon(if (recording) Icons.Outlined.Stop else Icons.Outlined.FiberManualRecord,
                            if (recording) "结束采集" else "开始采集", Modifier.size(20.dp))
                    }
                    MotionIconButton(onClick = onOpenFolder, tooltip = "打开诊断文件夹") {
                        Icon(Icons.Outlined.FolderOpen, "打开诊断文件夹", Modifier.size(20.dp))
                    }
                    MotionIconButton(onClick = onDismiss, tooltip = "关闭性能面板") {
                        Icon(Icons.Outlined.Close, "关闭性能面板", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    metrics.forEach { metric ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(metric.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(metric.value, Modifier.weight(1.15f), style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text("CPU 场景回调仅覆盖主窗口，未包含后续 Picture 完成、GPU 提交和独立弹窗。输入关联为事件分发到下一次回调结束（超过 1 秒未匹配则排除），不是像素响应时间。实际 Present、GPU 队列与显存请配合 PresentMon / GPUView。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Text("Ctrl + Shift + F12 · 关闭面板后采集仍可继续，默认 120 秒自动结束。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
