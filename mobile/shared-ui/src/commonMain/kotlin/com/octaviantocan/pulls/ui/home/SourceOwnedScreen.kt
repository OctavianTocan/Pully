package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/** Saveable route input and transient private cards belong to the same explicit source as their rows. */
@Composable
internal fun SourceOwnedScreen(owner: HomeScreenData, content: @Composable () -> Unit) {
    key(owner) { content() }
}
