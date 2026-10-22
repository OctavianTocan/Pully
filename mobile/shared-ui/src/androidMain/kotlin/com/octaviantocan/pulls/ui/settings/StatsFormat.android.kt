package com.octaviantocan.pulls.ui.settings

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun statsCount(value: Long, locale: String): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag(locale)).format(value)
internal actual fun statsDecimal(value: Double, decimals: Int, grouping: Boolean): String =
    String.format(Locale.US, if (grouping) "%,.${decimals}f" else "%.${decimals}f", value)
internal actual fun statsDay(value: String, locale: String): String =
    runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag(locale))) }.getOrDefault(value)

/** Existing Android callers/tests may still supply their actual java Locale. */
fun StatsFormat.count(value: Long, locale: Locale): String = statsCount(value, locale.toLanguageTag())
fun StatsFormat.day(value: String, locale: Locale): String = statsDay(value, locale.toLanguageTag())
