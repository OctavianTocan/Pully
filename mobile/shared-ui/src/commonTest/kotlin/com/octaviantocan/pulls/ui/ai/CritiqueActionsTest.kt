package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.data.Critique
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.SavedState
import com.octaviantocan.pulls.ui.review.DiffHarness
import com.octaviantocan.pulls.ui.review.ReviewCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun sampleCritique(head: String = "head-a", revision: String = "revision-1") = Critique(
    "A sample review", listOf(CritiqueFinding("finding-1", "src/file.kt", "additions", 4, null, "bug", "A sample finding", "Sample explanation", null)),
    0, head, "codex", "sample-model", "high", null, "2026-10-21T00:00:00Z", null, "variant-1", revision,
)

internal fun installCritique(h: DiffHarness, result: Critique = sampleCritique()): AiUi {
    h.page.saved = SavedState(emptyList(), result, emptyList(), emptyList(), null, null)
    return aiUi(h.page).also {
        it.critiqueFor = critiqueIdentity(result)
        it.selected = setOf("finding-1")
        it.postBody = "The selected finding"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CritiqueActionsTest {
    @Test
    fun aSentFindingPublicationKeepsItsConfirmedIdsOrErrorWhileFreshActionsPause() = runTest {
        for (failed in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            val response = CompletableDeferred<Unit>()
            val gate = com.octaviantocan.pulls.ui.home.HomeObservations()
            var posts = 0
            h.handler = { name, args -> when (name) {
                "review-data" -> buildJsonObject { put("canWrite", true) }
                "critique-post" -> {
                    posts++; response.await()
                    if (failed) h.serverError("The finding was rejected")
                    confirmedFinding()
                }
                else -> h.default(name, args)
            } }
            h.page.load(); runCurrent()
            val ui = installCritique(h)
            val actions = CritiqueActions(h.page, h.page.saved?.critique, { AiCapabilities.Full }, { ReviewCapabilities.Full }, com.octaviantocan.pulls.ui.review.ReviewActionReadiness({ gate.ready }))
            actions.post(listOf("finding-1")); runCurrent()
            assertEquals(1, posts)
            gate.pause(); h.cache.pauseObservations(); runCurrent()
            response.complete(Unit); runCurrent()
            assertFalse(ui.posting)
            assertEquals(if (failed) "The finding was rejected" else null, ui.postError)
            assertEquals(if (failed) emptySet() else setOf("finding-1"), ui.posted)
            assertEquals(if (failed) setOf("finding-1") else emptySet(), ui.selected)
            gate.close(); h.page.detach()
        }
    }

    @Test
    fun actualPublicationRequiresCompleteHostAndServerPermissionAndPostsTheSelectedResult() = runTest {
        val h = DiffHarness(backgroundScope)
        var permission = false
        val posts = mutableListOf<JsonObject>()
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", permission) }
            "critique-post" -> { posts += args; confirmedFinding() }
            else -> h.default(name, args)
        } }
        h.page.load(); runCurrent()
        val ui = installCritique(h)
        var review = ReviewCapabilities.Full
        var ai = AiCapabilities.Full
        val actions = CritiqueActions(h.page, h.page.saved?.critique, { ai }, { review })
        actions.post(listOf("finding-1")); runCurrent()
        assertTrue(posts.isEmpty())
        permission = true; h.page.reloadReviews(); runCurrent()
        review = ReviewCapabilities.ReadOnly
        actions.post(listOf("finding-1")); runCurrent()
        assertTrue(posts.isEmpty())
        review = ReviewCapabilities.Full; ai = AiCapabilities.None
        actions.post(listOf("finding-1")); runCurrent()
        assertTrue(posts.isEmpty())
        ai = AiCapabilities.Full
        actions.post(listOf("not-in-the-result")); runCurrent()
        assertTrue(posts.isEmpty())
        actions.post(listOf("finding-1")); runCurrent()
        val sent = posts.single()
        assertEquals("head-a", sent.getValue("headSha").jsonPrimitive.content)
        assertEquals("variant-1", sent.getValue("variant").jsonPrimitive.content)
        assertEquals("revision-1", sent.getValue("revision").jsonPrimitive.content)
        assertEquals(listOf("finding-1"), sent.getValue("ids").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("finding-1"), ui.posted)
        assertTrue(ui.selected.isEmpty())
        assertFalse(ui.posting)
        actions.post(listOf("finding-1")); runCurrent()
        assertEquals(1, posts.size)
        h.page.detach()
    }

    @Test
    fun aRejectedCurrentPublicationKeepsTheDraftAndCanRetryAfterTheOneInFlightRequest() = runTest {
        val h = DiffHarness(backgroundScope)
        var fail = true
        var posts = 0
        val gate = CompletableDeferred<Unit>()
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", true) }
            "critique-post" -> {
                posts++
                gate.await()
                if (fail) h.serverError("The result moved")
                confirmedFinding()
            }
            else -> h.default(name, args)
        } }
        h.page.load(); runCurrent()
        val ui = installCritique(h)
        val actions = CritiqueActions(h.page, h.page.saved?.critique, { AiCapabilities.Full }, { ReviewCapabilities.Full })
        actions.post(listOf("finding-1")); runCurrent()
        actions.post(listOf("finding-1")); runCurrent()
        assertEquals(1, posts)
        gate.complete(Unit); runCurrent()
        assertEquals("The result moved", ui.postError)
        assertEquals("The selected finding", ui.postBody)
        assertEquals(setOf("finding-1"), ui.selected)
        fail = false
        actions.post(listOf("finding-1")); runCurrent()
        assertEquals(2, posts)
        assertNull(ui.postError)
        assertEquals(setOf("finding-1"), ui.posted)
        h.page.detach()
    }

    @Test
    fun latePublicationCannotOverwriteAnotherResultViewSourceOrRevokedOrDisposedReader() = runTest {
        for (replacement in listOf("result", "head-aba", "source", "policy", "review-policy", "disposed")) for (failed in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            val gate = CompletableDeferred<Unit>()
            var reloads = 0
            h.handler = { name, args -> when (name) {
                "review-data" -> { reloads++; buildJsonObject { put("canWrite", true) } }
                "critique-post" -> {
                    withContext(NonCancellable) { gate.await() }
                    if (failed) h.serverError("Rejected old result")
                    confirmedFinding()
                }
                else -> h.default(name, args)
            } }
            h.page.load(); runCurrent()
            val ui = installCritique(h)
            var policy = AiCapabilities.Full
            var review = ReviewCapabilities.Full
            val actions = CritiqueActions(h.page, h.page.saved?.critique, { policy }, { review })
            actions.post(listOf("finding-1")); runCurrent()
            when (replacement) {
                "result" -> installCritique(h, sampleCritique(revision = "revision-2"))
                "head-aba" -> {
                    h.head = "head-b"; h.page.refresh(); runCurrent()
                    h.head = "head-a"; h.page.refresh(); runCurrent()
                    installCritique(h)
                }
                "source" -> h.generation++
                "policy" -> policy = AiCapabilities.None
                "review-policy" -> review = ReviewCapabilities.ReadOnly
                else -> actions.close()
            }
            reloads = 0
            gate.complete(Unit); runCurrent()
            val expectedReceipt = if (!failed && replacement in setOf("policy", "review-policy", "disposed")) setOf("finding-1") else emptySet()
            assertEquals(expectedReceipt, ui.posted, "$replacement / failed=$failed")
            assertEquals(setOf("finding-1"), ui.selected)
            assertNull(ui.postError)
            assertFalse(ui.posting)
            assertEquals(0, reloads)
            h.page.detach()
        }
    }

    @Test
    fun closingAndReopeningTheSameFindingsKeepsItsConfirmedReceiptAndCannotPostTwice() = runTest {
        val h = DiffHarness(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var posts = 0
        var reloads = 0
        h.handler = { name, args -> when (name) {
            "review-data" -> { reloads++; buildJsonObject { put("canWrite", true) } }
            "critique-post" -> {
                posts++
                gate.await()
                confirmedFinding()
            }
            else -> h.default(name, args)
        } }
        h.page.load(); runCurrent()
        val ui = installCritique(h)
        val original = CritiqueActions(h.page, h.page.saved?.critique, { AiCapabilities.Full }, { ReviewCapabilities.Full })
        original.post(listOf("finding-1")); runCurrent()
        original.close()
        val reopened = CritiqueActions(h.page, h.page.saved?.critique, { AiCapabilities.Full }, { ReviewCapabilities.Full })
        reopened.post(listOf("finding-1")); runCurrent()
        assertEquals(1, posts)
        reloads = 0
        gate.complete(Unit); runCurrent()
        assertEquals(setOf("finding-1"), ui.posted)
        assertEquals(setOf("finding-1"), ui.selected)
        assertNull(ui.postError)
        assertFalse(ui.posting)
        assertEquals(0, reloads)
        reopened.post(listOf("finding-1")); runCurrent()
        assertEquals(1, posts)
        h.page.detach()
    }
}

private fun confirmedFinding() = buildJsonObject { put("posted", JsonArray(listOf(JsonPrimitive("finding-1")))) }
