package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
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

internal enum class GlassSurfaceRole { CONTROL, ARTWORK, NAVIGATION, PANEL, THUMB, READING_DOCK, POPOVER, DRAWER, GALLERY, DETAIL_HERO }

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
    surfaceShape: Shape? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val surface = surfaceColor ?: colors.surface
    val light = surface.luminance() > .5f
    val foreground = if (surfaceColor == null) colors.onSurface
        else if (light) Color(0xFF242426) else Color.White
    val tintColor = if (softCard && surfaceColor == null) lerp(colors.background, surface, .70f) else surface
    val shape = remember(cornerRadius, surfaceShape) { surfaceShape ?: RoundedCornerShape(cornerRadius) }
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
            tint(tintColor.copy(alpha = if (role == GlassSurfaceRole.GALLERY) .05f else .025f + .025f * progress))
            // Keep the official Clear/Regular material and its responsive diffusion.
            // Let real content show through the body; the SDK bends and lights its
            // perimeter, capping displacement to half the actual shortest side.
            if (liquid && role != GlassSurfaceRole.DETAIL_HERO) {
                lightPosition(Alignment.TopStart)
                edgeSoftness(1.dp)
            }
            when (role) {
                GlassSurfaceRole.CONTROL, GlassSurfaceRole.THUMB -> {
                    val thumb = role == GlassSurfaceRole.THUMB
                    optics { copy(refractionStrength = if (thumb) .52f else .68f,
                        refractionHeightFraction = .28f,
                        refractionDisplacement = if (thumb) 7.dp else 14.dp,
                        refractionProfile = RefractionProfile.Edge(if (thumb) 4.dp else 9.dp),
                        refractionDetailIntensity = .26f) }
                    tint(tintColor.copy(alpha = if (light) .035f else .055f))
                    whitePoint(if (light) .08f else -.12f)
                    specularIntensity(if (light) .38f else .30f)
                    ambientResponse(.10f)
                    edgeShadow(Color.Transparent)
                    chromaticAberrationStrength(0f)
                    contentNormalBlend(.08f)
                }
                GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.READING_DOCK -> {
                    val dock = role == GlassSurfaceRole.READING_DOCK
                    optics { copy(refractionStrength = if (dock) .50f else .56f + .04f * progress,
                        refractionHeightFraction = .18f,
                        refractionDisplacement = if (dock) 12.dp else 16.dp + 2.dp * progress,
                        refractionProfile = RefractionProfile.Edge(if (dock) 7.dp else 9.dp + progress.dp)) }
                    tint(tintColor.copy(alpha = (if (dock) .05f else .035f) + .02f * progress))
                    // Stabilize foreground polarity even over opposite-tone comic pages;
                    // this is tonal protection, independent of blur and rim highlights.
                    whitePoint(if (light) if (dock) .34f else .30f else if (dock) -.38f else -.34f)
                    specularIntensity(if (light) .30f else .24f)
                    ambientResponse(.075f)
                    edgeShadow(Color.Transparent)
                    contentNormalBlend(.065f)
                }
                GlassSurfaceRole.DETAIL_HERO -> {
                    // One large passive lens. Keep the long-text interior stable and
                    // concentrate the more visible optics at the rounded perimeter.
                    optics { copy(refractionStrength = .22f, refractionHeightFraction = .10f,
                        refractionDisplacement = 5.dp, refractionProfile = RefractionProfile.Edge(5.dp),
                        blurRadius = OpticalSizeValue.Fixed(18.dp), depth = OpticalSizeValue.Fixed(1f)) }
                    tint(tintColor.copy(alpha = if (light) .20f else .24f))
                    whitePoint(if (light) .62f else -.62f)
                    specularIntensity(.16f)
                    ambientResponse(.035f)
                    edgeShadow(Color.Transparent)
                    chromaticAberrationStrength(0f)
                    contentNormalBlend(.025f)
                }
                else -> Unit
            }
            if (liquid && role != GlassSurfaceRole.GALLERY) chromaMultiplier(1.025f)
            if (role == GlassSurfaceRole.ARTWORK) {
                // Keep the cover visible through a shallow Clear lens, rather than
                // whitening the action into another opaque button over the artwork.
                optics { copy(refractionStrength = .60f, refractionHeightFraction = .24f,
                    refractionDisplacement = 12.dp, depth = OpticalSizeValue.Fixed(.50f),
                    blurRadius = OpticalSizeValue.Fixed(2.5.dp), refractionProfile = RefractionProfile.Edge(8.dp),
                    refractionDetailIntensity = .22f) }
                tint(tintColor.copy(alpha = if (light) .035f else .055f))
                whitePoint(if (light) .08f else -.10f)
                contrast(0f)
                chromaMultiplier(1.015f)
                ambientResponse(.085f)
                specularIntensity(if (light) .32f else .26f)
                edgeShadow(Color.Transparent)
                chromaticAberrationStrength(0f)
                contentNormalBlend(.06f)
            }
            if (role == GlassSurfaceRole.GALLERY) {
                optics { copy(refractionStrength = .30f, refractionDisplacement = 10.dp,
                    refractionProfile = RefractionProfile.Edge(7.dp), refractionHeightFraction = .16f,
                    progressive = HazeProgressive.verticalGradient(startIntensity = .15f, endIntensity = .85f)) }
                whitePoint(if (light) .06f else -.06f)
                specularIntensity(.12f)
                edgeShadow(Color.Transparent)
                ambientResponse(.055f)
                chromaMultiplier(1.025f)
            }
            if (interactive) {
                interactionLightRadiusFraction(.60f)
                hovered { animate(tween(MangaMotion.Quick), tween(MangaMotion.Quick)) { lightingIntensity(.28f) } }
                focused { animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) { lightingIntensity(.36f) } }
                pressed {
                    animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) {
                        lightingIntensity(if (role == GlassSurfaceRole.ARTWORK) .48f else if (clear) .60f else .46f)
                        refractionMultiplier(if (clear) 1.10f else 1.065f)
                        whitePointDelta(.025f)
                        // Long controls already own their content press motion. Keep the
                        // member boundary stable while Haze joins/separates the group.
                        scale(if (clear) .985f else 1f)
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
