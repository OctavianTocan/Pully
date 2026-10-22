package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.ui.SharedBackDispatcher
import com.octaviantocan.pulls.ui.SharedPageBackGesture
import com.octaviantocan.pulls.core.BACKWARD
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
    @Test
    fun aReviewPageFollowsTheOneRootGestureAndPopsOnlyItsNestedStack() {
        val root = PageMotion().apply { onScreen = "review" }
        val nested = PageMotion().apply { onScreen = "diff" }
        val pages = mutableListOf("overview", "diff")
        val backs = SharedBackDispatcher()
        var rootPops = 0
        val gesture = SharedPageBackGesture(nested, BACKWARD, { pages.size > 1 }, { true }, { pages.removeAt(pages.lastIndex) }) {
            nested.hold = 0f; nested.held = null
        }
        backs.register({ pages.size > 1 }, gesture, gesture::commit)
        val observer = homeBackObserver(root, backs, false, { true }, { true }, { rootPops++ }) {}
        observer.began(); observer.changed(0.6f)
        assertEquals("diff", nested.held)
        assertEquals(0.6f, nested.hold)
        assertNull(root.held)
        observer.committed()
        assertEquals(listOf("overview"), pages)
        assertEquals(0, rootPops)
        assertEquals(BACKWARD, nested.take())
        observer.began(); observer.committed()
        assertEquals(1, rootPops)
    }

    @Test
    fun cancellingNestedBackReturnsItsPageAndANewSheetCannotCommitTheCapturedPage() {
        val root = PageMotion().apply { onScreen = "review" }
        val nested = PageMotion().apply { onScreen = "diff" }
        val backs = SharedBackDispatcher()
        var nestedPops = 0
        var returned = 0
        var rootPops = 0
        val gesture = SharedPageBackGesture(nested, BACKWARD, { true }, { true }, { nestedPops++ }) {
            returned++; nested.held = null; nested.hold = 0f
        }
        backs.register({ true }, gesture, gesture::commit)
        val observer = homeBackObserver(root, backs, false, { true }, { true }, { rootPops++ }) {}
        observer.began(); observer.changed(0.4f); observer.cancelled()
        assertEquals(1, returned)
        assertNull(nested.held)
        assertEquals(0f, nested.hold)
        observer.began(); observer.changed(0.7f)
        var sheetDismissals = 0
        backs.register({ true }) { sheetDismissals++ }
        observer.committed()
        assertEquals(2, returned)
        assertEquals(0, nestedPops)
        assertEquals(0, rootPops)
        assertEquals(0, sheetDismissals)
    }

}
