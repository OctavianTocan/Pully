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

/** A non-Phosphor vector (`i-carbon-logo-github`, `i-simple-icons-*`, `i-octicon-*`). */
@Composable
fun DrawableIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalPullsColors.current.text, size: Dp = 18.dp, contentDescription: String? = null) {
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)
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
