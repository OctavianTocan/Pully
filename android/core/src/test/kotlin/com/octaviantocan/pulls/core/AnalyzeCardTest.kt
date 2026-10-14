package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyzeCardTest {
    private fun AnalyzeCard.marks() = steps.map { it.label to it.mark }

    @Test
    fun aRunningAnalysisShowsTheStepsItWasAskedToFollowBeforeTheyStart() {
        val card = analyzeCard("running", null, null, hasTour = false, hasBrief = false, then = listOf("tour", "brief"))
        assertEquals("Analyzing", card.title)
        assertTrue(card.working)
        assertFalse(card.restart)
        assertEquals(
            listOf("File notes" to StepMark.Active, "Summary" to StepMark.Pending, "Tour" to StepMark.Pending, "Brief" to StepMark.Pending),
            card.marks(),
        )
    }

    @Test
    fun aQuickAnalysisHasNoTourOrBriefStep() {
        val card = analyzeCard("running", null, null, hasTour = false, hasBrief = false)
        assertEquals(listOf("File notes", "Summary"), card.steps.map { it.label })
    }

    @Test
    fun aStoppedAnalysisSaysSoAndOffersARestart() {
        val card = analyzeCard("cancelled", null, null, hasTour = false, hasBrief = false)
        assertEquals("Analysis stopped", card.title)
        assertTrue(card.restart)
        assertFalse(card.working)
        assertEquals(listOf("File notes" to StepMark.Stopped, "Summary" to StepMark.Pending), card.marks())
    }

    @Test
    fun aFailedAnalysisOffersARestartToo() {
        val card = analyzeCard("failed", null, null, hasTour = false, hasBrief = false)
        assertEquals("Analysis failed", card.title)
        assertTrue(card.restart)
        assertEquals(StepMark.Failed, card.steps.first().mark)
    }

    @Test
    fun afterTheAnalysisTheTourAndBriefCarryOnTheCard() {
        val card = analyzeCard("done", "running", "running", hasTour = false, hasBrief = false, then = listOf("tour", "brief"))
        assertEquals("Writing the tour and the brief", card.title)
        assertTrue(card.working)
        assertFalse(card.restart)
        assertEquals(listOf("File notes" to StepMark.Done, "Summary" to StepMark.Done, "Tour" to StepMark.Active, "Brief" to StepMark.Active), card.marks())
        assertEquals("Designing the brief", analyzeCard("done", "done", "running", hasTour = true, hasBrief = false).title)
        assertEquals("Writing the tour", analyzeCard("done", "running", null, hasTour = false, hasBrief = false).title)
    }

    @Test
    fun aFinishedAnalysisWithAResultPerPassReadsAnalyzed() {
        val card = analyzeCard("done", "done", "done", hasTour = true, hasBrief = true)
        assertEquals("Analyzed", card.title)
        assertFalse(card.working)
        assertEquals(StepMark.Done, card.steps.last().mark)
    }

    @Test
    fun aStoppedOrFailedFollowUpMarksOnlyItsOwnStep() {
        val card = analyzeCard("done", "cancelled", "failed", hasTour = false, hasBrief = false)
        assertEquals("Analyzed", card.title)
        assertFalse(card.restart)
        assertEquals(listOf("File notes" to StepMark.Done, "Summary" to StepMark.Done, "Tour" to StepMark.Stopped, "Brief" to StepMark.Failed), card.marks())
    }

    @Test
    fun aSavedResultWinsOverAnOlderFailedTask() {
        val card = analyzeCard("done", "failed", null, hasTour = true, hasBrief = false)
        assertEquals(StepMark.Done, card.steps.single { it.label == "Tour" }.mark)
    }
}
