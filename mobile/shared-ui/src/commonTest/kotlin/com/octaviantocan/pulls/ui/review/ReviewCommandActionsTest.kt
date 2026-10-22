package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewCommandActionsTest {
    @Test
    fun actualPaletteProgressAndFileActionsUseReadinessAndExactCurrentFilesWithoutGithubWritePermission() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        var policy = ReviewCapabilities.ReadOnly
        val actions = ReviewCommandActions(h.page, { policy }, { AiCapabilities.None })
        assertFalse(h.page.canWrite)
        actions.toggleReviewed(file)
        assertTrue(h.page.reviewed.isEmpty())
        policy = ReviewCapabilities.Full
        actions.toggleReviewed(file); runCurrent()
        assertTrue(file.sha in h.page.reviewed)
        var reads = 0
        actions.file(file) { reads++ }
        actions.file(file.copy()) { reads++ }
        assertEquals(1, reads)
        h.page.detach()
    }

    @Test
    fun aRetainedPaletteCommandCannotAdoptAReplacedHeadOrPolicyAndDisposalRetiresAllKinds() = runTest {
        for (replacement in listOf("head-aba", "source", "policy", "disposed")) {
            val h = DiffHarness(backgroundScope)
            h.page.load(); runCurrent()
            val file = h.page.files.single()
            var review = ReviewCapabilities.Full
            var ai = AiCapabilities.Full
            val actions = ReviewCommandActions(h.page, { review }, { ai })
            var runs = 0
            actions.file(file) { runs++ }; actions.analyze { runs++ }; actions.ask { runs++ }
            assertEquals(3, runs)
            when (replacement) {
                "head-aba" -> {
                    h.head = "head-b"; h.page.refresh(); runCurrent()
                    h.head = "head-a"; h.page.refresh(); runCurrent()
                }
                "source" -> h.generation++
                "policy" -> { review = ReviewCapabilities.None; ai = AiCapabilities.None }
                else -> actions.close()
            }
            runs = 0
            actions.file(file) { runs++ }; actions.diff { runs++ }; actions.analyze { runs++ }; actions.ask { runs++ }; actions.ai { runs++ }
            assertEquals(0, runs, replacement)
            h.page.detach()
        }
    }
}
