package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animate

/**
 * Where a sheet rests: its normal height and its tall height (rule 77), and what moves it between them. Run without
 * motion, so every move is immediate. The panel is always the tall height (2000px here); its offset is how far it is
 * pushed down from there.
 */
class SheetPositionTest {
    private val scope = CoroutineScope(Job())
    private val speed = 500f

    private fun sheet(half: Float, content: Int, panel: Int = 2000, expandable: Boolean = true) = SheetPosition().apply {
        this.expandable = expandable
        this.half = half
        measured(panel, scope, false)
        measuredContent(content, scope, false)
    }

    private fun full(half: Float, panel: Int = 2000) = SheetPosition().apply {
        fills = true
        this.half = half
        measured(panel, scope, false)
    }

    @Test fun aLongSheetOpensAtHalfTheWindow() {
        // 1800px of content shows 1000px, half the window, so the 2000px panel is pushed down 1000px.
        assertEquals(1000f, sheet(1000f, 1800).offset)
    }

    @Test fun aShortSheetOpensAtItsContentHeight() {
        assertEquals(1400f, sheet(1000f, 600).offset)
    }

    @Test fun itOpensOnceBothThePanelAndTheContentHaveASize() {
        val position = SheetPosition().apply { half = 1000f }
        position.measured(2000, scope, false)
        assertFalse(position.settled)
        position.measuredContent(600, scope, false)
        assertTrue(position.settled)
        assertEquals(1400f, position.shift(2000f))
    }

    @Test fun aShortSheetLiftsToTheTallHeightAndComesBack() {
        val position = sheet(1000f, 600)
        position.dragBy(-300f)
        position.settle(scope, -2000f, speed, false) {}
        assertEquals(0f, position.offset)
        assertTrue(position.tall)
        // Down from the tall height goes to the normal one, not off the screen.
        position.dragBy(200f)
        position.settle(scope, 2000f, speed, false) { error("dismissed from the tall height") }
        assertEquals(1400f, position.offset)
        assertFalse(position.tall)
        // Down from the normal height closes it.
        var dismissed = false
        position.dragBy(100f)
        position.settle(scope, 2000f, speed, false) { dismissed = true }
        assertTrue(dismissed)
    }

    @Test fun aSlowDragUpPastHalfWayLiftsItAndAShortOneFallsBack() {
        val position = sheet(1000f, 600)
        position.dragBy(-400f)
        position.settle(scope, 0f, speed, false) {}
        assertEquals(1400f, position.offset)
        position.dragBy(-900f)
        position.settle(scope, 0f, speed, false) {}
        assertEquals(0f, position.offset)
    }

    @Test fun aFullSheetRestsAtHalfAndLaysItsContentOutAtWhatShows() {
        val position = full(1000f)
        assertEquals(1000f, position.offset)
        assertEquals(1000, position.shown())
        position.lift(scope, false)
        assertEquals(0f, position.offset)
        assertEquals(2000, position.shown())
        // Leaving, the content keeps its normal height and rides down with the panel, footer and all.
        position.dragBy(1500f)
        assertEquals(1000, position.shown())
    }

    @Test fun liftedBeforeItOpensItOpensTall() {
        val position = SheetPosition().apply { half = 1000f }
        position.lift(scope, false)
        position.measured(2000, scope, false)
        position.measuredContent(600, scope, false)
        assertEquals(0f, position.offset)
        assertTrue(position.tall)
    }

    @Test fun keyboardLiftStartsAtTheVisibleStopAndUsesTheReleasedDragSpring() = runBlocking {
        val clock = BroadcastFrameClock()
        val animatedScope = CoroutineScope(coroutineContext + clock)
        // Short action sheets, long searchable sheets and full chat sheets share the same lift.
        for (make in listOf<() -> SheetPosition>(
            { sheet(1000f, 600) },
            { sheet(1000f, 1800) },
            { full(1000f) },
        )) {
            val focused = make()
            val start = focused.shift(2000f)
            var expected = start
            animatedScope.launch {
                animate(start, 0f, animationSpec = PullsMotion.SheetSpring) { value, _ -> expected = maxOf(value, 0f) }
            }
            focused.lift(animatedScope, true)
            // A focus change must not first snap the pinned stop to tall before animation begins.
            assertEquals(start, focused.shift(2000f))
            yield()
            repeat(100) { frame ->
                clock.sendFrame(frame * 16_000_000L)
                yield()
                assertEquals(expected, focused.shift(2000f), 0.01f)
                if (frame == 2) assertTrue(focused.offset > 0f && focused.offset < start)
            }
            assertTrue(focused.settled)
            assertEquals(0f, focused.shift(2000f))
        }
    }

    @Test fun aSheetWithNoTallHeightStaysAtItsContent() {
        val position = sheet(1000f, 1800, expandable = false)
        assertEquals(200f, position.offset)
        position.lift(scope, false)
        assertEquals(200f, position.shift(2000f))
        // A finger cannot take it above its one rest, and a throw up leaves it there.
        position.dragBy(-500f)
        assertEquals(200f, position.offset)
        position.settle(scope, -2000f, speed, false) {}
        assertEquals(200f, position.offset)
        assertFalse(position.canRise)
    }

    @Test fun itFollowsAChangeOfHalfTheWindowWithNoChangeOfSize() {
        val position = sheet(1000f, 1800)
        position.updateHalf(600f)
        position.follow(scope, false)
        assertEquals(1400f, position.offset)
    }

    @Test fun aRestingPanelKeepsItsStopAsItsContentMovesFrameByFrame() {
        val position = sheet(1000f, 300)
        // The content animates, one frame after another (a page of another height); the stop is worked out from each size.
        listOf(400, 600, 900, 1200).forEach { px ->
            position.measuredContent(px, scope, false)
            assertEquals(2000f - minOf(px, 1000), position.shift(2000f))
        }
    }

    @Test fun aPanelThatWasPulledUpStaysUpWhenAnotherPageSlidesIn() {
        val position = sheet(1000f, 1800)
        position.settle(scope, -2000f, speed, false) {}
        position.measuredContent(400, scope, false)
        assertEquals(0f, position.shift(2000f))
    }

    @Test fun aSheetUnderAFingerIsLeftAlone() {
        val position = sheet(1000f, 1800)
        position.dragBy(100f)
        assertTrue(position.held)
        position.updateHalf(600f)
        position.follow(scope, false)
        position.measuredContent(1500, scope, false)
        assertEquals(1100f, position.offset)
    }

    @Test fun aSheetThatIsClosingIsLeftAlone() {
        val position = sheet(1000f, 1800)
        position.startClosing()
        position.updateHalf(600f)
        position.follow(scope, false)
        position.lift(scope, false)
        assertEquals(1000f, position.offset)
    }

    @Test fun aSheetThatRestsAtTheTallHeightStaysThere() {
        val position = sheet(1000f, 1800)
        position.settle(scope, -2000f, speed, false) {}
        assertEquals(0f, position.offset)
        position.updateHalf(600f)
        position.follow(scope, false)
        assertEquals(0f, position.shift(2000f))
        assertFalse(position.canRise)
    }
}
