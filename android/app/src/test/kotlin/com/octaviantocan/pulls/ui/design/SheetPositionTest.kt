package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Where a sheet rests when its size or half the window changes. Run without motion, so every move is immediate. */
class SheetPositionTest {
    private val scope = CoroutineScope(Job())

    private fun expandable(half: Float, height: Int) = SheetPosition().apply {
        expandable = true
        this.half = half
        measured(height, scope, false)
    }

    @Test fun anExpandableSheetOpensAtHalfTheWindow() {
        assertEquals(800f, expandable(1000f, 1800).offset)
    }

    @Test fun itFollowsAChangeOfHalfTheWindowWithNoChangeOfSize() {
        val position = expandable(1000f, 1800)
        position.half = 600f
        position.follow(scope, false)
        assertEquals(1200f, position.offset)
    }

    @Test fun itFollowsANewSize() {
        val position = expandable(1000f, 1800)
        position.measured(1500, scope, false)
        assertEquals(500f, position.shift(1500f))
    }

    @Test fun aRestingPanelKeepsItsStopAsItsSizeMovesFrameByFrame() {
        val position = expandable(1000f, 1800)
        // The size animates, one frame after another; the stop is worked out from each size, so the top never lags.
        listOf(1900, 2100, 2400).forEach { px ->
            position.measured(px, scope, false)
            assertEquals(px - 1000f, position.shift(px.toFloat()))
        }
    }

    @Test fun aPanelShorterThanHalfRestsAtTheTop() {
        val position = expandable(1000f, 900)
        assertEquals(0f, position.shift(900f))
        position.measured(1800, scope, false)
        assertEquals(800f, position.shift(1800f))
        position.measured(700, scope, false)
        assertEquals(0f, position.shift(700f))
    }

    @Test fun aPanelThatWasPulledUpStaysUpWhenAnotherPageSlidesIn() {
        val position = expandable(1000f, 1800)
        position.settle(scope, -2000f, 500f, false) {}
        position.measured(1400, scope, false)
        assertEquals(0f, position.shift(1400f))
    }

    @Test fun aSheetUnderAFingerIsLeftAlone() {
        val position = expandable(1000f, 1800)
        position.dragBy(100f)
        position.half = 600f
        position.follow(scope, false)
        position.measured(1500, scope, false)
        assertEquals(900f, position.offset)
    }

    @Test fun aSheetThatIsClosingIsLeftAlone() {
        val position = expandable(1000f, 1800)
        position.startClosing()
        position.half = 600f
        position.follow(scope, false)
        assertEquals(800f, position.offset)
    }

    @Test fun aSheetThatRestsAtFullHeightStaysThere() {
        val position = expandable(1000f, 1800)
        position.settle(scope, -2000f, 500f, false) {}
        assertEquals(0f, position.offset)
        position.half = 600f
        position.follow(scope, false)
        assertEquals(0f, position.offset)
    }
}
