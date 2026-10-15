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

    private fun step(kind: String, text: String? = null, status: String = "done", title: String = kind, tool: String? = null) =
        Activity(id = "$kind-$title-$text", kind = kind, title = title, text = text, status = status, tool = tool)

    @Test
    fun theAnswerSoFarIsTheNewestWritingStep() {
        assertEquals("The gate waits.", answerSoFar(listOf(step("thinking", "hmm"), step("text", "The gate waits.", status = "running"))))
        // Writing the follow-up questions comes after the answer and leaves it standing.
        assertEquals("Done.", answerSoFar(listOf(step("text", "Done."), step("status", title = "Suggesting follow-up questions", status = "running"))))
        // The server marks a long text it cut with an ellipsis at the front.
        assertEquals("end of it", answerSoFar(listOf(step("text", "…end of it", status = "running"))))
    }

    @Test
    fun anAsideBeforeAToolIsNotTheAnswer() {
        assertNull(answerSoFar(listOf(step("text", "Let me read the file."), step("tool", title = "Reading a.ts", status = "running", tool = "read_source"))))
        assertNull(answerSoFar(listOf(step("thinking", "hmm", status = "running"))))
        assertNull(answerSoFar(listOf(step("text", "", status = "running"))))
        assertNull(answerSoFar(emptyList()))
    }

    @Test
    fun theFilesALogReadAreItsSourcesEachOnce() {
        val log = listOf(
            step("tool", title = "Read src/gate.ts (lines 40–120)", tool = "read_source"),
            step("tool", title = "Read the diff of src/mount.tsx and 2 more files", tool = "read_file_diff"),
            step("tool", title = "Read src/gate.ts", tool = "read_source"),
            step("tool", title = "Read lib/a.ts and 1 more file", tool = "cat"),
            step("tool", title = "Read ./b.ts (lines 1–400)", tool = "read_source"),
            // Not a single file, not a read, and a read that failed.
            step("tool", title = "Read the PR description and comments", tool = "read_discussion"),
            step("tool", title = "Searched the code for `gate` in src", tool = "search_source"),
            step("tool", title = "Read src/broken.ts", status = "failed", tool = "read_source"),
        )
        assertEquals(listOf("src/gate.ts" to 40, "src/mount.tsx" to 0, "lib/a.ts" to 0, "b.ts" to 0), readFiles(log))
    }

    @Test
    fun theInboxSourcesAreThePullRequestsItRead() {
        val log = listOf(
            step("tool", title = "Read octo/app#12", tool = "read_pull_request"),
            step("tool", title = "Read the diff of octo/app#14 (src/a.ts)", tool = "read_pull_request_diff"),
            step("tool", title = "Read octo/app#15", status = "failed", tool = "read_pull_request"),
            step("thinking", "octo/app#99"),
        )
        assertEquals("Read octo/app#12\nRead the diff of octo/app#14 (src/a.ts)", pullReads(log))
    }
}
