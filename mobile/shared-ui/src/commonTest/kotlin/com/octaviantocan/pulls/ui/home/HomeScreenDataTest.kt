package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenDataTest {
    @Test
    fun pausingARepositoryReadRejectsItsUncooperativeReplyWithoutWritingTheDeviceCopy() = runTest {
        val h = DataHarness()
        val reads = HomeObservations()
        val release = CompletableDeferred<Unit>()
        h.handler = { _, _ -> withContext(NonCancellable) { release.await(); page(8) } }
        val store = h.data.repository("a", "b")
        val load = backgroundScope.launch { reads.read { store.load() } }
        runCurrent()
        assertEquals(listOf("repo-pulls"), h.calls)
        reads.pause()
        release.complete(Unit); runCurrent()
        assertTrue(load.isCancelled)
        assertTrue(store.items.isEmpty())
        assertTrue(h.disk.writes.isEmpty())
        assertFalse(store.isLoading)
        assertNull(store.error)
        reads.close(); h.data.invalidate()
    }

    @Test
    fun aPausedReadCannotPublishItsLateNetworkFailureAsARepositoryOrProfileError() = runTest {
        val h = DataHarness()
        val reads = HomeObservations()
        val release = CompletableDeferred<Unit>()
        h.handler = { _, _ -> withContext(NonCancellable) { release.await(); error("The fixture network is offline.") } }
        val store = h.data.repository("a", "b")
        var profileFailed = false
        val repository = backgroundScope.launch { reads.read { store.load() } }
        val profile = backgroundScope.launch { reads.read { if (!h.data.stats.refresh()) profileFailed = true } }
        runCurrent()
        assertEquals(setOf("repo-pulls", "github-stats"), h.calls.toSet())
        reads.pause()
        release.complete(Unit); runCurrent()
        assertTrue(repository.isCancelled)
        assertTrue(profile.isCancelled)
        assertNull(store.error)
        assertFalse(profileFailed)
        assertFalse(store.isLoading)
        assertTrue(h.memory.values.isEmpty())
        reads.close(); h.data.invalidate()
    }

    @Test
    fun aPausedInboxLoadCannotPublishItsLateFailureOrStartAFallbackFreshRequest() = runTest {
        val h = DataHarness()
        val reads = HomeObservations()
        val release = CompletableDeferred<Unit>()
        h.handler = { _, _ -> withContext(NonCancellable) { release.await(); error("The fixture network is offline.") } }
        val sync = MyPullsSync(
            cached = { state -> h.repo.myPullsCached(state)?.asSnapshot() },
            fresh = { state -> h.repo.myPulls(state) },
        )
        val load = backgroundScope.launch { reads.read { sync.select("closed") } }
        runCurrent()
        assertEquals(listOf("my-pulls-cached"), h.calls)
        reads.pause()
        release.complete(Unit); runCurrent()
        assertTrue(load.isCancelled)
        assertNull(sync.error)
        assertNull(sync.pulls)
        assertFalse(sync.updating)
        assertEquals(listOf("my-pulls-cached"), h.calls)
        reads.close(); sync.invalidate(); h.data.invalidate()
    }

    @Test
    fun repositoryShowsDeviceRowsWhileTheServerAnswersThenKeepsItsNewFirstPage() = runTest {
        val h = DataHarness()
        h.disk.kept = page(1)
        val answer = CompletableDeferred<JsonElement>()
        h.handler = { _, _ -> answer.await() }
        val store = h.data.repository("a", "b")
        val load = launch { store.load() }
        runCurrent()
        assertEquals(listOf(1), store.items.map { it.number })
        assertFalse(store.isLoading)
        answer.complete(page(2)); load.join()
        assertEquals(listOf(2), store.items.map { it.number })
        assertEquals(listOf(2), h.pages.get("a", "b")?.items?.map { it.number })
        assertEquals(page(2), h.disk.kept)
    }

    @Test
    fun refreshingDuringPaginationRejectsTheOldTailAndAllowsTheNewCursor() = runTest {
        val h = DataHarness()
        val oldTail = CompletableDeferred<JsonElement>()
        var firstPages = 0
        h.handler = { _, args ->
            when (args["next"]?.jsonPrimitive?.content) {
                "old-tail" -> oldTail.await()
                "new-tail" -> page(11)
                else -> if (++firstPages == 1) page(1, "old-tail") else page(10, "new-tail")
            }
        }
        val store = h.data.repository("a", "b")
        store.load()
        val old = launch { store.loadMore() }; runCurrent()
        assertTrue(store.isLoadingMore)
        store.refresh()
        assertFalse(store.isLoadingMore)
        store.loadMore()
        oldTail.complete(page(2)); old.join()
        assertEquals(listOf(10, 11), store.items.map { it.number })
        assertFalse(store.hasMore)
    }

    @Test
    fun anOlderFirstPageCannotReplaceTheNewerQuietRefreshOrRewriteItsCopy() = runTest {
        val h = DataHarness()
        val old = CompletableDeferred<JsonElement>()
        var calls = 0
        h.handler = { _, _ -> if (++calls == 1) old.await() else page(3) }
        val store = h.data.repository("a", "b")
        val load = launch { store.load() }; runCurrent()
        store.refresh()
        old.complete(page(2)); load.join()
        assertEquals(listOf(3), store.items.map { it.number })
        assertEquals(listOf(page(3)), h.disk.writes)
        assertFalse(store.isLoading)
    }

    @Test
    fun aSourceInvalidatedDuringDeviceReadStartsNoRequestAndRestoresNoRows() = runTest {
        val h = DataHarness()
        val disk = CompletableDeferred<JsonObject?>()
        h.disk.reader = { disk.await() }
        val store = h.data.repository("a", "b")
        val load = launch { store.load() }; runCurrent()
        h.valid = false; h.data.invalidate()
        disk.complete(page(1)); load.join()
        assertTrue(store.items.isEmpty())
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun viewedMetadataRemainsIndependentWhenTheFirstPageLeavesItsDeviceRead() = runTest {
        val h = DataHarness()
        val disk = CompletableDeferred<JsonObject?>()
        val viewed = CompletableDeferred<JsonElement>()
        h.disk.reader = { disk.await() }
        h.handler = { name, _ -> if (name == "repo-viewed") viewed.await() else page(2) }
        val store = h.data.repository("a", "b")
        val firstPage = launch { store.load() }; runCurrent()
        val metadata = launch { store.loadViewed() }; runCurrent()
        disk.complete(page(1)); firstPage.join()
        viewed.complete(viewed(2, 20)); metadata.join()
        assertEquals(listOf(2), store.items.map { it.number })
        assertEquals(Viewed(20, 3, 4, true), store.viewed[2])
    }

    @Test
    fun newerViewedAnswersWinAndAnInvalidatedRouteCannotAdoptTheLastAnswer() = runTest {
        val h = DataHarness()
        var answer = CompletableDeferred<JsonElement>()
        h.handler = { _, _ -> answer.await() }
        val store = h.data.repository("a", "b")
        val first = launch { store.loadViewed() }; runCurrent()
        val old = answer
        answer = CompletableDeferred()
        val second = launch { store.loadViewed() }; runCurrent()
        answer.complete(viewed(1, 30)); second.join()
        old.complete(viewed(1, 10)); first.join()
        assertEquals(30, store.viewed[1]?.additions)
        answer = CompletableDeferred()
        val late = launch { store.loadViewed() }; runCurrent()
        h.data.invalidate()
        answer.complete(viewed(1, 40)); late.join()
        assertTrue(store.viewed.isEmpty())
    }

    @Test
    fun aBrokenOrUnwritableDeviceCopyNeverHidesTheSuccessfulServerList() = runTest {
        val h = DataHarness()
        h.disk.reader = { error("unreadable device copy") }
        h.disk.failWrites = true
        h.handler = { _, _ -> page(7) }
        val store = h.data.repository("a", "b")
        store.load()
        assertEquals(listOf(7), store.items.map { it.number })
        assertNull(store.error)
    }

    @Test
    fun boundedFirstPagesRetainTheRecentlyOpenedRepositoryAndClearWithTheirOwner() {
        val h = DataHarness()
        (1..FirstPages.LIMIT).forEach { h.pages.put("a", "$it", FirstPages.Page(emptyList(), it, null)) }
        assertNotNull(h.pages.get("a", "1"))
        h.pages.put("a", "13", FirstPages.Page(emptyList(), 13, null))
        assertNotNull(h.pages.get("a", "1"))
        assertNull(h.pages.get("a", "2"))
        h.data.invalidate()
        assertNull(h.pages.get("a", "1"))
    }

    @Test
    fun aPostedRefreshThatPredatesDeletionCannotBringTheDeletedRowBack() = runTest {
        val h = DataHarness()
        h.memory.values["rows"] = "[${posted(1)},${posted(2)}]"
        val store = PostedDiffsStore(h.repo, h.memory, { true }, { h.valid })
        val old = CompletableDeferred<JsonElement>()
        h.handler = { name, _ -> if (name == "posted-list") old.await() else JsonNull }
        val refresh = launch { store.refresh() }; runCurrent()
        store.delete(store.rows.last())
        old.complete(JsonArray(listOf(posted(1), posted(2)))); refresh.join()
        assertEquals(listOf("1"), store.rows.map { it.id })
        assertEquals(listOf("1"), (Json.parseToJsonElement(h.memory.values.getValue("rows")) as JsonArray).map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test
    fun signedOutPostedCopiesAreNotAdoptedAndDoNotStartAnAccountRequest() = runTest {
        val h = DataHarness()
        h.memory.values["rows"] = "[${posted(1)}]"
        val store = PostedDiffsStore(h.repo, h.memory, { false }, { h.valid })
        assertTrue(store.rows.isEmpty())
        store.refresh()
        assertTrue(store.rows.isEmpty())
        assertTrue(h.calls.isEmpty())
    }

    @Test
    fun latePostedAndProfileAnswersCannotRefillAnInvalidatedOwnerOrItsStorage() = runTest {
        val h = DataHarness()
        val postedAnswer = CompletableDeferred<JsonElement>()
        val stats = CompletableDeferred<JsonElement>()
        h.handler = { name, _ -> if (name == "posted-list") postedAnswer.await() else stats.await() }
        val posts = launch { h.data.posted.refresh() }
        val numbers = launch { h.data.stats.refresh() }
        runCurrent()
        h.valid = false; h.data.invalidate()
        postedAnswer.complete(JsonArray(listOf(posted(1)))); stats.complete(calendar())
        posts.join(); numbers.join()
        assertTrue(h.data.posted.rows.isEmpty())
        assertNull(h.data.stats.last)
        assertTrue(h.memory.values.isEmpty())
    }

    @Test
    fun changingSourceEpochClearsEveryLiveRepositoryAndPrivateMemorySlice() = runTest {
        val h = DataHarness()
        val owner = HomeScreenDataOwner()
        owner.bind(h.repo.source(), 0) { h.data }
        val route = h.data.repository("a", "b")
        h.handler = { _, _ -> page(1) }; route.load()
        val next = DataHarness(generation = 1)
        assertSame(next.data, owner.bind(next.repo.source(), 1) { next.data })
        assertTrue(route.items.isEmpty())
        assertFalse(h.data.current)
        assertNull(h.pages.get("a", "b"))
    }

    @Test
    fun navigationRetainsPrivateInputButANewAuthorizationOwnerClearsIt() {
        val first = DataHarness()
        val ui = HomeUi("all")
        ui.bindSource(first.data)
        ui.search = "private review question"
        ui.askOpen = true
        ui.bindSource(first.data)
        assertEquals("private review question", ui.search)
        assertTrue(ui.askOpen)
        ui.bindSource(DataHarness(generation = 1).data)
        assertEquals("", ui.search)
        assertFalse(ui.askOpen)
        assertEquals("all", ui.filter)
    }


    @Test
    fun sourceOwnerReplacementRevokesSizeWritersWithoutErasingTheNewOwnersCounts() {
        val owner = HomeScreenDataOwner()
        val first = DataHarness()
        owner.bind(first.repo.source(), 0) { first.data }
        first.data.sizes.put("a", "b", 1, 10, 2, 1)
        assertEquals(10, first.data.sizes.get("a", "b", 1)?.additions)
        val next = DataHarness(generation = 1)
        owner.bind(next.repo.source(), 1) { next.data }
        next.data.sizes.put("a", "b", 1, 30, 4, 2)
        first.data.sizes.put("a", "b", 1, 999, 999, 999)
        first.data.invalidate()
        assertNull(first.data.sizes.get("a", "b", 1))
        assertEquals(30, next.data.sizes.get("a", "b", 1)?.additions)
        owner.clear()
        assertNull(next.data.sizes.get("a", "b", 1))
    }

    @Test
    fun anInvalidSavedServerProducesARetryablePageErrorWithoutADeviceCopyOrNativeRequest() = runTest {
        val h = DataHarness(server = "not a server")
        val store = h.data.repository("a", "b")
        store.load()
        assertNotNull(store.error)
        assertTrue(store.items.isEmpty())
        assertFalse(store.isLoading)
        assertTrue(h.calls.isEmpty())
    }
}

private class DataHarness(server: String = "https://pully.example", generation: Long = 0) {
    var valid = true
    val memory = ScreenMemory { valid }
    val calls = mutableListOf<String>()
    var handler: suspend (String, JsonObject) -> JsonElement = { _, _ -> JsonNull }
    val repo = Repo({ RepoConfiguration(server, generation = generation) }, RpcTransport { request ->
        val call = Json.parseToJsonElement(request.body).jsonObject
        val name = call.getValue("name").jsonPrimitive.content
        calls += name
        val result = handler(name, call["args"] as? JsonObject ?: JsonObject(emptyMap()))
        RpcResponse(200, buildJsonObject { put("result", result) }.toString())
    })
    val disk = ScreenPages()
    val pages = FirstPages(disk) { valid }
    private val sizes = PullSizes.source("test-home-source:${++testHomeSource}")
    private val effects = object : HomeScreenEffects {
        override fun track(name: String, vararg props: Pair<String, Any?>) = Unit
        override fun error(name: String, message: String?) = Unit
        override fun size(owner: String, repo: String, number: Int, additions: Int?, deletions: Int?, files: Int?) = sizes.put(owner, repo, number, additions, deletions, files)
    }
    val data = HomeScreenData(repo, pages, PostedDiffsStore(repo, memory, { true }, { valid }), GithubStatsStore(repo, memory) { valid }, { valid }, effects, sizes)
}

private class ScreenMemory(private val valid: () -> Boolean) : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override val current get() = valid()
    override fun get(key: String) = if (current) values[key] else null
    override fun set(key: String, value: String) { if (current) values[key] = value }
}

private class ScreenPages : RepoPageStorage {
    var kept: JsonObject? = null
    var reader: suspend () -> JsonObject? = { kept }
    var failWrites = false
    val writes = mutableListOf<JsonObject>()
    override suspend fun read(owner: String, name: String) = reader()
    override suspend fun write(owner: String, name: String, raw: JsonObject) {
        if (failWrites) error("device full")
        kept = raw; writes += raw
    }
}

private val PAGE_TIME = kotlin.time.Clock.System.now().toEpochMilliseconds()

private fun page(number: Int, cursor: String? = null): JsonObject = buildJsonObject {
    put("items", JsonArray(listOf(buildJsonObject { put("number", number); put("title", "Pull $number"); put("state", "open") })))
    put("totalCount", 20); cursor?.let { put("next", it) }; put("cachedAt", PAGE_TIME)
}
private fun posted(id: Int) = buildJsonObject {
    put("id", "$id"); put("title", "Posted $id"); put("author", "Agent"); put("version", 1); put("updatedAt", "2026-10-21T10:00:00Z")
    put("files", 1); put("additions", 2); put("deletions", 0)
}
private fun calendar() = Json.parseToJsonElement("""{"login":"octo","firstDay":"2024-02-28","days":[1,2,3],"total":6}""")

private var testHomeSource = 0
private fun viewed(number: Int, additions: Int) = JsonArray(listOf(buildJsonObject {
    put("number", number); put("additions", additions); put("deletions", 3); put("groups", 4); put("hasAiResult", true)
}))

/** Shared fake source; real composition/restoration runs on the Native host, without Android framework stubs. */
internal fun homeScreenDataFixture(generation: Long = 0): HomeScreenData = DataHarness(generation = generation).data
