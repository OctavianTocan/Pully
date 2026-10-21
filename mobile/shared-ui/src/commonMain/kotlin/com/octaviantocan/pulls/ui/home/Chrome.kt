package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The prototype's status colors: `--green` and `--danger` (lifted a step in the dark theme), the web's amber for pending. */
object StatusColors {
    fun green(dark: Boolean) = if (dark) Color(0xFF3CC98F) else Color(0xFF1C8A5A)
    fun red(dark: Boolean) = if (dark) Color(0xFFE8777C) else Color(0xFFC94F55)
    val amber500 = com.octaviantocan.pulls.ui.design.LightPullsColors.checksPending
    fun amberText(dark: Boolean) = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
}

/** `useNow({ interval })`. */
@Composable
fun rememberNow(intervalMs: Long = 60_000): Long {
    var now by remember { mutableLongStateOf(kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        }
    }
    return now
}

fun parseInstant(text: String): Long? = runCatching { kotlin.time.Instant.parse(text).toEpochMilliseconds() }.getOrNull()

fun Modifier.bottomBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val y = size.height - width.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), width.toPx())
}

fun Modifier.topBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    drawLine(color, Offset(0f, width.toPx() / 2), Offset(size.width, width.toPx() / 2), width.toPx())
}
