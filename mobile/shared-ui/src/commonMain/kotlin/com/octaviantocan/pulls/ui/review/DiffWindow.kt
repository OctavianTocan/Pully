package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable

/** The same native viewport used for code's split columns and long-press hit area. */
@Composable
internal expect fun diffScreenWidthDp(): Int

/** How wide the code's page is: its pane in an unfolded window or a sheet's panel, else the native viewport. */
@Composable
internal fun diffWidthDp(): Int = com.octaviantocan.pulls.ui.design.LocalPageWidth.current?.value?.let { kotlin.math.round(it).toInt() } ?: diffScreenWidthDp()
