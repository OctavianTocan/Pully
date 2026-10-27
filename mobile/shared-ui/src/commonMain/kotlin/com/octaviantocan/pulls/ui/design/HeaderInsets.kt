package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * The top padding of a screen header. Row W puts the header 48dp from the top of the screen; a
 * phone whose status bar is taller than 40dp keeps its own inset plus 8dp instead, and so does a phone held sideways.
 */
@Composable
fun headerTopPadding(): Dp {
    val bar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().value + 8f
    // A sideways phone has little height to give, so the header sits right under the status bar (rule 207).
    return (if (rememberWindowShape() == WindowShape.Sideways) max(bar, 16f) else max(bar, 48f)).dp
}
