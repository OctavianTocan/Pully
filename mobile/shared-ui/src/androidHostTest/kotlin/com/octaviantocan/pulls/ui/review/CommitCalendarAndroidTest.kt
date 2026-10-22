package com.octaviantocan.pulls.ui.review

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CommitCalendarAndroidTest {
    @Test fun portableLabelsMatchExistingAndroidFormatterAcrossYearAndWeekBoundaries() {
        val today = LocalDate.parse("2026-01-05")
        val zone = ZoneId.of("America/Los_Angeles")
        for (offset in -8L..400L) {
            val day = today.minusDays(offset)
            val commit = PrCommit("oid", "oid", "Title", committedDate = day.atStartOfDay(zone).toInstant().toString())
            val android = commitDays(listOf(commit), today, zone).single().first
            val portable = commitDayLabel(kotlinx.datetime.LocalDate.parse(day.toString()), kotlinx.datetime.LocalDate.parse(today.toString()))
            assertEquals(android, portable, day.toString())
        }
    }

    @Test fun groupingUsesLocalDayAcrossOffsetAndDaylightSavingChange() {
        val commits = listOf(
            PrCommit("a", "a", "First", committedDate = "2026-03-08T07:59:00Z"),
            PrCommit("b", "b", "Second", committedDate = "2026-03-08T08:01:00Z"),
            PrCommit("c", "c", "Undated"),
        )
        assertEquals(
            listOf("Yesterday" to listOf(0), "Today" to listOf(1, 2)),
            commitDays(commits, LocalDate.parse("2026-03-08"), ZoneId.of("America/Los_Angeles")),
        )
    }
}
