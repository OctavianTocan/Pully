package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.home.GhLabel
import com.octaviantocan.pulls.ui.home.GhUser
import com.octaviantocan.pulls.ui.home.HomeLink
import com.octaviantocan.pulls.ui.home.HomeLinks
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.LabelStyle
import com.octaviantocan.pulls.ui.home.MyPullsSync
import com.octaviantocan.pulls.ui.home.PullListQuery
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import com.octaviantocan.pulls.ui.home.RepoPull
import com.octaviantocan.pulls.ui.home.TimeUnit
import com.octaviantocan.pulls.ui.home.asRepoPullsPage
import com.octaviantocan.pulls.ui.home.asSnapshot
import com.octaviantocan.pulls.ui.home.relativeTime
import com.octaviantocan.pulls.ui.home.inShownRepos
import com.octaviantocan.pulls.ui.home.repoChoices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MyPullsSyncTest {
    private fun pull(n: Int) = PullRow("acme", "app", n, "PR $n", "octo", "2026-10-01T00:00:00Z", false, role = "authored")

    private class Memory(initial: Map<String, String> = emptyMap()) : KeyValueStore {
        val items = initial.toMutableMap()
        override fun get(key: String) = items[key]
        override fun set(key: String, value: String) { items[key] = value }
    }

    @Test
    fun showsTheDeviceCopyThenTheServerCacheThenTheFreshList() = runTest {
        val seed = MyPullsSync({ null }, { listOf(pull(1)) }, Memory(), now = { 100 })
        val storage = Memory()
        // Seed storage through the same writer the screen uses.
        val writer = MyPullsSync({ PullsSnapshot(listOf(pull(1)), 100) }, { error("unused") }, storage, now = { 100 })
        writer.select("open")
        assertEquals(listOf(1), writer.pulls?.map { it.number })
        assertTrue(seed.pulls == null)

        val cached = CompletableDeferred<PullsSnapshot?>()
        val fresh = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync({ cached.await() }, { fresh.await() }, storage, now = { 1_000_000 })
        val job = async { sync.select("open") }
        yield()
        assertEquals(listOf(1), sync.pulls?.map { it.number })
        assertTrue(sync.updating)
        cached.complete(PullsSnapshot(listOf(pull(2)), 200, refreshing = true))
        yield()
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        fresh.complete(listOf(pull(3)))
        job.await()
        assertFalse(sync.updating)
        assertEquals(listOf(3), sync.pulls?.map { it.number })
        assertEquals(1_000_000L, sync.fetchedAt)
        assertTrue(storage.items.getValue(MyPullsSync.STORAGE_PREFIX + "open").contains("\"number\":3"))
    }

    @Test
    fun aFirstLoadShowsTheRowsFoundSoFarThenTheWholeList() = runTest {
        val storage = Memory()
        var server: PullsSnapshot? = null
        val fresh = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync({ null }, { fresh.await() }, storage, now = { 1_000 }, peek = { _, classify ->
            assertFalse(classify)
            server
        })
        val job = async { sync.select("open") }
        runCurrent()
        assertNull(sync.pulls)
        server = PullsSnapshot(listOf(pull(1)), 900, refreshing = true, partial = true)
        advanceTimeBy(1_600)
        assertEquals(listOf(1), sync.pulls?.map { it.number })
        assertTrue(sync.partial)
        assertNull(sync.fetchedAt)
        server = PullsSnapshot(listOf(pull(1), pull(2)), 950, refreshing = true, partial = true)
        advanceTimeBy(1_500)
        assertEquals(listOf(1, 2), sync.pulls?.map { it.number })
        // Rows so far are never the device copy.
        assertTrue(storage.items.isEmpty())
        fresh.complete(listOf(pull(1), pull(2), pull(3)))
        job.await()
        assertFalse(sync.partial)
        assertFalse(sync.updating)
        assertEquals(listOf(1, 2, 3), sync.pulls?.map { it.number })
        assertTrue(storage.items.getValue(MyPullsSync.STORAGE_PREFIX + "open").contains("\"number\":3"))
    }

    @Test
    fun rowsSoFarNeverStandInForAWholeList() = runTest {
        val storage = Memory()
        MyPullsSync({ PullsSnapshot(listOf(pull(1), pull(2)), 100) }, { error("unused") }, storage, now = { 100 }).select("open")
        var peeks = 0
        val fresh = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync(
            { PullsSnapshot(listOf(pull(7)), 1_000, refreshing = true, partial = true) }, { fresh.await() }, storage,
            now = { 1_000_000 }, peek = { _, _ -> peeks++; null },
        )
        val job = async { sync.select("open") }
        advanceTimeBy(5_000)
        assertEquals(listOf(1, 2), sync.pulls?.map { it.number })
        assertFalse(sync.partial)
        assertEquals(0, peeks)
        fresh.complete(listOf(pull(3)))
        job.await()
        assertEquals(listOf(3), sync.pulls?.map { it.number })
    }

    @Test
    fun aFailedFirstLoadDropsItsRowsSoFarForTheFailureNote() = runTest {
        val fresh = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync(
            { PullsSnapshot(listOf(pull(1)), 900, refreshing = true, partial = true) }, { fresh.await() },
            now = { 1_000 }, peek = { _, _ -> null },
        )
        val job = async { sync.select("open") }
        runCurrent()
        assertEquals(listOf(1), sync.pulls?.map { it.number })
        assertTrue(sync.partial)
        fresh.completeExceptionally(IllegalStateException("The server request timed out."))
        job.await()
        assertNull(sync.pulls)
        assertFalse(sync.partial)
        assertEquals("The server request timed out.", sync.error)
    }

    @Test
    fun trustsARecentServerCopyUntilAskedToReload() = runTest {
        var freshCalls = 0
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull(2)), 990) }, { freshCalls++; listOf(pull(9)) }, now = { 1000 })
        sync.select("open")
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        assertEquals(0, freshCalls)
        sync.reload()
        assertEquals(1, freshCalls)
        assertEquals(listOf(9), sync.pulls?.map { it.number })
    }

    @Test
    fun keepsTheListOnScreenWhenAnUpdateFails() = runTest {
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull(2)), 0) }, { error("rate limited") }, now = { 60_000 })
        sync.select("open")
        assertFalse(sync.updating)
        assertEquals(listOf(2), sync.pulls?.map { it.number })
        assertEquals("rate limited", sync.error)
    }

    @Test
    fun dropsAnswersForAStateThatIsNoLongerSelected() = runTest {
        val slow = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync({ null }, { state -> if (state == "open") slow.await() else listOf(pull(5)) })
        val first = async { sync.select("open") }
        yield()
        sync.select("closed")
        assertEquals(listOf(5), sync.pulls?.map { it.number })
        slow.complete(listOf(pull(1)))
        first.await()
        assertEquals(listOf(5), sync.pulls?.map { it.number })
        assertFalse(sync.updating)
    }

    @Test
    fun readsTheServerSnapshot() {
        val snap = Json.parseToJsonElement("""{"pulls":[{"owner":"a","repo":"b","number":1,"title":"t","author":"x","isDraft":false,"updatedAt":"2026-10-11T00:00:00Z","labels":[],"state":"open","role":"owned"}],"fetchedAt":1791743511776,"refreshing":true}""").jsonObject.asSnapshot()!!
        assertEquals(1791743511776, snap.fetchedAt)
        assertTrue(snap.refreshing)
        assertEquals(1, snap.pulls.size)
    }
}

/** Port of `pull-request-list-query.test.ts`. */
