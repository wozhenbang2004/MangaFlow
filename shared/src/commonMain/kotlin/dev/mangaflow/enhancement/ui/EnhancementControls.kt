package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.enhancement.EnhancementTask
import dev.mangaflow.enhancement.EnhancementTaskPhase
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.ui.motion.*

@Composable
internal fun EnhancementSection(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
        content()
    }
}

/** One Ovio choice surface, sized for the available width and text scale. */
@Composable
internal fun <T> EnhancementChoices(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    description: String,
    columns: Int = 3,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val availableColumns = (maxWidth / (88.dp * scale)).toInt().coerceIn(1, columns)
        val cellCount = if (values.size == 4 && availableColumns == 3) 2 else availableColumns
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .035f)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            values.chunked(cellCount).forEach { row ->
                val index = row.indexOf(selected)
                MotionTabs(index.coerceAtLeast(0), row.map(label), onSelect = { onSelect(row[it]) },
                    modifier = Modifier.fillMaxWidth(row.size.toFloat() / cellCount), controlHeight = 48.dp,
                    treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
                    selectionVisible = index >= 0, role = Role.RadioButton,
                    selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun EnhancementDetailToggle(expanded: Boolean, onClick: () -> Unit, label: String = "详情") {
    MotionTextButton(onClick, contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(if (expanded) "收起" else label, style = MaterialTheme.typography.labelMedium)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
    }
}

@Composable
internal fun EnhancementQuiet(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EnhancementValue(name: String, value: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1.15f), style = MaterialTheme.typography.bodySmall)
        }
        if (!detail.isNullOrBlank()) EnhancementQuiet(detail)
    }
}

@Composable
internal fun EnhancementDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))

internal fun enhancementBytes(bytes: Long?): String = when {
    bytes == null -> "不可用"
    bytes >= 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 2) + " GiB"
    bytes >= 1024L * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MiB"
    bytes >= 1024 -> formatDecimal(bytes / 1024.0, 1) + " KiB"
    else -> "$bytes B"
}

internal fun engineReadinessLabel(value: EngineReadiness): String = when (value) {
    EngineReadiness.BUILT_IN -> "内置"
    EngineReadiness.NOT_INSTALLED -> "未安装"
    EngineReadiness.NEEDS_MODELS -> "需要模型"
    EngineReadiness.READY_FOR_TRIAL -> "可试用 · 尚未验证"
    EngineReadiness.UNSUPPORTED -> "暂不支持"
    EngineReadiness.FAILED -> "不可用"
}

internal fun engineStageLabel(value: EngineStage): String = when (value) {
    EngineStage.DOWNLOAD -> "正在下载"
    EngineStage.VERIFY -> "校验文件"
    EngineStage.INSTALL -> "正在安装"
    EngineStage.MODEL_LOAD -> "加载模型"
    EngineStage.PROCESS -> "正在处理"
    EngineStage.ENCODE -> "正在编码"
    EngineStage.VALIDATE -> "校验结果"
    EngineStage.COMPLETE -> "已完成"
}

internal fun EngineProgress.readableProgress(): String {
    val count = if (total != null && total > 0) "$completed / $total" else if (completed > 0) "$completed" else ""
    return listOfNotNull(if (failed) "失败" else engineStageLabel(stage), count.takeIf { it.isNotBlank() },
        processingFps?.let { formatDecimal(it, 1) + " 处理帧/秒" }).joinToString(" · ")
}

internal fun EnhancementTask.readableStatus(): String = listOfNotNull(phase.label,
    if (phase == EnhancementTaskPhase.RUNNING) progress?.readableProgress() ?: reason else reason,
).joinToString(" · ")
