package com.octaviantocan.pulls.ui.review

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSearchTest {
    private val path = "apps/web/src/domains/practice/components/OjinAgentMount.tsx"

    @Test
    fun anEmptySearchMatchesEveryFile() {
        assertTrue(matchesFile(path, ""))
        assertTrue(matchesFile(path, "   "))
    }

    @Test
    fun aSearchMatchesPartOfTheNameInAnyCase() {
        assertTrue(matchesFile(path, "agentmount"))
        assertTrue(matchesFile(path, "OJIN"))
    }

    @Test
    fun everyWordHasToBeInThePathInAnyOrder() {
        assertTrue(matchesFile(path, "mount practice"))
        assertFalse(matchesFile(path, "mount courses"))
    }
}
