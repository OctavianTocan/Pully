package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.BriefBlock
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.data.RpcConnectionException
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.ChatActions
import com.octaviantocan.pulls.ui.ai.chatUi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewObservationTest {
    @Test
    fun acceptedHistoryCloseAndRestoreReconcileOnceAfterVerifiedResumeOrRetireWhileStillPaused() = runTest {
        for (failed in listOf(false, true)) for (retirement in listOf("none", "source", "policy", "head")) {
            val h = ObservationHarness(backgroundScope)
            h.page.load(); runCurrent()
            val response = CompletableDeferred<Unit>()
            val gate = com.octaviantocan.pulls.ui.home.HomeObservations()
            val installed = mutableStateOf(AiCapabilities.Full)
            h.answer = { name, args -> when (name) {
                "chat-restore" -> {
                    assertEquals("history-accepted", args.getValue("id").jsonPrimitive.content)
                    response.await()
                    if (failed) h.fixture.serverError("The saved chat could not be restored")
                    JsonObject(emptyMap())
                }
                "review-state" -> completedResults()
                else -> h.default(name, args)
            } }
            var opened = 0
            val buttons = ChatActions(h.page, h.page.captureView(), ReviewActionReadiness({ gate.ready }, { installed.value })) { AiCapabilities.Full }
            assertTrue(buttons.restore("history-accepted") { opened++ })
            val accepted = assertNotNull(h.page.chatChange)
            buttons.close() // The real ChatHistorySheet closes immediately, before this queued write dispatches.
            runCurrent()
            assertEquals(1, h.calls.count { it.name == "chat-restore" })
            gate.pause(); h.cache.pauseObservations(); Snapshot.sendApplyNotifications(); runCurrent()
            val reads = h.calls.count { it.name == "review-state" }
            response.complete(Unit); runCurrent()
            assertEquals(0, opened)
            assertEquals(reads, h.calls.count { it.name == "review-state" })
            when (retirement) {
                "source" -> { h.fixture.generation++; h.cache.clear() }
                "policy" -> installed.value = AiCapabilities.None
                "head" -> h.fixture.head = "head-b"
            }
            Snapshot.sendApplyNotifications(); runCurrent()
            if (retirement in listOf("source", "policy")) {
                assertTrue(accepted.isCompleted) // Retirement wakes/cancels even while the live gate stays false.
                assertEquals(0, opened)
                assertEquals(reads, h.calls.count { it.name == "review-state" })
            } else {
                gate.resume(); Snapshot.sendApplyNotifications(); h.cache.resumeObservations(); runCurrent()
                assertEquals(if (retirement == "head") 0 else 1, opened)
                assertEquals("Saved answer", h.page.saved!!.chat.last().text)
                if (failed && retirement == "none") assertEquals("The saved chat could not be restored", chatUi(h.page).chatError)
                h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
                assertEquals(if (retirement == "head") 0 else 1, opened)
            }
            assertEquals(1, h.calls.count { it.name == "chat-restore" })
            assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
            gate.close(); h.page.detach()
        }
    }

    @Test
    fun scenePauseCancelsOnlyLocalWaitsAndResumeFollowsTheAuthoritativeRevisionOnce() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        val originalView = h.page.captureView()
        val buttons = ChatActions(h.page, originalView) { AiCapabilities.Full }
        buttons.changeInput("Keep the draft")
        var cancelledWaits = 0
        var revision = 0
        h.answer = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-list" -> JsonArray(listOf(task(revision, "running")))
            "ai-task-wait" -> try { awaitCancellation() } finally { cancelledWaits++ }
            else -> h.default(name, args)
        } }
        h.page.startTask("tour"); runCurrent()
        assertEquals(listOf("0"), h.waits.map { it.args.getValue("after").jsonPrimitive.content })
        h.cache.pauseObservations(); h.cache.pauseObservations(); runCurrent()
        assertEquals(1, cancelledWaits)
        assertEquals(originalView, h.page.captureView())
        assertEquals("Keep the draft", chatUi(h.page).chatInput)
        assertTrue(h.page.tasks.getValue("task-1").running)
        val callsWhilePaused = h.calls.size
        testScheduler.advanceTimeBy(120_000); runCurrent()
        h.page.reloadSaved(); h.page.reloadReviews(); h.cache.warm(h.page.repo, h.page.pull!!); runCurrent()
        assertEquals(callsWhilePaused, h.calls.size)
        revision = 8
        h.cache.resumeObservations(); h.cache.resumeObservations(); runCurrent()
        assertEquals(listOf("0", "8"), h.waits.map { it.args.getValue("after").jsonPrimitive.content })
        assertEquals(2, h.calls.count { it.name == "github-pr" })
        assertEquals("true", h.calls.last { it.name == "github-pr" }.args["fresh"]?.jsonPrimitive?.content)
        assertEquals("Keep the draft", chatUi(h.page).chatInput)
        assertEquals(1, h.calls.count { it.name == "ai-task-start" })
        assertFalse(h.calls.any { it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun completedServerOwnedAnalysisPassesLoadTheirActualResultsBeforeOneCompletion() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        var finished = false
        h.answer = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> awaitCancellation()
            "ai-task-list" -> if (finished) JsonArray(listOf(
                task(5, "done", "analyze"), task(3, "done", "tour", "tour-1"), task(4, "done", "brief", "brief-1"),
            )) else JsonArray(emptyList())
            "review-state" -> if (finished) completedResults() else JsonObject(emptyMap())
            else -> h.default(name, args)
        } }
        val completed = mutableListOf<String>()
        h.page.startTask("analyze", then = listOf("tour", "brief"), onDone = {
            assertEquals("Completed tour", h.page.tour?.title)
            assertEquals("Saved brief", (h.page.saved!!.brief!!.page.blocks.single() as BriefBlock.Text).markdown)
            assertEquals("Saved answer", h.page.saved!!.chat.last().text)
            completed += it.status
        })
        runCurrent()
        h.cache.pauseObservations(); runCurrent()
        finished = true
        h.cache.resumeObservations(); runCurrent()
        assertEquals(listOf("done"), completed)
        assertEquals(listOf(SessionCue.Ready), h.cues)
        assertEquals("done", h.updates.last().status)
        val listAt = h.calls.indexOfLast { it.name == "ai-task-list" }
        assertTrue(h.calls.drop(listAt + 1).any { it.name == "review-state" })
        h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
        assertEquals(listOf("done"), completed)
        assertEquals(listOf(SessionCue.Ready), h.cues)
        val start = h.calls.single { it.name == "ai-task-start" }
        assertEquals(listOf("tour", "brief"), (start.args["then"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals(1, h.calls.count { it.name == "ai-task-wait" })
        assertFalse(h.calls.any { it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun aResumedServerTaskThatWasAlreadyBeingObservedAlsoSettlesOnce() = runTest {
        val h = ObservationHarness(backgroundScope)
        var finished = false
        h.answer = { name, args -> when (name) {
            "ai-task-list" -> JsonArray(listOf(task(if (finished) 5 else 2, if (finished) "done" else "running")))
            "ai-task-wait" -> awaitCancellation()
            "review-state" -> if (finished) completedResults() else JsonObject(emptyMap())
            else -> h.default(name, args)
        } }
        h.page.load(); runCurrent()
        h.cache.pauseObservations(); runCurrent()
        finished = true
        h.cache.resumeObservations(); runCurrent()
        assertEquals(listOf(SessionCue.Ready), h.cues)
        assertEquals("done", h.updates.last().status)
        assertEquals("Completed tour", h.page.tour?.title)
        h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
        assertEquals(listOf(SessionCue.Ready), h.cues)
        assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun resumeWaitsForTheFreshHeadSavedStateAndTaskListBeforeStartingAnotherPoll() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.answer = { name, args -> if (name == "ai-task-list") JsonArray(listOf(task(1, "running")))
            else if (name == "ai-task-wait") awaitCancellation() else h.default(name, args) }
        h.page.load(); runCurrent()
        h.cache.pauseObservations(); runCurrent()
        val opening = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        val tasks = CompletableDeferred<Unit>()
        h.answer = { name, args -> when (name) {
            "github-pr" -> { opening.await(); h.default(name, args) }
            "review-state" -> { saved.await(); JsonObject(emptyMap()) }
            "ai-task-list" -> { tasks.await(); JsonArray(listOf(task(9, "running"))) }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        h.cache.resumeObservations(); runCurrent()
        assertEquals(1, h.waits.size)
        opening.complete(Unit); runCurrent(); assertEquals(1, h.waits.size)
        saved.complete(Unit); runCurrent(); assertEquals(1, h.waits.size)
        tasks.complete(Unit); runCurrent()
        assertEquals(listOf("1", "9"), h.waits.map { it.args.getValue("after").jsonPrimitive.content })
        h.page.detach()
    }

    @Test
    fun anUncooperativeOldPollCannotSettleTheResumedGeneration() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        val old = CompletableDeferred<JsonElement>()
        val resumed = CompletableDeferred<JsonElement>()
        var waits = 0
        h.answer = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-list" -> JsonArray(listOf(task(8, "running")))
            "ai-task-wait" -> if (++waits == 1) withContext(NonCancellable) { old.await() } else resumed.await()
            else -> h.default(name, args)
        } }
        val completions = mutableListOf<Int>()
        h.page.startTask("tour", onDone = { completions += it.rev }); runCurrent()
        h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
        assertEquals(8, h.page.tasks["task-1"]?.rev)
        old.complete(task(12, "done")); runCurrent()
        assertEquals(8, h.page.tasks["task-1"]?.rev)
        assertTrue(completions.isEmpty()); assertTrue(h.cues.isEmpty())
        resumed.complete(task(9, "done")); runCurrent()
        assertEquals(listOf(9), completions)
        assertEquals(listOf(SessionCue.Ready), h.cues)
        h.page.detach()
    }

    @Test
    fun aPrePauseSavedResultCannotPublishAnOutcomeWhileObservationIsStopped() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        val late = CompletableDeferred<JsonElement>()
        h.answer = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> task(2, "done")
            "review-state" -> withContext(NonCancellable) { late.await() }
            else -> h.default(name, args)
        } }
        var completed = false
        h.page.startTask("tour", onDone = { completed = true }); runCurrent()
        h.cache.pauseObservations(); runCurrent()
        late.complete(completedResults()); runCurrent()
        assertFalse(completed); assertTrue(h.cues.isEmpty())
        assertNull(h.page.tour)
        assertTrue(h.page.tasks.getValue("task-1").running)
        h.page.detach()
    }

    @Test
    fun foregroundFailureKeepsTheDraftAndResultsAndRetriesWithCappedBackoff() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.answer = { name, args -> if (name == "review-state") completedResults() else h.default(name, args) }
        h.page.load(); runCurrent()
        ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }.changeInput("Keep offline draft")
        h.cache.pauseObservations(); runCurrent()
        val attempts = mutableListOf<Long>()
        var offline = true
        h.answer = { name, args -> when (name) {
            "github-pr" -> { attempts += testScheduler.currentTime; if (offline) error("Offline") else h.default(name, args) }
            "review-state" -> completedResults()
            else -> h.default(name, args)
        } }
        h.cache.resumeObservations(); runCurrent()
        assertEquals(RpcConnectionException().message, h.page.loadError)
        assertEquals("Completed tour", h.page.tour?.title)
        assertEquals("Keep offline draft", chatUi(h.page).chatInput)
        testScheduler.advanceTimeBy(91_000); runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 31_000L, 61_000L, 91_000L), attempts)
        offline = false
        testScheduler.advanceTimeBy(30_000); runCurrent()
        assertNull(h.page.loadError)
        assertEquals("Keep offline draft", chatUi(h.page).chatInput)
        assertTrue(h.cues.isEmpty())
        assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun aFailedSavedOrTaskReconciliationCannotRestartTheOldPollBeforeRetrySucceeds() = runTest {
        for (failedRead in listOf("review-state", "ai-task-list")) {
            val h = ObservationHarness(backgroundScope)
            h.answer = { name, args -> when (name) {
                "ai-task-list" -> JsonArray(listOf(task(2, "running")))
                "ai-task-wait" -> awaitCancellation()
                "review-state" -> completedResults()
                else -> h.default(name, args)
            } }
            h.page.load(); runCurrent()
            ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }.changeInput("Retain partial-resume draft")
            h.cache.pauseObservations(); runCurrent()
            var offline = true
            h.answer = { name, args -> when {
                name == failedRead && offline -> error("Offline")
                name == "ai-task-list" -> JsonArray(listOf(task(6, "running")))
                name == "ai-task-wait" -> awaitCancellation()
                name == "review-state" -> completedResults()
                else -> h.default(name, args)
            } }
            h.cache.resumeObservations(); runCurrent()
            assertEquals("Saved review work is unavailable. Trying again.", h.page.loadError)
            assertEquals(1, h.waits.size)
            assertEquals("Completed tour", h.page.tour?.title)
            offline = false
            testScheduler.advanceTimeBy(1_000); runCurrent()
            assertNull(h.page.loadError)
            assertEquals(listOf("2", "6"), h.waits.map { it.args.getValue("after").jsonPrimitive.content })
            assertEquals("Retain partial-resume draft", chatUi(h.page).chatInput)
            assertTrue(h.cues.isEmpty())
            assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
            h.page.detach()
        }
    }

    @Test
    fun aPausedSourceReplacementCannotResumeTheOldSessionOrItsDraft() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }.changeInput("Private draft")
        h.answer = { name, args -> when (name) {
            "ai-task-start" -> JsonPrimitive("task-1")
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        h.page.startTask("tour"); runCurrent()
        h.cache.pauseObservations(); runCurrent()
        h.fixture.generation++; h.cache.clear(); runCurrent()
        val replacement = h.newPage()
        replacement.load(); runCurrent()
        val openingsBeforeResume = h.calls.count { it.name == "github-pr" }
        assertNull(replacement.opened)
        assertEquals("", chatUi(replacement).chatInput)
        h.cache.resumeObservations(); runCurrent()
        assertEquals(openingsBeforeResume + 1, h.calls.count { it.name == "github-pr" })
        assertEquals(1, h.calls.count { it.name == "ai-task-start" })
        assertNotNull(replacement.opened)
        assertNull(h.page.opened); assertTrue(h.page.tasks.isEmpty())
        assertEquals(1, h.calls.count { it.name == "ai-task-wait" })
        assertFalse(h.calls.any { it.name == "ai-task-cancel" })
        replacement.detach()
    }

    @Test
    fun anInitiallyPausedCacheDoesNotOpenOrWarmUntilResume() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.detach()
        h.cache.pauseObservations()
        val page = h.newPage()
        page.load(); h.cache.warm(page.repo, page.pull!!); runCurrent()
        assertTrue(h.calls.isEmpty())
        h.cache.resumeObservations(); h.cache.resumeObservations(); runCurrent()
        assertNotNull(page.opened)
        assertEquals(1, h.calls.count { it.name == "github-pr" })
        assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
        page.detach()
    }

    @Test
    fun processReopenReadsSavedResultsWithoutReplayingAStartOrNotifyingHistoricalWork() = runTest {
        val first = ObservationHarness(backgroundScope)
        var started = false
        var finished = false
        val server: suspend (String, JsonObject) -> JsonElement = { name, args -> when (name) {
            "ai-task-start" -> { started = true; JsonPrimitive("task-1") }
            "ai-task-list" -> JsonArray(if (started) listOf(task(if (finished) 10 else 2, if (finished) "done" else "running")) else emptyList())
            "ai-task-wait" -> awaitCancellation()
            "review-state" -> if (finished) completedResults() else JsonObject(emptyMap())
            else -> first.default(name, args)
        } }
        first.answer = server
        first.page.load(); runCurrent()
        first.page.startTask("tour"); runCurrent()
        first.page.detach()
        finished = true
        val reopened = ObservationHarness(backgroundScope)
        reopened.answer = server
        reopened.page.load(); runCurrent()
        assertEquals("Completed tour", reopened.page.tour?.title)
        assertNotNull(reopened.page.saved?.brief)
        assertEquals("Saved answer", reopened.page.saved!!.chat.last().text)
        assertTrue(reopened.cues.isEmpty())
        assertFalse(reopened.calls.any { it.name in setOf("ai-task-start", "ai-task-cancel", "ai-task-wait") })
        reopened.page.detach()
    }

    @Test
    fun aPendingCommitChoiceIsRetriedAfterTheFreshPrRatherThanLostOnPause() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        var commits = 0
        h.answer = { name, args -> when (name) {
            "github-commit" -> if (++commits == 1) awaitCancellation() else opening("commit-head", "commit-key")
            else -> h.default(name, args)
        } }
        h.page.selectCommits("commit-head" to "commit-head"); runCurrent()
        h.cache.pauseObservations(); runCurrent()
        h.fixture.head = "head-b"
        h.cache.resumeObservations(); runCurrent()
        assertEquals(2, commits)
        assertEquals("commit-head" to "commit-head", h.page.selection)
        assertEquals("commit-key", h.page.key)
        assertEquals("head-b", h.page.prOpened?.headSha)
        assertFalse(h.page.loading)
        h.page.selectCommits(null); runCurrent()
        assertEquals("head-b", h.page.opened?.headSha)
        h.page.detach()
    }

    @Test
    fun aLateWarmFromBeforePauseCannotRefillTheResumedCache() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.detach()
        val delayed = CompletableDeferred<JsonElement>()
        h.answer = { name, args -> if (name == "github-pr") withContext(NonCancellable) { delayed.await() } else h.default(name, args) }
        h.cache.warm(h.page.repo, h.page.pull!!); runCurrent()
        h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
        delayed.complete(opening("private-old-head")); runCurrent()
        val page = h.newPage()
        assertNull(page.opened)
        h.answer = h::default
        page.load(); runCurrent()
        assertEquals("head-a", page.opened?.headSha)
        page.detach()
    }

    @Test
    fun anAcceptedStartKeepsItsRequestAliveDuringPauseAndNeverStartsASecondTask() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        val accepted = CompletableDeferred<JsonElement>()
        h.answer = { name, args -> when (name) {
            "ai-task-start" -> accepted.await()
            "ai-task-list" -> JsonArray(listOf(task(4, "running")))
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        h.page.startTask("tour"); runCurrent()
        h.cache.pauseObservations(); runCurrent()
        accepted.complete(JsonPrimitive("task-1")); runCurrent()
        assertTrue(h.page.tasks.getValue("task-1").running)
        assertTrue(h.waits.isEmpty())
        h.cache.resumeObservations(); runCurrent()
        assertEquals(listOf("4"), h.waits.map { it.args.getValue("after").jsonPrimitive.content })
        assertEquals(1, h.calls.count { it.name == "ai-task-start" })
        assertFalse(h.calls.any { it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun anAcceptedChatRestoreWaitsForResumeBeforeReadingOrOpeningTheConversation() = runTest {
        for (changedHead in listOf(false, true)) {
            val h = ObservationHarness(backgroundScope)
            h.page.load(); runCurrent()
            val restored = CompletableDeferred<Unit>()
            h.answer = { name, args -> when (name) {
                "chat-restore" -> { restored.await(); JsonObject(emptyMap()) }
                "review-state" -> completedResults()
                else -> h.default(name, args)
            } }
            var opened = 0
            val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
            assertTrue(buttons.restore("history-1") { opened++ }); runCurrent()
            h.cache.pauseObservations(); runCurrent()
            val reads = h.calls.count { it.name == "review-state" }
            restored.complete(Unit); runCurrent()
            assertEquals(reads, h.calls.count { it.name == "review-state" })
            assertEquals(0, opened)
            if (changedHead) h.fixture.head = "head-b"
            h.cache.resumeObservations(); runCurrent()
            assertEquals(if (changedHead) 0 else 1, opened)
            assertEquals("Saved answer", h.page.saved!!.chat.last().text)
            assertEquals(1, h.calls.count { it.name == "chat-restore" })
            assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
            h.page.detach()
        }
    }

    @Test
    fun pauseDuringAnAcceptedRestoresPostWriteReadCancelsThatReadButKeepsTheMutation() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        var reading = false
        var cancelledReads = 0
        h.answer = { name, args -> when (name) {
            "review-state" -> {
                reading = true
                try { awaitCancellation() } finally { cancelledReads++ }
            }
            else -> h.default(name, args)
        } }
        var opened = 0
        val buttons = ChatActions(h.page, h.page.captureView()) { AiCapabilities.Full }
        assertTrue(buttons.restore("history-1") { opened++ }); runCurrent()
        assertTrue(reading)
        h.cache.pauseObservations(); runCurrent()
        assertEquals(1, cancelledReads)
        assertEquals(0, opened)
        val pausedCalls = h.calls.size
        testScheduler.advanceTimeBy(30_000); runCurrent()
        assertEquals(pausedCalls, h.calls.size)
        h.answer = { name, args -> if (name == "review-state") completedResults() else h.default(name, args) }
        h.cache.resumeObservations(); runCurrent()
        assertEquals(1, opened)
        assertEquals("Saved answer", h.page.saved!!.chat.last().text)
        assertEquals(1, h.calls.count { it.name == "chat-restore" })
        assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun aPausedAcceptedChangesInterruptedReadRetiresWithoutResumeAndNeverReplaysItsQueuedQuestion() = runTest {
        for (change in listOf("restore", "clear")) for (failed in listOf(false, true))
            for (retirement in listOf("none", "policy", "source", "session")) {
                val h = ObservationHarness(backgroundScope)
                h.page.load(); runCurrent()
                val gate = com.octaviantocan.pulls.ui.home.HomeObservations()
                val installed = mutableStateOf(AiCapabilities.Full)
                val port = ReviewActionReadiness({ gate.ready }, { installed.value })
                var cancelledReads = 0
                h.answer = { name, args -> when (name) {
                    "chat-restore", "chat-clear" -> {
                        if (failed) h.fixture.serverError("$change rejected")
                        JsonObject(emptyMap())
                    }
                    "review-state" -> try { awaitCancellation() } finally { cancelledReads++ }
                    else -> h.default(name, args)
                } }
                var opened = 0
                val history = ChatActions(h.page, h.page.captureView(), port) { AiCapabilities.Full }
                val readsBefore = h.calls.count { it.name == "review-state" }
                assertTrue(if (change == "restore") history.restore("history-held-read") { opened++ } else history.newChat())
                val accepted = assertNotNull(h.page.chatChange)
                history.close() // The accepted History mutation outlives the sheet, including queued dispatch.
                runCurrent()
                assertEquals(readsBefore + 1, h.calls.count { it.name == "review-state" })
                val followUp = ChatActions(h.page, h.page.captureView(), port) { AiCapabilities.Full }
                if (!failed) {
                    assertTrue(followUp.send("Keep this follow-up unsent"))
                    runCurrent()
                    assertTrue(followUp.waiting)
                }
                gate.pause(); h.cache.pauseObservations(); Snapshot.sendApplyNotifications(); runCurrent()
                assertEquals(1, cancelledReads)
                assertTrue(accepted.isActive)
                assertEquals(0, opened)
                if (failed) assertEquals("$change rejected", chatUi(h.page).chatError)
                else {
                    assertFalse(followUp.waiting)
                    assertNull(chatUi(h.page).chatPending)
                    assertEquals("Keep this follow-up unsent", chatUi(h.page).chatInput)
                }
                val pausedCalls = h.calls.size
                testScheduler.advanceTimeBy(30_000); runCurrent()
                assertEquals(pausedCalls, h.calls.size)
                when (retirement) {
                    "policy" -> installed.value = AiCapabilities.None
                    "source" -> { h.fixture.generation++; h.cache.clear() }
                    "session" -> h.page.detach()
                }
                Snapshot.sendApplyNotifications(); runCurrent()
                if (retirement != "none") {
                    assertTrue(accepted.isCompleted)
                    assertFalse(gate.ready)
                    assertEquals(pausedCalls, h.calls.size)
                    assertEquals(0, opened)
                }
                if (retirement == "none" || retirement == "policy") {
                    // Reinstalling a retired policy must not revive the old change or its abandoned question.
                    installed.value = AiCapabilities.Full
                    h.answer = { name, args -> if (name == "review-state") completedResults() else h.default(name, args) }
                    gate.resume(); h.cache.resumeObservations(); Snapshot.sendApplyNotifications(); runCurrent()
                    assertTrue(accepted.isCompleted)
                    assertEquals(if (retirement == "none" && change == "restore") 1 else 0, opened)
                    assertEquals("Saved answer", h.page.saved!!.chat.last().text)
                    assertFalse(followUp.changing)
                    if (!failed) assertEquals("Keep this follow-up unsent", chatUi(h.page).chatInput)
                }
                assertEquals(1, h.calls.count { it.name == "chat-$change" })
                assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
                followUp.close(); gate.close(); h.page.detach()
            }
    }

    @Test
    fun retiringAnAcceptedChangesPolicyCancelsItsActiveReadAndRejectsAnUncooperativeLateReply() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.page.load(); runCurrent()
        val late = CompletableDeferred<JsonElement>()
        val installed = mutableStateOf(AiCapabilities.Full)
        h.answer = { name, args -> if (name == "review-state") withContext(NonCancellable) { late.await() } else h.default(name, args) }
        var opened = 0
        val history = ChatActions(h.page, h.page.captureView(), ReviewActionReadiness({ true }, { installed.value })) { AiCapabilities.Full }
        val readsBefore = h.calls.count { it.name == "review-state" }
        assertTrue(history.restore("history-late-read") { opened++ })
        val accepted = assertNotNull(h.page.chatChange)
        history.close(); runCurrent()
        assertEquals(readsBefore + 1, h.calls.count { it.name == "review-state" })
        installed.value = AiCapabilities.None
        Snapshot.sendApplyNotifications(); runCurrent()
        assertTrue(accepted.isCompleted)
        assertEquals(0, opened)
        val retiredCalls = h.calls.size
        late.complete(completedResults()); runCurrent()
        assertTrue(h.page.saved!!.chat.isEmpty())
        assertEquals(retiredCalls, h.calls.size)
        installed.value = AiCapabilities.Full
        Snapshot.sendApplyNotifications(); runCurrent()
        assertEquals(0, opened)
        assertEquals(1, h.calls.count { it.name == "chat-restore" })
        assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
        h.page.detach()
    }

    @Test
    fun aFullViewReplacementRetiresAnAcceptedReadAndItsQueuedQuestionWhileLiveActionsStayClosed() = runTest {
        val h = ObservationHarness(backgroundScope)
        h.answer = { name, args -> if (name == "github-commit") opening("commit-head", "commit-key") else h.default(name, args) }
        h.page.load(); runCurrent()
        h.page.selectCommits("commit-head" to "commit-head"); runCurrent()
        val owner = assertNotNull(h.page.captureView())
        assertEquals("commit-key", owner.key)
        var cancelledReads = 0
        h.answer = { name, args -> if (name == "review-state") {
            try { awaitCancellation() } finally { cancelledReads++ }
        } else h.default(name, args) }
        val gate = com.octaviantocan.pulls.ui.home.HomeObservations()
        val port = ReviewActionReadiness({ gate.ready })
        var opened = 0
        val history = ChatActions(h.page, owner, port) { AiCapabilities.Full }
        assertTrue(history.restore("history-old-commit") { opened++ })
        val accepted = assertNotNull(h.page.chatChange)
        history.close(); runCurrent()
        val followUp = ChatActions(h.page, owner, port) { AiCapabilities.Full }
        assertTrue(followUp.send("This belongs to the old commit")); runCurrent()
        val heldCalls = h.calls.size
        gate.pause()
        // The real model restores its already-loaded PR view synchronously. Cancel its queued reads before dispatch.
        h.page.selectCommits(null)
        h.cache.pauseObservations(); Snapshot.sendApplyNotifications(); runCurrent()
        assertEquals("pull-1", h.page.key)
        assertFalse(h.page.isCurrentView(owner))
        assertTrue(accepted.isCompleted)
        assertEquals(1, cancelledReads)
        assertEquals(heldCalls, h.calls.size)
        assertEquals(0, opened)
        assertEquals("", chatUi(h.page).chatInput)
        assertNull(chatUi(h.page).chatPending)
        assertEquals(1, h.calls.count { it.name == "chat-restore" })
        assertFalse(h.calls.any { it.name == "ai-task-start" || it.name == "ai-task-cancel" })
        followUp.close(); gate.close(); h.page.detach()
    }

    @Test
    fun aResumedRunningTaskRetainsItsCallbackUntilTerminalResultsAndChainedTasksCanBeRead() = runTest {
        for (failedRead in listOf("review-state", "ai-task-list")) {
            val h = ObservationHarness(backgroundScope)
            h.page.load(); runCurrent()
            val terminal = CompletableDeferred<JsonElement>()
            var resumed = false
            var completedOnServer = false
            var recovered = false
            h.answer = { name, args -> when {
                name == "ai-task-start" -> JsonPrimitive("task-1")
                name == failedRead && completedOnServer && !recovered -> error("Offline results")
                name == "review-state" -> if (completedOnServer) completedResults() else JsonObject(emptyMap())
                name == "ai-task-list" -> if (completedOnServer) JsonArray(listOf(
                    task(6, "done", "analyze"), task(3, "done", "tour", "tour-1"), task(4, "done", "brief", "brief-1"),
                )) else JsonArray(listOf(task(4, "running", "analyze")))
                name == "ai-task-wait" -> if (resumed) terminal.await() else awaitCancellation()
                else -> h.default(name, args)
            } }
            val completed = mutableListOf<Int>()
            h.page.startTask("analyze", then = listOf("tour", "brief"), onDone = {
                assertEquals("Completed tour", h.page.tour?.title)
                assertNotNull(h.page.saved?.brief)
                completed += it.rev
            }); runCurrent()
            h.cache.pauseObservations(); runCurrent()
            resumed = true
            h.cache.resumeObservations(); runCurrent()
            completedOnServer = true
            terminal.complete(task(6, "done", "analyze")); runCurrent()
            assertTrue(completed.isEmpty())
            assertTrue(h.cues.isEmpty())
            assertTrue(h.page.tasks.getValue("task-1").running)
            assertNotNull(h.page.taskObservationErrors["task-1"])
            recovered = true
            testScheduler.advanceTimeBy(1_000); runCurrent()
            assertEquals(listOf(6), completed)
            assertEquals(listOf(SessionCue.Ready), h.cues)
            assertEquals(2, h.waits.size)
            assertEquals(1, h.calls.count { it.name == "ai-task-start" })
            assertFalse(h.calls.any { it.name == "ai-task-cancel" })
            h.cache.pauseObservations(); h.cache.resumeObservations(); runCurrent()
            assertEquals(listOf(6), completed)
            assertEquals(listOf(SessionCue.Ready), h.cues)
            h.page.detach()
        }
    }
}

private data class ObservedCall(val name: String, val args: JsonObject)

private class ObservationHarness(private val scope: CoroutineScope) {
    val fixture = DiffHarness(scope)
    val cache get() = fixture.cache
    val calls = mutableListOf<ObservedCall>()
    val cues = mutableListOf<SessionCue>()
    val updates = mutableListOf<TaskState>()
    val waits get() = calls.filter { it.name == "ai-task-wait" }
    var answer: suspend (String, JsonObject) -> JsonElement = fixture::default
    private val effects = object : SessionEffects {
        override fun cue(cue: SessionCue) { cues += cue }
        override fun updated(task: TaskState, source: SessionWorkSource?) { updates += task }
    }
    init {
        fixture.page.detach()
        fixture.handler = { name, args -> calls += ObservedCall(name, args); answer(name, args) }
    }
    val page = newPage()
    fun newPage() = ReviewSession(fixture.page.target, fixture.page.repo, scope, fixture.page.settings, cache = cache, effects = effects)
    fun default(name: String, args: JsonObject) = fixture.default(name, args)
}

private fun task(revision: Int, status: String, kind: String = "tour", id: String = "task-1"): JsonElement =
    Json.parseToJsonElement("""{"id":"$id","key":"pull-1","kind":"$kind","label":"Review","rev":$revision,"status":"$status","startedAt":1000}""")

private fun opening(head: String, key: String = "pull-1"): JsonElement = Json.parseToJsonElement(
    """{"key":"$key","diff":{"head":{"sha":"$head"},"files":[{"path":"src/file.kt","sha":"$head-blob","status":"modified"}]},"groups":[]}""",
)

private fun completedResults(): JsonElement = Json.parseToJsonElement(
    """{"tour":{"title":"Completed tour","stops":[{"title":"Read this"}],"headSha":"head-a"},"brief":{"tree":{"type":"Stack","props":{"children":[{"type":"Text","props":{"markdown":"Saved brief"}}]}},"headSha":"head-a","generatedAt":"2026-10-21T00:00:00Z"},"chat":[{"role":"user","text":"Question"},{"role":"assistant","text":"Saved answer"}]}""",
)
