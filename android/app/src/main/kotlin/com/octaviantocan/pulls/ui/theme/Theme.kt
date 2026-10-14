package com.octaviantocan.pulls.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.ui.design.DarkPullsColors
import com.octaviantocan.pulls.ui.design.LightPullsColors
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsType

// The few stock Material widgets left (menus, dialogs, text selection) read these; the rest of the
// app reads `PullsColors`, which holds the same palette under the prototype's names.
private val Dark = darkColorScheme(
    primary = DarkPullsColors.accentTeal,
    onPrimary = DarkPullsColors.base,
    primaryContainer = DarkPullsColors.accentWash,
    secondary = DarkPullsColors.accentOrange,
    background = DarkPullsColors.base,
    surface = DarkPullsColors.base,
    surfaceVariant = DarkPullsColors.raised,
    onBackground = DarkPullsColors.text,
    onSurface = DarkPullsColors.text,
    onSurfaceVariant = DarkPullsColors.muted,
    outline = DarkPullsColors.border,
    error = DarkPullsColors.error,
    errorContainer = Color(0xFF3A1C18),
)

private val Light = lightColorScheme(
    primary = LightPullsColors.primary,
    onPrimary = Color.White,
    primaryContainer = LightPullsColors.accentWash,
    secondary = LightPullsColors.accentOrange,
    background = LightPullsColors.base,
    surface = LightPullsColors.base,
    surfaceVariant = LightPullsColors.paper,
    onBackground = LightPullsColors.text,
    onSurface = LightPullsColors.text,
    onSurfaceVariant = LightPullsColors.muted,
    outline = LightPullsColors.border,
    error = LightPullsColors.error,
    errorContainer = Color(0xFFFDE8E6),
)

/**
 * Material for the few stock widgets left (menus, dialogs), the prototype's tokens
 * ([PullsColors]) and the web's i18n messages for everything else. `dark` follows the
 * app's own setting; the first run is light, the look the app was designed in.
 */
@Composable
fun PullsTheme(dark: Boolean = isSystemInDarkTheme(), locale: String = Messages.detect(), content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages = androidx.compose.runtime.remember(locale) { Messages.load(context, locale) }
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 768
    if (PullsType.wide != wide) PullsType.wide = wide
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPullsColors provides if (dark) DarkPullsColors else LightPullsColors,
            LocalMessages provides messages,
            androidx.compose.material3.LocalTextStyle provides PullsType.base,
            content = content,
        )
    }
}
