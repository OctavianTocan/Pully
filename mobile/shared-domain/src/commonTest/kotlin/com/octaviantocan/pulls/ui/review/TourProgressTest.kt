package com.octaviantocan.pulls.ui.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TourProgressTest {
    private val line = 300f
    private val bottom = 1500f

    /** Four stops of 500px from the top of the list, the list scrolled [by] px. */
    private fun at(by: Int, count: Int = 4) = (1..count).map { n -> Triple(n, 100 + (n - 1) * 500 - by, 500) }

    @Test
    fun atRestTheRingCountsOnlyWhatIsAboveTheReadingLine() {
        assertEquals(0.1f, tourProgress(4, at(0), 0, line, bottom, rest = null), 0.001f)
        assertEquals(0f, tourProgress(4, at(-400), 0, line, bottom, rest = null))
    }

    @Test
    fun theRingMovesWithTheScrollInsideAStop() {
        val before = tourProgress(4, at(600), 1, line, bottom, rest = null)
        val after = tourProgress(4, at(700), 1, line, bottom, rest = null)
        assertEquals(0.05f, after - before, 0.001f)
    }

    @Test
    fun theRingIsFullWhenTheListCannotScrollFurther() {
        assertEquals(1f, tourProgress(4, at(1100), 3, line, bottom, rest = 0f, lastHeight = 500f))
    }

    @Test
    fun aShortLastCardComingIntoViewDoesNotMakeTheRingLeap() {
        // The glossary, 150px tall, just appears at the bottom: the line has not moved yet.
        val stops = at(800)
        val hidden = tourProgress(4, stops, 2, line, bottom, rest = null)
        val glimpsed = tourProgress(4, stops, 2, line, bottom, rest = 149f, lastHeight = 150f)
        assertEquals(hidden, glimpsed, 0.01f)
    }

    @Test
    fun theRingNeverGoesBackAsTheListScrollsToItsEnd() {
        var last = 0f
        for (by in 0..1100 step 10) {
            val rest = (1100 - by).toFloat()
            val value = tourProgress(4, at(by), if (by < 600) 1 else 2, line, bottom, rest = if (rest <= 400f) rest else null, lastHeight = 400f)
            assertTrue(value >= last, "went back at $by: $value < $last")
            last = value
        }
        assertEquals(1f, last)
    }

    @Test
    fun pastTheLastStopIsTheWholeTour() {
        assertEquals(1f, tourProgress(4, emptyList(), 5, line, bottom, rest = null))
        assertEquals(0f, tourProgress(0, emptyList(), 0, line, bottom, rest = null))
    }
}
