package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.ChatActions
import com.octaviantocan.pulls.ui.ai.CritiqueActions
import com.octaviantocan.pulls.ui.ai.chatUi
import com.octaviantocan.pulls.ui.ai.installCritique
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewActionReadinessTest {
    @Test
    fun aLiveHostCannotGrantAnUndrawnWorkflowAndRevocationDuringItsPolicyReadFailsClosed() {
        var ready = true
        var liveAi = AiCapabilities.Full
        var liveReview = ReviewCapabilities.Full
        val port = ReviewActionReadiness({ ready }, { liveAi }, { liveReview })
        assertEquals(AiCapabilities.None, port.ai(AiCapabilities.None))
        assertEquals(ReviewCapabilities.ReadOnly, port.review(ReviewCapabilities.ReadOnly))
        liveAi = AiCapabilities.ReadOnly; liveReview = ReviewCapabilities.ReadOnly
        assertEquals(AiCapabilities.ReadOnly, port.ai(AiCapabilities.Full))
        assertEquals(ReviewCapabilities.ReadOnly, port.review(ReviewCapabilities.Full))
        val reentrant = ReviewActionReadiness({ ready }, { ready = false; AiCapabilities.Full })
        assertEquals(AiCapabilities.None, reentrant.ai(AiCapabilities.Full))
        assertEquals(ReviewCapabilities.None, port.review(ReviewCapabilities.Full))
        assertEquals(AiCapabilities.ReadOnly, port.installedAi(AiCapabilities.Full))
        assertEquals(ReviewCapabilities.ReadOnly, port.installedReview(ReviewCapabilities.Full))
    }

    @Test
    fun retainedCommentThreadProgressAndFindingsWritersCannotUseAnInactiveButStillCurrentView() = runTest {
        val h = DiffHarness(backgroundScope)
        val calls = mutableListOf<String>()
        h.handler = { name, args ->
            calls += name
            when (name) {
                "review-data" -> buildJsonObject { put("canWrite", true) }
                else -> h.default(name, args)
            }
        }
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        val file = h.page.files.single()
        val draft = DraftTarget(file.path, "additions", 7)
        ui.drafts[file.path] = draft
        val critiqueUi = installCritique(h)
        var ready = true
        val port = ReviewActionReadiness({ ready })
        val comment = DiffCommentActions(h.page, ui, file, backgroundScope, { ReviewCapabilities.Full }, port) { _, _ -> }
        val thread = ReviewThreadActions(h.page, backgroundScope, { ReviewCapabilities.Full }, port) { _, _ -> }
        val progress = ReviewProgressActions(h.page, port) { ReviewCapabilities.Full }
        val findings = CritiqueActions(h.page, h.page.saved?.critique, { AiCapabilities.Full }, { ReviewCapabilities.Full }, port)
        val commands = ReviewCommandActions(h.page, { ReviewCapabilities.Full }, { AiCapabilities.Full }, port)
        val frame = ReviewFrameOwner(h.page, { true }, { ReviewCapabilities.Full }, { AiCapabilities.Full }, true, port)
        val tour = TourReaderActions(h.page, h.page.captureTourRead(), port) { AiCapabilities.Full }
        val view = h.page.captureView()
        assertTrue(thread.canWrite); assertTrue(progress.enabled); assertTrue(findings.canPublish)
        calls.clear()
        ready = false // No policy replacement or composition frame has run.
        assertEquals(view, h.page.captureView())
        assertTrue(commands.current); assertTrue(frame.current)
        comment.post(draft, "A retained pending comment")
        thread.run({ pull -> h.page.repo.reply(pull, 11, "A retained thread reply") })
        assertFalse(progress.mark(file, true))
        findings.post(listOf("finding-1"))
        commands.toggleReviewed(file)
        tour.regenerate { h.page.startTask("tour") }
        assertFalse(frame.critique(null, { h.page.startTask("critique") }) {})
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(h.page.reviewed.isEmpty())
        assertTrue(critiqueUi.posted.isEmpty())
        assertEquals(setOf("finding-1"), critiqueUi.selected)
        assertTrue(ui.drafts[file.path] === draft)
        ready = true
        assertTrue(thread.canWrite); assertTrue(findings.canPublish)
        comment.post(draft, "After verified foreground resumes")
        runCurrent()
        assertEquals(1, calls.count { it == "review-comment" })
        h.page.detach()
    }

    @Test
    fun aQuestionWaitingForItsAcceptedClearRechecksTheLiveGateWithoutRecomposition() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val clear = CompletableDeferred<Unit>()
        var clears = 0
        var starts = 0
        h.handler = { name, args -> when (name) {
            "chat-clear" -> { clears++; clear.await(); JsonObject(emptyMap()) }
            "ai-task-start" -> { starts++; JsonPrimitive("wrong-background-question") }
            else -> h.default(name, args)
        } }
        val gate = com.octaviantocan.pulls.ui.home.HomeObservations()
        val port = ReviewActionReadiness({ gate.ready })
        val actions = ChatActions(h.page, h.page.captureView(), port) { AiCapabilities.Full }
        val view = h.page.captureView()
        assertTrue(actions.newChat())
        assertTrue(actions.send("Preserve the unsent follow-up"))
        runCurrent(); assertEquals(1, clears)
        gate.pause(); h.cache.pauseObservations(); Snapshot.sendApplyNotifications()
        assertTrue(actions.current)
        clear.complete(Unit); runCurrent()
        assertEquals(0, starts)
        assertEquals(view, h.page.captureView())
        assertNull(chatUi(h.page).chatPending)
        assertEquals("Preserve the unsent follow-up", chatUi(h.page).chatInput)
        assertEquals("The question was not sent. Try again.", chatUi(h.page).chatError)
        gate.resume(); h.cache.resumeObservations(); Snapshot.sendApplyNotifications(); runCurrent()
        assertTrue(actions.canAsk)
        assertEquals(0, starts)
        gate.close()
        h.page.detach()
    }

    @Test
    fun anAlreadySentQuestionKeepsItsSourceLifetimeWhenFreshActionsBecomeIneligible() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val accepted = CompletableDeferred<Unit>()
        var starts = 0
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { starts++; accepted.await(); JsonPrimitive("accepted-chat") }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        var ready = true
        val actions = ChatActions(h.page, h.page.captureView(), ReviewActionReadiness({ ready })) { AiCapabilities.Full }
        assertTrue(actions.send("This request reached the server"))
        runCurrent(); assertEquals(1, starts)
        ready = false
        assertFalse(actions.send("A new background question"))
        accepted.complete(Unit); runCurrent()
        assertTrue(actions.current)
        assertTrue(h.page.tasks["accepted-chat"]?.running == true)
        assertEquals("This request reached the server", chatUi(h.page).chatPending)
        assertEquals(1, starts)
        h.page.detach()
    }
}
