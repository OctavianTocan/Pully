package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.ui.review.DiffHarness
import com.octaviantocan.pulls.ui.review.NoNodes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Mounts the real PillInput state owner on Native without requiring a font or an app window. */
@OptIn(ExperimentalCoroutinesApi::class)
class AskComposerOwnershipTest {
    @Test
    fun draftsStayWithTheirExactSessionFullViewAndThread() = runTest {
        val first = DiffHarness(backgroundScope)
        val second = DiffHarness(backgroundScope)
        first.page.load(); second.page.load(); runCurrent()
        assertEquals(first.page.captureView(), second.page.captureView())
        var session by mutableStateOf(first.page)
        var thread by mutableStateOf("first-thread")
        var state: AskComposerState? = null
        var input = ""
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        suspend fun frame(nanos: Long) {
            Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(nanos); runCurrent()
        }
        try {
            composition.setContent {
                val composer = rememberAskComposerState(session, session.captureView(), thread)
                state = composer
                input = composer.question
            }
            runCurrent(); frame(1L)
            val original = assertNotNull(state)
            original.question = "Private unsent question"
            frame(2L)
            assertSame(original, state)
            assertEquals("Private unsent question", input)

            session = second.page
            frame(3L)
            val otherSource = assertNotNull(state)
            assertNotSame(original, otherSource)
            assertEquals("", otherSource.question)
            otherSource.question = "Other account's unsent question"

            second.head = "head-b"; second.page.refresh(); runCurrent()
            second.head = "head-a"; second.page.refresh(); runCurrent()
            frame(4L)
            val newRevision = assertNotNull(state)
            assertNotSame(otherSource, newRevision)
            assertEquals("", newRevision.question)
            newRevision.question = "Current thread's question"

            thread = "replacement-thread"
            frame(5L)
            val newThread = assertNotNull(state)
            assertNotSame(newRevision, newThread)
            assertEquals("", newThread.question)
            newThread.question = "Still typing"
            frame(6L)
            assertSame(newThread, state)
            assertEquals("Still typing", input)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel()
            first.page.detach(); second.page.detach()
        }
    }
}
