package com.octaviantocan.pulls.ui.home

import kotlinx.datetime.LocalDate
import java.time.format.TextStyle
import java.util.Locale

actual fun profileDateLabel(date: LocalDate, style: ProfileDateStyle): String {
    val native = java.time.LocalDate.of(date.year, date.monthNumber, date.day)
    val locale = Locale.getDefault()
    return when (style) {
        ProfileDateStyle.DayShort -> native.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        ProfileDateStyle.MonthShort -> native.month.getDisplayName(TextStyle.SHORT, locale)
        ProfileDateStyle.MonthFull -> native.month.getDisplayName(TextStyle.FULL, locale)
        ProfileDateStyle.MonthNarrow -> native.month.getDisplayName(TextStyle.NARROW, locale)
    }
}
