package com.octaviantocan.pulls.ui.home

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GithubStatsTest {
    @Test
    fun monthlyTotalsUseTheActualLeapDayAndKeepTheLastTwelveCalendarMonths() {
        val stats = GithubStats.parse(Json.parseToJsonElement("""{"login":"octo","firstDay":"2024-02-28","days":[1,2,3],"total":6}"""))!!
        val months = stats.months()
        assertEquals(12, months.size)
        assertEquals(LocalDate(2023, 4, 1), months.first().first)
        assertEquals(listOf(LocalDate(2024, 2, 1) to 3, LocalDate(2024, 3, 1) to 3), months.takeLast(2))
        assertEquals(6, months.sumOf { it.second })
    }

    @Test
    fun aYearBoundaryKeepsDecemberAndJanuaryInTheirOwnBars() {
        val stats = GithubStats.parse(Json.parseToJsonElement("""{"firstDay":"2025-12-31","days":[4,7]}"""))!!
        assertEquals(listOf(LocalDate(2025, 12, 1) to 4, LocalDate(2026, 1, 1) to 7), stats.months().takeLast(2))
    }

    @Test
    fun aMissingOrImpossibleCalendarIsNotPresentedAsRealProfileNumbers() {
        assertNull(GithubStats.parse(Json.parseToJsonElement("""{"login":"octo","total":200}""")))
        assertNull(GithubStats.parse(Json.parseToJsonElement("""{"firstDay":"2024-02-30","days":[2]}""")))
    }
}
