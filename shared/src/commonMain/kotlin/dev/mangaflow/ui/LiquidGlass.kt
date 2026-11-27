package dev.mangaflow.ui

import androidx.compose.animation.core.spring
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
import dev.chrisbanes.haze.glass.OpticalSizePoint
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.RefractionProfile
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.material3
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

internal enum class GlassSurfaceRole {
    CONTROL, ARTWORK, NAVIGATION, PANEL, THUMB, READING_DOCK, POPOVER, DRAWER, GALLERY, DETAIL_HERO,
    VIDEO_DOCK,
}

// The short side grows as a compact rail expands into a search field or a two-row dock.
private val toolbarBlur = OpticalSizeValue.Responsive(
    OpticalSizePoint(44.dp, 8.dp), OpticalSizePoint(84.dp, 12.dp), OpticalSizePoint(144.dp, 17.dp),
)
private val toolbarDepth = OpticalSizeValue.Responsive(
    OpticalSizePoint(44.dp, .58f), OpticalSizePoint(84.dp, .78f), OpticalSizePoint(144.dp, .92f),
)
private val glassPressIn = spring<Float>(dampingRatio = 1f, stiffness = MangaMotion.PressAttackStiffness)
private val glassPressOut = spring<Float>(dampingRatio = MangaMotion.PressDamping, stiffness = MangaMotion.PressStiffness)

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
    surfaceShape: RoundedCornerShape? = null,
    preferSource: Boolean = false,
    contentColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val surface = surfaceColor ?: colors.surface
    val light = surface.luminance() > .5f
    val foreground = contentColor ?: if (surfaceColor == null) colors.onSurface
        else if (light) Color(0xFF242426) else Color.White
    val toolbar = role == GlassSurfaceRole.NAVIGATION || role == GlassSurfaceRole.READING_DOCK
    val tintColor = when {
        surfaceColor != null -> surfaceColor
        toolbar -> colors.surfaceContainerHigh
        softCard -> lerp(colors.background, surface, .70f)
        else -> surface
    }
    val protectArtworkInk = role == GlassSurfaceRole.ARTWORK && contentColor != null
    val shape = remember(cornerRadius, surfaceShape) { surfaceShape ?: RoundedCornerShape(cornerRadius) }
    val liquid = role != GlassSurfaceRole.PANEL && role != GlassSurfaceRole.POPOVER && role != GlassSurfaceRole.DRAWER
    val clear = role == GlassSurfaceRole.CONTROL || role == GlassSurfaceRole.ARTWORK ||
        role == GlassSurfaceRole.THUMB || role == GlassSurfaceRole.VIDEO_DOCK
    val motion = LocalMotionEnabled.current
    val accessibility = LocalGlassAccessibilitySettings.current
    // Declaring hover/press installs Haze's non-consuming, node-local pointer observer.
    // External sources add focus/keyboard/ancestor presses; raw touch needs no second detector.
    val interactive = liquid && motion && (trackPointer || interactionSource != null)
    val expansion = rememberUpdatedState(materialExpansion)
    // Popups/dialogs, artwork-specific samples and nested glass controls need the
    // explicitly selected source. A window backdrop cannot cross a window boundary.
    val input = remember(state, role, preferSource) {
        val sources = HazeInput.Sources(state, retention = if (preferSource || role == GlassSurfaceRole.DRAWER ||
            role == GlassSurfaceRole.VIDEO_DOCK)
            HazeSourceRetention.ClearWhenUnavailable else HazeSourceRetention.KeepLastFrame)
        if (!preferSource && (role == GlassSurfaceRole.NAVIGATION || role == GlassSurfaceRole.READING_DOCK))
            HazeInput.Backdrop(sources)
        else sources
    }
    val material = if (liquid) {
        val themedGlass = (if (clear) GlassStyle.clear else GlassStyle.regular).material3(tintColor)
        val glassStyle = remember(themedGlass, shape, role, interactive, light, accessibility, protectArtworkInk) {
            themedGlass.then {
                shape(shape)
                val progress = expansion.value().coerceIn(0f, 1f)
                tint(tintColor.copy(alpha = if (role == GlassSurfaceRole.GALLERY) .05f else .025f + .025f * progress))
                // The exterior shadow gives the surface lift. Keep only directional light on the glass itself.
                edgeShadow(Color.Transparent)
                if (role != GlassSurfaceRole.DETAIL_HERO) {
                    lightPosition(Alignment.TopStart)
                    edgeSoftness(1.dp)
                }
                when (role) {
                    GlassSurfaceRole.CONTROL, GlassSurfaceRole.THUMB -> {
                        val thumb = role == GlassSurfaceRole.THUMB
                        optics { copy(refractionStrength = if (thumb) .52f else .46f,
                            refractionHeightFraction = .28f,
                            refractionDisplacement = if (thumb) 4.dp else 7.dp,
                            refractionProfile = RefractionProfile.Edge(if (thumb) 3.dp else 4.dp),
                            refractionDetailIntensity = .12f) }
                        tint(tintColor.copy(alpha = if (light) .13f else .17f))
                        whitePoint(if (light) .08f else -.12f)
                        specularIntensity(if (thumb) .28f else .22f)
                        ambientResponse(.08f)
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.08f)
                    }
                    GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.READING_DOCK -> {
                        val dock = role == GlassSurfaceRole.READING_DOCK
                        optics { copy(refractionStrength = if (dock) .32f else .40f,
                            refractionHeightFraction = .20f,
                            refractionDisplacement = 6.dp + 2.dp * progress,
                            refractionProfile = RefractionProfile.Edge(4.dp + 1.dp * progress),
                            blurRadius = toolbarBlur, depth = toolbarDepth) }
                        // A single tonal layer protects glyphs over opposite-tone artwork.
                        // Tint is also honoured by Haze's software fallback, unlike whitePoint.
                        val bodyTint = if (light) .68f else .76f
                        tint(tintColor.copy(alpha = bodyTint + (if (dock) .04f else 0f) + .025f * progress))
                        whitePoint(if (light) .045f else -.08f)
                        specularIntensity(if (light) .18f else .15f)
                        ambientResponse(.06f)
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.045f)
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
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.025f)
                    }
                    GlassSurfaceRole.VIDEO_DOCK -> {
                        // Brighten the sampled video without covering it with a white plate.
                        // The pale backing is visible if the capture cannot supply opaque pixels.
                        backgroundColor(tintColor.copy(alpha = .60f))
                        tint(tintColor.copy(alpha = .20f))
                        whitePoint(.47f)
                        contrast(0f)
                        optics { copy(refractionStrength = .76f, refractionDisplacement = 11.dp,
                            refractionProfile = RefractionProfile.Edge(8.dp), refractionHeightFraction = .24f,
                            depth = OpticalSizeValue.Fixed(.46f), blurRadius = OpticalSizeValue.Fixed(3.5.dp),
                            refractionDetailIntensity = 0f) }
                        specularIntensity(.26f)
                        ambientResponse(.08f)
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.05f)
                    }
                    else -> Unit
                }
                if (role == GlassSurfaceRole.VIDEO_DOCK) chromaMultiplier(.98f)
                else if (role != GlassSurfaceRole.GALLERY) chromaMultiplier(1.05f)
                if (role == GlassSurfaceRole.ARTWORK) {
                    // Keep the cover visible through a shallow Clear lens, rather than
                    // whitening the action into another opaque button over the artwork.
                    optics { copy(refractionStrength = .42f, refractionHeightFraction = .24f,
                        refractionDisplacement = 7.dp, depth = OpticalSizeValue.Fixed(.50f),
                        blurRadius = OpticalSizeValue.Fixed(2.5.dp), refractionProfile = RefractionProfile.Edge(4.dp),
                        refractionDetailIntensity = .12f) }
                    val artworkTint = if (protectArtworkInk) {
                        if (light) .54f else .56f
                    } else if (light) .13f else .17f
                    tint(tintColor.copy(alpha = artworkTint))
                    whitePoint(if (light) .08f else -.10f)
                    contrast(0f)
                    chromaMultiplier(1.015f)
                    ambientResponse(.07f)
                    specularIntensity(if (light) .24f else .19f)
                    chromaticAberrationStrength(0f)
                    contentNormalBlend(.06f)
                }
                if (role == GlassSurfaceRole.GALLERY) {
                    optics { copy(refractionStrength = .30f, refractionDisplacement = 10.dp,
                        refractionProfile = RefractionProfile.Edge(7.dp), refractionHeightFraction = .16f,
                        progressive = HazeProgressive.verticalGradient(startIntensity = .15f, endIntensity = .85f)) }
                    whitePoint(if (light) .06f else -.06f)
                    specularIntensity(.12f)
                    ambientResponse(.055f)
                    chromaMultiplier(1.025f)
                }
                if (interactive) {
                    interactionLightRadiusFraction(if (clear) .76f else .66f)
                    hovered { animate(tween(MangaMotion.Quick), tween(MangaMotion.Quick)) { lightingIntensity(.28f) } }
                    focused { animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) { lightingIntensity(.36f) } }
                    pressed {
                        animate(glassPressIn, glassPressOut) {
                            lightingIntensity(if (clear) .72f else .54f)
                            refractionMultiplier(if (clear) 1.16f else 1.10f)
                            whitePointDelta(if (light) .025f else .015f)
                            scale(scaleX = if (clear) .975f else .997f, scaleY = if (clear) .95f else .982f)
                        }
                    }
                }
                if (accessibility.reduceTransparency || accessibility.increaseContrast) {
                    tint(tintColor.copy(alpha = if (accessibility.reduceTransparency) 1f else .84f))
                }
            }
        }
        Modifier.hazeGlass(
            input = input,
            style = glassStyle,
            interactionSource = interactionSource,
            interactionTransformTarget = GlassTransformTarget.MaterialOnly,
            interactionReducedMotionPolicy = if (motion) GlassReducedMotionPolicy.System else GlassReducedMotionPolicy.Reduced,
            memberTransition = if (motion) GlassMemberTransition.MatchedGeometry else GlassMemberTransition.None,
            groupKey = groupKey,
        )
    } else {
        val preset = if (role == GlassSurfaceRole.PANEL) HazeMaterials.regular(tintColor) else HazeMaterials.thin(tintColor)
        val material3Blur = HazeBlurStyle.Material3(tintColor)
        val blurStyle = remember(material3Blur, preset, tintColor, accessibility) {
            material3Blur.then(preset).then {
                noiseFactor(0f)
                if (accessibility.reduceTransparency || accessibility.increaseContrast) {
                    colorEffects(listOf(HazeColorEffect.tint(tintColor.copy(alpha =
                        if (accessibility.reduceTransparency) 1f else .86f))))
                }
                fallbackColorEffect(HazeColorEffect.tint(tintColor.copy(alpha =
                    if (accessibility.reduceTransparency || accessibility.increaseContrast) 1f else .90f)))
            }
        }
        Modifier.hazeBlur(input = input, style = blurStyle)
    }
    val shadow = if (softCard) Modifier.softSurfaceShadow(shape, light,
        strength = elevation?.let { (it.value / 16f).coerceIn(0f, 1f) } ?: if (toolbar) .72f else 1f,
        radius = if (toolbar) 16.dp else 24.dp,
        strengthScale = { 1f + .28f * expansion.value().coerceIn(0f, 1f) })
    else Modifier.shadow(elevation ?: 3.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = .04f), spotColor = Color.Black.copy(alpha = .065f))
    CompositionLocalProvider(LocalContentColor provides foreground) {
        // Haze draws the material below its content and transforms only that material.
        Box(modifier.then(shadow).clip(shape).then(material), content = content)
    }
}
