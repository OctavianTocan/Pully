package com.octaviantocan.pulls.ui.home

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.RpcResponse
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.ui.settings.AccountObservationOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Exercises the mounted production read effects in the real Native Compose runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeReadEffectTest {
    @Test
    fun aResumedFirstPageDrainsTheNewTailForAnUnchangedSearch() = runTest {
        val reads = HomeObservations(false)
        val newFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var firstPages = 0
        fun page(number: Int, next: String? = null): RpcResponse {
            val cursor = next?.let { ",\"next\":\"$it\"" }.orEmpty()
            return RpcResponse(200, """{"result":{"items":[{"number":$number,"title":"Match $number"}],"totalCount":2$cursor}}""")
        }
        val repo = Repo({ RepoConfiguration("https://fixtures.pully.invalid") }, RpcTransport { request ->
            val call = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("repo-pulls", call.getValue("name").jsonPrimitive.content)
            val args = call.getValue("args").jsonObject
            val cursor = args["next"]?.jsonPrimitive?.content
            calls += cursor ?: "first"
            when (cursor) {
                "old-tail" -> page(2)
                "new-tail" -> page(99)
                else -> if (++firstPages == 1) page(1, "old-tail") else {
                    newFirst.await()
                    page(3, "new-tail")
                }
            }
        })
        val effects = object : HomeScreenEffects {
            override fun track(name: String, vararg props: Pair<String, Any?>) = Unit
            override fun error(name: String, message: String?) = Unit
            override fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) = Unit
        }
        val pages = FirstPages(object : RepoPageStorage {
            override suspend fun read(owner: String, name: String): JsonObject? = null
            override suspend fun write(owner: String, name: String, raw: JsonObject) = Unit
        }) { true }
        val store = RepoPullsStore(repo, "sample", "project", pages, { true }, effects)
        store.load(); store.loadAll()
        assertFalse(store.hasMore)
        assertEquals(listOf(1, 2), store.items.map { it.number })
        val query = mutableStateOf("match")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(HomeReadNoNodes(), recomposer)
        suspend fun frame(time: Long) { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(time); runCurrent() }
        try {
            composition.setContent {
                val selectedQuery = query.value
                HomeReadEffect(reads, store) { store.load() }
                HomeReadEffect(reads, selectedQuery, store.refreshes) { if (selectedQuery.isNotBlank()) store.loadAll() }
            }
            runCurrent(); frame(1L)
            assertEquals(listOf("first", "old-tail"), calls)
            reads.resume(); frame(2L)
            assertEquals(listOf("first", "old-tail", "first"), calls)
            assertFalse(store.hasMore) // The search observer finishes before the held new first page arrives.
            newFirst.complete(Unit); runCurrent(); frame(3L); frame(4L)
            assertEquals(listOf("first", "old-tail", "first", "new-tail"), calls)
            assertEquals(listOf(3, 99), store.items.map { it.number })
            assertFalse(store.hasMore)
        } finally {
            newFirst.complete(Unit); reads.close(); store.invalidate()
            composition.dispose(); recomposer.close(); runner.cancel()
        }
    }

    @Test
    fun verificationRestartsTheLatestDrawnFilterWithoutAnotherNativeEvent() = runTest {
        val reads = HomeObservations(false)
        val verification = CompletableDeferred<Boolean>()
        val owner = AccountObservationOwner(backgroundScope, { verification.await() }, reads::pause, reads::resume)
        val calls = mutableListOf<Pair<String, String>>()
        val repo = Repo({ RepoConfiguration("https://fixtures.pully.invalid", mapOf("X-Pully-Session" to "sample-owner")) }, RpcTransport { request ->
            assertEquals(mapOf("X-Pully-Session" to "sample-owner"), request.headers)
            val call = Json.parseToJsonElement(request.body).jsonObject
            val name = call.getValue("name").jsonPrimitive.content
            val state = call.getValue("args").jsonObject.getValue("state").jsonPrimitive.content
            calls += name to state
            assertEquals("my-pulls-cached", name)
            RpcResponse(200, """{"result":{"pulls":[],"fetchedAt":1000}}""")
        })
        val sync = MyPullsSync(
            cached = { state -> reads.read { repo.myPullsCached(state)?.asSnapshot() } },
            fresh = { state -> reads.read { repo.myPulls(state) } }, now = { 1000L },
        )
        val selected = mutableStateOf("open")
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(HomeReadNoNodes(), recomposer)
        suspend fun frame(time: Long) { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(time); runCurrent() }
        try {
            composition.setContent {
                val filter = selected.value
                HomeReadEffect(reads, sync, filter) { sync.select(filter) }
            }
            owner.resumed(); runCurrent(); frame(1L)
            assertFalse(reads.ready)
            assertTrue(calls.isEmpty())
            selected.value = "closed"; frame(2L)
            assertTrue(calls.isEmpty())
            verification.complete(true); runCurrent(); frame(3L); frame(4L)
            assertEquals(listOf("my-pulls-cached" to "closed"), calls)
            assertNotNull(sync.pulls)
            assertFalse(sync.updating)
            assertEquals(1000L, sync.fetchedAt)
            frame(5L)
            assertEquals(1, calls.size)
            owner.stopped()
            selected.value = "open"; frame(6L)
            assertEquals(1, calls.size)
            owner.resumed(); runCurrent(); frame(7L); frame(8L)
            assertEquals(listOf("my-pulls-cached" to "closed", "my-pulls-cached" to "open"), calls)
        } finally {
            owner.close(); reads.close()
            composition.dispose(); recomposer.close(); runner.cancel()
        }
    }

    @Test
    fun actualTaskPollingStopsBeforeAFrameAndOldRepliesCannotReplaceTheResumedList() = runTest {
        val reads = HomeObservations()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val repo = Repo({ RepoConfiguration("https://fixtures.pully.invalid") }, RpcTransport { request ->
            val call = Json.parseToJsonElement(request.body).jsonObject
            assertEquals("ai-task-list-all", call.getValue("name").jsonPrimitive.content)
            val number = ++calls
            if (number == 2) withContext(NonCancellable) { release.await() }
            val id = when (number) { 1 -> "first"; 2 -> "obsolete"; else -> "resumed" }
            RpcResponse(200, """{"result":[{"id":"$id","kind":"tour","key":"sample-76","status":"running"}]}""")
        })
        var tasks = emptyList<WorkTask>()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(HomeReadNoNodes(), recomposer)
        suspend fun frame(time: Long) { Snapshot.sendApplyNotifications(); runCurrent(); clock.sendFrame(time); runCurrent() }
        try {
            composition.setContent { tasks = rememberWork(repo, reads) }
            runCurrent(); frame(1L); frame(2L)
            assertEquals(listOf("first"), tasks.map { it.id })
            advanceTimeBy(4_000L); runCurrent()
            assertEquals(2, calls)
            reads.pause(); runCurrent()
            advanceTimeBy(60_000L); runCurrent()
            assertEquals(2, calls) // No composition frame has delivered the pause.
            assertEquals(listOf("first"), tasks.map { it.id })
            reads.resume(); frame(3L); frame(4L)
            assertEquals(3, calls)
            assertEquals(listOf("resumed"), tasks.map { it.id })
            release.complete(Unit); runCurrent(); frame(5L)
            assertEquals(listOf("resumed"), tasks.map { it.id })
            reads.close(); runCurrent()
            advanceTimeBy(60_000L); runCurrent()
            assertEquals(3, calls)
        } finally {
            release.complete(Unit); reads.close()
            composition.dispose(); recomposer.close(); runner.cancel()
        }
    }
}

private class HomeReadNoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
