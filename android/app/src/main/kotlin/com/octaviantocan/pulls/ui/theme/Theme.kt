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

// Web app palette: dark surface from main.css, accent trio from uno.config.ts.
private val Teal = Color(0xFF5FC2A4)
private val TealDeep = Color(0xFF1F8F72)
private val Orange = Color(0xFFF57A4F)
private val Ink = Color(0xFF111111)

private val Dark = darkColorScheme(
    primary = Teal,
    onPrimary = Ink,
    primaryContainer = Color(0xFF16382F),
    secondary = Orange,
    background = Ink,
    surface = Ink,
    surfaceVariant = Color(0xFF1C1C1C),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFFA3A3A3),
    outline = Color(0xFF2A2A2A),
    error = Color(0xFFE85D4C),
    errorContainer = Color(0xFF3A1C18),
)

private val Light = lightColorScheme(
    primary = TealDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F3EA),
    secondary = Color(0xFFD9552A),
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFF4F4F4),
    onBackground = Color(0xFF171717),
    onSurface = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF5C5C5C),
    outline = Color(0xFFE4E4E4),
    error = Color(0xFFB42318),
    errorContainer = Color(0xFFFDE8E6),
)

/**
 * Material for the few stock widgets left (menus, dialogs), the web tokens
 * ([PullsColors]) and the web's i18n messages for everything else. `dark` follows the
 * app's own setting when one is saved, the system otherwise, like the web `DarkToggle`.
 */
@Composable
fun PullsTheme(dark: Boolean = isSystemInDarkTheme(), locale: String = Messages.detect(), content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages = androidx.compose.runtime.remember(locale) { Messages.load(context, locale) }
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalPullsColors provides if (dark) DarkPullsColors else LightPullsColors,
            LocalMessages provides messages,
            androidx.compose.material3.LocalTextStyle provides PullsType.base,
            content = content,
        )
    }
}
