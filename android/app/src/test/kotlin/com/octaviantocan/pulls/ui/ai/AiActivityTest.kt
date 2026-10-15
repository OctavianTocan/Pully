package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.Activity
import com.octaviantocan.pulls.ui.design.Lu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors `components/ai/ai-activity.test.ts` (the transcript helpers are not ported; see `AiActivity.kt`). */
class AiActivityTest {
    private fun activity(id: String, kind: String = "tool", title: String = id, status: String = "running", startedAt: Long = 0, tool: String? = null) =
        Activity(id = id, kind = kind, title = title, status = status, startedAt = startedAt, tool = tool)

    @Test
    fun upsertAppendsInArrivalOrderAndReplacesNewerRevisionsInPlace() {
        val first = upsertActivities(emptyList(), listOf(activity("a") to 1, activity("b") to 1), 10)
        val next = upsertActivities(first, listOf(activity("a", status = "done", title = "Read a.ts") to 3, activity("c") to 4), 20)
        assertEquals(listOf(Triple("a", "Read a.ts", 20L), Triple("b", "b", 10L), Triple("c", "c", 20L)), next.map { Triple(it.id, it.activity.title, it.seenAt) })
    }

    @Test
    fun theFirstStepNeverNamesTheModelEvenInAnOldLog() {
        assertEquals("Started", stepTitle(activity("start", kind = "status", title = "Started Claude (some-model)", status = "done")))
        assertEquals("Started", stepTitle(activity("start", kind = "status", title = "Started", status = "done")))
        assertEquals("Starting Claude Code…", stepTitle(activity("start", kind = "status", title = "Starting Claude Code…")))
        assertEquals("Started Codex here", stepTitle(activity("tool:1", title = "Started Codex here")))
    }

    @Test
    fun upsertIgnoresReplaysOfRevisionsItAlreadyHas() {
        val log = upsertActivities(emptyList(), listOf(activity("a", title = "new") to 5), 10)
        val again = upsertActivities(log, listOf(activity("a", title = "old") to 2), 20)[0]
        assertEquals("new", again.activity.title)
        assertEquals(10L, again.seenAt)
    }

    @Test
    fun trackActivitiesStampsOnlyChangedEntries() {
        val log = trackActivities(emptyList(), listOf(activity("a"), activity("b")), 10)
        val next = trackActivities(log, listOf(activity("a"), activity("b", title = "changed")), 20)
        assertEquals(listOf(10L, 20L), next.map { it.seenAt })
    }

    private val running = TrackedActivity(activity("a", startedAt = 0), 1, 34_000)

    @Test
    fun timingReportsWhenTheStepLastChangedAndHowLongItRan() {
        assertEquals(ActivityTiming(TimingState.Active, "4s", "38s"), activityTiming(running, 38_000))
    }

    @Test
    fun timingReadsActiveNowWithinASecond() {
        assertEquals(TimingState.Now, activityTiming(running, 34_500)?.state)
    }

    @Test
    fun timingReadsIdleAfter30sOfSilence() {
        assertEquals(ActivityTiming(TimingState.Idle, "45s", "1m 19s"), activityTiming(running, 79_000))
    }

    @Test
    fun timingHasNothingToSayAboutSettledSteps() {
        assertNull(activityTiming(running.copy(activity = running.activity.copy(status = "done")), 79_000))
    }

    @Test
    fun summarizeCountsToolStepsByKindInFixedOrder() {
        val log = listOf(
            activity("1", tool = "read_source"),
            activity("2", tool = "Bash"),
            activity("3", tool = "read_file_diff"),
            activity("4", tool = "search_diff"),
            activity("5", tool = "read_discussion"),
            activity("6", kind = "thinking"),
        )
        assertEquals(
            listOf(ToolCategory.Command to 1, ToolCategory.Read to 2, ToolCategory.Search to 1, ToolCategory.Other to 1),
            summarizeTools(log),
        )
    }

    @Test
    fun unknownAndMissingToolNamesAreOther() {
        assertEquals(ToolCategory.Other, toolCategory(null))
        assertEquals(ToolCategory.Other, toolCategory("StructuredOutput"))
    }

    @Test
    fun startStatusIsNoNoticeButALaterSettledStatusIs() {
        assertFalse(isWarning(activity("start", kind = "status", status = "done"), 0))
        assertTrue(isWarning(activity("retry-1", kind = "status", status = "done"), 3))
        assertFalse(isWarning(activity("retry-1", kind = "status", status = "running"), 3))
    }

    @Test
    fun eachStepShowsTheIconOfWhatItDoes() {
        assertEquals(Lu.file, stepGlyph(activity("a", tool = "read_source"), false))
        assertEquals(Lu.search, stepGlyph(activity("a", tool = "grep"), false))
        assertEquals(Lu.terminal, stepGlyph(activity("a", tool = "bash"), false))
        assertEquals(Lu.edit, stepGlyph(activity("a", tool = "apply_patch"), false))
        assertEquals(Lu.tool, stepGlyph(activity("a", tool = "fetch_pr"), false))
        assertEquals(Lu.think, stepGlyph(activity("a", kind = "thinking"), false))
        assertEquals(Lu.write, stepGlyph(activity("a", kind = "text"), false))
        assertEquals(Lu.cog, stepGlyph(activity("a", kind = "status"), false))
    }

    @Test
    fun aFailedStepOrANoticeOverridesWhatItDoes() {
        assertEquals(Lu.fail, stepGlyph(activity("a", tool = "bash", status = "failed"), false))
        assertEquals(Lu.refresh, stepGlyph(activity("a", kind = "status", status = "done"), true))
    }
}
