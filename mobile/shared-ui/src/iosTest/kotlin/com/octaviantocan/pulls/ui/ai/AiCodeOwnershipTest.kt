package com.octaviantocan.pulls.ui.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AiCodeOwnershipTest {
    @Test
    fun mountedSnippetAndProbeReloadForANewRevisionEvenWhenTextAndHeadMatchAgain() = runTest {
        assertCodeReloadsAcrossViewRevisions()
    }
}
