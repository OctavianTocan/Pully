package com.octaviantocan.pulls.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** The shared panel owns its scrim and motion; native code supplies its edge-to-edge modal window. */
internal expect object SheetPlatform {
    @Composable
    fun Dialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

    /** Current native container height, before keyboard consumption and safe-area padding. */
    @Composable
    fun screenHeight(): Dp
}
