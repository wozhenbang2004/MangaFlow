package dev.mangaflow.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.staticCompositionLocalOf

/** beUI motion roles adapted to Compose. Fixed upstreams and licenses are in THIRD_PARTY_NOTICES. */
object MangaMotion {
    const val Quick = 120
    const val Content = 180
    const val Enter = 260
    const val Exit = 180
    val EnterEasing = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val ExitEasing = CubicBezierEasing(.4f, 0f, 1f, 1f)
    // beUI PRESS: stiffness 500, damping 30, mass .6, converted to Compose's unit mass.
    const val PressStiffness = 833f
    const val PressDamping = .866f
    const val PressAttackStiffness = 1_600f
    const val IconPressScale = .94f
    const val SurfacePressScale = .985f
    const val HapticInterval = 70L
    const val BusyDelay = 160L
    const val TabLeadingStiffness = 520f
    const val TabTrailingStiffness = 230f
    const val TabLeadingDamping = 1.009f
    const val TabTrailingDamping = .989f
    const val PanelStiffness = 840f
    const val PanelDamping = 1.38f
    // Approximate Compose counterpart of beUI Morphing Search's 580ms / .22 bounce role.
    const val SearchStiffness = 230f
    const val SearchDamping = .78f
    const val SearchClip = 320
    const val NavigationStiffness = 520f
    const val NavigationDamping = 1f
    const val NavigationFade = 160
    const val RootEntryAlpha = .9f
    const val RootFadeIn = 180
    const val GestureStiffness = 420f
    const val GestureDamping = .86f
    const val SwitchStiffness = 300f
    const val SwitchDamping = .72f
    const val Chrome = 180
    val SearchEasing = CubicBezierEasing(.16f, 1f, .3f, 1f)
    const val ListEnter = 200
    const val ListExit = 140
    val ListFadeEasing = CubicBezierEasing(.2f, 0f, .15f, 1f)
}

val LocalMotionEnabled = staticCompositionLocalOf { true }
val LocalTouchExplorationEnabled = staticCompositionLocalOf { false }
