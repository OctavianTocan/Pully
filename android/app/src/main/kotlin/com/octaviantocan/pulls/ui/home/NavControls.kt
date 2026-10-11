package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.t

/**
 * The web `NavControls` (language menu, settings, dark toggle), used in `AppHeader` on
 * home and in the review page header. The command palette button is `sm:` and up
 * on the web and keyboard-driven, so the phone leaves it out.
 */
@Composable
fun NavControls(nav: Nav, modifier: Modifier = Modifier) {
    NavControlsRow(nav, modifier)
}

/** [NavControls] with leading extra buttons, the web component's default slot. */
@Composable
fun NavControlsRow(nav: Nav, modifier: Modifier = Modifier, leading: @Composable RowScope.() -> Unit = {}) {
    // `gap-1 coarse:gap-0`: the 44dp hit areas already space the icons.
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        leading()
        // `hidden sm:flex`: the palette button with its `mod+k` hint.
        if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 640) PaletteButton()
        LanguageMenu()
        IconButton("gear-duotone", t("common.settings"), nav.settings)
        DarkToggle()
    }
}

/** The palette button: `border border-base rounded-md px-1.5 py-1 op-fade`, a magnifier and `DisplayKbd mod+k`. */
@Composable
fun PaletteButton() {
    val c = LocalPullsColors.current
    val label = t("palette.title")
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.defaultMinSize(minWidth = com.octaviantocan.pulls.ui.design.TOUCH, minHeight = com.octaviantocan.pulls.ui.design.TOUCH)
            .clickable(onClickLabel = t("palette.title")) { com.octaviantocan.pulls.ui.Commands.open() }
            .semantics { contentDescription = label }.padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            androidx.compose.ui.Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            com.octaviantocan.pulls.ui.design.PhIcon("magnifying-glass-duotone", tint = c.fade, size = 14.dp)
            Kbd("Ctrl")
            Kbd("K")
        }
    }
}

/** `DisplayKbd`: a small bordered key cap. */
@Composable
fun Kbd(key: String) {
    val c = LocalPullsColors.current
    Text(
        key,
        androidx.compose.ui.Modifier.border(1.dp, c.border, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        style = com.octaviantocan.pulls.ui.design.PullsType.micro.copy(fontFamily = com.octaviantocan.pulls.ui.design.PullsType.mono),
        color = c.fade,
        maxLines = 1,
        softWrap = false,
    )
}

/** `LanguageMenu`: the translate button and an in-flow list of the 12 locales by native name. */
@Composable
fun LanguageMenu() {
    val settings = AppSettings.get(LocalContext.current)
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton("translate-duotone", t("settings.language"), { open = !open }, active = open)
        PullsDropdown(open, { open = false }) {
            Messages.LOCALES.forEach { (code, native) ->
                DropdownOption(native, code == settings.locale, {
                    settings.updateLocale(code)
                    open = false
                })
            }
        }
    }
}

/** `DarkToggle`: sun in light mode, moon in dark; flips the app theme. */
@Composable
fun DarkToggle() {
    val settings = AppSettings.get(LocalContext.current)
    val dark = LocalPullsColors.current.dark
    IconButton(if (dark) "moon-duotone" else "sun-duotone", if (dark) "Dark" else "Light", { settings.updateTheme(if (dark) "light" else "dark") })
}
