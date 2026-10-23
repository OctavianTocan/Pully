package com.octaviantocan.pulls.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import kotlinx.coroutines.delay

/** A non-Phosphor vector (`i-carbon-logo-github`, `i-simple-icons-*`, `i-octicon-*`). */
@Composable
fun DrawableIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalPullsColors.current.text, size: Dp = 18.dp, contentDescription: String? = null) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
}

@Composable
fun timeAgo(thenMs: Long?, nowMs: Long): String {
    if (thenMs == null) return ""
    val locale = AppSettings.get(LocalContext.current).locale
    return remember(thenMs, nowMs / 30_000, locale) { runCatching { timeAgo(thenMs, nowMs, locale) }.getOrDefault("") }
}
