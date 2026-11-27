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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
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
import dev.chrisbanes.haze.glass.hazeGlassContainer
import dev.chrisbanes.haze.glass.material3.material3
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.glass.discoveryGlassShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

internal enum class GlassSurfaceRole {
    CONTROL, ARTWORK, NAVIGATION, SEARCH, PANEL, THUMB, TAB_LENS, READING_DOCK, POPOVER, DRAWER, GALLERY, DETAIL_HERO,
    VIDEO_DOCK,
}

// A compact rail lets cover details through. Expanded search and reading tools need more diffusion.
private val navigationBlur = OpticalSizeValue.Responsive(
    OpticalSizePoint(44.dp, 4.dp), OpticalSizePoint(84.dp, 10.dp), OpticalSizePoint(144.dp, 17.dp),
)
private val readingDockBlur = OpticalSizeValue.Responsive(
    OpticalSizePoint(44.dp, 8.dp), OpticalSizePoint(84.dp, 12.dp), OpticalSizePoint(144.dp, 17.dp),
)
private val desktopReadingDockBlur = OpticalSizeValue.Responsive(
    OpticalSizePoint(44.dp, 4.dp), OpticalSizePoint(84.dp, 7.dp), OpticalSizePoint(144.dp, 11.dp),
)
private val toolbarDepth = OpticalSizeValue.Responsive(
    OpticalSizePoint(44.dp, .58f), OpticalSizePoint(84.dp, .78f), OpticalSizePoint(144.dp, .92f),
)
private val glassPressIn = spring<Float>(dampingRatio = 1f, stiffness = MangaMotion.PressAttackStiffness)
private val glassPressOut = spring<Float>(dampingRatio = MangaMotion.PressDamping, stiffness = MangaMotion.PressStiffness)
private val glassSettle = spring<Float>(dampingRatio = MangaMotion.SwitchDamping, stiffness = MangaMotion.NavigationStiffness)
private const val MaxTabLensOpacity = .76f

/** The ink inside equal hit cells. Label bounds come from the same type size and weights as the tabs. */
internal data class HeaderInkCells(val inset: Dp, val ink: List<DpSize>, val wraps: Boolean = false)

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
    headerInk: HeaderInkCells? = null,
    lensTravel: () -> Float = { 0f },
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val surface = surfaceColor ?: colors.surface
    val light = surface.luminance() > .5f
    val navigationTint = if (light) .35f else .51f
    val foreground = contentColor ?: if (surfaceColor == null) colors.onSurface
        else if (light) Color(0xFF242426) else Color.White
    val toolbar = role == GlassSurfaceRole.NAVIGATION || role == GlassSurfaceRole.SEARCH || role == GlassSurfaceRole.READING_DOCK
    val desktopReadingDock = platformKind == PlatformKind.DESKTOP && role == GlassSurfaceRole.READING_DOCK && surfaceColor == null
    val compactHeader = headerInk != null && role == GlassSurfaceRole.NAVIGATION
    val tabLens = role == GlassSurfaceRole.TAB_LENS
    val tintColor = when {
        surfaceColor != null -> surfaceColor
        compactHeader -> surface
        toolbar || tabLens -> lerp(colors.surface, colors.surfaceContainerHigh, .24f)
        softCard -> lerp(colors.background, surface, .70f)
        else -> surface
    }
    val protectArtworkInk = role == GlassSurfaceRole.ARTWORK && contentColor != null
    val shape = remember(cornerRadius, surfaceShape) { surfaceShape ?: RoundedCornerShape(cornerRadius) }
    val liquid = role != GlassSurfaceRole.PANEL && role != GlassSurfaceRole.POPOVER && role != GlassSurfaceRole.DRAWER
    val clear = role == GlassSurfaceRole.CONTROL || role == GlassSurfaceRole.ARTWORK ||
        role == GlassSurfaceRole.THUMB || tabLens || role == GlassSurfaceRole.VIDEO_DOCK
    val motion = LocalMotionEnabled.current
    val accessibility = LocalGlassAccessibilitySettings.current
    if (tabLens && (!motion || accessibility.reduceTransparency || accessibility.increaseContrast)) {
        Box(modifier, content = content)
        return
    }
    // Declaring hover/press installs Haze's non-consuming, node-local pointer observer.
    // External sources add focus/keyboard/ancestor presses; raw touch needs no second detector.
    val interactive = liquid && motion && !tabLens && (trackPointer || interactionSource != null)
    val pointerLens = platformKind == PlatformKind.DESKTOP && (toolbar || clear) &&
        !accessibility.increaseContrast && !accessibility.reduceTransparency
    val expansion = rememberUpdatedState(materialExpansion)
    val lensDirection = if (tabLens) rememberUpdatedState(lensTravel) else null
    // Popups/dialogs, artwork-specific samples and nested glass controls need the
    // explicitly selected source. A window backdrop cannot cross a window boundary.
    val input = remember(state, role, preferSource) {
        val sources = HazeInput.Sources(state, retention = if (preferSource || role == GlassSurfaceRole.DRAWER ||
            role == GlassSurfaceRole.VIDEO_DOCK)
            HazeSourceRetention.ClearWhenUnavailable else HazeSourceRetention.KeepLastFrame)
        if (!preferSource && toolbar)
            HazeInput.Backdrop(sources)
        else sources
    }
    val material = if (liquid) {
        val themedGlass = (if (clear) GlassStyle.clear else GlassStyle.regular).material3(tintColor)
        val glassStyle = remember(themedGlass, shape, role, interactive, light, accessibility, protectArtworkInk, compactHeader, pointerLens, desktopReadingDock) {
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
                    GlassSurfaceRole.NAVIGATION, GlassSurfaceRole.SEARCH -> if (compactHeader) {
                        // The clear centre carries cover colour. The actual optical rim supplies
                        // the glint on a short curved segment as the boundary turns toward the light.
                        optics { copy(refractionStrength = .66f, refractionHeightFraction = .19f,
                            refractionDisplacement = 8.dp, refractionProfile = RefractionProfile.Edge(3.5.dp),
                            depth = OpticalSizeValue.Fixed(.52f), blurRadius = OpticalSizeValue.Fixed(2.5.dp),
                            refractionDetailIntensity = 0f) }
                        tint(tintColor.copy(alpha = if (light) .23f else .37f))
                        whitePoint(0f)
                        contrast(0f)
                        edgeSoftness(.8.dp)
                        specularIntensity(if (light) .43f else .32f)
                        specularExponent(52f)
                        ambientResponse(.035f)
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.025f)
                    } else {
                        optics { copy(refractionStrength = .58f, refractionHeightFraction = .22f,
                            refractionDisplacement = 9.dp + 2.dp * progress,
                            refractionProfile = RefractionProfile.Edge(4.dp + 1.dp * progress),
                            blurRadius = navigationBlur, depth = toolbarDepth) }
                        val bodyTint = navigationTint + (if (light) .20f else .18f) * progress
                        tint(tintColor.copy(alpha = bodyTint))
                        whitePoint(if (light) .015f else -.03f)
                        contrast(0f)
                        specularIntensity(if (light) .28f else .22f)
                        specularExponent(32f)
                        ambientResponse(.055f)
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.045f)
                    }
                    GlassSurfaceRole.TAB_LENS -> {
                        // The lit arc rolls toward the leading shoulder of the lens, then recentres as it settles.
                        val direction = lensDirection?.value?.invoke()?.coerceIn(-1f, 1f) ?: 0f
                        val stretch = kotlin.math.abs(direction)
                        lightPosition(BiasAlignment(-.16f + .74f * direction, -.95f))
                        alpha(MaxTabLensOpacity * progress)
                        optics { copy(refractionStrength = .62f + .20f * stretch, refractionHeightFraction = .30f,
                            refractionDisplacement = 7.dp + 3.dp * stretch, refractionProfile = RefractionProfile.Edge(4.dp),
                            depth = OpticalSizeValue.Fixed(.50f), blurRadius = OpticalSizeValue.Fixed(1.5.dp),
                            refractionDetailIntensity = 0f) }
                        tint(tintColor.copy(alpha = navigationTint))
                        whitePoint(if (light) .015f else -.03f)
                        contrast(0f)
                        specularIntensity(if (light) .40f else .32f)
                        specularExponent(28f)
                        ambientResponse(.065f)
                        chromaticAberrationStrength(0f)
                        contentNormalBlend(.045f)
                    }
                    GlassSurfaceRole.READING_DOCK -> {
                        optics { copy(refractionStrength = if (desktopReadingDock) .43f else .32f,
                            refractionHeightFraction = .20f,
                            refractionDisplacement = 6.dp + 2.dp * progress,
                            refractionProfile = RefractionProfile.Edge(4.dp + 1.dp * progress),
                            blurRadius = if (desktopReadingDock) desktopReadingDockBlur else readingDockBlur, depth = toolbarDepth) }
                        val bodyTint = when {
                            desktopReadingDock -> if (light) .36f else .46f
                            light -> .60f
                            else -> .71f
                        }
                        tint(tintColor.copy(alpha = bodyTint + .025f * progress))
                        whitePoint(if (desktopReadingDock) 0f else if (light) .045f else -.08f)
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
                else if (compactHeader) chromaMultiplier(1.015f)
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
                    val touchLens = (role == GlassSurfaceRole.NAVIGATION || role == GlassSurfaceRole.SEARCH || role == GlassSurfaceRole.CONTROL) &&
                        !accessibility.reduceTransparency && !accessibility.increaseContrast
                    interactionLightRadiusFraction(when {
                        pointerLens && toolbar -> .34f
                        pointerLens -> .56f
                        touchLens -> .98f
                        clear -> .76f
                        else -> .66f
                    })
                    hovered {
                        animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) {
                            lightingIntensity(if (pointerLens) .36f else .28f)
                            if (pointerLens) refractionMultiplier(if (clear) 1.09f else 1.06f)
                        }
                    }
                    focused { animate(tween(MangaMotion.Quick), tween(MangaMotion.Content)) { lightingIntensity(.36f) } }
                    pressed {
                        if (pointerLens) {
                            // Keep long mouse-driven toolbars anchored. The material catches the
                            // pointer and recovers its depth; the text and hit targets stay still.
                            animate(glassPressIn, tween(MangaMotion.Quick)) {
                                lightingIntensity(if (light) .60f else .50f)
                            }
                            animate(glassPressIn, glassSettle) {
                                refractionMultiplier(if (clear) 1.34f else 1.24f)
                                scale(scaleX = if (toolbar) 1f else .989f, scaleY = if (toolbar) .982f else .962f)
                            }
                        } else if (touchLens) {
                            // The contact light fades first; the refracted edge and material then settle.
                            animate(glassPressIn, tween(MangaMotion.Quick)) {
                                lightingIntensity(if (light) .82f else .70f)
                                whitePointDelta(if (light) .025f else .015f)
                            }
                            animate(glassPressIn, glassSettle) {
                                refractionMultiplier(if (clear) 1.65f else 1.72f - .24f * progress)
                                scale(scaleX = if (clear) .95f else .985f + .01f * progress,
                                    scaleY = if (clear) .91f else .935f + .04f * progress)
                            }
                        } else animate(glassPressIn, glassPressOut) {
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
            memberTransition = if (motion && !tabLens) GlassMemberTransition.MatchedGeometry else GlassMemberTransition.None,
            groupKey = groupKey,
        )
    } else {
        val preset = if (role == GlassSurfaceRole.PANEL) HazeMaterials.regular(tintColor) else HazeMaterials.thin(tintColor)
        val material3Blur = HazeBlurStyle.Material3(tintColor)
        val blurStyle = remember(material3Blur, preset, role, tintColor, accessibility) {
            material3Blur.then(preset).then {
                blurRadius(when (role) {
                    GlassSurfaceRole.POPOVER -> 18.dp
                    GlassSurfaceRole.DRAWER -> 24.dp
                    else -> 27.dp
                })
                noiseFactor(0f)
                if (accessibility.reduceTransparency || accessibility.increaseContrast) {
                    colorEffects(listOf(HazeColorEffect.tint(tintColor.copy(alpha =
                        if (accessibility.reduceTransparency) 1f else .93f))))
                } else if (role == GlassSurfaceRole.POPOVER) {
                    colorEffects(listOf(HazeColorEffect.tint(tintColor.copy(alpha = if (light) .74f else .79f))))
                } else {
                    // A single frosted sheet. The heading has a clear paper ground; more of the
                    // underlying colour comes through as the surface falls away from the light.
                    val top = if (role == GlassSurfaceRole.DRAWER) .75f else .83f
                    val base = if (role == GlassSurfaceRole.DRAWER) .60f else .72f
                    val density = if (light) 0f else .06f
                    colorEffects(listOf(HazeColorEffect.tint(Brush.verticalGradient(listOf(
                        tintColor.copy(alpha = top + density), tintColor.copy(alpha = base + density))))))
                }
                fallbackColorEffect(HazeColorEffect.tint(tintColor.copy(alpha =
                    if (accessibility.reduceTransparency || accessibility.increaseContrast) 1f else .94f)))
            }
        }
        Modifier.hazeBlur(input = input, style = blurStyle)
    }
    val shadow = if (compactHeader) Modifier.discoveryGlassShadow(shape, light,
        strength = elevation?.let { (it.value / 10f).coerceIn(0f, 1f) } ?: 1f)
    else if (softCard) Modifier.softSurfaceShadow(shape, light,
        strength = elevation?.let { (it.value / 16f).coerceIn(0f, 1f) } ?: if (toolbar) .72f else 1f,
        radius = if (toolbar || role == GlassSurfaceRole.POPOVER) 14.dp else 20.dp,
        strengthScale = { 1f + .28f * expansion.value().coerceIn(0f, 1f) })
    else Modifier.shadow(elevation ?: 3.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = .04f), spotColor = Color.Black.copy(alpha = .065f))
    // Blend with the material pixels before drawing the labels. The soft floor/ceiling responds
    // to passing artwork in the same frame, without reading a bitmap or switching glyph colours.
    val inkContrast = if (role == GlassSurfaceRole.SEARCH &&
        !accessibility.reduceTransparency && !accessibility.increaseContrast) {
        Modifier.drawWithCache {
            val inputHeight = 44.dp.toPx()
            val paper = Brush.verticalGradient(listOf(tintColor.copy(alpha = 0f),
                tintColor.copy(alpha = if (light) .48f else .54f)),
                startY = inputHeight * .72f, endY = inputHeight * 1.55f)
            onDrawBehind { if (size.height > inputHeight) drawRect(paper) }
        }
    } else if ((role == GlassSurfaceRole.NAVIGATION || tabLens || desktopReadingDock) &&
        !accessibility.reduceTransparency && !accessibility.increaseContrast) {
        val darkInk = foreground.luminance() < .5f
        val tone = if (darkInk) Color(0xFFA0A0A0) else Color(0xFF56565A)
        val blend = if (darkInk) BlendMode.Lighten else BlendMode.Darken
        if (headerInk != null) Modifier.headerInkContrast(headerInk, tone, blend)
        else Modifier.drawWithCache {
            // A comic can be solid black or white. Support the ink through the middle of the
            // dock while the outer curve keeps the page colour and its original contrast.
            val featherSize = if (desktopReadingDock) 9.dp else 4.dp
            val feather = (featherSize.toPx() / size.height.coerceAtLeast(1f)).coerceAtMost(.49f)
            val edge = tone.copy(alpha = 0f)
            val center = tone.copy(alpha = .85f)
            val guard = Brush.verticalGradient(0f to edge, feather to center, 1f - feather to center, 1f to edge)
            onDrawBehind {
                val amount = if (tabLens) MaxTabLensOpacity * expansion.value().coerceIn(0f, 1f) else 1f
                drawRect(guard, alpha = amount, blendMode = blend)
            }
        }
    } else Modifier
    // An outer header container draws its members below all its children. Keep this moving lens
    // in its own paint order, above the rail and below the stationary tab labels.
    val lensContainer = if (tabLens) Modifier.hazeGlassContainer(mergeDistance = 0.dp) else Modifier
    CompositionLocalProvider(LocalContentColor provides foreground) {
        // Haze draws the material below its content and transforms only that material.
        Box(modifier.then(shadow).clip(shape).then(lensContainer).then(material).then(inkContrast), content = content)
    }
}

/** Each correction blends into the sampled pixels behind one label or glyph. The gaps stay clear. */
private fun Modifier.headerInkContrast(cells: HeaderInkCells, tone: Color, blend: BlendMode) = drawWithCache {
    val inset = cells.inset.toPx()
    val slot = (size.width - 2f * inset) / cells.ink.size.coerceAtLeast(1)
    if (slot <= 0f || size.height <= 0f) return@drawWithCache onDrawBehind { }
    val spots = cells.ink.mapIndexed { index, ink ->
        val width = ink.width.toPx().coerceAtMost(slot)
        val lines = if (cells.wraps && ink.width.toPx() > slot) 2 else 1
        val height = (ink.height.toPx() * lines).coerceAtMost(size.height)
        val rx = minOf(slot / 2f, width * .67f + 7.dp.toPx())
        val ry = minOf(size.height / 2f, height * .67f + 5.dp.toPx())
        val visual = if (layoutDirection == LayoutDirection.Rtl) cells.ink.lastIndex - index else index
        val x = inset + (visual + .5f) * slot
        val y = size.height / 2f
        Rect(x - rx, y - ry, x + rx, y + ry)
    }
    val centre = tone.copy(alpha = .94f)
    val guard = Brush.radialGradient(0f to centre, .72f to centre, .87f to tone.copy(alpha = .52f),
        1f to tone.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    onDrawBehind {
        for (spot in spots) withTransform({
            translate(spot.center.x, spot.center.y)
            scale(spot.width / 2f, spot.height / 2f, Offset.Zero)
        }) {
            drawCircle(guard, radius = 1f, center = Offset.Zero, blendMode = blend)
        }
    }
}
