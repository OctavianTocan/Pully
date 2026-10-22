package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ToasterTest {
    @Test
    fun aVisibleActionRunsOnceWhileItsTicketIsCurrentAndThenClears() {
        val toaster = Toaster()
        var calls = 0
        lateinit var ticket: ToastMessage
        toaster.show("Closed", "Undo") {
            assertSame(ticket, toaster.message)
            calls++
            toaster.act(ticket)
        }
        ticket = assertNotNull(toaster.message)
        toaster.act(ticket)
        toaster.act(ticket)
        assertEquals(1, calls)
        assertNull(toaster.message)
    }

    @Test
    fun replacedAndExpiredToastActionsCannotRun() {
        val toaster = Toaster()
        var calls = 0
        toaster.show("Old", "Undo") { calls++ }
        val replaced = assertNotNull(toaster.message)
        toaster.show("New", "Undo") { calls++ }
        val current = assertNotNull(toaster.message)
        toaster.act(replaced)
        assertSame(current, toaster.message)
        toaster.clear(current)
        toaster.act(current)
        assertEquals(0, calls)
        assertNull(toaster.message)
    }

    @Test
    fun anActionCanShowAReplacementWithoutClearingOrReenteringIt() {
        val toaster = Toaster()
        var calls = 0
        toaster.show("Old", "Undo") {
            calls++
            toaster.show("New", "Open") { calls++ }
            toaster.act(assertNotNull(toaster.message))
        }
        toaster.act(assertNotNull(toaster.message))
        assertEquals(1, calls)
        val replacement = assertNotNull(toaster.message)
        assertEquals("New", replacement.text)
        toaster.act(replacement)
        assertEquals(2, calls)
        assertNull(toaster.message)
    }

    @Test
    fun aFailedActionRetiresOnlyItsOwnTicketAndAllowsTheNextAction() {
        val toaster = Toaster()
        var calls = 0
        toaster.show("Old", "Undo") {
            toaster.show("Replacement", "Retry") { calls++ }
            error("Rejected")
        }
        val ticket = assertNotNull(toaster.message)
        assertFailsWith<IllegalStateException> { toaster.act(ticket) }
        val replacement = assertNotNull(toaster.message)
        assertEquals("Replacement", replacement.text)
        toaster.act(ticket)
        assertSame(replacement, toaster.message)
        toaster.act(replacement)
        assertEquals(1, calls)
        assertNull(toaster.message)
    }
}
