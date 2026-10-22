package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFrameOwnerTest {
    @Test
    fun currentReadinessIsRequiredEvenAfterAFileHasAlreadyOpened() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var review = ReviewCapabilities.Full
        var ai = AiCapabilities.Full
        val owner = ReviewFrameOwner(h.page, { true }, { review }, { ai }, true)
        assertTrue(owner.canReadDiff)
        assertTrue(owner.canReadTour)
        review = ReviewCapabilities.None
        ai = AiCapabilities.None
        assertFalse(owner.canReadDiff)
        assertFalse(owner.canReadTour)
        var starts = 0
        var feedback = 0
        assertFalse(owner.critique("security", { starts++ }) { feedback++ })
        assertEquals(0, starts)
        assertEquals(0, feedback)
        h.page.detach()
    }

    @Test
    fun critiqueCannotEscapeHeadAbaSourceRetirementOrDisposal() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        fun owner() = ReviewFrameOwner(h.page, { true }, { ReviewCapabilities.Full }, { AiCapabilities.Full }, true)
        val old = owner()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        var starts = 0
        var feedback = 0
        assertFalse(old.critique(null, { starts++ }) { feedback++ })
        val disposed = owner().also { it.close() }
        assertFalse(disposed.critique(null, { starts++ }) { feedback++ })
        val retired = owner()
        h.generation++
        assertFalse(retired.critique(null, { starts++ }) { feedback++ })
        assertFalse(retired.canReadDiff)
        assertFalse(retired.canReadTour)
        assertEquals(0, starts)
        assertEquals(0, feedback)
        h.page.detach()
    }

    @Test
    fun critiqueStartsTheActualSessionOnceAndChecksOwnershipAgainBeforeFeedback() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var sourceCurrent = true
        val owner = ReviewFrameOwner(h.page, { sourceCurrent }, { ReviewCapabilities.Full }, { AiCapabilities.Full }, true)
        var starts = 0
        val feedback = mutableListOf<Boolean>()
        val start: (String?) -> Unit = { lens ->
            assertEquals("security", lens)
            starts++
            h.page.startTask("critique")
        }
        assertTrue(owner.critique("security", start) { feedback += it })
        assertTrue(owner.critique("security", start) { feedback += it })
        assertEquals(1, starts)
        assertEquals(listOf(false, true), feedback)
        // The installed host can synchronously invalidate its source while beginning work.
        val other = DiffHarness(backgroundScope)
        other.page.load(); runCurrent()
        val guard = ReviewFrameOwner(other.page, { sourceCurrent }, { ReviewCapabilities.Full }, { AiCapabilities.Full }, true)
        assertTrue(guard.critique(null, { sourceCurrent = false }) { feedback += it })
        assertEquals(listOf(false, true), feedback)
        h.page.detach(); other.page.detach()
    }

    @Test
    fun openingNavigationRetiresWhenTheDiffArrivesAndCannotStartPaidWorkEarly() = runTest {
        val h = DiffHarness(backgroundScope)
        val opening = ReviewFrameOwner(h.page, { true }, { ReviewCapabilities.ReadOnly }, { AiCapabilities.ReadOnly }, true)
        assertTrue(opening.canReadDiff)
        assertTrue(opening.canReadTour)
        assertFalse(opening.canCritique)
        h.page.load(); runCurrent()
        assertFalse(opening.canReadDiff)
        assertFalse(opening.canReadTour)
        val withoutTour = ReviewFrameOwner(h.page, { true }, { ReviewCapabilities.ReadOnly }, { AiCapabilities.ReadOnly }, false)
        assertFalse(withoutTour.canReadTour)
        h.page.detach()
    }
}
