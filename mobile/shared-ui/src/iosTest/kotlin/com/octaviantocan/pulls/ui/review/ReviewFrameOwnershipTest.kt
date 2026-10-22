package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewFrameOwnershipTest {
    @Test
    fun theMountedFrameUsesLatestPoliciesAndClosesItsRetainedCallbacks() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var mounted by mutableStateOf(true)
        var review by mutableStateOf(ReviewCapabilities.Full)
        var ai by mutableStateOf(AiCapabilities.Full)
        var owner: ReviewFrameOwner? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        var nanos = 0L
        suspend fun frame() {
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++nanos); runCurrent()
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalReviewCapabilities provides review, LocalAiCapabilities provides ai) {
                    if (mounted) owner = rememberReviewFrameOwner(h.page, { true }, true)
                }
            }
            runCurrent(); frame()
            val first = assertNotNull(owner)
            assertTrue(first.canReadDiff); assertTrue(first.canReadTour)
            review = ReviewCapabilities.None; ai = AiCapabilities.None; frame()
            assertSame(first, owner)
            assertFalse(first.canReadDiff); assertFalse(first.canReadTour); assertFalse(first.canCritique)
            review = ReviewCapabilities.Full; ai = AiCapabilities.Full; frame()
            h.head = "head-b"; h.page.refresh(); runCurrent()
            h.head = "head-a"; h.page.refresh(); runCurrent(); frame(); frame()
            val next = assertNotNull(owner)
            assertNotSame(first, next)
            assertFalse(first.current)
            assertTrue(next.canReadDiff)
            mounted = false; frame()
            assertFalse(next.current)
            var starts = 0
            var feedback = 0
            assertFalse(next.critique(null, { starts++ }) { feedback++ })
            assertEquals(0, starts); assertEquals(0, feedback)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
