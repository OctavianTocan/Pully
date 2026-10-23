package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiffCommentActionsTest {
    @Test
    fun aSentCommentSettlesItsReceiptWhilePausedButNeverIntoARetiredSource() = runTest {
        for (failed in listOf(false, true)) for (retired in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            val reply = CompletableDeferred<Unit>()
            val readiness = com.octaviantocan.pulls.ui.home.HomeObservations()
            var reject = failed
            var writes = 0
            var reads = 0
            h.handler = { name, args -> when (name) {
                "review-data" -> { reads++; buildJsonObject { put("canWrite", true) } }
                "review-comment" -> {
                    writes++; reply.await()
                    if (reject) h.serverError("The comment was rejected")
                    buildJsonObject {}
                }
                else -> h.default(name, args)
            } }
            h.page.load(); runCurrent()
            val ui = DiffUi(h.page)
            val file = h.page.files.single()
            val draft = DraftTarget(file.path, "additions", 7)
            ui.drafts[file.path] = draft
            var completions = 0
            val cues = mutableListOf<Cue>()
            val actions = DiffCommentActions(h.page, ui, file, backgroundScope, { ReviewCapabilities.Full }, ReviewActionReadiness({ readiness.ready })) { cue, _ -> cues += cue }
            fun send() = actions.post(draft, "A sent comment") { completions++; ui.drafts.remove(file.path) }
            send(); runCurrent()
            assertEquals(1, writes)
            readiness.pause(); h.cache.pauseObservations(); runCurrent()
            if (retired) h.generation++
            reads = 0
            reply.complete(Unit); runCurrent()
            assertFalse(actions.busy)
            assertEquals(if (!failed && !retired) 1 else 0, completions)
            assertEquals(if (failed && !retired) "The comment was rejected" else null, actions.error)
            assertTrue(cues.isEmpty())
            assertEquals(0, reads)
            readiness.resume(); h.cache.resumeObservations(); runCurrent()
            if (!retired) {
                assertTrue(reads > 0)
                if (failed) {
                    assertSame(draft, ui.drafts[file.path])
                    reject = false
                    send(); runCurrent()
                    assertEquals(2, writes)
                    assertEquals(1, completions)
                } else {
                    assertNull(ui.drafts[file.path])
                    send(); runCurrent()
                    assertEquals(1, writes) // The confirmed draft cannot become a duplicate comment.
                }
            }
            readiness.close(); h.page.detach()
        }
    }

    @Test
    fun theActualPendingReviewWriteKeepsItsSelectedRangeAndSendsOnce() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        val writes = mutableListOf<JsonObject>()
        val feedback = mutableListOf<Cue>()
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", true) }
            "review-comment" -> { writes += args; gate.await(); buildJsonObject {} }
            else -> h.default(name, args)
        } }
        h.page.reloadReviews(); runCurrent(); assertTrue(h.page.canWrite)
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val draft = DraftTarget(file.path, "additions", 7, 4)
        ui.drafts[file.path] = draft
        val actions = DiffCommentActions(h.page, ui, file, backgroundScope, { ReviewCapabilities.Full }) { cue, _ -> feedback += cue }
        var cleared = 0
        actions.post(draft, "  Review these lines  ") { cleared++ }
        actions.post(draft, "A repeated click") { cleared++ }
        runCurrent()
        assertEquals(1, writes.size)
        assertTrue(actions.busy)
        val sent = writes.single()
        assertEquals("review", sent.getValue("mode").jsonPrimitive.content)
        assertEquals("head-a", sent.getValue("headSha").jsonPrimitive.content)
        assertEquals("Review these lines", sent.getValue("body").jsonPrimitive.content)
        assertEquals("4", sent.getValue("startLine").jsonPrimitive.content)
        assertEquals("7", sent.getValue("line").jsonPrimitive.content)
        assertEquals("additions", sent.getValue("side").jsonPrimitive.content)
        gate.complete(Unit); runCurrent()
        assertEquals(1, cleared)
        assertEquals(listOf(Cue.Success), feedback)
        assertFalse(actions.busy)
        h.page.detach()
    }

    @Test
    fun hostReadinessAndServerWritePermissionBothPrecedeTheRequestAndRejectedWritesRetry() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        var permitted = false
        var fail = true
        var writes = 0
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", permitted) }
            "review-comment" -> { writes++; if (fail) h.serverError("Permission changed"); buildJsonObject {} }
            else -> h.default(name, args)
        } }
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val draft = DraftTarget(file.path, "deletions", 7)
        ui.drafts[file.path] = draft
        var policy = ReviewCapabilities.Full
        val feedback = mutableListOf<Cue>()
        val actions = DiffCommentActions(h.page, ui, file, backgroundScope, { policy }) { cue, _ -> feedback += cue }
        actions.post(draft, "Question"); runCurrent()
        assertEquals(0, writes)
        permitted = true; h.page.reloadReviews(); runCurrent(); assertTrue(h.page.canWrite)
        policy = ReviewCapabilities.ReadOnly
        actions.post(draft, "Question"); runCurrent()
        assertEquals(0, writes)
        policy = ReviewCapabilities.Full
        actions.post(draft, "Question"); runCurrent()
        assertEquals("Permission changed", actions.error)
        assertFalse(actions.busy)
        assertEquals(draft, ui.drafts[file.path])
        fail = false
        actions.post(draft, "Question"); runCurrent()
        assertEquals(2, writes)
        assertNull(actions.error)
        assertEquals(listOf(Cue.Error, Cue.Success), feedback)
        h.page.detach()
    }

    @Test
    fun lateCommentAfterHeadAbaCannotClearReloadOrPublishFeedbackInTheReplacementView() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        var reloads = 0
        h.handler = { name, args -> when (name) {
            "review-data" -> { reloads++; buildJsonObject { put("canWrite", true) } }
            "review-comment" -> { withContext(NonCancellable) { gate.await() }; buildJsonObject {} }
            else -> h.default(name, args)
        } }
        h.page.reloadReviews(); runCurrent(); assertTrue(h.page.canWrite)
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val draft = DraftTarget(file.path, "additions", 7)
        ui.drafts[file.path] = draft
        val feedback = mutableListOf<Cue>()
        val actions = DiffCommentActions(h.page, ui, file, backgroundScope, { ReviewCapabilities.Full }) { cue, _ -> feedback += cue }
        var cleared = 0
        actions.post(draft, "Earlier comment") { cleared++ }; runCurrent()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        ui.captureFileRead()
        val replacement = DraftTarget(file.path, "additions", 3)
        ui.drafts[file.path] = replacement
        reloads = 0
        gate.complete(Unit); runCurrent()
        assertEquals(0, cleared)
        assertEquals(0, reloads)
        assertTrue(feedback.isEmpty())
        assertEquals(replacement, ui.drafts[file.path])
        assertFalse(actions.busy)
        assertNull(actions.error)
        h.page.detach()
    }

    @Test
    fun aSelectionRecreatedAfterGoingAwayAndBackIsNotConsumedByTheOriginalSend() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", true) }
            "review-comment" -> { gate.await(); buildJsonObject {} }
            else -> h.default(name, args)
        } }
        h.page.reloadReviews(); runCurrent(); assertTrue(h.page.canWrite)
        val file = h.page.files.single()
        val ui = DiffUi(h.page)
        val draft = DraftTarget(file.path, "additions", 7)
        ui.drafts[file.path] = draft
        val actions = DiffCommentActions(h.page, ui, file, backgroundScope, { ReviewCapabilities.Full }) { _, _ -> }
        var cleared = 0
        actions.post(draft, "Earlier selection") { cleared++ }; runCurrent()
        ui.drafts[file.path] = DraftTarget(file.path, "deletions", 3)
        val replacement = DraftTarget(file.path, "additions", 7)
        assertEquals(draft, replacement)
        assertNotSame(draft, replacement)
        ui.drafts[file.path] = replacement
        gate.complete(Unit); runCurrent()
        assertEquals(0, cleared)
        assertSame(replacement, ui.drafts[file.path])
        h.page.detach()
    }
}
