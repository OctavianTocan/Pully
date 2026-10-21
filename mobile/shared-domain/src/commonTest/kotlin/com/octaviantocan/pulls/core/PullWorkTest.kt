package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullWorkTest {
    private fun task(key: String, kind: String, status: String, startedAt: Long = 0) = WorkTask(key, kind, status, startedAt = startedAt)

    @Test
    fun aRunningPassMarksThePullAsAnalyzingEvenAfterTheAnalysisFinished() {
        val marks = pullWorkByKey(listOf(task("a", "tour", "running"), task("a", "analyze", "done")))
        assertEquals(mapOf("a" to PullWork.Analyzing), marks)
    }

    @Test
    fun aFinishedAnalysisIsAnalyzedAndAFailedOrStoppedOneIsNothing() {
        val marks = pullWorkByKey(listOf(task("done", "analyze", "done"), task("failed", "analyze", "failed"), task("stopped", "analyze", "cancelled")))
        assertEquals(mapOf("done" to PullWork.Analyzed), marks)
    }

    @Test
    fun aFailedRerunHidesTheOlderSuccessInEitherOrderOfTheList() {
        val older = task("a", "analyze", "done", startedAt = 100)
        val newer = task("a", "analyze", "failed", startedAt = 200)
        assertEquals(emptyMap(), pullWorkByKey(listOf(newer, older)))
        assertEquals(emptyMap(), pullWorkByKey(listOf(older, newer)))
    }

    @Test
    fun aSuccessfulRerunOutranksAnOlderFailure() {
        val marks = pullWorkByKey(listOf(task("a", "analyze", "done", startedAt = 200), task("a", "analyze", "failed", startedAt = 100)))
        assertEquals(mapOf("a" to PullWork.Analyzed), marks)
    }

    @Test
    fun aQuestionOrAChatReplyIsNotAnalysis() {
        assertEquals(emptyMap(), pullWorkByKey(listOf(task("a", "ask", "running"), task("a", "chat", "running"))))
    }

    @Test
    fun aTourThatFinishedAloneMarksNothing() {
        assertEquals(emptyMap(), pullWorkByKey(listOf(task("a", "tour", "done"))))
    }

    @Test
    fun pullKeysRoundTripAndOtherSourcesAreNotPulls() {
        val key = pullKey("octo", "pulls.review", 12)
        assertEquals("github:octo/pulls.review#12", key)
        assertEquals(PullRef("octo", "pulls.review", 12), parsePullKey(key))
        assertNull(parsePullKey("github:octo/repo@abc...def"))
        assertNull(parsePullKey("github:octo/repo@abc123"))
        assertNull(parsePullKey("local:x@y"))
    }

    @Test
    fun runningWorkIsNamedInTheOrderItStarted() {
        val tasks = listOf(task("a", "brief", "running", 3), task("a", "tour", "running", 2), task("b", "analyze", "running", 1), task("a", "ask", "running", 4), task("a", "analyze", "done", 0))
        assertEquals(listOf("Writing the tour", "Designing the brief"), runningWork(tasks, "a"))
        assertEquals(listOf("Analyzing"), runningWork(tasks, "b"))
    }

    @Test
    fun theRunningTourOfAPullIsFoundByItsId() {
        val tasks = listOf(
            WorkTask("a", "tour", "done", startedAt = 1, id = "old"),
            WorkTask("a", "tour", "running", startedAt = 2, id = "new"),
            WorkTask("a", "analyze", "running", startedAt = 3, id = "analysis"),
            WorkTask("b", "tour", "running", startedAt = 4, id = "other"),
        )
        assertEquals("new", runningTaskId(tasks, "a", "tour"))
        assertNull(runningTaskId(tasks, "a", "brief"))
    }

    @Test
    fun aTaskWithoutAnIdCannotBeStopped() {
        assertNull(runningTaskId(listOf(WorkTask("a", "tour", "running")), "a", "tour"))
    }
}
