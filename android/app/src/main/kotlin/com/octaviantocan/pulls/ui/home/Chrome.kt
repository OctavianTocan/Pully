package com.octaviantocan.pulls.ui.home

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.os.Build
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

/** The prototype's status colors: `--green` and `--danger` (lifted a step in the dark theme), the web's amber for pending. */
object StatusColors {
    fun green(dark: Boolean) = if (dark) Color(0xFF3CC98F) else Color(0xFF1C8A5A)
    fun red(dark: Boolean) = if (dark) Color(0xFFE8777C) else Color(0xFFC94F55)
    val amber500 = com.octaviantocan.pulls.ui.design.LightPullsColors.checksPending
    fun amberText(dark: Boolean) = if (dark) Color(0xFFFBBF24) else Color(0xFFB45309)
}

/** A non-Phosphor vector (`i-carbon-logo-github`, `i-simple-icons-*`, `i-octicon-*`). */
@Composable
fun DrawableIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalPullsColors.current.text, size: Dp = 18.dp, contentDescription: String? = null) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
}

/** `useNow({ interval })`. */
@Composable
fun rememberNow(intervalMs: Long = 60_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMs) {
        while (true) {
            delay(intervalMs)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** `formatTimeAgo`: `Intl.RelativeTimeFormat(locale, { numeric: 'auto' })` through ICU. */
fun timeAgo(thenMs: Long, nowMs: Long, locale: String): String {
    val (value, unit) = relativeTime(thenMs, nowMs)
    val formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLanguageTag(locale))
    if (Build.VERSION.SDK_INT >= 28) {
        val icuUnit = when (unit) {
            TimeUnit.Year -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR
            TimeUnit.Month -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH
            TimeUnit.Week -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK
            TimeUnit.Day -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY
            TimeUnit.Hour -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR
            TimeUnit.Minute -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE
            TimeUnit.Second -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
        }
        return formatter.format(value.toDouble(), icuUnit)
    }
    if (unit == TimeUnit.Second) return formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
    val relUnit = when (unit) {
        TimeUnit.Year -> RelativeDateTimeFormatter.RelativeUnit.YEARS
        TimeUnit.Month -> RelativeDateTimeFormatter.RelativeUnit.MONTHS
        TimeUnit.Week -> RelativeDateTimeFormatter.RelativeUnit.WEEKS
        TimeUnit.Day -> RelativeDateTimeFormatter.RelativeUnit.DAYS
        TimeUnit.Hour -> RelativeDateTimeFormatter.RelativeUnit.HOURS
        else -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
    }
    val direction = if (value < 0) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
    return formatter.format(kotlin.math.abs(value).toDouble(), direction, relUnit)
}

@Composable
fun timeAgo(thenMs: Long?, nowMs: Long): String {
    if (thenMs == null) return ""
    val locale = AppSettings.get(LocalContext.current).locale
    return remember(thenMs, nowMs / 30_000, locale) { runCatching { timeAgo(thenMs, nowMs, locale) }.getOrDefault("") }
}

fun parseInstant(text: String): Long? = runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()

fun Modifier.bottomBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    val y = size.height - width.toPx() / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), width.toPx())
}

fun Modifier.topBorder(color: Color, width: Dp = 1.dp): Modifier = drawBehind {
    drawLine(color, Offset(0f, width.toPx() / 2), Offset(size.width, width.toPx() / 2), width.toPx())
}
