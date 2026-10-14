package com.octaviantocan.pulls.core

import kotlin.test.assertEquals
import kotlin.test.Test

class PathTailTest {
    private fun under(n: Int): (String) -> Boolean = { it.length <= n }

    @Test
    fun aPathThatFitsIsWhole() {
        assertEquals("src/app", pathTail("src/app", under(20)))
    }

    @Test
    fun aLongPathKeepsItsEndAndLosesItsFront() {
        assertEquals("…/domains/practice/components", pathTail("apps/web/src/domains/practice/components", under(30)))
        assertEquals("…/components", pathTail("apps/web/src/domains/practice/components", under(14)))
    }

    @Test
    fun theCutIsAlwaysAtAFolder() {
        val tail = pathTail("packages/core/src/cache", under(12))
        assertEquals("…/src/cache", tail)
    }

    @Test
    fun aLastFolderTooLongForTheSpaceIsStillShownAlone() {
        assertEquals("…/averyveryverylongfoldername", pathTail("a/averyveryverylongfoldername", under(10)))
    }

    @Test
    fun noPathIsNothing() {
        assertEquals("", pathTail("", under(0)))
    }

    @Test
    fun aLongNameBreaksBetweenItsWords() {
        assertEquals("Assessment\u200BSession\u200BPage.tsx", softBreaks("AssessmentSessionPage.tsx"))
        assertEquals("use-\u200Bpull-\u200Brequest.test.ts", softBreaks("use-pull-request.test.ts"))
        assertEquals("snake_\u200Bcase.py", softBreaks("snake_case.py"))
    }

    @Test
    fun aNameWithNothingToBreakIsUnchanged() {
        assertEquals("index.ts", softBreaks("index.ts"))
        assertEquals("README.md", softBreaks("README.md"))
        assertEquals("trailing-", softBreaks("trailing-"))
    }
}
