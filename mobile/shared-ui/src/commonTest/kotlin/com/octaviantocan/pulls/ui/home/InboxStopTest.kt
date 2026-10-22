package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
class InboxStopTest {
    @Test
    fun aReal503KeepsTheActualRunningAnswerReportsErrorFeedbackAndAllowsAConfirmedRetry() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.rejectionStatus = 503
        var reject = true
        h.handler = { name, args ->
            if (name == "ai-task-cancel" && reject) h.serverError(ERROR) else h.default(name, args)
        }
        val mount = h.open(); runCurrent()
        val drawn = h.actions(mount)
        val running = assertNotNull(h.ask.task)
        drawn.stop(running); runCurrent()
        assertEquals(ERROR, h.ask.error)
        assertEquals(listOf(Cue.Error), h.cues)
        assertEquals("running", h.ask.task?.status)
        assertTrue(h.ask.busy)
        assertEquals(1, h.count("ai-task-cancel"))
        assertEquals(0, h.count("ai-task-start"))

        reject = false
        drawn.stop(running); runCurrent()
        assertNull(h.ask.error)
        assertEquals("running", h.ask.task?.status) // A successful receipt is not an invented task outcome.
        assertEquals(2, h.count("ai-task-cancel"))
        h.conversation = inboxConversation("Accepted", null, "stopped", "accepted-task")
        h.snapshots.send(inboxTask("accepted-task", "cancelled", 3)); runCurrent()
        assertEquals("cancelled", h.ask.task?.status)
        assertFalse(h.ask.busy)
        assertEquals(listOf(Cue.Error), h.cues)
        drawn.stop(running); runCurrent()
        assertEquals(2, h.count("ai-task-cancel"))
        h.close()
    }

    @Test
    fun anAcceptedRejectionSurvivesSameSourcePauseAndDismissalWithoutReplayingFeedback() = runTest {
        for (dismiss in listOf(false, true)) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
            h.tasks = listOf(inboxTask("accepted-task", rev = 2))
            h.rejectionStatus = 503
            val reply = CompletableDeferred<Unit>()
            h.handler = { name, args ->
                if (name == "ai-task-cancel") {
                    withContext(NonCancellable) { reply.await() }
                    h.serverError(ERROR)
                } else h.default(name, args)
            }
            val mount = h.open(); runCurrent()
            val drawn = h.actions(mount)
            drawn.stop(h.ask.task); runCurrent()
            assertEquals(1, h.count("ai-task-cancel"))
            h.observations.pause(); h.native.stop(); drawn.close()
            if (dismiss) mount.close()
            reply.complete(Unit); runCurrent()
            assertEquals(ERROR, h.ask.error)
            assertEquals("running", h.ask.task?.status)
            assertTrue(h.cues.isEmpty())
            h.native.resume()
            if (dismiss) h.open()
            h.observations.resume(); runCurrent()
            assertEquals(ERROR, h.ask.error)
            assertTrue(h.ask.busy)
            assertTrue(h.cues.isEmpty())
            assertEquals(1, h.count("ai-task-cancel"))
            assertEquals(0, h.count("ai-task-start"))
            h.close()
        }
    }

    @Test
    fun aLateRejectionCannotPopulateARetiredSourceOrANewerAcceptedConversation() = runTest {
        for (retirement in listOf("source", "controller", "new-question")) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
            h.tasks = listOf(inboxTask("accepted-task", rev = 2))
            h.rejectionStatus = 503
            val reply = CompletableDeferred<Unit>()
            h.handler = { name, args ->
                if (name == "ai-task-cancel") {
                    withContext(NonCancellable) { reply.await() }
                    h.serverError(ERROR)
                } else h.default(name, args)
            }
            val mount = h.open(); runCurrent()
            h.actions(mount).stop(h.ask.task); runCurrent()
            assertEquals(1, h.count("ai-task-cancel"))
            when (retirement) {
                "source" -> h.generation++
                "controller" -> h.ask.invalidate()
                else -> {
                    h.conversation = inboxConversation("Accepted", null, "stopped", "accepted-task")
                    h.snapshots.send(inboxTask("accepted-task", "cancelled", 3)); runCurrent()
                    h.actions(mount).ask("The next accepted question"); runCurrent()
                    assertEquals("new-task", h.ask.task?.id)
                }
            }
            reply.complete(Unit); runCurrent()
            assertNull(h.ask.error, retirement)
            assertTrue(h.cues.isEmpty(), retirement)
            assertEquals(1, h.count("ai-task-cancel"))
            assertEquals(if (retirement == "new-question") 1 else 0, h.count("ai-task-start"))
            if (retirement == "new-question") assertEquals("The next accepted question", h.ask.chat?.turns?.single()?.question)
            h.close()
        }
    }

    @Test
    fun aQueuedStopRechecksTheActualDrawnControlAndLatestSourceReadinessBeforeDispatch() = runTest {
        for (retirement in listOf("drawn-control", "sheet", "foreground", "verification", "policy", "source")) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
            h.tasks = listOf(inboxTask("accepted-task", rev = 2))
            h.rejectionStatus = 503
            h.handler = { name, args -> if (name == "ai-task-cancel") h.serverError(ERROR) else h.default(name, args) }
            val mount = h.open(); runCurrent()
            val drawn = h.actions(mount)
            drawn.stop(h.ask.task)
            when (retirement) {
                "drawn-control" -> drawn.close()
                "sheet" -> mount.close()
                "foreground" -> h.native.stop()
                "verification" -> h.observations.pause()
                "policy" -> h.readerPolicy = AiCapabilities.ReadOnly
                else -> h.generation++
            }
            runCurrent()
            assertEquals(0, h.count("ai-task-cancel"), retirement)
            assertEquals(0, h.count("ai-task-start"), retirement)
            assertNull(h.ask.error)
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }

    private companion object { const val ERROR = "Sample Inbox stop unavailable." }
}
