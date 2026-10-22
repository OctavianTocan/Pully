package com.octaviantocan.pulls.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun rememberHardwareKeyboardPresent() =
    LocalConfiguration.current.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
