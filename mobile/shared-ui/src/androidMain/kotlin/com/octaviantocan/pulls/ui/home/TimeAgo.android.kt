package com.octaviantocan.pulls.ui.home

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.os.Build

/** `formatTimeAgo`: `Intl.RelativeTimeFormat(locale, { numeric: 'auto' })` through ICU. */
actual fun timeAgo(thenMs: Long, nowMs: Long, locale: String): String {
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
