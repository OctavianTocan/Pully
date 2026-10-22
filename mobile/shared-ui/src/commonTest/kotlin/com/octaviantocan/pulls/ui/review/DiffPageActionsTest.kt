package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiffPageActionsTest {
    @Test
    fun actualViewerProgressRequiresHostReadinessButNotGithubWritePermission() = runTest {
        val h = DiffHarness(backgroundScope)
        val writes = installTwoFiles(h)
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        val files = h.page.files
        var policy = ReviewCapabilities.ReadOnly
        val actions = DiffPageActions(h.page, ui, files.first(), backgroundScope, { policy }, { AiCapabilities.ReadOnly }, { true })
        val moved = mutableListOf<String>()
        val cues = mutableListOf<Cue>()
        assertFalse(h.page.canWrite)
        actions.toggleReviewed(files, cues::add, moved::add); runCurrent()
        assertTrue(writes.isEmpty())
        assertTrue(h.page.reviewed.isEmpty())
        policy = ReviewCapabilities.Full
        actions.toggleReviewed(files, cues::add, moved::add); runCurrent()
        assertEquals(setOf("head-a-blob"), h.page.reviewed)
        val sent = writes.single()
        assertEquals("pull-1", sent.getValue("key").jsonPrimitive.content)
        assertEquals(listOf("head-a-blob"), sent.getValue("shas").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", sent.getValue("reviewed").jsonPrimitive.content)
        advanceTimeBy(219); runCurrent(); assertTrue(moved.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf("src/next.kt"), moved)
        assertTrue(cues.isEmpty())
        actions.toggleReviewed(files, cues::add, moved::add); runCurrent()
        assertTrue(h.page.reviewed.isEmpty())
        advanceTimeBy(220); runCurrent()
        assertEquals(listOf("src/next.kt"), moved)
        h.page.detach()
    }

    @Test
    fun delayedReviewedNavigationCannotMoveAnotherViewFileOrRevokedHost() = runTest {
        for (replacement in listOf("head-aba", "another-file", "file-visit-aba", "revoked-host", "reader-disposed")) {
            val h = DiffHarness(backgroundScope)
            installTwoFiles(h)
            h.page.load(); runCurrent()
            val ui = DiffUi(h.page)
            var shown = true
            var policy = ReviewCapabilities.Full
            val actions = DiffPageActions(h.page, ui, h.page.files.first(), backgroundScope, { policy }, { AiCapabilities.Full }, { shown })
            val moved = mutableListOf<String>()
            actions.toggleReviewed(h.page.files, {}, moved::add); runCurrent()
            when (replacement) {
                "head-aba" -> {
                    h.head = "head-b"; h.page.refresh(); runCurrent()
                    h.head = "head-a"; h.page.refresh(); runCurrent()
                }
                "another-file" -> shown = false
                "file-visit-aba" -> { shown = false; actions.close(); shown = true }
                "reader-disposed" -> actions.close()
                else -> policy = ReviewCapabilities.ReadOnly
            }
            advanceTimeBy(220); runCurrent()
            assertTrue(moved.isEmpty(), replacement)
            h.page.detach()
        }
    }

    @Test
    fun retainedReadAndAskHandlersRespectTheFullOwnerAndLatestPaidPolicy() = runTest {
        val h = DiffHarness(backgroundScope)
        installTwoFiles(h)
        h.page.load(); runCurrent()
        val ui = DiffUi(h.page)
        var policy = ReviewCapabilities.Full
        var ai = AiCapabilities.Full
        val actions = DiffPageActions(h.page, ui, h.page.files.first(), backgroundScope, { policy }, { ai }, { true })
        var reads = 0; var asks = 0
        actions.read { reads++ }; actions.ask { asks++ }
        ai = AiCapabilities.ReadOnly
        actions.read { reads++ }; actions.ask { asks++ }
        assertEquals(listOf(2, 1), listOf(reads, asks))
        ai = AiCapabilities.Full
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        actions.read { reads++ }; actions.ask { asks++ }
        assertEquals(listOf(2, 1), listOf(reads, asks))
        val current = DiffPageActions(h.page, ui, h.page.files.first(), backgroundScope, { policy }, { ai }, { true })
        policy = ReviewCapabilities.None
        current.read { reads++ }; current.ask { asks++ }
        assertEquals(listOf(2, 1), listOf(reads, asks))
        h.page.detach()
    }
}

internal fun installTwoFiles(h: DiffHarness): MutableList<JsonObject> {
    val writes = mutableListOf<JsonObject>()
    h.handler = { name, args -> when (name) {
        "github-pr" -> Json.parseToJsonElement("""{"key":"pull-1","diff":{"head":{"sha":"${h.head}"},"files":[{"path":"src/file.kt","sha":"${h.head}-blob","status":"modified"},{"path":"src/next.kt","sha":"${h.head}-next","status":"modified"}]},"groups":[]}""")
        "reviewed-set" -> { writes += args; JsonObject(emptyMap()) }
        else -> h.default(name, args)
    } }
    return writes
}
