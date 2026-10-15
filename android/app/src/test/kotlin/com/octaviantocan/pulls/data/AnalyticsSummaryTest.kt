package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsSummaryTest {
    @Test
    fun readsTheServersSummary() {
        val summary = Json.parseToJsonElement(
            """{"from":"2026-10-09","to":"2026-10-15",
            "totals":{"events":120,"sessions":4,"devices":1,"prsOpened":9,"filesReviewed":31,"aiRuns":5,"aiFailures":1,"costUsd":0.42,"tokensIn":1000,"tokensOut":200,"crashes":0,"errors":2},
            "days":[{"day":"2026-10-14","sessions":1,"prsOpened":2,"filesReviewed":3,"aiRuns":1,"costUsd":0.1},{"day":"2026-10-15","sessions":3,"prsOpened":7,"filesReviewed":28,"aiRuns":4,"costUsd":0.32}],
            "screens":[{"screen":"review","views":12,"seconds":640.5}],
            "engines":[{"engine":"codex","model":"gpt-5","runs":3,"failures":1,"costUsd":0.3,"tokensIn":900,"tokensOut":100,"medianMs":4200}],
            "tasks":[{"task":"analyze","runs":2,"failures":0,"costUsd":0.2}],
            "calls":[{"name":"github-pr","calls":9,"failures":0,"medianMs":350,"p95Ms":1200}],
            "speed":[{"metric":"app.open","app":"android","count":4,"medianMs":800,"p95Ms":1500}],
            "github":{"calls":40,"failures":1,"lowestRateLeft":4200},
            "cache":{"hit":6,"refetch":2,"stale":1},
            "errors":[{"name":"error.client","app":"android","kind":"pr.load","message":"Not Found","count":2,"lastAt":"2026-10-15T20:00:00Z"}],
            "names":[{"name":"pr.open","count":9}]}""",
        ).asAnalyticsSummary()
        assertEquals("2026-10-09", summary.from)
        assertEquals(4L, summary.totals.sessions)
        assertEquals(0.42, summary.totals.costUsd)
        assertEquals(listOf(2L, 7L), summary.days.map { it.prsOpened })
        assertEquals(640.5, summary.screens.single().seconds)
        assertEquals("gpt-5", summary.engines.single().model)
        assertEquals(1200.0, summary.calls.single().p95Ms)
        assertEquals(4200L, summary.github.lowestRateLeft)
        assertEquals(2L, summary.cache.refetch)
        assertEquals("pr.load", summary.errors.single().kind)
        assertTrue(!summary.empty)
    }

    @Test
    fun anOlderOrEmptyAnswerReadsAsNothingCounted() {
        val summary = Json.parseToJsonElement("""{"from":"2026-10-09","to":"2026-10-15","totals":{"events":0}}""").asAnalyticsSummary()
        assertTrue(summary.empty)
        assertTrue(summary.days.isEmpty())
        assertNull(summary.github.lowestRateLeft)
    }
}
