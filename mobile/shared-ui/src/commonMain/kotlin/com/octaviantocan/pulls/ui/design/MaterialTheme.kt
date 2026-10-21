package com.octaviantocan.pulls.ui.design

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp

// The few stock Material widgets left (menus, dialogs, text selection) read these; the rest of the
// app reads `PullsColors`, which holds the same palette under the prototype's names. Each colour theme
// gets its own scheme from its palette, the way Pully's light and dark ones were written by hand.
private fun materialScheme(c: PullsColors) = if (c.dark) {
    darkColorScheme(
        primary = c.accentTeal,
        onPrimary = c.base,
        primaryContainer = c.accentWash,
        secondary = c.accentOrange,
        background = c.base,
        surface = c.base,
        surfaceVariant = c.raised,
        onBackground = c.text,
        onSurface = c.text,
        onSurfaceVariant = c.muted,
        outline = c.border,
        error = c.error,
        errorContainer = lerp(c.base, c.error, 0.18f),
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = c.onCta,
        primaryContainer = c.accentWash,
        secondary = c.accentOrange,
        background = c.base,
        surface = c.base,
        surfaceVariant = c.paper,
        onBackground = c.text,
        onSurface = c.text,
        onSurfaceVariant = c.muted,
        outline = c.border,
        error = c.error,
        errorContainer = lerp(c.base, c.error, 0.12f),
    )
}

/** The existing Pully Material palette and feedback provider, shared by both native hosts. */
@Composable
fun PullsMaterialTheme(colors: PullsColors, messages: Messages, content: @Composable () -> Unit) {
    val scheme = remember(colors) { materialScheme(colors) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPullsColors provides colors,
            LocalIndication provides PullsIndication,
            LocalMessages provides messages,
            androidx.compose.material3.LocalTextStyle provides PullsType.base,
            content = content,
        )
    }
}
