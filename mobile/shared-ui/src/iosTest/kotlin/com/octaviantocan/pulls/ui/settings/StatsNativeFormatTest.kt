package com.octaviantocan.pulls.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** These cases execute the actual Foundation formatters on the required Native simulator lane. */
class StatsNativeFormatTest {
    @Test
    fun foundationUsesTheAppsLanguageForCountsAndGregorianDays() {
        assertEquals("12,345", StatsFormat.count(12_345, "en-US"))
        assertEquals("12.345", StatsFormat.count(12_345, "de-DE"))
        assertEquals("15 Oct", StatsFormat.day("2026-10-15", "en-GB"))
        assertEquals("29 Feb", StatsFormat.day("2024-02-29", "en-GB"))
        assertEquals("2023-02-29", StatsFormat.day("2023-02-29", "en-GB"))
        assertEquals("nonsense", StatsFormat.day("nonsense", "en-GB"))
    }

    @Test
    fun originalMoneyTokensAndTimeUnitsUseFixedEnglishDecimalFormatting() {
        assertEquals("$0.00", StatsFormat.usd(0.0))
        assertEquals("<$0.01", StatsFormat.usd(0.004))
        assertEquals("$1,234.50", StatsFormat.usd(1234.5))
        assertEquals("950", StatsFormat.tokens(950))
        assertEquals("12.4k", StatsFormat.tokens(12_400))
        assertEquals("100k", StatsFormat.tokens(100_000))
        assertEquals("3.1M", StatsFormat.tokens(3_100_000))
        assertEquals("42 s", StatsFormat.seconds(42.0))
        assertEquals("5 min", StatsFormat.seconds(300.0))
        assertEquals("2.5 h", StatsFormat.seconds(9_000.0))
        assertEquals("350 ms", StatsFormat.ms(350.0))
        assertEquals("0 ms", StatsFormat.ms(0.49999999999999994))
        assertEquals("1 ms", StatsFormat.ms(0.5))
        assertEquals("1.2 s", StatsFormat.ms(1_200.0))
    }
}
