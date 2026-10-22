package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.octaviantocan.pulls.ui.design.sharedVector

/** The original Android artwork, generated from its canonical XML paths for both Compose hosts. */
@Composable
fun SignInArtwork(mark: SignInMark, tint: Color, size: Dp) {
    val asset = when (mark) {
        SignInMark.Pully -> "ic_stat_pulls"
        SignInMark.Google -> "ic_si_google"
    }
    Icon(requireNotNull(sharedVector(asset)) { "Missing canonical account artwork: $asset" },
        contentDescription = null, modifier = Modifier.size(size), tint = tint)
}
