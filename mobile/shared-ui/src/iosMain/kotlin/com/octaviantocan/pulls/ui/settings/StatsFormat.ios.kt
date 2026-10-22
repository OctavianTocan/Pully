@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.octaviantocan.pulls.ui.settings

import kotlinx.datetime.LocalDate
import platform.Foundation.*

internal actual fun statsCount(value: Long, locale: String): String = NSNumberFormatter().apply {
    this.locale = NSLocale(localeIdentifier = locale)
    numberStyle = NSNumberFormatterDecimalStyle
    minimumFractionDigits = 0uL
    maximumFractionDigits = 0uL
}.stringFromNumber(NSNumber(longLong = value)) ?: value.toString()

internal actual fun statsDecimal(value: Double, decimals: Int, grouping: Boolean): String = NSNumberFormatter().apply {
    locale = NSLocale(localeIdentifier = "en_US_POSIX")
    numberStyle = NSNumberFormatterDecimalStyle
    usesGroupingSeparator = grouping
    minimumFractionDigits = decimals.toULong()
    maximumFractionDigits = decimals.toULong()
    roundingMode = NSNumberFormatterRoundHalfUp
}.stringFromNumber(NSNumber(double = value)) ?: value.toString()

internal actual fun statsDay(value: String, locale: String): String = runCatching {
    val date = LocalDate.parse(value)
    val zone = requireNotNull(NSTimeZone.timeZoneForSecondsFromGMT(0))
    val gregorian = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian).apply { timeZone = zone }
    val components = NSDateComponents().apply { year = date.year.toLong(); month = date.monthNumber.toLong(); day = date.day.toLong() }
    val native = requireNotNull(gregorian.dateFromComponents(components))
    NSDateFormatter().apply {
        this.locale = NSLocale(localeIdentifier = locale)
        calendar = gregorian
        timeZone = zone
        dateFormat = "d MMM"
    }.stringFromDate(native)
}.getOrDefault(value)
