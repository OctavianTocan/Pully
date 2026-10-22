package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.roundToInt

@Composable
internal actual fun diffScreenWidthDp(): Int = with(LocalDensity.current) {
    LocalWindowInfo.current.containerSize.width.toDp().value.roundToInt()
}
