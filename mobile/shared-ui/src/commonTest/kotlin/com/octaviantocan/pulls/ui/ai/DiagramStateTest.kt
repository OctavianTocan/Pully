package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DiagramStateTest {
    @Test
    fun diagramsShareOnlyTheCurrentReviewAndRetainABoundedNumberOfResults() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = diagramState(h.page)
        var calls = 0
        h.handler = { name, args -> if (name == "mermaid-svg") { calls++; svg("private drawing") } else h.default(name, args) }
        val key = DiagramKey(false, "graph TD; A-->B")
        assertEquals("private drawing", state.load(key)?.getOrNull())
        state.load(key)
        assertEquals(1, calls)
        val other = DiffHarness(backgroundScope)
        other.page.load(); runCurrent()
        assertNull(diagramState(other.page).cached(key))
        for (n in 1..32) state.load(DiagramKey(false, "graph TD; A-->B$n"))
        assertEquals(16, state.retainedResults)
        assertEquals(0, state.retainedRequests)
        assertNull(state.cached(key))
        h.head = "head-b"; h.page.refresh(); runCurrent()
        assertNull(state.cached(DiagramKey(false, "graph TD; A-->B32")))
        h.page.detach(); other.page.detach()
    }

    @Test
    fun largeDrawingRendersWithoutStayingInThePrivateCache() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val text = "x".repeat(2_000_001)
        h.handler = { name, args -> if (name == "mermaid-svg") svg(text) else h.default(name, args) }
        val state = diagramState(h.page)
        val key = DiagramKey(false, "large")
        assertEquals(text.length, state.load(key)?.getOrNull()?.length)
        assertNull(state.cached(key))
        assertEquals(0, state.retainedRequests)
        h.page.detach()
    }

    @Test
    fun queuedCancellationAndLateHeadResponsesLeaveNoPrivateRequestKeys() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val answer = CompletableDeferred<JsonElement>()
        var calls = 0
        h.handler = { name, args -> if (name == "mermaid-svg") { calls++; answer.await() } else h.default(name, args) }
        val state = diagramState(h.page)
        val key = DiagramKey(true, "graph TD; private-->drawing")
        var old: Result<String>? = null
        val first = backgroundScope.launch { old = state.load(key) }
        runCurrent()
        val waiter = backgroundScope.launch { state.load(key) }
        runCurrent()
        waiter.cancelAndJoin()
        assertEquals(1, calls)
        h.head = "head-b"; h.page.refresh(); runCurrent()
        assertNull(state.cached(key))
        answer.complete(svg("old private SVG"))
        first.join()
        assertNull(old)
        assertNull(state.cached(key))
        assertEquals(0, state.retainedRequests)
        h.page.detach()
    }

    @Test
    fun cancellationDoesNotBecomeACachedRenderingFailure() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "mermaid-svg") answer.await() else h.default(name, args) }
        val state = diagramState(h.page)
        val key = DiagramKey(false, "cancelled")
        val request = backgroundScope.launch { state.load(key) }
        runCurrent()
        request.cancelAndJoin()
        assertNull(state.cached(key))
        assertEquals(0, state.retainedRequests)
        h.handler = { name, args -> if (name == "mermaid-svg") svg("fresh") else h.default(name, args) }
        assertEquals("fresh", state.load(key)?.getOrNull())
        h.page.detach()
    }
}

private fun svg(text: String): JsonElement = buildJsonObject { put("svg", text) }
