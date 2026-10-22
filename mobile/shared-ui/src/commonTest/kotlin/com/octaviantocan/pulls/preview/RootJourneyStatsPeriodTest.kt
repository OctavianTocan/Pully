package com.octaviantocan.pulls.preview

import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RootJourneyStatsPeriodTest {
    @Test
    fun eachRealStatsRequestReturnsItsFullInclusiveUtcPeriodAndMatchingDailyTotals() = runTest {
        val transport = RootJourneyTransport.fullJourney(object : RootJourneyStateStore {
            private var raw: String? = null
            override fun read() = raw
            override fun write(state: String) { raw = state }
        }) { 1_000L }
        val repo = Repo({ RepoConfiguration(PREVIEW_SERVER, mapOf("X-Pully-Session" to RootJourneyTransport.SESSION)) }, transport)
        for ((span, from) in listOf(7 to "2026-10-15", 30 to "2026-09-22", 90 to "2026-07-24")) {
            val summary = repo.analyticsSummary(span)
            assertEquals(from, summary.from)
            assertEquals("2026-10-21", summary.to)
            assertEquals(span, summary.days.size)
            assertEquals(from, summary.days.first().day)
            assertEquals(summary.to, summary.days.last().day)
            assertEquals(span, summary.days.map { it.day }.distinct().size)
            assertEquals(summary.totals.sessions, summary.days.sumOf { it.sessions })
            assertEquals(summary.totals.prsOpened, summary.days.sumOf { it.prsOpened })
            assertEquals(summary.totals.filesReviewed, summary.days.sumOf { it.filesReviewed })
            assertEquals(summary.totals.aiRuns, summary.days.sumOf { it.aiRuns })
            assertEquals(summary.totals.costUsd, summary.days.sumOf { it.costUsd })
        }
    }
}
