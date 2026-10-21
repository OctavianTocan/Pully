package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.ui.SharedBackDispatcher
import com.octaviantocan.pulls.ui.design.PageMotion
import kotlinx.coroutines.Job
import kotlin.test.*

class HomeBackEffectTest {
    @Test
    fun anEditorBackInterruptingARouteReturnLeavesThePageFullyInPlace() {
        val page = Any()
        val motion = PageMotion().apply { onScreen = page }
        val backs = SharedBackDispatcher()
        var popped = false
        val returning = Job()
        val observer = homeBackObserver(motion, backs, false, { true }, { true }, { popped = true }) {
            // The animation adapter is returning from the cancelled route gesture.
            motion.putBack = returning
        }
        observer.began(); observer.changed(0.4f); observer.cancelled()
        assertSame(page, motion.held)
        assertEquals(0.4f, motion.hold)
        var editing = true
        backs.register({ editing }) { editing = false }
        observer.began(); observer.changed(0.8f); observer.committed()
        assertTrue(returning.isCancelled)
        assertFalse(editing)
        assertFalse(popped)
        assertNull(motion.held)
        assertEquals(0f, motion.hold)
    }

    @Test
    fun removingTheCapturedEditorDuringAGestureCannotTurnItIntoARoutePop() {
        val motion = PageMotion().apply { onScreen = Any() }
        val backs = SharedBackDispatcher()
        var edits = 0
        var popped = false
        val editor = backs.register({ true }) { edits++ }
        val observer = homeBackObserver(motion, backs, false, { true }, { true }, { popped = true }) {}
        observer.began()
        backs.remove(editor)
        observer.committed()
        assertEquals(0, edits)
        assertFalse(popped)
        assertNull(motion.held)
        assertEquals(0f, motion.hold)
    }
}
