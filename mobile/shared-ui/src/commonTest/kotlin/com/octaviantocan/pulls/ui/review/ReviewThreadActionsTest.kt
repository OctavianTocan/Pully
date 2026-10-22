package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.sound.Cue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewThreadActionsTest {
    @Test
    fun aSentThreadReplyKeepsSuccessOrFailureAndItsDraftOwnerWhileForegroundReadsPause() = runTest {
        for (failed in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            val response = CompletableDeferred<Unit>()
            val gate = com.octaviantocan.pulls.ui.home.HomeObservations()
            var writes = 0
            var reads = 0
            h.handler = { name, args -> when (name) {
                "review-data" -> { reads++; buildJsonObject { put("canWrite", true) } }
                "review-reply" -> {
                    writes++; response.await()
                    if (failed) h.serverError("The reply was rejected")
                    JsonObject(emptyMap())
                }
                else -> h.default(name, args)
            } }
            h.page.load(); runCurrent()
            val actions = ReviewThreadActions(h.page, backgroundScope, { ReviewCapabilities.Full }, ReviewActionReadiness({ gate.ready })) { _, _ -> }
            var completed = 0
            actions.run({ pull -> h.page.repo.reply(pull, 11, "An accepted reply") }) { completed++ }
            runCurrent(); assertEquals(1, writes)
            gate.pause(); h.cache.pauseObservations(); runCurrent()
            assertTrue(actions.canKeepDraft); assertFalse(actions.canWrite)
            reads = 0
            response.complete(Unit); runCurrent()
            assertFalse(actions.busy)
            assertEquals(if (failed) 0 else 1, completed)
            assertEquals(if (failed) "The reply was rejected" else null, actions.error)
            assertEquals(0, reads)
            gate.resume(); h.cache.resumeObservations(); runCurrent()
            assertEquals(1, writes)
            assertTrue(reads > 0)
            gate.close(); h.page.detach()
        }
    }

    @Test
    fun allActualThreadWritesRequireCurrentHostReadinessAndServerPermission() = runTest {
        val h = DiffHarness(backgroundScope)
        var permitted = false
        val requests = mutableListOf<String>()
        h.handler = { name, args -> when (name) {
            "review-data" -> buildJsonObject { put("canWrite", permitted) }
            "review-reply", "review-resolve", "review-edit", "review-delete" -> { requests += name; JsonObject(emptyMap()) }
            else -> h.default(name, args)
        } }
        h.page.load(); runCurrent()
        var policy = ReviewCapabilities.Full
        val actions = ReviewThreadActions(h.page, backgroundScope, { policy }) { _, _ -> }
        val operations: List<suspend (OpenTarget.Pull) -> Unit> = listOf(
            { pull -> h.page.repo.reply(pull, 11, "A reply") },
            { pull -> h.page.repo.resolve(pull, "thread-11") },
            { pull -> h.page.repo.editComment(pull, 12, "An edit") },
            { pull -> h.page.repo.deleteComment(pull, 12) },
        )
        operations.forEach { actions.run(it); runCurrent() }
        assertTrue(requests.isEmpty())
        permitted = true; h.page.reloadReviews(); runCurrent()
        assertTrue(h.page.canWrite)
        policy = ReviewCapabilities.ReadOnly
        operations.forEach { actions.run(it); runCurrent() }
        assertTrue(requests.isEmpty())
        policy = ReviewCapabilities.Full
        operations.forEach { actions.run(it); runCurrent() }
        assertEquals(listOf("review-reply", "review-resolve", "review-edit", "review-delete"), requests)
        assertFalse(actions.busy)
        h.page.detach()
    }

    @Test
    fun lateThreadSuccessAndFailureCannotAffectARevokedDisposedOrReplacedOwner() = runTest {
        for (replacement in listOf("head-aba", "revoked-host", "reader-disposed")) for (failed in listOf(false, true)) {
            val h = DiffHarness(backgroundScope)
            val gate = CompletableDeferred<Unit>()
            var reloads = 0
            h.handler = { name, args -> when (name) {
                "review-data" -> { reloads++; buildJsonObject { put("canWrite", true) } }
                "review-reply" -> {
                    withContext(NonCancellable) { gate.await() }
                    if (failed) error("Rejected original reply")
                    JsonObject(emptyMap())
                }
                else -> h.default(name, args)
            } }
            h.page.load(); runCurrent(); h.page.reloadReviews(); runCurrent()
            var policy = ReviewCapabilities.Full
            val cues = mutableListOf<Cue>()
            val actions = ReviewThreadActions(h.page, backgroundScope, { policy }) { cue, _ -> cues += cue }
            var cleared = 0
            actions.run({ pull -> h.page.repo.reply(pull, 11, "Earlier reply") }) { cleared++ }; runCurrent()
            assertTrue(actions.busy)
            when (replacement) {
                "head-aba" -> {
                    h.head = "head-b"; h.page.refresh(); runCurrent()
                    h.head = "head-a"; h.page.refresh(); runCurrent()
                }
                "revoked-host" -> policy = ReviewCapabilities.ReadOnly
                else -> actions.close()
            }
            reloads = 0
            gate.complete(Unit); runCurrent()
            assertEquals(0, cleared, "$replacement / failed=$failed")
            assertEquals(0, reloads)
            assertTrue(cues.isEmpty())
            assertNull(actions.error)
            assertFalse(actions.busy)
            h.page.detach()
        }
    }
}
