package com.octaviantocan.pulls.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The `analytics-summary` answer (`AnalyticsSummary` in `packages/core/src/analytics.ts`), for Settings, Stats. */
data class AnalyticsSummary(
    /** The first and last day counted, `YYYY-MM-DD`. */
    val from: String,
    val to: String,
    val totals: Totals,
    /** Every day of the range, oldest first. */
    val days: List<Day>,
    val screens: List<ScreenTime>,
    val engines: List<EngineUse>,
    val tasks: List<TaskUse>,
    /** Slowest median first. */
    val calls: List<CallTime>,
    val speed: List<Speed>,
    val github: GitHub,
    val cache: Cache,
    val errors: List<Problem>,
) {
    data class Totals(
        val events: Long = 0, val sessions: Long = 0, val devices: Long = 0, val prsOpened: Long = 0, val filesReviewed: Long = 0,
        val aiRuns: Long = 0, val aiFailures: Long = 0, val costUsd: Double = 0.0, val tokensIn: Long = 0, val tokensOut: Long = 0,
        val crashes: Long = 0, val errors: Long = 0,
    )
    data class Day(val day: String, val sessions: Long, val prsOpened: Long, val filesReviewed: Long, val aiRuns: Long, val costUsd: Double)
    data class ScreenTime(val screen: String, val views: Long, val seconds: Double)
    /** [priced]: how many of the runs reported a cost; fewer than [runs] means the cost shown is not all of it (O9). */
    data class EngineUse(val engine: String, val model: String, val runs: Long, val failures: Long, val costUsd: Double, val tokensIn: Long, val tokensOut: Long, val medianMs: Double, val cacheRead: Long = 0, val priced: Long = runs) {
        val tokens: Long get() = tokensIn + tokensOut
    }
    data class TaskUse(val task: String, val runs: Long, val failures: Long, val costUsd: Double, val tokensIn: Long = 0, val tokensOut: Long = 0, val cacheRead: Long = 0, val priced: Long = runs) {
        val tokens: Long get() = tokensIn + tokensOut
    }
    data class CallTime(val name: String, val calls: Long, val failures: Long, val medianMs: Double, val p95Ms: Double)
    data class Speed(val metric: String, val app: String, val count: Long, val medianMs: Double, val p95Ms: Double)
    data class GitHub(val calls: Long = 0, val failures: Long = 0, val lowestRateLeft: Long? = null)
    data class Cache(val hit: Long = 0, val refetch: Long = 0, val stale: Long = 0)
    data class Problem(val name: String, val app: String, val kind: String, val message: String, val count: Long, val lastAt: String)

    /** Nothing was counted in the range. */
    val empty: Boolean get() = totals.events == 0L
}

private fun JsonObject.n(key: String): Long = l(key) ?: 0L
private fun JsonObject.f(key: String): Double = d(key) ?: 0.0

/** Reads the answer; a missing field reads as zero or empty, so an older server's answer still shows. */
fun JsonElement.asAnalyticsSummary(): AnalyticsSummary {
    val o = this as? JsonObject ?: JsonObject(emptyMap())
    val t = o.obj("totals") ?: JsonObject(emptyMap())
    val g = o.obj("github") ?: JsonObject(emptyMap())
    val c = o.obj("cache") ?: JsonObject(emptyMap())
    return AnalyticsSummary(
        from = o.s("from"),
        to = o.s("to"),
        totals = AnalyticsSummary.Totals(
            events = t.n("events"), sessions = t.n("sessions"), devices = t.n("devices"), prsOpened = t.n("prsOpened"),
            filesReviewed = t.n("filesReviewed"), aiRuns = t.n("aiRuns"), aiFailures = t.n("aiFailures"), costUsd = t.f("costUsd"),
            tokensIn = t.n("tokensIn"), tokensOut = t.n("tokensOut"), crashes = t.n("crashes"), errors = t.n("errors"),
        ),
        days = o.arr("days").map { AnalyticsSummary.Day(it.s("day"), it.n("sessions"), it.n("prsOpened"), it.n("filesReviewed"), it.n("aiRuns"), it.f("costUsd")) },
        screens = o.arr("screens").map { AnalyticsSummary.ScreenTime(it.s("screen"), it.n("views"), it.f("seconds")) },
        engines = o.arr("engines").map {
            AnalyticsSummary.EngineUse(
                it.s("engine"), it.s("model"), it.n("runs"), it.n("failures"), it.f("costUsd"), it.n("tokensIn"), it.n("tokensOut"), it.f("medianMs"),
                cacheRead = it.n("cacheRead"), priced = it.l("priced") ?: it.n("runs"),
            )
        },
        tasks = o.arr("tasks").map {
            AnalyticsSummary.TaskUse(
                it.s("task"), it.n("runs"), it.n("failures"), it.f("costUsd"), it.n("tokensIn"), it.n("tokensOut"),
                cacheRead = it.n("cacheRead"), priced = it.l("priced") ?: it.n("runs"),
            )
        },
        calls = o.arr("calls").map { AnalyticsSummary.CallTime(it.s("name"), it.n("calls"), it.n("failures"), it.f("medianMs"), it.f("p95Ms")) },
        speed = o.arr("speed").map { AnalyticsSummary.Speed(it.s("metric"), it.s("app"), it.n("count"), it.f("medianMs"), it.f("p95Ms")) },
        github = AnalyticsSummary.GitHub(g.n("calls"), g.n("failures"), g.l("lowestRateLeft")),
        cache = AnalyticsSummary.Cache(c.n("hit"), c.n("refetch"), c.n("stale")),
        errors = o.arr("errors").map { AnalyticsSummary.Problem(it.s("name"), it.s("app"), it.s("kind"), it.s("message"), it.n("count"), it.s("lastAt")) },
    )
}
