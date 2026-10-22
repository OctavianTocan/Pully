package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.sound.Cue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InboxStopOrderingTest {
    @Test
    fun anOlderRejectionCannotReportAfterANewerConfirmedStopBeforeTaskObservation() = runTest {
        newerStop(observeTerminal = false)
    }

    @Test
    fun anOlderRejectionCannotReportAfterANewerConfirmedStopAndItsAuthoritativeOutcome() = runTest {
        newerStop(observeTerminal = true)
    }

    private suspend fun TestScope.newerStop(observeTerminal: Boolean) {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.rejectionStatus = 503
        val first = CompletableDeferred<Unit>()
        var stops = 0
        h.handler = { name, args ->
            if (name == "ai-task-cancel" && ++stops == 1) {
                withContext(NonCancellable) { first.await() }
                h.serverError(ERROR)
            } else h.default(name, args)
        }
        val mount = h.open(); runCurrent()
        val drawn = h.actions(mount)
        val task = assertNotNull(h.ask.task)
        drawn.stop(task); runCurrent()
        assertEquals(1, h.count("ai-task-cancel"))
        drawn.stop(task); runCurrent()
        assertEquals(2, h.count("ai-task-cancel"))
        assertNull(h.ask.error)
        assertTrue(h.cues.isEmpty())
        if (observeTerminal) {
            h.conversation = inboxConversation("Accepted", null, "stopped", "accepted-task")
            h.snapshots.send(inboxTask("accepted-task", "cancelled", 3)); runCurrent()
            assertEquals("cancelled", h.ask.task?.status)
            assertFalse(h.ask.busy)
        } else assertTrue(h.ask.busy)

        first.complete(Unit); runCurrent()
        assertNull(h.ask.error)
        assertTrue(h.cues.isEmpty())
        assertEquals(if (observeTerminal) "cancelled" else "running", h.ask.task?.status)
        assertEquals(2, h.count("ai-task-cancel"))
        assertEquals(0, h.count("ai-task-start"))
        h.close()
    }

    @Test
    fun anAuthoritativeTerminalTaskSilencesTheHeldFailureWithoutANewerStop() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.rejectionStatus = 503
        val first = CompletableDeferred<Unit>()
        h.handler = { name, args ->
            if (name == "ai-task-cancel") {
                withContext(NonCancellable) { first.await() }
                h.serverError(ERROR)
            } else h.default(name, args)
        }
        val mount = h.open(); runCurrent()
        h.actions(mount).stop(h.ask.task); runCurrent()
        assertEquals(1, h.count("ai-task-cancel"))
        h.conversation = inboxConversation("Accepted", null, "stopped", "accepted-task")
        h.snapshots.send(inboxTask("accepted-task", "cancelled", 3)); runCurrent()
        assertEquals("cancelled", h.ask.task?.status)
        first.complete(Unit); runCurrent()
        assertNull(h.ask.error)
        assertTrue(h.cues.isEmpty())
        assertEquals(1, h.count("ai-task-cancel"))
        assertEquals(0, h.count("ai-task-start"))
        h.close()
    }

    @Test
    fun aQueuedStopWhichLosesItsControlCannotSuppressTheAcceptedRejection() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.rejectionStatus = 503
        val first = CompletableDeferred<Unit>()
        h.handler = { name, args ->
            if (name == "ai-task-cancel") {
                withContext(NonCancellable) { first.await() }
                h.serverError(ERROR)
            } else h.default(name, args)
        }
        val mount = h.open(); runCurrent()
        val drawn = h.actions(mount)
        drawn.stop(h.ask.task); runCurrent()
        drawn.stop(h.ask.task)
        drawn.close()
        h.observations.pause(); h.native.stop(); mount.close()
        runCurrent()
        assertEquals(1, h.count("ai-task-cancel"))
        first.complete(Unit); runCurrent()
        assertEquals(ERROR, h.ask.error)
        assertTrue(h.ask.busy)
        assertTrue(h.cues.isEmpty())
        h.native.resume(); h.open(); h.observations.resume(); runCurrent()
        assertEquals(ERROR, h.ask.error)
        assertEquals(emptyList<Cue>(), h.cues)
        assertEquals(1, h.count("ai-task-cancel"))
        h.close()
    }

    private companion object { const val ERROR = "Sample first stop unavailable." }
}
