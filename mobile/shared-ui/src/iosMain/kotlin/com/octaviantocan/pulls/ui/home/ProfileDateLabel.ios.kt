package com.octaviantocan.pulls.ui.home

import kotlinx.datetime.LocalDate
import platform.Foundation.*

actual fun profileDateLabel(date: LocalDate, style: ProfileDateStyle): String {
    val zone = requireNotNull(NSTimeZone.timeZoneForSecondsFromGMT(0))
    val gregorian = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian).apply { timeZone = zone }
    val components = NSDateComponents().apply { year = date.year.toLong(); month = date.monthNumber.toLong(); day = date.day.toLong() }
    val native = requireNotNull(gregorian.dateFromComponents(components))
    val formatter = NSDateFormatter().apply {
        locale = NSLocale.currentLocale
        // The shared contribution dates use Gregorian months, including on a device with another calendar.
        calendar = gregorian
        timeZone = zone
        dateFormat = when (style) {
            ProfileDateStyle.DayShort -> "EEE"
            ProfileDateStyle.MonthShort -> "MMM"
            ProfileDateStyle.MonthFull -> "MMMM"
            ProfileDateStyle.MonthNarrow -> "MMMMM"
        }
    }
    return formatter.stringFromDate(native)
}
