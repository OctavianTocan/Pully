package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.SettingsPreferences
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InboxAskTest {
    @Test
    fun theActualSavedTranscriptReferencesAndSuggestionsRemainReadableAcrossOrdinaryDismissal() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Original question", "Read owner/repo#7", "done", "saved-task")
        val first = h.open(); runCurrent()
        val turn = assertNotNull(h.ask.chat).turns.single()
        assertEquals("Original question", turn.question)
        assertEquals("Read owner/repo#7", turn.answer)
        assertEquals(listOf(ListedPull("owner", "repo", 7)), turn.pulls)
        assertEquals(listOf("What changed?"), h.ask.chat?.suggestions)
        val drawn = h.actions(first)
        drawn.input("A private draft")
        assertTrue(drawn.copy("The selected answer"))
        assertEquals(listOf("The selected answer"), h.native.copied)
        first.close()
        drawn.input("Old input"); drawn.ask("Old question"); drawn.clear(); drawn.read { error("Disposed navigation ran") }
        assertFalse(drawn.copy("Old answer"))
        assertEquals("A private draft", h.ask.input)
        val reopened = h.open(); runCurrent()
        assertEquals(turn, h.ask.chat?.turns?.single())
        assertEquals("A private draft", h.ask.input)
        assertEquals(0, h.count("ai-task-start"))
        assertEquals(1, h.native.observers.size)
        reopened.close(); h.close()
    }

    @Test
    fun sendingUsesTheActualLocaleHiddenRepositoriesAndModelEnvelopeOnceAndKeepsANextDraft() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.settings.updateLocale("it")
        h.settings.setReposShown(listOf("z/last", "a/first"), false)
        val mount = h.open(); runCurrent()
        val drawn = h.actions(mount)
        drawn.input("  What is waiting on me?  ")
        drawn.send(); drawn.send()
        assertEquals("What is waiting on me?", h.ask.pending)
        assertEquals(1_234L, h.ask.pendingStartedAt)
        runCurrent()
        val args = h.calls.single { it.first == "ai-task-start" }.second
        assertEquals("inbox", args.getValue("kind").jsonPrimitive.content)
        assertEquals("inbox", args.getValue("key").jsonPrimitive.content)
        assertEquals("it", args.getValue("locale").jsonPrimitive.content)
        assertEquals("What is waiting on me?", args.getValue("question").jsonPrimitive.content)
        assertEquals(listOf("a/first", "z/last"), args.getValue("skip").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("codex", args.getValue("model").jsonObject.getValue("engine").jsonPrimitive.content)
        assertEquals(1, h.events.size)
        assertEquals(setOf("where", "chars", "retry"), h.events.single().second.keys)
        assertEquals(22, h.events.single().second["chars"])
        assertNull(h.ask.pending)
        assertEquals("new-task", h.ask.task?.id)
        h.ask.sendOrQueue("A question for later")
        assertEquals("A question for later", h.ask.input)
        assertEquals(1, h.count("ai-task-start"))
        assertTrue(h.ask.busy)
        h.close()
    }

    @Test
    fun anImmediateStartFailureCannotEraseItsRestoredQuestionOrANewerDrawnEdit() = runTest {
        for (next in listOf(null, "A newer drawn edit")) {
            val immediate = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
            val h = InboxAskHarness(immediate)
            try {
                h.conversation = inboxConversation("Saved", "The saved answer", "done", "saved-task")
                val mount = h.open(); runCurrent()
                h.handler = { name, args -> if (name == "ai-task-start") {
                    next?.let { h.actions(mount).input(it) }
                    h.serverError("The question was rejected")
                } else h.default(name, args) }
                val drawn = h.actions(mount)
                drawn.input("My unsent question")
                drawn.send()
                // The real Repo failure and any restoration already completed before this Send returned.
                assertEquals(1, h.count("ai-task-start"))
                assertEquals("The question was rejected", h.ask.error)
                assertNull(h.ask.pending)
                assertEquals(next ?: "My unsent question", h.ask.input)
                assertEquals("The saved answer", h.ask.chat?.turns?.single()?.answer)
                assertFalse(h.ask.busy)
                assertEquals(listOf(Cue.Error), h.cues)
            } finally { h.close() }
        }
    }

    @Test
    fun anImmediateReusedTaskReceiptCannotEraseItsUnsentQuestionOrANewerDrawnEdit() = runTest {
        for (next in listOf(null, "A newer drawn edit")) {
            val immediate = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
            val h = InboxAskHarness(immediate)
            try {
                h.conversation = inboxConversation("Saved", "The saved answer", "done", "saved-task")
                val mount = h.open(); runCurrent()
                h.handler = { name, args -> if (name == "ai-task-start") {
                    next?.let { h.actions(mount).input(it) }
                    h.conversation = inboxConversation("An answer from another device", null, null, "existing-task")
                    h.tasks = listOf(inboxTask("existing-task", rev = 2))
                    JsonPrimitive("existing-task")
                } else h.default(name, args) }
                val drawn = h.actions(mount)
                drawn.input("My unsent question")
                drawn.send()
                // The accepted primitive ID and actual saved-conversation reconciliation are immediate too.
                assertEquals(1, h.count("ai-task-start"))
                assertEquals("An answer from another device", h.ask.chat?.turns?.single()?.question)
                assertEquals("existing-task", h.ask.task?.id)
                assertEquals(next ?: "My unsent question", h.ask.input)
                assertNull(h.ask.pending)
                assertTrue(h.ask.busy)
                assertEquals(0, h.count("ai-task-cancel"))
                assertTrue(h.cues.isEmpty())
            } finally { h.close() }
        }
    }

    @Test
    fun theServersExistingRunningAnswerKeepsTheNewQuestionAsAnUnsentDraft() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Already running", null, null, "existing-task")
        h.tasks = listOf(inboxTask("existing-task"))
        h.handler = { name, args -> if (name == "ai-task-start") JsonPrimitive("existing-task") else h.default(name, args) }
        // Home sends before its sheet loads: the server is the authority about an answer already running elsewhere.
        h.ask.sendOrQueue("My next question")
        val mount = h.open(); runCurrent()
        assertEquals("existing-task", h.ask.task?.id)
        assertEquals("My next question", h.ask.input)
        assertEquals("Already running", h.ask.chat?.turns?.single()?.question)
        assertEquals(1, h.count("ai-task-start"))
        h.actions(mount).send(); runCurrent()
        assertEquals(1, h.count("ai-task-start"))
        h.close()
    }

    @Test
    fun aRejectedRedoRestoresTheSavedAnswerAndQuestionAndCanRetryThroughTheRealRpc() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Original question", "Original answer", "done", "old-task")
        var reject = true
        h.handler = { name, args -> if (name == "ai-task-start" && reject) h.serverError("The server rejected this question") else h.default(name, args) }
        val mount = h.open(); runCurrent()
        val original = h.ask.chat!!.turns.single()
        h.actions(mount).retry(original); runCurrent()
        assertEquals(original, h.ask.chat?.turns?.single())
        assertEquals(original.question, h.ask.input)
        assertEquals("The server rejected this question", h.ask.error)
        assertFalse(h.ask.busy)
        assertEquals(listOf(Cue.Error), h.cues)
        reject = false
        h.actions(mount).retry(original); runCurrent()
        assertEquals(2, h.count("ai-task-start"))
        assertEquals(JsonPrimitive(true), h.calls.last { it.first == "ai-task-start" }.second["retry"])
        assertTrue(h.ask.busy)
        h.close()
    }

    @Test
    fun aRejectedRedoOrClearKeepsTheSavedAnswerEvenWhenTheFollowupReadAlsoFails() = runTest {
        for (operation in listOf("redo", "clear")) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Saved question", "Saved answer", "done", "saved-task")
            val mount = h.open(); runCurrent()
            val saved = assertNotNull(h.ask.chat)
            h.handler = { name, args -> when (name) {
                "ai-task-start", "inbox-chat-clear" -> h.serverError("The write was rejected")
                "inbox-chat" -> h.serverError("The conversation is temporarily unavailable")
                else -> h.default(name, args)
            } }
            val drawn = h.actions(mount)
            if (operation == "redo") drawn.retry(saved.turns.single()) else drawn.clear()
            runCurrent()
            assertEquals(saved, h.ask.chat)
            assertEquals("The write was rejected", h.ask.error)
            assertEquals("The conversation is temporarily unavailable", h.ask.observationError)
            assertFalse(h.ask.busy)
            assertTrue(h.ask.accepting)
            if (operation == "redo") assertEquals("Saved question", h.ask.input)
            h.close()
        }
    }

    @Test
    fun acceptedStartAndClearWritesOutliveSheetDismissalWhileTheirOldControlsAreInert() = runTest {
        for (operation in listOf("start", "clear")) for (pause in listOf("dismissal", "background", "verification")) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Saved", "Answer", "done", "old-task")
            val accepted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            h.handler = { name, args ->
                if (name == (if (operation == "start") "ai-task-start" else "inbox-chat-clear")) {
                    accepted.complete(Unit)
                    release.await()
                }
                h.default(name, args)
            }
            val mount = h.open(); runCurrent()
            val observed = h.calls.filter { it.first in INBOX_READ_METHODS }.groupingBy { it.first }.eachCount()
            val drawn = h.actions(mount)
            if (operation == "start") { drawn.input("Accepted question"); drawn.send() } else drawn.clear()
            runCurrent(); assertTrue(accepted.isCompleted)
            h.ask.sendOrQueue("Keep this next draft")
            when (pause) {
                "dismissal" -> mount.close()
                "background" -> h.native.stop()
                else -> h.observations.pause()
            }
            drawn.input("Retired input"); drawn.ask("Retired question"); drawn.clear(); drawn.stop(h.ask.task)
            assertFalse(drawn.copy("Retired answer"))
            release.complete(Unit); runCurrent()
            assertEquals(1, h.count(if (operation == "start") "ai-task-start" else "inbox-chat-clear"))
            assertEquals("Keep this next draft", h.ask.input)
            assertNull(h.ask.pending)
            if (operation == "start") assertEquals("new-task", h.ask.task?.id) else assertTrue(h.ask.chat?.turns?.isEmpty() == true)
            assertEquals(0, h.count("ai-task-cancel"))
            assertTrue(h.cues.isEmpty())
            assertEquals(observed, h.calls.filter { it.first in INBOX_READ_METHODS }.groupingBy { it.first }.eachCount(), "$operation read after $pause")
            when (pause) {
                "dismissal" -> h.open()
                "background" -> h.native.resume()
                else -> h.observations.resume()
            }
            runCurrent()
            assertEquals("Keep this next draft", h.ask.input)
            if (operation == "start") assertEquals("Accepted question", h.ask.chat?.turns?.single()?.question)
            else assertTrue(h.ask.chat?.turns?.isEmpty() == true)
            h.close()
        }
    }

    @Test
    fun aPausedAcceptedResponseDefersTheReadAndReusedTaskDraftUntilAuthoritativeResume() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Already running", null, null, "existing-task")
        h.tasks = listOf(inboxTask("existing-task", rev = 2))
        val accepted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.handler = { name, args -> if (name == "ai-task-start") {
            accepted.complete(Unit); release.await(); JsonPrimitive("existing-task")
        } else h.default(name, args) }
        h.ask.sendOrQueue("My unsent next question")
        val mount = h.open(); runCurrent()
        assertTrue(accepted.isCompleted)
        h.native.stop()
        release.complete(Unit); runCurrent()
        assertEquals("existing-task", h.ask.task?.id)
        assertNull(h.ask.pending)
        assertEquals("", h.ask.input)
        assertTrue(h.calls.none { it.first in INBOX_READ_METHODS })
        h.native.resume(); runCurrent()
        assertEquals("Already running", h.ask.chat?.turns?.single()?.question)
        assertEquals("My unsent next question", h.ask.input)
        assertEquals("existing-task", h.ask.task?.id)
        assertEquals(1, h.count("ai-task-start"))
        assertEquals(0, h.count("ai-task-cancel"))
        h.actions(mount).send(); runCurrent()
        assertEquals(1, h.count("ai-task-start"))
        h.close()
    }

    @Test
    fun queuedControlsRecheckTheLatestRootAndDrawnReadinessBeforeTheirWriteBegins() = runTest {
        for (operation in listOf("start", "clear", "stop")) for (retirement in listOf("root-policy", "reader-policy", "dismissal", "verification")) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Saved", if (operation == "stop") null else "Answer", if (operation == "stop") null else "done", "old-task")
            if (operation == "stop") h.tasks = listOf(inboxTask("old-task"))
            val mount = h.open(); runCurrent()
            val drawn = h.actions(mount)
            when (operation) {
                "start" -> { drawn.input("A question not accepted yet"); drawn.send() }
                "clear" -> drawn.clear()
                else -> drawn.stop(h.ask.task)
            }
            when (retirement) {
                "root-policy" -> h.policy = AiCapabilities.ReadOnly
                "reader-policy" -> h.readerPolicy = AiCapabilities.ReadOnly
                "verification" -> h.observations.pause()
                else -> mount.close()
            }
            runCurrent()
            assertEquals(0, h.count("ai-task-start"), "$operation after $retirement")
            assertEquals(0, h.count("inbox-chat-clear"), "$operation after $retirement")
            assertEquals(0, h.count("ai-task-cancel"), "$operation after $retirement")
            assertEquals("Saved", h.ask.chat?.turns?.single()?.question)
            if (operation == "start") assertEquals("A question not accepted yet", h.ask.input)
            assertNull(h.ask.pending)
            assertTrue(h.cues.isEmpty())
            h.close()
        }
    }

    @Test
    fun explicitStopTargetsOnlyTheDrawnRunningTaskAndWaitsForTheServersOutcome() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        val mount = h.open(); runCurrent()
        val drawn = h.actions(mount)
        val running = assertNotNull(h.ask.task)
        drawn.stop(running.copy(id = "a-different-task")); runCurrent()
        assertEquals(0, h.count("ai-task-cancel"))
        drawn.stop(running); runCurrent()
        assertEquals("accepted-task", h.calls.single { it.first == "ai-task-cancel" }.second.getValue("id").jsonPrimitive.content)
        assertEquals("running", h.ask.task?.status)
        assertTrue(h.ask.busy)
        h.conversation = inboxConversation("Accepted", null, "stopped", "accepted-task")
        h.snapshots.send(inboxTask("accepted-task", "cancelled", 3)); runCurrent()
        assertEquals("cancelled", h.ask.task?.status)
        assertEquals("stopped", h.ask.chat?.turns?.single()?.outcome)
        assertFalse(h.ask.busy)
        drawn.stop(running); runCurrent()
        assertEquals(1, h.count("ai-task-cancel"))
        assertTrue(h.cues.isEmpty())
        h.close()
    }

    @Test
    fun explicitReadOnlyReconnectReloadsTheSavedConversationWithoutStartingOrCancellingWork() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.handler = { name, args -> if (name == "ai-task-wait") h.serverError("Connection interrupted") else h.default(name, args) }
        val mount = h.open(); runCurrent()
        assertEquals("Connection interrupted", h.ask.observationError)
        h.conversation = inboxConversation("Accepted", "The authoritative saved answer", "done", "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", "done", 3))
        h.policy = AiCapabilities.ReadOnly; h.readerPolicy = AiCapabilities.ReadOnly
        val drawn = h.actions(mount)
        assertFalse(drawn.canAsk)
        assertTrue(drawn.current)
        drawn.reconnect(); runCurrent()
        assertEquals(2, h.count("ai-task-list-all"))
        assertEquals("The authoritative saved answer", h.ask.chat?.turns?.single()?.answer)
        assertEquals("done", h.ask.task?.status)
        assertNull(h.ask.observationError)
        assertFalse(h.ask.busy)
        assertEquals(0, h.count("ai-task-start"))
        assertEquals(0, h.count("ai-task-cancel"))
        assertTrue(h.cues.isEmpty())
        h.close()
    }

    @Test
    fun initialVerificationAndNativeForegroundMustBothPermitObservation() = runTest {
        val h = InboxAskHarness(backgroundScope, initialReady = false)
        h.conversation = inboxConversation("Verified", "Saved answer", "done", "saved-task")
        val mount = h.open(); runCurrent()
        h.ask.sendOrQueue("Must not start before verification")
        assertTrue(h.calls.isEmpty())
        assertFalse(h.actions(mount).current)
        assertNull(h.ask.chat)
        h.native.stop()
        h.observations.resume(); runCurrent()
        assertTrue(h.calls.isEmpty())
        h.native.resume(); runCurrent()
        assertEquals("Verified", h.ask.chat?.turns?.single()?.question)
        assertTrue(h.actions(mount).canAsk)
        val reads = h.calls.size
        h.native.stop()
        h.observations.pause(); h.observations.resume(); runCurrent()
        assertEquals(reads, h.calls.size)
        assertFalse(h.actions(mount).current)
        assertEquals(1, h.native.observers.size)
        h.close()
    }

    @Test
    fun verificationPauseCancelsEveryActualReadWithoutAFrameAndRejectsNoncancellableReplies() = runTest {
        for (operation in INBOX_READ_METHODS) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
            h.tasks = listOf(inboxTask("accepted-task", rev = 2))
            val accepted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var first = true
            h.handler = { name, args -> if (name == operation && first) {
                first = false
                val late: JsonElement = when (operation) {
                    "inbox-chat" -> h.conversation
                    "ai-task-list-all" -> JsonArray(h.tasks)
                    else -> inboxTask("accepted-task", "done", 3)
                }
                accepted.complete(Unit)
                withContext(NonCancellable) { release.await() }
                late
            } else h.default(name, args) }
            try {
                val mount = h.open(); runCurrent()
                assertTrue(accepted.isCompleted, operation)
                h.observations.pause()
                assertFalse(h.actions(mount).current)
                val reads = h.calls.size
                advanceTimeBy(30_000); runCurrent()
                assertEquals(reads, h.calls.size, operation)
                h.conversation = inboxConversation("Accepted", "Current after verification", "done", "accepted-task")
                h.tasks = listOf(inboxTask("accepted-task", "done", 4))
                h.observations.resume(); runCurrent()
                assertEquals("Current after verification", h.ask.chat?.turns?.single()?.answer, operation)
                release.complete(Unit); runCurrent()
                assertEquals("Current after verification", h.ask.chat?.turns?.single()?.answer, operation)
                assertFalse(h.ask.busy, operation)
                assertNull(h.ask.error)
                assertNull(h.ask.observationError)
                assertTrue(h.cues.isEmpty())
                assertEquals(0, h.count("ai-task-cancel"))
            } finally {
                release.complete(Unit)
                h.close()
            }
        }
    }

    @Test
    fun sourceRetirementSynchronouslyClearsPrivateStateAndRejectsANoncancellableLateStart() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Private saved question", "Private answer", "done", "old-task")
        val release = CompletableDeferred<Unit>()
        h.handler = { name, args ->
            if (name == "ai-task-start") withContext(NonCancellable) { release.await() }
            h.default(name, args)
        }
        try {
            val mount = h.open(); runCurrent()
            h.actions(mount).input("Private question")
            h.actions(mount).send(); runCurrent()
            val retained = h.actions(mount)
            h.ask.invalidate() // Before replacement credentials publish, all old private references are gone.
            assertNull(h.ask.chat); assertNull(h.ask.task); assertNull(h.ask.pending)
            assertNull(h.ask.error); assertNull(h.ask.observationError); assertEquals("", h.ask.input)
            h.generation++
            release.complete(Unit); runCurrent()
            retained.input("Old draft"); retained.ask("Old question"); retained.clear(); retained.stop(h.ask.task)
            assertFalse(retained.copy("Old answer"))
            h.ask.sendOrQueue("Old root callback")
            assertNull(h.ask.chat); assertNull(h.ask.task); assertEquals("", h.ask.input)
            assertEquals(1, h.count("ai-task-start"))
            assertEquals(0, h.count("ai-task-cancel"))
            assertTrue(h.cues.isEmpty())
        } finally {
            release.complete(Unit)
            h.close()
        }
    }

    @Test
    fun revokedReadinessAndANewConversationRetireDrawnTypingCopyRetryClearAndNavigation() = runTest {
        for (retirement in listOf("root-policy", "reader-policy", "new-chat", "source")) {
            val h = InboxAskHarness(backgroundScope)
            h.conversation = inboxConversation("Saved", "Answer", "done", "old-task")
            val mount = h.open(); runCurrent()
            val drawn = h.actions(mount)
            val turn = h.ask.chat!!.turns.single()
            when (retirement) {
                "root-policy" -> h.policy = AiCapabilities.None
                "reader-policy" -> h.readerPolicy = AiCapabilities.None
                "new-chat" -> { drawn.clear(); runCurrent(); h.actions(mount).input("New conversation draft") }
                else -> h.generation++
            }
            val writes = h.count("ai-task-start") + h.count("inbox-chat-clear")
            drawn.input("Retired draft"); drawn.send(); drawn.ask("Retired question"); drawn.retry(turn); drawn.clear()
            drawn.read { error("Retired navigation ran") }
            assertFalse(drawn.copy("Private old answer"))
            assertEquals(writes, h.count("ai-task-start") + h.count("inbox-chat-clear"))
            if (retirement == "new-chat") assertEquals("New conversation draft", h.ask.input)
            h.close()
        }
    }

    @Test
    fun aLostOrUnknownTaskObservationRemainsRunningAndResumesOnlyFromMatchingAuthoritativeReplies() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        var reads = 0
        h.handler = { name, args -> if (name == "ai-task-wait") when (reads++) {
            0 -> h.serverError("The connection was interrupted")
            1 -> inboxTask("accepted-task", "unknown", 3)
            2 -> inboxTask("a-different-task", "done", 3)
            3 -> JsonObject(inboxTask("accepted-task", "done", 3).filterKeys { it != "status" })
            4 -> JsonObject(inboxTask("accepted-task", "done", 3).filterKeys { it != "rev" })
            5 -> inboxTask("accepted-task", "done", 1)
            else -> {
                h.conversation = inboxConversation("Accepted", "The saved answer", "done", "accepted-task")
                inboxTask("accepted-task", "done", 3)
            }
        } else h.default(name, args) }
        val mount = h.open(); runCurrent()
        assertEquals("running", h.ask.task?.status)
        assertEquals("The connection was interrupted", h.ask.observationError)
        assertTrue(h.ask.busy)
        assertTrue(h.cues.isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertEquals("running", h.ask.task?.status)
        assertNotNull(h.ask.observationError)
        advanceTimeBy(2_000); runCurrent()
        assertEquals("running", h.ask.task?.status)
        advanceTimeBy(4_000); runCurrent()
        assertEquals("running", h.ask.task?.status)
        advanceTimeBy(8_000); runCurrent()
        assertEquals("running", h.ask.task?.status)
        advanceTimeBy(16_000); runCurrent()
        assertEquals(2, h.ask.task?.rev)
        assertEquals("running", h.ask.task?.status)
        advanceTimeBy(100); runCurrent()
        assertEquals("done", h.ask.task?.status)
        assertEquals("The saved answer", h.ask.chat?.turns?.single()?.answer)
        assertNull(h.ask.observationError)
        assertFalse(h.ask.busy)
        assertEquals(listOf(Cue.Ready), h.cues)
        assertEquals(0, h.count("ai-task-cancel"))
        h.actions(mount).stop(h.ask.task); runCurrent()
        assertEquals(0, h.count("ai-task-cancel"))
        h.close()
    }

    @Test
    fun backgroundPausesOnlyObservationAndForegroundReconcilesSavedTerminalResultsWithoutResurrection() = runTest {
        val h = InboxAskHarness(backgroundScope)
        h.conversation = inboxConversation("Accepted", null, null, "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", rev = 2))
        h.open(); runCurrent()
        val observed = h.count("ai-task-wait")
        h.native.stop()
        assertEquals("running", h.ask.task?.status)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(observed, h.count("ai-task-wait"))
        assertEquals(0, h.count("ai-task-cancel"))
        h.conversation = inboxConversation("Accepted", "Finished while away", "done", "accepted-task")
        h.tasks = listOf(inboxTask("accepted-task", "done", 4))
        h.native.resume(); runCurrent()
        assertEquals("done", h.ask.task?.status)
        assertEquals("Finished while away", h.ask.chat?.turns?.single()?.answer)
        h.native.stop(); h.tasks = listOf(inboxTask("accepted-task", "running", 99))
        h.native.resume(); runCurrent()
        assertEquals("done", h.ask.task?.status)
        assertFalse(h.ask.busy)
        assertTrue(h.cues.isEmpty())
        h.close()
    }
}

internal class InboxAskHarness(scope: CoroutineScope, initialReady: Boolean = true) {
    var generation = 0L
    var policy = AiCapabilities.Full
    var readerPolicy = AiCapabilities.Full
    val calls = mutableListOf<Pair<String, JsonObject>>()
    val cues = mutableListOf<Cue>()
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    var conversation: JsonObject = buildJsonObject { put("turns", JsonArray(emptyList())); put("suggestions", JsonArray(emptyList())) }
    var tasks = emptyList<JsonObject>()
    val snapshots = Channel<JsonObject>(Channel.UNLIMITED)
    var handler: suspend (String, JsonObject) -> JsonElement = ::default
    var rejectionStatus = 200
    val native = InboxAskNative()
    val observations = HomeObservations(initialReady)
    val settings = AppSettings(InboxAskPreferences(), object : SettingsEffects {
        override fun touched(name: String) {}
        override fun settingsChanged(key: String, value: String) {}
        override fun usageStatsEnabled(enabled: Boolean) {}
    }) { "en" }
    private val repo = Repo(
        configuration = { RepoConfiguration("https://review.example", engine = "codex", generation = generation) },
        transport = RpcTransport { request ->
            val call = Json.parseToJsonElement(request.body).jsonObject
            val name = call.getValue("name").jsonPrimitive.content
            val args = call.getValue("args").jsonObject
            calls += name to args
            val envelope = try { val result = handler(name, args); buildJsonObject { put("result", result) } }
            catch (rejected: InboxAskRejection) { buildJsonObject { put("error", rejected.message) } }
            RpcResponse(if ("error" in envelope) rejectionStatus else 200, envelope.toString(), "application/json")
        },
    )
    val ask = InboxAsk(repo, scope, settings, native, object : InboxAskEffects {
        override fun nowMillis() = 1_234L
        override fun track(name: String, vararg props: Pair<String, Any?>) { events += name to props.toMap() }
        override fun feedback(cue: Cue, emphasis: Emphasis) { cues += cue }
    }, current = { true }, capabilities = { policy }, observations = observations)
    fun open() = InboxAskMount(ask) { readerPolicy }.also { it.attach() }
    fun actions(mount: InboxAskMount) = InboxAskActions(ask, mount)
    fun count(name: String) = calls.count { it.first == name }
    fun close() { ask.invalidate(); observations.close(); snapshots.close() }
    fun serverError(message: String): Nothing = throw InboxAskRejection(message)
    suspend fun default(name: String, args: JsonObject): JsonElement = when (name) {
        "inbox-chat" -> conversation
        "ai-task-list-all" -> JsonArray(tasks)
        "ai-task-wait" -> snapshots.receive()
        "ai-task-start" -> {
            val question = args.getValue("question").jsonPrimitive.content
            conversation = inboxConversation(question, null, null, "new-task")
            tasks = listOf(inboxTask("new-task"))
            JsonPrimitive("new-task")
        }
        "inbox-chat-clear" -> { conversation = buildJsonObject { put("turns", JsonArray(emptyList())) }; tasks = emptyList(); JsonNull }
        else -> JsonNull
    }
}

internal class InboxAskNative : NativeHomeActions {
    val copied = mutableListOf<String>()
    val observers = mutableSetOf<HomeLifecycleObserver>()
    private var foreground = true
    override fun copyText(text: String) { copied += text }
    override fun openOnGithub(url: String) = error("This test opens references through its drawn callback")
    override fun openInBrowser(url: String) = error("This test uses the real guarded UriHandler seam")
    override fun observeLifecycle(observer: HomeLifecycleObserver): HomeLifecycleSubscription {
        observers += observer
        if (foreground) observer.resumed() else observer.stopped()
        return object : HomeLifecycleSubscription { override fun cancel() { observers -= observer } }
    }
    override fun observeBack(rightEdge: Boolean, observer: HomeBackObserver): HomeLifecycleSubscription = error("Only the root installs Back")
    fun stop() { foreground = false; observers.toList().forEach { it.stopped() } }
    fun resume() { foreground = true; observers.toList().forEach { it.resumed() } }
}

private class InboxAskPreferences : SettingsPreferences, SettingsPreferences.Editor {
    private val values = mutableMapOf<String, Any?>()
    override fun getString(name: String, default: String?) = values[name] as? String ?: default
    override fun getBoolean(name: String, default: Boolean) = values[name] as? Boolean ?: default
    override fun getFloat(name: String, default: Float) = values[name] as? Float ?: default
    @Suppress("UNCHECKED_CAST") override fun getStringSet(name: String, default: Set<String>?) = values[name] as? Set<String> ?: default
    override fun contains(name: String) = name in values
    override fun edit(): SettingsPreferences.Editor = this
    override fun putString(name: String, value: String?) = apply { values[name] = value }
    override fun putBoolean(name: String, value: Boolean) = apply { values[name] = value }
    override fun putFloat(name: String, value: Float) = apply { values[name] = value }
    override fun putStringSet(name: String, value: Set<String>) = apply { values[name] = value }
    override fun apply() {}
}

private class InboxAskRejection(message: String) : Exception(message)
private val INBOX_READ_METHODS = setOf("inbox-chat", "ai-task-list-all", "ai-task-wait")
internal fun inboxTask(id: String, status: String = "running", rev: Int = 0) = buildJsonObject {
    put("id", id); put("kind", "inbox"); put("key", "inbox"); put("status", status); put("rev", rev)
}
internal fun inboxConversation(question: String, answer: String?, outcome: String?, id: String) = buildJsonObject {
    put("turns", JsonArray(listOf(buildJsonObject {
        put("question", question); answer?.let { put("answer", it) }; outcome?.let { put("outcome", it) }; put("taskId", id)
        put("pulls", JsonArray(listOf(buildJsonObject { put("owner", "owner"); put("repo", "repo"); put("number", 7) })))
    })))
    put("suggestions", JsonArray(listOf(JsonPrimitive("What changed?"))))
}
