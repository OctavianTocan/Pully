package com.octaviantocan.pulls.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.octaviantocan.pulls.data.AppSettings

/** Android's preferences lookup around the shared picker. */
@Composable
internal fun ColorThemeSheet(onClose: () -> Unit) = ColorThemeSheet(AppSettings.get(LocalContext.current), onClose)
