package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAnalysisStateTest {
    @Test
    fun discoveryCannotAdoptCandidatesOrChangeModesAfterTheHeadChanges() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = sharedAnalysisState(h.page)
        val reply = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "shared-list") reply.await() else h.default(name, args) }
        h.page.analyzeMode = "plain"
        val request = backgroundScope.launch { state.discover(state.viewOwner()) }
        runCurrent()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        val modeAfterRefresh = h.page.analyzeMode
        reply.complete(json("""{"loaded":{},"candidates":[{"login":"old-owner"}]}"""))
        request.join()
        assertTrue(state.candidates.isEmpty())
        assertEquals(modeAfterRefresh, h.page.analyzeMode)
        h.page.detach()
    }

    @Test
    fun loadingASharedResultSerializesWritesAndChangesModeAfterSavedWorkReloads() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = sharedAnalysisState(h.page)
        val load = CompletableDeferred<JsonElement>()
        val saved = CompletableDeferred<JsonElement>()
        var writes = 0
        h.handler = { name, args -> when (name) {
            "shared-list" -> json("""{"candidates":[{"login":"reader","own":true,"stale":false,"locale":"fr"}]}""")
            "shared-load" -> { writes++; load.await() }
            "review-state" -> saved.await()
            else -> h.default(name, args)
        } }
        val owner = state.viewOwner()
        state.discover(owner)
        val candidate = state.candidates.single()
        assertEquals(SharedCandidate("reader", true, false, "fr"), candidate)
        h.page.analyzeMode = "plain"
        val request = backgroundScope.launch { state.load(owner, candidate) }
        runCurrent()
        state.load(owner, candidate)
        assertEquals(1, writes)
        load.complete(json("{}")); runCurrent()
        assertTrue(state.loading)
        assertEquals("plain", h.page.analyzeMode)
        saved.complete(json("{}")); request.join()
        assertFalse(state.loading)
        assertTrue(state.candidates.isEmpty())
        assertEquals("ai", h.page.analyzeMode)
        h.page.detach()
    }

    @Test
    fun rejectedAdoptionKeepsTheCandidateAndCanBeRetriedButCancellationShowsNoError() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = sharedAnalysisState(h.page)
        h.handler = { name, args -> when (name) {
            "shared-list" -> json("""{"candidates":[{"login":"reader"}]}""")
            "shared-load" -> h.serverError("Try again")
            else -> h.default(name, args)
        } }
        val owner = state.viewOwner()
        state.discover(owner)
        val candidate = state.candidates.single()
        state.load(owner, candidate)
        assertEquals("Try again", state.notice)
        assertEquals(listOf(candidate), state.candidates)
        assertFalse(state.loading)
        h.handler = { name, args -> if (name == "shared-load") h.serverError("") else h.default(name, args) }
        state.load(owner, candidate)
        val priorNotice = state.notice
        assertEquals("The shared analysis could not be loaded. Try again.", priorNotice)
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "shared-load") answer.await() else h.default(name, args) }
        val request = backgroundScope.launch { state.load(owner, candidate) }
        runCurrent(); request.cancelAndJoin()
        assertEquals(priorNotice, state.notice)
        assertFalse(state.loading)
        h.page.detach()
    }

    @Test
    fun retainedCandidateCallbackAndLateWriteCannotMutateAnotherHead() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = sharedAnalysisState(h.page)
        val answer = CompletableDeferred<JsonElement>()
        var writes = 0
        h.handler = { name, args -> when (name) {
            "shared-list" -> json("""{"candidates":[{"login":"reader"}]}""")
            "shared-load" -> { writes++; answer.await() }
            else -> h.default(name, args)
        } }
        val owner = state.viewOwner()
        state.discover(owner)
        val candidate = state.candidates.single()
        h.page.analyzeMode = "plain"
        val request = backgroundScope.launch { state.load(owner, candidate) }
        runCurrent()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        val modeAfterRefresh = h.page.analyzeMode
        val next = state.viewOwner()
        assertTrue(next != owner)
        answer.complete(json("{}")); request.join()
        state.load(owner, candidate)
        assertEquals(1, writes)
        assertFalse(state.loading)
        assertTrue(state.candidates.isEmpty())
        assertNull(state.notice)
        assertEquals(modeAfterRefresh, h.page.analyzeMode)
        h.page.detach()
    }
}

private fun json(source: String): JsonElement = Json.parseToJsonElement(source)
