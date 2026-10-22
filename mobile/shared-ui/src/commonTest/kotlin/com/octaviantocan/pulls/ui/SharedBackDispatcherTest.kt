package com.octaviantocan.pulls.ui

import kotlin.test.*

class SharedBackDispatcherTest {
    @Test
    fun editingConsumesTheFirstBackAndTheUnchangedRouteStackHandlesTheNext() {
        val stack = AppNavigation()
        stack.nav.openRepo("a", "b")
        val back = SharedBackDispatcher()
        var editing = true
        val edit = back.register({ editing }) { editing = false }
        assertTrue(back.consume(back.current()!!))
        assertFalse(editing)
        assertEquals(2, stack.stack.size)
        assertFalse(back.canConsume)
        stack.nav.back()
        assertEquals(listOf(Screen.Home), stack.stack.toList())
        back.remove(edit)
    }

    @Test
    fun anUnderlyingPageCannotConsumeBackFromThePageAboveIt() {
        val stack = AppNavigation()
        val back = SharedBackDispatcher()
        var homeEditCancelled = false
        back.register({ stack.stack.last() == Screen.Home }) { homeEditCancelled = true }
        stack.nav.openRepo("a", "b")
        assertFalse(back.canConsume)
        stack.nav.back()
        assertTrue(back.consume(back.current()!!))
        assertTrue(homeEditCancelled)
    }

    @Test
    fun aCapturedConsumerCannotRunAfterItIsRemovedOrReplacedByAnotherEditor() {
        val back = SharedBackDispatcher()
        var calls = 0
        val old = back.register({ true }) { calls++ }
        val newer = back.register({ true }) { calls += 10 }
        assertFalse(back.consume(old))
        assertTrue(back.consume(newer))
        back.remove(newer); back.remove(old)
        assertFalse(back.consume(old))
        assertEquals(10, calls)
    }
}
