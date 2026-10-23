@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.home

import platform.Foundation.*

actual fun timeAgo(thenMs: Long, nowMs: Long, locale: String): String {
    val (value, unit) = relativeTime(thenMs, nowMs)
    val components = NSDateComponents().apply {
        when (unit) {
            TimeUnit.Year -> year = value
            TimeUnit.Month -> month = value
            TimeUnit.Week -> weekOfYear = value
            TimeUnit.Day -> day = value
            TimeUnit.Hour -> hour = value
            TimeUnit.Minute -> minute = value
            TimeUnit.Second -> second = value
        }
    }
    return NSRelativeDateTimeFormatter().apply {
        this.locale = NSLocale(localeIdentifier = locale)
        unitsStyle = NSRelativeDateTimeFormatterUnitsStyleFull
        dateTimeStyle = NSRelativeDateTimeFormatterStyleNamed
    }.localizedStringFromDateComponents(components)
}
