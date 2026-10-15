package com.octaviantocan.pulls.ui.settings

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsFormatTest {
    @Test
    fun spendIsDollarsToTheCent() {
        assertEquals("$0.00", StatsFormat.usd(0.0))
        assertEquals("<$0.01", StatsFormat.usd(0.004))
        assertEquals("$1,234.50", StatsFormat.usd(1234.5))
    }

    @Test
    fun timesPickTheirUnit() {
        assertEquals("42 s", StatsFormat.seconds(42.0))
        assertEquals("5 min", StatsFormat.seconds(300.0))
        assertEquals("2.5 h", StatsFormat.seconds(9_000.0))
        assertEquals("350 ms", StatsFormat.ms(350.0))
        assertEquals("1.2 s", StatsFormat.ms(1_200.0))
    }

    @Test
    fun countsAndDaysFollowTheLanguage() {
        assertEquals("12,345", StatsFormat.count(12_345, Locale.US))
        assertEquals("15 Oct", StatsFormat.day("2026-10-15", Locale.UK))
        assertEquals("nonsense", StatsFormat.day("nonsense", Locale.UK))
    }

    @Test
    fun screenNamesReadAsWords() {
        assertEquals("Home", StatsFormat.screen("home"))
        assertEquals("Settings stats", StatsFormat.screen("settings-stats"))
    }
}
