package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.unit.dp

// The shape and space scales from android/DESIGN.md. `pnpm run android:design` fails when a
// value here and the front matter disagree. New code reads these, never a bare number (rule 47).

/** Corner radii. `full` is a stadium: it is clamped to half the shorter side. */
object PullsRadius {
    val xs = 2.dp
    val sm = 4.dp
    val md = 6.dp
    val lg = 8.dp
    val xl = 12.dp
    val card = 16.dp
    val sheet = 24.dp
    val full = 9999.dp
}

/** Spacing steps of 4dp. `x4` is the 16dp page margin. */
object PullsSpace {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x6 = 24.dp
    val x8 = 32.dp
}

/** Shadow elevations. `float` is the canvas shadow of `floatShadow`. */
object PullsElevation {
    val float = 8.dp
    val raised = 4.dp
    val thumb = 2.dp
    val modal = 24.dp
}
