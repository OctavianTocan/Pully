package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
