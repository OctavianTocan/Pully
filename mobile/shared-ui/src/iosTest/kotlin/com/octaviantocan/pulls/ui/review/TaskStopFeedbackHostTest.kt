package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Input
import com.octaviantocan.pulls.sound.NativeControlFeedback
import com.octaviantocan.pulls.sound.NativeGestureFeedback
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Toaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mount the same task-backed Stop used by Tour, review, inline Ask and Chat in the real Native Compose runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskStopFeedbackHostTest {
    @Test
    fun theMountedStopReportsAReal503AndKeepsItsActualRetryAvailable() = runTest {
        val cues = mutableListOf<Cue>()
        val previous = runCatching { ControlFeedback.installed() }.getOrNull()
        ControlFeedback.install(recordingFeedback(cues))
        val h = StopFixture(backgroundScope)
        h.page.load(); runCurrent()
        val toaster = Toaster()
        var ready = true
        val readiness = ReviewActionReadiness({ ready })
        var stop: (() -> Unit)? = null
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(NoNodes(), recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAiCapabilities provides AiCapabilities.Full,
                    LocalReviewActionReadiness provides readiness, LocalToaster provides toaster) {
                    stop = guardedStop(h.page, listOf("tour"), h.page.taskFor("tour"), running = true)
                }
            }
            runCurrent(); Snapshot.sendApplyNotifications(); clock.sendFrame(1L); runCurrent()
            val actualStop = assertNotNull(stop)
            actualStop(); runCurrent()
            assertEquals(StopFixture.ERROR, toaster.message?.text)
            assertEquals(listOf(Cue.Error), cues)
            assertTrue(h.page.tasks.getValue("server-tour").running)
            assertNotNull(stop)

            ready = false // A retained control must check this before another Compose frame.
            actualStop(); runCurrent()
            assertEquals(listOf("server-tour"), h.cancellations)
            ready = true
            h.reject = false
            actualStop(); runCurrent()
            assertEquals(listOf("server-tour", "server-tour"), h.cancellations)
            assertEquals(listOf(Cue.Error), cues)
            h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
            assertEquals("cancelled", h.page.tasks.getValue("server-tour").status)
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
            if (previous != null) ControlFeedback.install(previous)
        }
    }

    @Test
    fun disposingTheMountedControlCancelsOnlyQueuedStopsAndKeepsAnAcceptedReceipt() = runTest {
        val cues = mutableListOf<Cue>()
        val previous = runCatching { ControlFeedback.installed() }.getOrNull()
        ControlFeedback.install(recordingFeedback(cues))
        try {
            for (accepted in listOf(false, true)) {
                val h = StopFixture(backgroundScope)
                h.page.load(); runCurrent()
                val toaster = Toaster()
                val reply = CompletableDeferred<com.octaviantocan.pulls.data.RpcResponse>()
                h.cancelReply = reply
                var stop: (() -> Unit)? = null
                var ready = true
                val readiness = ReviewActionReadiness({ ready })
                val clock = BroadcastFrameClock()
                val recomposer = Recomposer(coroutineContext + clock)
                val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
                val composition = Composition(NoNodes(), recomposer)
                try {
                    composition.setContent {
                        CompositionLocalProvider(LocalAiCapabilities provides AiCapabilities.Full,
                            LocalReviewActionReadiness provides readiness, LocalToaster provides toaster) {
                            stop = guardedStop(h.page, listOf("tour"), h.page.taskFor("tour"), running = true)
                        }
                    }
                    runCurrent(); Snapshot.sendApplyNotifications(); clock.sendFrame(1L); runCurrent()
                    assertNotNull(stop).invoke()
                    if (accepted) runCurrent()
                    ready = false
                    h.cache.pauseObservations()
                    composition.dispose()
                    reply.complete(h.failure()); runCurrent()
                    if (accepted) {
                        assertEquals(listOf("server-tour"), h.cancellations)
                        assertEquals(StopFixture.ERROR, toaster.message?.text)
                    } else {
                        assertEquals(emptyList(), h.cancellations)
                        assertNull(toaster.message)
                    }
                    assertTrue(h.page.tasks.getValue("server-tour").running)
                } finally {
                    composition.dispose(); recomposer.close(); runner.cancel(); h.page.detach()
                    reply.complete(h.failure())
                }
            }
            assertEquals(listOf(Cue.Error), cues)
        } finally {
            if (previous != null) ControlFeedback.install(previous)
        }
    }
}

private fun recordingFeedback(cues: MutableList<Cue>) = object : NativeControlFeedback {
    override fun play(cue: Cue, emphasis: Emphasis, direction: Direction?, input: Input?) { cues += cue }
    override fun hold() = Unit
    override fun gesture(role: NativeGestureFeedback) = Unit
}
