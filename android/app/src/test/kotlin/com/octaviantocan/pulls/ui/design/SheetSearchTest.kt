package com.octaviantocan.pulls.ui.design

import kotlin.test.Test
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
}
