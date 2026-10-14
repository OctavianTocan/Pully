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
        assertEquals(500f, position.offset)
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
