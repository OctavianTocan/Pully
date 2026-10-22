package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.octaviantocan.pulls.settings.AppSettings

/** Whether the Light, Dark and System choice asks for the dark look right now. */
@Composable
fun wantsDark(settings: AppSettings): Boolean {
    val system = isSystemInDarkTheme()
    return when (settings.theme) { "dark" -> true; "light" -> false; else -> system }
}
