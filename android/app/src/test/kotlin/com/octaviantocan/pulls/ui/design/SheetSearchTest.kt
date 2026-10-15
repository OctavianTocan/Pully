package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SheetSearchTest {
    private val path = "apps/web/src/domains/practice/components/OjinAgentMount.tsx"

    @Test
    fun anEmptySearchMatchesEveryRow() {
        assertTrue(matchesSearch(path, ""))
        assertTrue(matchesSearch(path, "   "))
    }

    @Test
    fun aSearchMatchesPartOfTheTextInAnyCase() {
        assertTrue(matchesSearch(path, "agentmount"))
        assertTrue(matchesSearch(path, "OJIN"))
    }

    @Test
    fun everyWordHasToBeInTheTextInAnyOrder() {
        assertTrue(matchesSearch(path, "mount practice"))
        assertFalse(matchesSearch(path, "mount courses"))
        assertTrue(matchesSearch("OctavianTocan/pulls.review", "octavian review"))
    }

    @Test
    fun groupedRowsKeepTheirOrderWhenAKeyComesBack() {
        val rows = listOf("Alpha" to "A", "Beta" to "B", "Another" to "A")
        assertEquals(
            listOf("A" to listOf("Alpha"), "B" to listOf("Beta"), "A" to listOf("Another")),
            searchRuns(rows) { it.second }.map { (key, run) -> key to run.map { it.first } },
        )
    }
}
