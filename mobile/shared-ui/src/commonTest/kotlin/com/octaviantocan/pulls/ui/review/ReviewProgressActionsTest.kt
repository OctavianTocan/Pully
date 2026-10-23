package com.octaviantocan.pulls.ui.review

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewProgressActionsTest {
    @Test
    fun aMetadataOnlyRefreshRequiresTheExactFileThatDrewTheControl() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val first = h.page.files.single()
        val view = h.page.captureView()
        val actions = ReviewProgressActions(h.page) { ReviewCapabilities.Full }
        h.changeOnlyReviewTitle(); h.page.refresh(); runCurrent()
        val current = h.page.files.single()
        assertEquals(first, current)
        assertNotSame(first, current)
        assertEquals(view, h.page.captureView())
        var writes = 0
        h.handler = { name, args -> if (name == "reviewed-set") writes++; h.default(name, args) }
        assertFalse(actions.mark(first, true)); runCurrent()
        assertTrue(h.page.reviewed.isEmpty())
        assertEquals(0, writes)
        assertTrue(actions.mark(current, true)); runCurrent()
        assertEquals(setOf(current.sha), h.page.reviewed)
        assertEquals(1, writes)
        h.page.detach()
    }

    @Test
    fun actualOverviewAndSheetProgressRequiresHostReadinessWithoutGithubWritePermission() = runTest {
        val h = DiffHarness(backgroundScope)
        var writes = 0
        h.handler = { name, args -> if (name == "reviewed-set") writes++; h.default(name, args) }
        h.page.load(); runCurrent()
        assertFalse(h.page.canWrite)
        val file = h.page.files.single()
        var policy = ReviewCapabilities.ReadOnly
        val actions = ReviewProgressActions(h.page) { policy }
        assertFalse(actions.enabled)
        assertFalse(actions.mark(file, true)); runCurrent()
        assertTrue(h.page.reviewed.isEmpty())
        assertEquals(0, writes)
        policy = ReviewCapabilities.Full
        assertTrue(actions.mark(file, true)); runCurrent()
        assertEquals(setOf(file.sha), h.page.reviewed)
        assertEquals(1, writes)
        policy = ReviewCapabilities.ReadOnly
        assertFalse(actions.mark(file, false)); runCurrent()
        assertEquals(1, writes)
        assertEquals(setOf(file.sha), h.page.reviewed)
        h.page.detach()
    }

    @Test
    fun anOldOverviewOrSheetCannotMarkAfterHeadAbaOrDisposal() = runTest {
        val h = DiffHarness(backgroundScope)
        var writes = 0
        h.handler = { name, args -> if (name == "reviewed-set") writes++; h.default(name, args) }
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val old = ReviewProgressActions(h.page) { ReviewCapabilities.Full }
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        assertFalse(old.mark(file, true)); runCurrent()
        assertEquals(0, writes)
        val replacement = ReviewProgressActions(h.page) { ReviewCapabilities.Full }
        replacement.close()
        assertFalse(replacement.mark(h.page.files.single(), true)); runCurrent()
        assertTrue(h.page.reviewed.isEmpty())
        assertEquals(0, writes)
        h.page.detach()
    }

    @Test
    fun sourceRevocationDeniesEvenARetainedFullReadinessCallback() = runTest {
        val h = DiffHarness(backgroundScope)
        var writes = 0
        h.handler = { name, args -> if (name == "reviewed-set") writes++; h.default(name, args) }
        h.page.load(); runCurrent()
        val file = h.page.files.single()
        val actions = ReviewProgressActions(h.page) { ReviewCapabilities.Full }
        h.generation++
        assertFalse(actions.mark(file, true)); runCurrent()
        assertEquals(0, writes)
        assertTrue(h.page.reviewed.isEmpty())
        h.page.detach()
    }
}
