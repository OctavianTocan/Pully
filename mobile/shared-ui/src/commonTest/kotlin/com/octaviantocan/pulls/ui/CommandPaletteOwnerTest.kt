package com.octaviantocan.pulls.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPaletteOwnerTest {
    @Test
    fun synchronousRetirementClearsPrivateInputAndRetainedActionsBeforeReplacement() {
        var generation = 1
        val owner = CommandPaletteOwner { generation == 1 }
        val page = CommandPageState(owner)
        val stop = owner.observeRetirement(page::clear)
        var runs = 0
        val command = Command("account", "Account") { runs++ }
        page.changeQuery("private account")
        page.expand("Account")
        Commands.open(PaletteScope("account", "Account"))
        generation++
        owner.close()
        assertFalse(owner.current())
        assertFalse(Commands.paletteOpen)
        assertNull(Commands.scope)
        assertEquals("", page.query)
        assertTrue(page.expanded.isEmpty())
        page.changeQuery("old input")
        page.expand("Old")
        page.run(command) { it.run() }
        assertEquals(0, runs)
        assertEquals("", page.query)
        assertTrue(page.expanded.isEmpty())
        stop()
        owner.close()
    }
}
