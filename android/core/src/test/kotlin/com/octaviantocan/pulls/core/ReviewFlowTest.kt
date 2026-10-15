package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewFlowTest {
    private val order = listOf("a", "b", "c", "d")

    @Test
    fun theNextFileThatIsNotReviewedFollowsTheOneJustFinished() {
        assertEquals("c", nextUnreviewed(order, "a") { it in setOf("a", "b") })
    }

    @Test
    fun filesAlreadyReviewedBelowAreSkipped() {
        assertEquals("d", nextUnreviewed(order, "b") { it in setOf("b", "c") })
    }

    @Test
    fun theSearchWrapsToTheTopWhenTheOnesBelowAreDone() {
        assertEquals("a", nextUnreviewed(order, "c") { it in setOf("c", "d") })
    }

    @Test
    fun nothingFollowsWhenEveryFileIsReviewed() {
        assertNull(nextUnreviewed(order, "b") { true })
    }

    @Test
    fun aFileOutsideTheListHasNoNext() {
        assertNull(nextUnreviewed(order, "zzz") { false })
    }

    @Test
    fun theFinishedFileIsNeverItsOwnNext() {
        assertNull(nextUnreviewed(listOf("only"), "only") { false })
    }

    @Test
    fun aDragToTheLeftShowsTheNextTabAndToTheRightThePreviousOne() {
        assertEquals(2, swipedTab(1, 3, -120f, 80f))
        assertEquals(0, swipedTab(1, 3, 120f, 80f))
    }

    @Test
    fun theFirstAndLastTabsStayPut() {
        assertEquals(0, swipedTab(0, 3, 300f, 80f))
        assertEquals(2, swipedTab(2, 3, -300f, 80f))
    }

    @Test
    fun aShortDragChangesNothing() {
        assertEquals(1, swipedTab(1, 3, 79f, 80f))
        assertEquals(1, swipedTab(1, 3, -79f, 80f))
    }

    @Test
    fun aSingleTabHasNothingToSwipeTo() {
        assertEquals(0, swipedTab(0, 1, -500f, 80f))
    }

    @Test
    fun aDragOnTheDiffGoesToTheNextOrPreviousFile() {
        assertEquals(3, swipedFile(2, 5, -120f, 80f))
        assertEquals(1, swipedFile(2, 5, 120f, 80f))
    }

    @Test
    fun pastTheLastFileTheDiffContinuesToTheNextView() {
        assertEquals(5, swipedFile(4, 5, -120f, 80f))
    }

    @Test
    fun beforeTheFirstFileTheDiffGoesBackToThePreviousView() {
        assertEquals(-1, swipedFile(0, 5, 120f, 80f))
    }

    @Test
    fun aShortDragOnTheDiffStaysOnTheFile() {
        assertEquals(2, swipedFile(2, 5, 79f, 80f))
        assertEquals(2, swipedFile(2, 5, -79f, 80f))
        assertEquals(0, swipedFile(0, 5, 79f, 80f))
    }

    @Test
    fun theDescriptionIsTheSummaryUntilTheAiWritesOne() {
        assertTrue(showsDescription(summary = null, switchedAwayFrom = null))
    }

    @Test
    fun aWrittenSummaryTakesTheDescriptionsPlace() {
        assertFalse(showsDescription(summary = "What changed", switchedAwayFrom = null))
    }

    @Test
    fun switchingToTheDescriptionKeepsItOverThatSummary() {
        assertTrue(showsDescription(summary = "What changed", switchedAwayFrom = "What changed"))
    }

    @Test
    fun theNextSummaryTakesThePlaceAgain() {
        assertFalse(showsDescription(summary = "What changed now", switchedAwayFrom = "What changed"))
    }
}
