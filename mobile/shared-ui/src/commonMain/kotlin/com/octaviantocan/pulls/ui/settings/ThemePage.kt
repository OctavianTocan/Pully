package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.design.ColorTheme
import com.octaviantocan.pulls.ui.design.ColorThemes
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.SearchList
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.ThemeWipe
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A picker keeps the preferences and authority of the Settings/entry that opened it. */
class ColorThemePicker(val settings: AppSettings, private val eligible: () -> Boolean) {
    fun choose(scope: CoroutineScope, theme: ColorTheme, dark: Boolean, motion: Boolean) {
        scope.applyColorTheme(settings, theme, dark, motion, eligible)
    }
}

/**
 * Applies [theme] through the theme wipe (H5), from the top right when it sits further down the list than the theme on
 * screen and from the top left when it sits higher. A theme that looks the same as the one on screen (a one-look theme
 * picked again) changes the setting without a wipe, as the Light and Dark choice does (J2).
 */
fun CoroutineScope.applyColorTheme(settings: AppSettings, theme: ColorTheme, dark: Boolean, motion: Boolean, eligible: () -> Boolean = { true }) {
    if (!eligible()) return
    val current = ColorThemes.of(settings.colorTheme)
    if (current.id == theme.id) return
    val from = ColorThemes.all.indexOf(current)
    val to = ColorThemes.all.indexOf(theme)
    launch {
        ThemeWipe.run(fromRight = to > from, motion = motion, changes = current.colors(dark) != theme.colors(dark)) {
            if (eligible()) settings.updateColorTheme(theme.id)
        }
    }
}

/**
 * Every colour theme, Pully first, each with dots of its screen, accent, success and error colours in the look the
 * Light, Dark and System choice asks for, and a tick on the one in use, under "Find a theme". A tap applies it at once
 * and stays here, so themes can be tried one after the other (K18, L4).
 */
@Composable
fun ColumnScope.ColorThemePage(picker: ColorThemePicker) {
    val settings = picker.settings
    val c = LocalPullsColors.current
    val dark = wantsDark(settings)
    val motion = rememberMotionEnabled()
    val scope = rememberCoroutineScope()
    val chosen = ColorThemes.of(settings.colorTheme)
    // A search over the themes, as the other picking sheets have (recording 15 Oct 15:37, 02:51 to 02:58; L4).
    SearchList(ColorThemes.all, text = { it.name }, placeholder = "Find a theme", empty = "No theme matches") { theme, first ->
        SheetRow(theme.name, { picker.choose(scope, theme, dark, motion) }, first = first, iconless = true) {
            ThemeDots(theme.colors(dark))
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (theme.id == chosen.id) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
            }
        }
    }
}

/** A theme in four dots: its screen, its accent, success and error. The screen's dot is ringed so it shows on any card. */
@Composable
fun ThemeDots(palette: PullsColors, modifier: Modifier = Modifier) {
    val ring = LocalPullsColors.current.border
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(palette.base, palette.accentTeal, palette.success, palette.error).forEachIndexed { index, color ->
            Dot(color, if (index == 0) ring else null)
        }
    }
}

@Composable
private fun Dot(color: Color, ring: Color?) {
    val shape = CircleShape
    Box(Modifier.size(14.dp).background(color, shape).then(if (ring != null) Modifier.border(1.dp, ring, shape) else Modifier))
}

/** The same palette list for Commands on either host. */
@Composable
fun ColorThemeSheet(picker: ColorThemePicker, onClose: () -> Unit) {
    PullsSheet(onClose, t("settings.colorTheme")) { ColorThemePage(picker) }
}

@Composable
fun ColorThemeSheet(settings: AppSettings, eligible: () -> Boolean, onClose: () -> Unit) {
    ColorThemeSheet(ColorThemePicker(settings, eligible), onClose)
}
