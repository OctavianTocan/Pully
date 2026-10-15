package com.octaviantocan.pulls.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.lerp
import com.octaviantocan.pulls.ui.design.ColorTheme
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.LocalColorTheme
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsIndication
import com.octaviantocan.pulls.ui.design.PullsType

// The few stock Material widgets left (menus, dialogs, text selection) read these; the rest of the
// app reads `PullsColors`, which holds the same palette under the prototype's names. Each colour theme
// gets its own scheme from its palette, the way Pulls' light and dark ones were written by hand.
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

/**
 * Material for the few stock widgets left (menus, dialogs), the prototype's tokens
 * ([PullsColors]) and the web's i18n messages for everything else. `dark` follows the
 * app's own setting; the first run is light, the look the app was designed in. [theme] is the
 * colour theme (K18), drawn in its light or dark palette as `dark` asks.
 */
@Composable
fun PullsTheme(dark: Boolean = isSystemInDarkTheme(), theme: ColorTheme = ColorThemes.Pulls, locale: String = Messages.detect(), content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages = androidx.compose.runtime.remember(locale) { Messages.load(context, locale) }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 768
    if (PullsType.wide != wide) PullsType.wide = wide
    val colors = theme.colors(dark)
    val scheme = androidx.compose.runtime.remember(colors) { materialScheme(colors) }
    MaterialTheme(colorScheme = scheme) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPullsColors provides colors,
            LocalColorTheme provides theme,
            LocalIndication provides PullsIndication,
            LocalMessages provides messages,
            androidx.compose.material3.LocalTextStyle provides PullsType.base,
            content = content,
        )
    }
}
