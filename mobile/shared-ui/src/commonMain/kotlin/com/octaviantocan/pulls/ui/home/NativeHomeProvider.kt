package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.staticCompositionLocalOf

/** The native scene installs its real services before shared interactive screens consume them. */
val LocalNativeHomeActions = staticCompositionLocalOf<NativeHomeActions> {
    error("Install native clipboard, external-link and lifecycle services before presenting Home content.")
}
