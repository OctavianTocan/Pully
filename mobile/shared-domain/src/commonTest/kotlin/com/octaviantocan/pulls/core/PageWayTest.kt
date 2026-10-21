package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rule 105: every change moves the way the person went. */
class PageWayTest {
    @Test
    fun aPageAfterTheOneOnScreenComesInFromTheRight() {
        assertEquals(FORWARD, pageWay(from = 0, to = 2))
        assertEquals(BACKWARD, pageWay(from = 2, to = 0))
    }

    @Test
    fun theSamePlaceCountsAsForward() {
        assertEquals(FORWARD, pageWay(from = 1, to = 1))
    }

    @Test
    fun theFingerPushesThePagesTheWayItMoves() {
        assertEquals(FORWARD, swipeWay(-40f))
        assertEquals(BACKWARD, swipeWay(40f))
        assertEquals(0, swipeWay(0f))
    }

    @Test
    fun aSwipeAndTheChangeItMakesAgree() {
        // Whatever a drag lands on, the pages move the way the finger went: left is the next tab, and forward.
        for (dx in listOf(-300f, -90f, 90f, 300f)) {
            val step = swipeStep(dx, threshold = 80f)
            assertEquals(swipeWay(dx), step, "drag of $dx")
            assertEquals(pageWay(1, 1 + step), swipeWay(dx), "drag of $dx")
        }
    }

    @Test
    fun aBackSwipeMovesThePageWithTheFinger() {
        assertEquals(BACKWARD, backWay(fromRightEdge = false))
        assertEquals(FORWARD, backWay(fromRightEdge = true))
    }

    @Test
    fun aQuickThrowCountsHoweverShort() {
        assertEquals(FORWARD, swipeStep(-20f, threshold = 80f, velocity = -1500f, fling = 1000f))
        assertEquals(BACKWARD, swipeStep(20f, threshold = 80f, velocity = 1500f, fling = 1000f))
    }

    @Test
    fun aSlowShortDragGoesNowhere() {
        assertEquals(0, swipeStep(-20f, threshold = 80f, velocity = -400f, fling = 1000f))
    }

    @Test
    fun aThrowBackAgainstTheDragCancelsIt() {
        assertEquals(0, swipeStep(-120f, threshold = 80f, velocity = 1500f, fling = 1000f))
        assertEquals(0, swipeStep(120f, threshold = 80f, velocity = -1500f, fling = 1000f))
    }

    @Test
    fun aThrowCarriesTabsAndFilesOnButNotPastTheEnds() {
        assertEquals(2, swipedTab(1, 3, -20f, 80f, velocity = -1500f, fling = 1000f))
        assertEquals(2, swipedTab(2, 3, -20f, 80f, velocity = -1500f, fling = 1000f))
        assertEquals(5, swipedFile(4, 5, -20f, 80f, velocity = -1500f, fling = 1000f))
        assertEquals(-1, swipedFile(0, 5, 20f, 80f, velocity = 1500f, fling = 1000f))
    }
}
