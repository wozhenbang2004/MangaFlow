package dev.mangaflow.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A busy state remains visible without scheduling an infinite animation when motion is off. */
@Composable
internal fun MotionBusyIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
) {
    if (LocalMotionEnabled.current) {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
    } else {
        Canvas(modifier.size(40.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }) {
            val width = strokeWidth.toPx()
            val diameter = (size.minDimension - width).coerceAtLeast(0f)
            val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(color.copy(alpha = .16f), 0f, 360f, false, origin, Size(diameter, diameter),
                style = Stroke(width, cap = StrokeCap.Round))
            drawArc(color, -90f, 240f, false, origin, Size(diameter, diameter),
                style = Stroke(width, cap = StrokeCap.Round))
        }
    }
}

/** Known progress keeps the platform's actual progress and semantics, including its defaults. */
@Composable
internal fun MotionBusyIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp,
    trackColor: Color = Color.Unspecified,
) {
    if (trackColor == Color.Unspecified) {
        CircularProgressIndicator(progress = progress, modifier = modifier, color = color, strokeWidth = strokeWidth)
    } else {
        CircularProgressIndicator(progress = progress, modifier = modifier, color = color,
            strokeWidth = strokeWidth, trackColor = trackColor)
    }
}
