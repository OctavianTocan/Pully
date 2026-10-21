package com.octaviantocan.pulls.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.octaviantocan.pulls.ui.design.LocalColorTheme
import com.octaviantocan.pulls.ui.design.ColorTheme
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.load
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsType

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
    CompositionLocalProvider(LocalColorTheme provides theme) {
        com.octaviantocan.pulls.ui.design.PullsMaterialTheme(theme.colors(dark), messages, content)
    }
}
