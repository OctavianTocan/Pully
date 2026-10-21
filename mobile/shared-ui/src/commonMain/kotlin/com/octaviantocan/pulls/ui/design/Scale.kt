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

/** The blur of each canvas shadow (Row W), in dp. `PullsShadow` adds the offset and the strength. */
object PullsElevation {
    val button = 10.dp
    val note = 12.dp
    val bar = 14.dp
    val search = 16.dp
    val menu = 20.dp
    val thumb = 3.dp
    val modal = 40.dp
}
