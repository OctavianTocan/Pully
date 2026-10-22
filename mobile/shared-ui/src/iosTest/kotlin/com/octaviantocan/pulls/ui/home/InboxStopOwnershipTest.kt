package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InboxStopOwnershipTest {
    @Test
    fun theActualMountedStopPublishesAReal503AndItsExistingErrorFeedbackThenRetries() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.rejectionStatus = 503
        var reject = true
        h.handler = { name, args ->
            if (name == "ai-task-cancel" && reject) h.serverError("Sample Inbox stop unavailable.") else h.default(name, args)
        }
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(InboxStopNoNodes(), recomposer)
        lateinit var actions: InboxAskActions
        var drawnError: String? = null
        var time = 0L
        suspend fun frame() { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(++time); runCurrent() }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalAiCapabilities provides AiCapabilities.Full) {
                    val mount = rememberInboxAskMount(h.ask)
                    actions = rememberInboxAskActions(h.ask, mount)
                    drawnError = h.ask.error
                }
            }
            runCurrent(); frame(); frame()
            val actualStop = actions
            actualStop.stop(assertNotNull(h.ask.task)); runCurrent(); frame()
            assertEquals("Sample Inbox stop unavailable.", drawnError)
            assertEquals(listOf(Cue.Error), h.cues)
            assertTrue(h.ask.busy)
            assertEquals("running", h.ask.task?.status)
            h.observations.pause() // A retained control must obey this before a new Compose frame.
            actualStop.stop(h.ask.task); runCurrent()
            assertEquals(1, h.count("ai-task-cancel"))
            h.observations.resume(); runCurrent(); frame()
            reject = false
            actions.stop(h.ask.task); runCurrent(); frame()
            assertNull(drawnError)
            assertEquals(2, h.count("ai-task-cancel"))
            assertTrue(h.ask.busy)
            h.conversation = inboxConversation("Accepted", null, "stopped", "accepted-task")
            h.snapshots.send(inboxTask("accepted-task", "cancelled", 3)); runCurrent(); frame()
            assertEquals("cancelled", h.ask.task?.status)
            assertEquals(listOf(Cue.Error), h.cues)
            assertEquals(0, h.count("ai-task-start"))
        } finally {
            composition.dispose(); recomposer.close(); runner.cancel(); h.close()
        }
    }
}

private class InboxStopNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
