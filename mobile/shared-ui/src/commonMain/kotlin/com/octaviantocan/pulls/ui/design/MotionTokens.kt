package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/*
 * Motion, from transitions.dev: one smooth-out curve, a short list of durations, and a little
 * blur and scale on things that appear. Menus and modals scale from just under full size and grow
 * out of their trigger; a page change slides eight pixels through a 3px blur; text swaps through
 * 2px. Closing is quicker than opening. Everything moves with transform, opacity and blur.
 */
object PullsMotion {
    const val STAGGER = 40
    const val MICRO = 80
    const val QUICK = 150
    const val FAST = 250
    const val MEDIUM = 350
    const val SLOW = 400
    const val VERY_SLOW = 500

    /** One pass of the skeleton's light band (rule 85). */
    const val SHIMMER = 1200

    /** `--ease-smooth-out`: modals, dropdowns, panels, page slides and resizes. */
    val SmoothOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** `ease-in-out`: icon and text swaps. */
    val InOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Gathering speed to the end: a page leaving the whole width, so the next one arriving on [SmoothOut] carries on its speed (H36). */
    val Accelerate = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** A dropdown opens from 0.97 and closes to 0.99. */
    const val DROPDOWN_OPEN_SCALE = 0.97f
    const val DROPDOWN_CLOSE_SCALE = 0.99f

    /** A modal opens and closes through 0.96. */
    const val MODAL_SCALE = 0.96f

    /** An icon swap starts and ends at a quarter size. */
    const val ICON_SCALE = 0.25f

    val BlurSmall = 2.dp
    val BlurMedium = 3.dp

    /** The most an edge fade blurs, at the edge itself (rule 74). */
    val EdgeBlur = 9.dp

    /** How deep the fade under a pinned header goes, and under a bottom pill (rule 74). */
    val EdgeTop = 32.dp
    val EdgeBottom = 120.dp

    /** How far a page slides in (`--distance-base`). */
    val SlideDistance = 8.dp

    /** How far a finger can drag the rows of a page, and where they have faded most (rule 88). */
    val FingerRange = 96.dp

    /** The room between two pages that sit side by side, past the screen's own margins (H20, H36), narrowed by K7. */
    val PageGap = 16.dp

    /** A throw faster than this, per second, changes the page however short the drag was (rule 105). */
    val Fling = 500.dp

    /**
     * How a sheet comes up and settles (H3): a spring that goes a little past its stop and comes back, instead of a
     * straight glide. Damping 0.74 overshoots by about 3% of the way, a fifth less than the 0.72 that was "a bit much"
     * (J1), and stiffness 320 settles in about 450ms.
     */
    val SheetSpring = spring<Float>(dampingRatio = 0.74f, stiffness = 320f)

    /**
     * How pages that sit side by side settle after a tab or a swipe (H20): no overshoot, so the edge of the next page
     * never shows, landing in about 400ms and carrying the speed the finger let go at. It is measured in pages, so it
     * runs on to half a pixel: with the default threshold of a hundredth it stopped 10px short and snapped the rest (J7).
     */
    val PageSpring = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = 0.0005f)
}

/** False when the native accessibility setting asks animations to hold still (rule 73). */
@Composable
expect fun rememberMotionEnabled(): Boolean
