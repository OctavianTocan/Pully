package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.core.PSEUDO_PROBE
import com.octaviantocan.pulls.core.PseudoKind
import com.octaviantocan.pulls.ui.review.NoNodes
import com.octaviantocan.pulls.ui.review.Tok
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertNotNull

import com.octaviantocan.pulls.ui.review.DiffHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class AiCodeStateTest {
    @Test
    fun longSnippetsUseBoundedRequestsAndMissingRowsRemainRetryable() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = codeState(h.page)
        val key = SnippetKey(false, "kt", (1..401).joinToString("\n") { "line $it" })
        val calls = mutableListOf<List<String>>()
        var omitLast = true
        h.handler = { name, args -> if (name == "highlight") {
            val lines = args.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
            calls += lines
            response(if (omitLast) lines.dropLast(1) else lines)
        } else h.default(name, args) }
        state.load(key)
        assertEquals(listOf(400, 1), calls.map { it.size })
        assertEquals(401, state.tokens(key)?.size)
        assertNull(state.tokens(key)?.get(399))
        assertNull(state.tokens(key)?.get(400))
        omitLast = false
        state.load(key)
        assertEquals(listOf("line 400", "line 401"), calls.last())
        assertEquals("line 401", state.tokens(key)?.last()?.single()?.text)
        h.page.detach()
    }

    @Test
    fun privateSnippetTokensAreNeverSharedWithAnotherReviewOrHead() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = codeState(h.page)
        assertSame(state, codeState(h.page))
        val key = SnippetKey(false, "kt", "private code")
        h.handler = { name, args -> if (name == "highlight") response(listOf(key.code)) else h.default(name, args) }
        state.load(key)
        assertEquals(key.code, state.tokens(key)?.single()?.single()?.text)
        val other = DiffHarness(backgroundScope)
        other.page.load(); runCurrent()
        assertNull(codeState(other.page).tokens(key))
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        assertNull(state.tokens(key))
        h.page.detach(); other.page.detach()
    }

    @Test
    fun streamingPrefixesLeaveNoIdleCoordinationHoldingPrivateCode() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = codeState(h.page)
        h.handler = { name, args -> if (name == "highlight") response(args.getValue("lines").jsonArray.map { it.jsonPrimitive.content }) else h.default(name, args) }
        val code = "private streamed response ".repeat(4)
        for (end in 1..80) {
            state.load(SnippetKey(false, "kt", code.take(end)))
            assertEquals(0, state.pendingHighlights)
            assertEquals(0, state.retainedHighlightKeys)
        }
        // The original64-entry bound remains effective even though every prefix has a distinct key.
        assertNull(state.tokens(SnippetKey(false, "kt", code.take(1))))
        assertEquals(code.take(80), state.tokens(SnippetKey(false, "kt", code.take(80)))?.single()?.single()?.text)
        h.page.detach()
    }

    @Test
    fun cancellingAWaiterKeepsTheSharedRequestSerializedAndReleasesAllCoordination() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = codeState(h.page)
        val key = SnippetKey(false, "kt", "same private code")
        val answer = CompletableDeferred<JsonElement>()
        var calls = 0
        h.handler = { name, args -> if (name == "highlight") { calls++; answer.await() } else h.default(name, args) }
        val first = backgroundScope.launch { state.load(key) }
        runCurrent()
        val waiter = backgroundScope.launch { state.load(key) }
        runCurrent()
        assertEquals(1, calls)
        assertEquals(2, state.pendingHighlights)
        waiter.cancelAndJoin()
        assertEquals(1, state.pendingHighlights)
        answer.complete(response(listOf(key.code)))
        first.join()
        assertEquals(0, state.pendingHighlights)
        assertEquals(0, state.retainedHighlightKeys)
        state.load(key)
        assertEquals(1, calls)
        assertEquals(0, state.pendingHighlights)
        assertEquals(0, state.retainedHighlightKeys)
        h.page.detach()
    }

    @Test
    fun anEarlierHeadCannotRefillTheCurrentSnippetCache() = runTest {
        val h = DiffHarness(backgroundScope)
        h.page.load(); runCurrent()
        val state = codeState(h.page)
        val key = SnippetKey(false, "kt", "earlier code")
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { name, args -> if (name == "highlight") answer.await() else h.default(name, args) }
        val work = backgroundScope.launch { state.load(key) }
        runCurrent()
        h.head = "head-b"
        h.page.refresh(); runCurrent()
        assertNull(state.tokens(key))
        answer.complete(response(listOf(key.code)))
        work.join()
        assertNull(state.tokens(key))
        h.page.detach()
    }
}

private fun response(lines: List<String>): JsonElement = buildJsonObject {
    put("lines", JsonArray(lines.map { text -> JsonArray(listOf(buildJsonObject { put("text", text) })) }))
}

/** Runs against the actual Native Compose runtime; Android host stubs cannot execute android.os.Trace. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.assertCodeReloadsAcrossViewRevisions() {
    val h = DiffHarness(backgroundScope)
    h.page.load(); runCurrent()
    val requests = mutableListOf<List<String>>()
    h.handler = { name, args -> if (name == "highlight") {
        val lines = args.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
        requests += lines
        response(lines)
    } else h.default(name, args) }
    val key = SnippetKey(false, "kt", "same code on both heads")
    var tokens: List<List<Tok>?>? = null
    var colors: Map<PseudoKind, Color?>? = null
    val clock = BroadcastFrameClock()
    val recomposer = Recomposer(coroutineContext + clock)
    val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(NoNodes(), recomposer)
    suspend fun frame(nanos: Long) {
        Snapshot.sendApplyNotifications()
        runCurrent()
        clock.sendFrame(nanos)
        runCurrent()
    }
    try {
        composition.setContent {
            tokens = rememberSnippetTokens(h.page, key)
            colors = rememberPseudoColors(h.page, false)
        }
        runCurrent(); frame(1L)
        assertEquals(key.code, tokens?.single()?.single()?.text)
        assertNotNull(colors)
        assertEquals(1, requests.count { it == listOf(key.code) })
        assertEquals(1, requests.count { it == listOf(PSEUDO_PROBE) })

        // Native composition may observe only the final A: the existing full revision still changed twice.
        h.head = "head-b"; h.page.refresh(); runCurrent()
        h.head = "head-a"; h.page.refresh(); runCurrent()
        frame(2L); frame(3L)
        assertEquals(key.code, tokens?.single()?.single()?.text)
        assertNotNull(colors)
        assertEquals(2, requests.count { it == listOf(key.code) })
        assertEquals(2, requests.count { it == listOf(PSEUDO_PROBE) })
        frame(4L)
        assertEquals(4, requests.size)
    } finally {
        composition.dispose()
        recomposer.close()
        runner.cancel()
        h.page.detach()
    }
}
