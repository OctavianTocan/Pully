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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Mounts the exact helper used by FilePages: returning to a path cannot revive its old visit's actions. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiffPageLifetimeTest {
    @Test
    fun actualRememberedReaderClosesTheEarlierVisitBeforeReturningToTheSameFile() = runTest {
        val h = DiffHarness(backgroundScope)
        installTwoFiles(h)
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        var file by mutableStateOf(h.page.files.first())
        var actual: DiffPageActions? = null
        val moved = mutableListOf<String>()
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
                CompositionLocalProvider(LocalReviewCapabilities provides ReviewCapabilities.Full, LocalAiCapabilities provides AiCapabilities.Full) {
                    actual = rememberDiffPageActions(h.page, ui, file, backgroundScope) { true }
                }
            }
            runCurrent(); frame()
            val firstVisit = assertNotNull(actual)
            frame(); assertSame(firstVisit, actual)
            firstVisit.toggleReviewed(h.page.files, {}, moved::add); runCurrent()
            file = h.page.files[1]; frame()
            assertFalse(firstVisit.current)
            file = h.page.files.first(); frame()
            assertNotSame(firstVisit, actual)
            assertTrue(assertNotNull(actual).current)
            advanceTimeBy(220); runCurrent()
            assertTrue(moved.isEmpty())
            var asks = 0
            firstVisit.ask { asks++ }
            assertEquals(0, asks)
            composition.dispose(); assertFalse(assertNotNull(actual).current)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
        }
    }
}
