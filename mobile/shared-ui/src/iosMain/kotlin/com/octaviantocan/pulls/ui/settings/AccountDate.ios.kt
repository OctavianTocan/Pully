package com.octaviantocan.pulls.ui.settings

import com.octaviantocan.pulls.data.pullTimestampMillis
import platform.Foundation.*

actual fun dayOf(iso: String): String {
    val millis = pullTimestampMillis(iso) ?: return iso
    val formatter = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
        timeZone = NSTimeZone.localTimeZone
        dateFormat = "d MMM yyyy, HH:mm"
    }
    return formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
}
