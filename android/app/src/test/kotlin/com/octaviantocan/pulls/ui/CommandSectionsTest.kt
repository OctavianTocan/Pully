package com.octaviantocan.pulls.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The groups of Commands: what the page suggests first, General last (N14). */
class CommandSectionsTest {
    private fun command(id: String, group: String?, suggested: Boolean = false) = Command(id, id, group, suggested = suggested) {}

    @Test
    fun suggestedComesFirstAndGeneralLast() {
        val sections = commandSections(
            listOf(command("theme", null), command("next", "Pull request"), command("ask", "Tour", suggested = true), command("update", "General"), command("diff", "Tour")),
            general = "General",
        )
        assertEquals(listOf(SUGGESTED, "Pull request", "Tour", "General"), sections.map { it.first })
        assertEquals(listOf("ask"), sections.first().second.map { it.id })
        assertEquals(listOf("theme", "update"), sections.last().second.map { it.id })
    }

    @Test
    fun noSuggestionsMeansNoSuggestedGroup() {
        assertEquals(listOf("General"), commandSections(listOf(command("theme", null)), "General").map { it.first })
    }
}
