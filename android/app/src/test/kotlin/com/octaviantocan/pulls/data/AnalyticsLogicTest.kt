package com.octaviantocan.pulls.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyticsLogicTest {
    private val event = AnalyticsEvent("pr.open", "2026-10-15T20:58:00Z", "0.37.0", "s-1", "d_1", mapOf("repo" to "o/r", "number" to 4L, "cached" to true, "ms" to 12.5))

    @Test
    fun anEventIsOneLineOfTheServersShape() {
        val line = event.encode()
        assertFalse('\n' in line)
        val obj = Json.parseToJsonElement(line).jsonObject
        assertEquals(setOf("name", "at", "app", "version", "session", "device", "props"), obj.keys)
        assertEquals("android", obj.getValue("app").jsonPrimitive.content)
        val props = obj.getValue("props").jsonObject
        assertEquals("o/r", props.getValue("repo").jsonPrimitive.content)
        assertEquals("4", props.getValue("number").jsonPrimitive.content)
        assertEquals("true", props.getValue("cached").jsonPrimitive.content)
        assertTrue(AnalyticsRules.isEvent(line))
    }

    @Test
    fun aBatchHoldsTheLinesAsAnArray() {
        val body = Json.parseToJsonElement(AnalyticsRules.batchBody(listOf(event.encode(), event.copy(name = "file.view").encode()))) as JsonObject
        assertEquals(listOf("pr.open", "file.view"), body.getValue("events").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
    }

    @Test
    fun aLineCutShortByAKillIsNotAnEvent() {
        val line = event.encode()
        assertFalse(AnalyticsRules.isEvent(line.dropLast(5)))
        assertFalse(AnalyticsRules.isEvent(""))
        assertFalse(AnalyticsRules.isEvent("[1,2]"))
    }

    @Test
    fun propsThatCouldCarryWhatWasReadOrTypedAreNeverSent() {
        val cleaned = AnalyticsRules.cleanProps(
            mapOf(
                "queryLength" to 3, "path" to "src/a.kt", "filePath" to "x", "commentBody" to "hi", "diff" to "@@", "AnswerText" to "a",
                "accessToken" to "t", "api_key" to "k", "Authorization" to "b", "prompt" to "p", "content" to "c",
                "chars" to 3, "kind" to "kt", "ok" to false, "nothing" to null, "list" to listOf(1), "nan" to Double.NaN,
            ),
        )
        assertEquals(mapOf<String, Any>("chars" to 3L, "kind" to "kt", "ok" to false), cleaned)
    }

    @Test
    fun stringsAreCutAndAStackMayBeLonger() {
        val long = "x".repeat(5_000)
        val cleaned = AnalyticsRules.cleanProps(mapOf("message" to long, "stack" to long))
        assertEquals(AnalyticsRules.MAX_STRING, (cleaned["message"] as String).length)
        assertEquals(AnalyticsRules.MAX_STACK, (cleaned["stack"] as String).length)
    }

    @Test
    fun theQueueKeepsAtMostFiveThousandDroppingTheOldest() {
        assertEquals(0, AnalyticsRules.overflow(AnalyticsRules.MAX_QUEUE))
        val drop = AnalyticsRules.overflow(AnalyticsRules.MAX_QUEUE + 1)
        assertTrue(drop > 0)
        assertTrue(AnalyticsRules.MAX_QUEUE + 1 - drop <= AnalyticsRules.MAX_QUEUE)
        val lines = (1..6_000).toList()
        val kept = AnalyticsRules.newest(lines)
        assertEquals(AnalyticsRules.MAX_QUEUE, kept.size)
        assertEquals(6_000, kept.last())
        assertEquals(1_001, kept.first())
    }

    @Test
    fun onlyAnAnswerThatWillNeverChangeDropsABatch() {
        assertEquals(AnalyticsRules.Outcome.Sent, AnalyticsRules.outcome(204))
        assertEquals(AnalyticsRules.Outcome.Refused, AnalyticsRules.outcome(400))
        assertEquals(AnalyticsRules.Outcome.Refused, AnalyticsRules.outcome(413))
        for (status in listOf(401, 403, 404, 429, 500, 502, 503)) assertEquals(AnalyticsRules.Outcome.Later, AnalyticsRules.outcome(status))
    }

    @Test
    fun aSessionStartsAgainAfterHalfAnHourAway() {
        val minute = 60_000L
        assertEquals("a", AnalyticsRules.session("a", backgroundAt = null, now = 0) { "b" })
        assertEquals("a", AnalyticsRules.session("a", backgroundAt = 0, now = 29 * minute) { "b" })
        assertEquals("b", AnalyticsRules.session("a", backgroundAt = 0, now = 30 * minute) { "b" })
        assertEquals("b", AnalyticsRules.session(null, backgroundAt = null, now = 0) { "b" })
        assertEquals("b", AnalyticsRules.session("not valid!", backgroundAt = null, now = 0) { "b" })
    }

    @Test
    fun idsAreTheServersShape() {
        assertTrue(AnalyticsRules.validId("0f8c2b9e-1d2a-4c55-9d1e-1f2a3b4c5d6e"))
        assertFalse(AnalyticsRules.validId(""))
        assertFalse(AnalyticsRules.validId("a".repeat(65)))
        assertFalse(AnalyticsRules.validId("a b"))
    }

    @Test
    fun aColdOpenIsTheProcesssFirstActivity() {
        assertEquals("cold" to 900L, AnalyticsRules.appOpen(processStart = 100, activityStart = 300, firstActivity = true, now = 1_000))
        // A process WorkManager woke long before the app was opened is not a cold open of the app.
        assertEquals("warm" to 400L, AnalyticsRules.appOpen(processStart = 0, activityStart = 60_000, firstActivity = true, now = 60_400))
        assertEquals("warm" to 250L, AnalyticsRules.appOpen(processStart = 0, activityStart = 1_000, firstActivity = false, now = 1_250))
    }

    @Test
    fun aFileIsItsExtensionOnly() {
        assertEquals("kt", AnalyticsRules.fileKind("android/app/src/Main.KT"))
        assertEquals("none", AnalyticsRules.fileKind("Makefile"))
        assertEquals("none", AnalyticsRules.fileKind(".gitignore"))
        assertEquals("none", AnalyticsRules.fileKind("weird."))
        assertEquals("json", AnalyticsRules.fileKind("a.b/c.json"))
        assertEquals("other", AnalyticsRules.fileKind("notes.this-is-not-an-extension"))
    }

    @Test
    fun aCrashSendsItsClassMessageAndTopFrames() {
        val error = IllegalStateException("boom")
        val props = AnalyticsRules.crashProps(error, "review")
        assertEquals("IllegalStateException", props["kind"])
        assertEquals("boom", props["message"])
        assertEquals("review", props["screen"])
        val frames = (props["stack"] as String).lines()
        assertTrue(frames.size in 1..AnalyticsRules.CRASH_FRAMES)
    }

    @Test
    fun screenTimeRunsUntilTheNextScreenOrTheBackground() {
        val clock = ScreenClock()
        assertEquals(listOf("screen.view" to mapOf<String, Any?>("screen" to "home", "from" to "start")), clock.show("home", 0))
        assertEquals(emptyList(), clock.show("home", 1_000))
        val switched = clock.show("review", 12_000)
        assertEquals("screen.time" to mapOf<String, Any?>("screen" to "home", "seconds" to 12.0), switched[0])
        assertEquals("screen.view" to mapOf<String, Any?>("screen" to "review", "from" to "home"), switched[1])
        assertEquals(listOf("screen.time" to mapOf<String, Any?>("screen" to "review", "seconds" to 3.0)), clock.pause(15_000))
        // Time in the background does not count, and nothing is counted twice.
        assertEquals(emptyList(), clock.pause(20_000))
        clock.resume(600_000)
        assertEquals("screen.time" to mapOf<String, Any?>("screen" to "review", "seconds" to 2.0), clock.show("home", 602_000)[0])
    }
}
