package dev.mangaflow.ui.motion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role

/** Material sizing/colors/type and Foundation semantics, with the app's existing quiet press. */
@Composable
internal fun MotionTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MotionButton(onClick, modifier, enabled, shape, colors, contentPadding = contentPadding,
    interactionSource = interactionSource, content = content)

@Composable
internal fun MotionOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = MotionButton(onClick, modifier, enabled, shape, colors, border = border, contentPadding = contentPadding,
    interactionSource = interactionSource, content = content)

@Composable
internal fun MotionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    Surface(modifier.minimumInteractiveComponentSize()
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction,
            indication = null, onClick = onClick)
        .motionPress(interaction, enabled), shape = shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = border) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(Modifier.defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
                .padding(contentPadding), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}
