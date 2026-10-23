package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.Toaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewMutationActionsTest {
    @Test
    fun sentSubmitDiscardAndPrActionsKeepTheirReceiptsAcrossPauseAndReconcileWithoutReplay() = runTest {
        for (operation in listOf("submit", "discard", "close")) for (failed in listOf(false, true)) for (retired in listOf(false, true)) {
            val h = MutationHarness(DiffHarness(backgroundScope))
            val reply = CompletableDeferred<Unit>()
            val readiness = com.octaviantocan.pulls.ui.home.HomeObservations()
            h.respond = {
                reply.await()
                if (failed) h.diff.serverError("Rejected accepted action")
                if (operation == "close") h.state = "closed" else h.pending = null
            }
            h.page.load(); runCurrent()
            val actions = ReviewMutationActions(h.page, h.top, reviewOverview(h.page), ReviewActionReadiness({ readiness.ready })) { ReviewCapabilities.Full }
            var completed = 0
            val accepted = when (operation) {
                "submit" -> actions.submit("COMMENT", "Accepted summary", 7) { completed++ }
                "discard" -> actions.discard(7) { completed++ }
                else -> actions.pullRequest("close") { completed++ }
            }
            assertTrue(accepted)
            runCurrent(); assertEquals(1, h.requests.size)
            readiness.pause(); h.diff.cache.pauseObservations(); runCurrent()
            assertFalse(actions.canSubmit)
            if (!retired) assertTrue(actions.canKeepSubmission)
            if (retired) h.diff.generation++
            h.reads = 0
            reply.complete(Unit); runCurrent()
            assertNull(h.top.busy)
            assertEquals(if (!failed && !retired) 1 else 0, completed, "$operation / $failed / $retired")
            assertEquals(if (failed && !retired) "Rejected accepted action" else null, h.top.actionError)
            assertEquals(0, h.reads)
            readiness.resume(); h.diff.cache.resumeObservations(); runCurrent()
            assertEquals(1, h.requests.size)
            if (!retired) {
                assertTrue(h.reads > 0)
                if (!failed && operation != "close") assertNull(h.page.reviewData?.pendingReviewId)
            }
            readiness.close(); h.page.detach()
        }
    }

    @Test
    fun actualReviewSubmissionSendsItsDrawnHeadAndOnlyCurrentPermittedVerdicts() = runTest {
        val h = MutationHarness(DiffHarness(backgroundScope))
        h.page.load(); runCurrent()
        var policy = ReviewCapabilities.ReadOnly
        val actions = h.actions { policy }
        assertFalse(actions.submit("APPROVE", "", 7) {})
        policy = ReviewCapabilities.Full
        h.write = false; h.page.reloadReviews(); runCurrent()
        assertFalse(actions.submit("APPROVE", "", 7) {})
        h.write = true; h.page.reloadReviews(); runCurrent()
        var done = 0
        assertTrue(actions.submit("APPROVE", "", 7) { done++ })
        assertFalse(actions.submit("COMMENT", "Second tap", 7) { done++ })
        runCurrent()
        val sent = h.requests.single()
        assertEquals("review-submit", sent.first)
        assertEquals("head-a", sent.second.getValue("headSha").jsonPrimitive.content)
        assertEquals("APPROVE", sent.second.getValue("verdict").jsonPrimitive.content)
        assertEquals("7", sent.second.getValue("pendingReviewId").jsonPrimitive.content)
        assertEquals(1, done)
        h.state = "closed"; h.page.reloadOverview(); runCurrent()
        assertFalse(actions.submit("REQUEST_CHANGES", "A stale verdict", 7) {})
        assertTrue(actions.submit("COMMENT", "A plain comment", 7) {})
        runCurrent()
        assertEquals("COMMENT", h.requests.last().second.getValue("verdict").jsonPrimitive.content)
        h.pending = null; h.page.reloadReviews(); runCurrent()
        assertTrue(actions.submit("COMMENT", "A new independent review", null) {})
        runCurrent()
        assertEquals(JsonNull, h.requests.last().second.getValue("pendingReviewId"))
        h.page.detach()
    }

    @Test
    fun discardRequiresTheDrawnPendingReviewAndAConfirmedCurrentOwner() = runTest {
        val h = MutationHarness(DiffHarness(backgroundScope))
        h.page.load(); runCurrent()
        val actions = h.actions()
        assertFalse(actions.discard(null) {})
        h.pending = 8; h.page.reloadReviews(); runCurrent()
        assertFalse(actions.discard(7) {})
        assertTrue(actions.discard(8) {})
        runCurrent()
        assertEquals(listOf("review-discard"), h.requests.map { it.first })
        assertEquals("8", h.requests.single().second.getValue("pendingReviewId").jsonPrimitive.content)
        actions.close()
        assertFalse(actions.discard(8) {})
        h.page.detach()
    }

    @Test
    fun everyPrActionRechecksItsCurrentGitHubAbilityAndUsesTheDrawnExpectedHead() = runTest {
        val cases = listOf("merge", "auto-merge", "cancel-auto-merge", "update-branch", "close", "draft", "ready", "reopen")
        for (action in cases) {
            val h = MutationHarness(DiffHarness(backgroundScope))
            h.state = when (action) { "ready" -> "draft"; "reopen" -> "closed"; else -> "open" }
            h.blocked = action == "auto-merge"
            h.waiting = action == "cancel-auto-merge"
            h.page.load(); runCurrent()
            var policy = ReviewCapabilities.ReadOnly
            val actions = h.actions { policy }
            val method = "squash".takeIf { action == "merge" || action == "auto-merge" }
            assertFalse(actions.pullRequest(action, method))
            policy = ReviewCapabilities.Full
            h.allowed = false; h.page.reloadOverview(); runCurrent()
            assertFalse(actions.pullRequest(action, method), action)
            h.allowed = true; h.page.reloadOverview(); runCurrent()
            assertTrue(actions.pullRequest(action, method), action)
            runCurrent()
            val request = h.requests.single()
            assertEquals("pr-action", request.first)
            assertEquals(action, request.second.getValue("action").jsonPrimitive.content)
            assertEquals("head-a", request.second.getValue("expectedHeadOid").jsonPrimitive.content)
            assertEquals("PR_sample", request.second.getValue("id").jsonPrimitive.content)
            h.page.detach()
        }
    }

    @Test
    fun mergeNeedsAFreshMatchingHeadAndAnOfferedMethod() = runTest {
        val h = MutationHarness(DiffHarness(backgroundScope))
        val fresh = CompletableDeferred<Unit>()
        val original = h.diff.handler
        h.diff.handler = { name, args ->
            if (name == "pr-overview" && args["cached"] != JsonPrimitive(true)) fresh.await()
            original(name, args)
        }
        h.page.load(); runCurrent()
        val actions = h.actions()
        assertTrue(h.page.overviewStored)
        assertFalse(actions.pullRequest("merge", "squash"))
        fresh.complete(Unit); runCurrent()
        assertFalse(h.page.overviewStored)
        assertFalse(actions.pullRequest("merge", "unoffered"))
        assertTrue(actions.canPrAction("merge", "squash"))
        h.overviewHead = "head-b"; h.page.reloadOverview(); runCurrent()
        assertFalse(actions.pullRequest("merge", "squash"))
        assertFalse(actions.submit("APPROVE", "", 7) {})
        assertTrue(h.requests.isEmpty())
        h.page.detach()
    }

    @Test
    fun lateSuccessAndFailureNeverPublishIntoAReplacedSourceHeadPolicyOrDisposedConfirmation() = runTest {
        for (replacement in listOf("source", "head-aba", "policy", "disposed")) for (failed in listOf(false, true)) {
            val h = MutationHarness(DiffHarness(backgroundScope))
            val gate = CompletableDeferred<Unit>()
            h.respond = {
                withContext(NonCancellable) { gate.await() }
                if (failed) h.diff.serverError("Rejected original action")
            }
            h.page.load(); runCurrent()
            var policy = ReviewCapabilities.Full
            val actions = h.actions { policy }
            var done = 0
            assertTrue(actions.pullRequest("close", after = { done++ }))
            runCurrent()
            when (replacement) {
                "source" -> h.diff.generation++
                "head-aba" -> {
                    h.diff.head = "head-b"; h.page.refresh(); runCurrent()
                    h.diff.head = "head-a"; h.page.refresh(); runCurrent()
                }
                "policy" -> policy = ReviewCapabilities.ReadOnly
                else -> actions.close()
            }
            h.reads = 0
            gate.complete(Unit); runCurrent()
            assertEquals(0, done, "$replacement / failed=$failed")
            assertEquals(0, h.reads)
            assertTrue(h.cues.isEmpty())
            assertNull(h.top.actionError)
            assertNull(h.top.busy)
            assertFalse(actions.pullRequest("close"))
            h.page.detach()
        }
    }

    @Test
    fun rejectedCurrentActionsKeepTheirErrorAndRetryWithoutASecondInFlightRequest() = runTest {
        val h = MutationHarness(DiffHarness(backgroundScope))
        h.respond = { h.diff.serverError("Checks changed") }
        h.page.load(); runCurrent()
        val actions = h.actions()
        assertTrue(actions.pullRequest("merge", "squash")); runCurrent()
        assertEquals("Checks changed", h.top.actionError)
        assertEquals(listOf(Cue.Error), h.cues)
        h.respond = {}
        assertTrue(actions.pullRequest("merge", "squash")); runCurrent()
        assertNull(h.top.actionError)
        assertEquals(listOf(Cue.Error, Cue.Success), h.cues)
        h.page.detach()
    }

    @Test
    fun aRetainedConfirmationAndItsUndoReadTheSameLiveGateBeforeAnyRecomposition() = runTest {
        val h = MutationHarness(DiffHarness(backgroundScope))
        h.page.load(); runCurrent()
        var ready = true
        val port = ReviewActionReadiness({ ready })
        val actions = ReviewMutationActions(h.page, h.top, reviewOverview(h.page), port) { ReviewCapabilities.Full }
        val view = h.page.captureView()
        h.top.reviewCapabilities = ReviewCapabilities.Full
        val toaster = Toaster()
        actions.showUndo(toaster, "Closed #1", "reopen")
        val retainedUndo = assertNotNull(toaster.message)
        ready = false
        assertTrue(actions.current)
        assertEquals(view, h.page.captureView())
        assertFalse(actions.submit("COMMENT", "A retained confirmation", 7) {})
        assertFalse(actions.pullRequest("close"))
        actions.close()
        h.reads = 0
        toaster.act(retainedUndo); runCurrent()
        assertTrue(h.requests.isEmpty())
        assertEquals(0, h.reads) // A revoked Undo must not even begin its revalidation read.
        h.page.detach()
    }

    @Test
    fun visibleUndoOutlivesItsSheetButNeverItsToastOrTheRoutesReadiness() = runTest {
        val h = MutationHarness(DiffHarness(backgroundScope))
        h.page.load(); runCurrent()
        h.top.reviewCapabilities = ReviewCapabilities.Full
        val actions = h.actions()
        val toaster = Toaster()
        h.respond = { h.state = "closed" }
        assertTrue(actions.pullRequest("close") { actions.showUndo(toaster, "Closed #1", "reopen") })
        runCurrent()
        val message = assertNotNull(toaster.message)
        actions.close() // Closing the successful confirmation does not dismiss its explicit Undo.
        toaster.act(message); runCurrent()
        assertEquals(listOf("close", "reopen"), h.requests.map { it.second.getValue("action").jsonPrimitive.content })
        assertNull(toaster.message)
        toaster.act(message); runCurrent()
        assertEquals(2, h.requests.size)
        val another = h.actions()
        another.showUndo(toaster, "Closed #1", "reopen")
        val retained = assertNotNull(toaster.message)
        h.top.reviewCapabilities = ReviewCapabilities.ReadOnly
        toaster.act(retained); runCurrent()
        assertEquals(2, h.requests.size)
        h.page.detach()
    }
}

private class MutationHarness(val diff: DiffHarness) {
    val page get() = diff.page
    var write = true
    var allowed = true
    var state = "open"
    var pending: Long? = 7L
    var blocked = false
    var waiting = false
    var overviewHead: String? = null
    var reads = 0
    var respond: suspend () -> Unit = {}
    val requests = mutableListOf<Pair<String, JsonObject>>()
    val cues = mutableListOf<Cue>()
    val top = ReviewTopUi { cue, _ -> cues += cue }
    init {
        diff.handler = { name, args -> when (name) {
            "review-data" -> { reads++; Json.parseToJsonElement("""{"canWrite":$write,"pendingReview":{"id":$pending}}""") }
            "pr-overview" -> {
                reads++
                Json.parseToJsonElement("""{"overview":{"id":"PR_sample","number":1,"state":"$state","head":{"oid":"${overviewHead ?: diff.head}"},"mergeable":"MERGEABLE","mergeStateStatus":"${if (blocked) "BLOCKED" else "CLEAN"}","viewer":{"canPush":$allowed,"canClose":$allowed,"canReopen":$allowed,"canUpdate":$allowed,"canUpdateBranch":$allowed,"canEnableAutoMerge":$allowed,"canDisableAutoMerge":$allowed,"isAuthor":true},"merge":{"methods":["squash","rebase"],"defaultMethod":"squash"}${if (waiting) ",\"autoMerge\":{\"method\":\"squash\"}" else ""}}}""")
            }
            "pr-action", "review-submit", "review-discard" -> { requests += name to args; respond(); JsonPrimitive(true) }
            else -> diff.default(name, args)
        } }
    }
    fun actions(policy: () -> ReviewCapabilities = { ReviewCapabilities.Full }) = ReviewMutationActions(page, top, reviewOverview(page), capabilities = policy)
}
