package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal actual fun diffScreenWidthDp(): Int = LocalConfiguration.current.screenWidthDp
