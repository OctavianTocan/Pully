package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.DiffHarness
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AskActionsTest {
    private val selection = AskSelection("src/file.kt", "additions", 4, 7)

    @Test
    fun readOnlyEmptyAndRetiredCallbacksCannotStartOrCloseAThread() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val owner = assertNotNull(h.page.captureView())
        val calls = mutableListOf<String>()
        h.handler = { name, args -> calls += name; h.default(name, args) }
        h.page.ask("thread", selection, "Question", 0, owner, AiCapabilities.ReadOnly)
        h.page.closeAskThread("thread", null, owner, AiCapabilities.ReadOnly)
        h.page.ask("thread", selection, "   ", 0, owner, AiCapabilities.Full)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(askUi(h.page).askPending.isEmpty())
        h.head = "head-b"; h.page.refresh(); runCurrent()
        calls.clear()
        h.page.ask("thread", selection, "Retired question", 0, owner, AiCapabilities.Full)
        h.page.closeAskThread("thread", null, owner, AiCapabilities.Full)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(askUi(h.page).askPending.isEmpty())
        h.page.detach()
    }

    @Test
    fun aRetainedLineAskAndAQueuedCloseCheckTheLiveGateWithoutACompositionFrame() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val owner = assertNotNull(h.page.captureView())
        val calls = mutableListOf<String>()
        h.handler = { name, args -> calls += name; h.default(name, args) }
        var ready = false
        val port = ReviewActionReadiness({ ready })
        h.page.ask("thread", selection, "A retained question", 0, owner, AiCapabilities.Full, readiness = port)
        h.page.closeAskThread("thread", null, owner, AiCapabilities.Full, port)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(askUi(h.page).askPending.isEmpty())
        ready = true
        h.page.closeAskThread("thread", null, owner, AiCapabilities.Full, port)
        ready = false // The coroutine's next request has not reached transport yet.
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(owner, h.page.captureView())
        ready = true
        h.page.closeAskThread("thread", null, owner, AiCapabilities.Full, port)
        runCurrent()
        assertEquals(1, calls.count { it == "ask-thread-close" })
        h.page.detach()
    }

    @Test
    fun selectedLinesSendOnceAndARejectedStartCanRetryTheOriginalRpc() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val owner = assertNotNull(h.page.captureView())
        val delayed = CompletableDeferred<Unit>()
        val starts = mutableListOf<JsonObject>()
        val errors = mutableListOf<String>()
        var fail = true
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> {
                starts += args
                if (fail) { delayed.await(); h.serverError("Provider unavailable") }
                JsonPrimitive("ask-task")
            }
            "ai-task-wait" -> awaitCancellation()
            else -> h.default(name, args)
        } }
        h.page.ask("thread", selection, "  Why does this change?  ", 2, owner, AiCapabilities.Full, onStartError = errors::add)
        h.page.ask("thread", selection, "A repeated click", 2, owner, AiCapabilities.Full)
        runCurrent()
        assertEquals(1, starts.size)
        val args = starts.single()
        assertEquals("ask", args.getValue("kind").jsonPrimitive.content)
        assertEquals("pull-1", args.getValue("key").jsonPrimitive.content)
        assertEquals("thread", args.getValue("threadId").jsonPrimitive.content)
        assertEquals("Why does this change?", args.getValue("question").jsonPrimitive.content)
        val range = args.getValue("selection").jsonObject
        assertEquals("src/file.kt", range.getValue("path").jsonPrimitive.content)
        assertEquals("additions", range.getValue("side").jsonPrimitive.content)
        assertEquals("4", range.getValue("startLine").jsonPrimitive.content)
        assertEquals("7", range.getValue("endLine").jsonPrimitive.content)
        delayed.complete(Unit); runCurrent()
        assertEquals(listOf("Provider unavailable"), errors)
        assertTrue(askUi(h.page).askPending.isEmpty())
        fail = false
        h.page.ask("thread", selection, "Why does this change?", 2, owner, AiCapabilities.Full)
        runCurrent()
        assertEquals(2, starts.size)
        assertEquals("Why does this change?" to 2, askUi(h.page).askPending["thread"])
        h.page.detach()
    }

    @Test
    fun aLateStartCannotReloadOrPublishIntoTheSameHeadAfterAba() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val owner = assertNotNull(h.page.captureView())
        val delayed = CompletableDeferred<Unit>()
        var started = 0
        var failed = 0
        var reloads = 0
        h.handler = { name, args -> when (name) {
            "ai-task-start" -> { withContext(NonCancellable) { delayed.await() }; JsonPrimitive("old-task") }
            "review-state" -> { reloads++; h.default(name, args) }
            else -> h.default(name, args)
        } }
        h.page.ask("thread", selection, "Old question", 0, owner, AiCapabilities.Full, afterStart = { started++ }, onStartError = { failed++ })
        runCurrent()
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        assertTrue(askUi(h.page).askPending.isEmpty())
        reloads = 0
        delayed.complete(Unit); runCurrent()
        assertEquals(0, started)
        assertEquals(0, failed)
        assertEquals(0, reloads)
        assertTrue(askUi(h.page).askPending.isEmpty())
        h.page.detach()
    }

    @Test
    fun closingRunsOnceAndALateCloseCannotReloadTheReplacementView() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val owner = assertNotNull(h.page.captureView())
        val delayed = CompletableDeferred<Unit>()
        var closes = 0
        var reloads = 0
        h.handler = { name, args -> when (name) {
            "ask-thread-close" -> {
                closes++
                withContext(NonCancellable) { delayed.await() }
                h.default(name, args)
            }
            "review-state" -> { reloads++; h.default(name, args) }
            else -> h.default(name, args)
        } }
        h.page.closeAskThread("thread", null, owner, AiCapabilities.Full)
        h.page.closeAskThread("thread", null, owner, AiCapabilities.Full)
        runCurrent()
        assertEquals(1, closes)
        h.head = "head-b"; h.page.refresh(); runCurrent()
        askUi(h.page)
        reloads = 0
        delayed.complete(Unit); runCurrent()
        assertEquals(0, reloads)
        h.page.detach()
    }
}
