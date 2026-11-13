package dev.mangaflow.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeSourceRetention
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.glass.GlassMemberTransition
import dev.chrisbanes.haze.glass.GlassReducedMotionPolicy
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassTransformTarget
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.material3
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

internal enum class GlassSurfaceRole { CONTROL, ARTWORK, NAVIGATION, PANEL, THUMB, READING_DOCK, POPOVER, DRAWER, GALLERY }

/** Application role/layout glue; the complete Haze 2.1 runtime owns every material stage. */
@Composable
internal fun LiquidGlassSurface(
    state: HazeState,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    role: GlassSurfaceRole = GlassSurfaceRole.CONTROL,
    interactionSource: MutableInteractionSource? = null,
    trackPointer: Boolean = false,
    elevation: Dp? = null,
    surfaceColor: Color? = null,
    softCard: Boolean = role == GlassSurfaceRole.NAVIGATION || role == GlassSurfaceRole.DRAWER,
    materialExpansion: () -> Float = { 0f },
    groupKey: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val surface = surfaceColor ?: colors.surface
    val light = surface.luminance() > .5f
    val foreground = if (surfaceColor == null) colors.onSurface
        else if (light) Color(0xFF242426) else Color.White
    val tintColor = if (softCard && surfaceColor == null) lerp(colors.background, surface, .70f) else surface
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val liquid = role !in setOf(GlassSurfaceRole.PANEL, GlassSurfaceRole.POPOVER, GlassSurfaceRole.DRAWER)
    val clear = role == GlassSurfaceRole.CONTROL || role == GlassSurfaceRole.ARTWORK || role == GlassSurfaceRole.THUMB
    val motion = LocalMotionEnabled.current
    // Declaring hover/press installs Haze's non-consuming, node-local pointer observer.
    // External sources add focus/keyboard/ancestor presses; raw touch needs no second detector.
    val interactive = liquid && motion && (trackPointer || interactionSource != null)
    val expansion = rememberUpdatedState(materialExpansion)
    // Popups/dialogs, artwork-specific samples and nested glass controls need the
    // explicitly selected source. A window backdrop cannot cross a window boundary.
    val input = remember(state, role) {
        when (role) {
            GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.READING_DOCK -> HazeInput.Backdrop(state)
            else -> HazeInput.Sources(state, retention = if (role == GlassSurfaceRole.DRAWER)
                HazeSourceRetention.ClearWhenUnavailable else HazeSourceRetention.KeepLastFrame)
        }
    }
    val themedGlass = (if (clear) GlassStyle.clear else GlassStyle.regular).material3(tintColor)
    val glassStyle = remember(themedGlass, shape, role, interactive, light, tintColor, surfaceColor) {
        themedGlass.then {
            shape(shape)
            val progress = expansion.value().coerceIn(0f, 1f)
            tint(tintColor.copy(alpha = if (role == GlassSurfaceRole.GALLERY) .10f else .035f + .055f * progress))
            // Keep SDK size-responsive diffusion/depth and adaptive accessibility. Author
            // only the lens at the boundary, bounding compact-control displacement
            // separately from the SDK's large general-purpose 48/56dp presets.
            when (role) {
                GlassSurfaceRole.CONTROL, GlassSurfaceRole.THUMB -> {
                    val thumb = role == GlassSurfaceRole.THUMB
                    optics { copy(refractionStrength = if (thumb) .20f else .26f,
                        refractionHeightFraction = .14f,
                        refractionDisplacement = if (thumb) 3.dp else 6.dp,
                        refractionProfile = RefractionProfile.Edge(if (thumb) 2.dp else 3.5.dp),
                        refractionDetailIntensity = .12f) }
                    tint(tintColor.copy(alpha = if (light) .14f else .18f))
                    whitePoint(if (light) .28f else -.40f)
                    specularIntensity(if (light) .18f else .14f)
                    ambientResponse(.06f)
                    edgeShadow(Color.Transparent)
                    chromaticAberrationStrength(0f)
                    contentNormalBlend(.035f)
                }
                GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.READING_DOCK -> {
                    val dock = role == GlassSurfaceRole.READING_DOCK
                    optics { copy(refractionStrength = if (dock) .24f else .30f + .025f * progress,
                        refractionHeightFraction = .14f,
                        refractionDisplacement = if (dock) 6.dp else 8.dp + 2.dp * progress,
                        refractionProfile = RefractionProfile.Edge(if (dock) 3.dp else 4.dp + progress.dp)) }
                    tint(tintColor.copy(alpha = (if (dock) .10f else .065f) + .035f * progress))
                    // Stabilize foreground polarity even over opposite-tone comic pages;
                    // this is tonal protection, independent of blur and rim highlights.
                    whitePoint(if (light) if (dock) .58f else .55f else if (dock) -.60f else -.56f)
                    specularIntensity(if (light) .18f else .14f)
                    ambientResponse(.05f)
                    edgeShadow(Color.Transparent)
                    contentNormalBlend(.04f)
                }
                else -> Unit
            }
            if (liquid && role != GlassSurfaceRole.GALLERY) chromaMultiplier(1.04f)
            if (role == GlassSurfaceRole.ARTWORK) {
                // Retain the latest compact artwork action's quiet, single-lens treatment.
                optics { copy(refractionStrength = .18f, refractionHeightFraction = .12f,
                    refractionDisplacement = 3.dp, depth = OpticalSizeValue.Fixed(.80f),
                    blurRadius = OpticalSizeValue.Fixed(3.dp), refractionProfile = RefractionProfile.Edge(2.5.dp)) }
                tint(tintColor.copy(alpha = if (light) .08f else .12f))
                whitePoint(if (light) .04f else -.04f)
                contrast(0f)
                chromaMultiplier(1.02f)
                ambientResponse(.025f)
                specularIntensity(.06f)
                edgeShadow(Color.Transparent)
                chromaticAberrationStrength(0f)
                contentNormalBlend(.025f)
            }
            if (role == GlassSurfaceRole.GALLERY) {
                optics { copy(refractionStrength = .10f, refractionDisplacement = 3.dp,
                    refractionProfile = RefractionProfile.Edge(3.dp), refractionHeightFraction = .10f,
                    progressive = HazeProgressive.verticalGradient(startIntensity = .25f, endIntensity = 1f)) }
                whitePoint(if (light) .10f else -.08f)
                specularIntensity(0f)
                edgeShadow(Color.Transparent)
                ambientResponse(.03f)
                chromaMultiplier(1.05f)
            }
            if (interactive) {
                hovered { animate(tween(MangaMotion.Quick), tween(MangaMotion.Quick)) { lightingIntensity(.15f) } }
                focused { animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) { lightingIntensity(.25f) } }
                pressed {
                    animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) {
                        lightingIntensity(if (role == GlassSurfaceRole.ARTWORK) .12f else if (clear) .26f else .22f)
                        refractionMultiplier(if (clear) 1.06f else 1.035f)
                        // Long controls already own their content press motion. Keep the
                        // member boundary stable while Haze joins/separates the group.
                        scale(if (clear) .995f else 1f)
                    }
                }
            }
        }
    }
    val preset = if (role == GlassSurfaceRole.DRAWER) HazeMaterials.thin(tintColor) else HazeMaterials.regular(tintColor)
    val material3Blur = HazeBlurStyle.Material3(tintColor).then(preset)
    val accessibility = LocalGlassAccessibilitySettings.current
    val blurStyle = remember(material3Blur, tintColor, accessibility) {
        material3Blur.then {
            noiseFactor(0f)
            if (accessibility.reduceTransparency || accessibility.increaseContrast) {
                colorEffects(listOf(HazeColorEffect.tint(tintColor.copy(alpha =
                    if (accessibility.reduceTransparency) 1f else .86f))))
            }
            fallbackColorEffect(HazeColorEffect.tint(tintColor.copy(alpha = .90f)))
        }
    }
    val material = if (liquid) Modifier.hazeGlass(
        input = input,
        style = glassStyle,
        interactionSource = interactionSource,
        interactionTransformTarget = GlassTransformTarget.MaterialOnly,
        interactionReducedMotionPolicy = if (motion) GlassReducedMotionPolicy.System else GlassReducedMotionPolicy.Reduced,
        memberTransition = if (motion) GlassMemberTransition.MatchedGeometry else GlassMemberTransition.None,
        groupKey = groupKey,
    ) else Modifier.hazeBlur(input = input, style = blurStyle)
    val shadow = if (softCard) Modifier.softSurfaceShadow(shape, light,
        strength = elevation?.let { (it.value / 16f).coerceIn(0f, 1f) } ?: 1f,
        strengthScale = { 1f + .28f * expansion.value().coerceIn(0f, 1f) })
    else Modifier.shadow(elevation ?: 3.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = .04f), spotColor = Color.Black.copy(alpha = .065f))
    CompositionLocalProvider(LocalContentColor provides foreground) {
        // Haze draws the material below its content and transforms only that material.
        Box(modifier.then(shadow).clip(shape).then(material), content = content)
    }
}
