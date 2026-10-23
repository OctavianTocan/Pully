package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.data.PullJev
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.home.ArchiveStore
import com.octaviantocan.pulls.ui.home.GuardedKeyValueStore
import com.octaviantocan.pulls.ui.home.HomeEffects
import com.octaviantocan.pulls.ui.home.HomeState
import com.octaviantocan.pulls.ui.home.InboxGrouping
import com.octaviantocan.pulls.ui.home.InboxLayout
import com.octaviantocan.pulls.ui.home.InboxSort
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.MyPullsSync
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import com.octaviantocan.pulls.ui.home.TourEntry
import com.octaviantocan.pulls.ui.home.TourIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeIdentityTest {
    @Test
    fun invalidatedStorageCannotExposeOrModifyItsOldOwnersPrivateRecords() {
        val disk = HomeMemory()
        var current = true
        val lease = GuardedKeyValueStore(disk) { current }
        val archive = ArchiveStore(lease)
        val tours = TourIndex(lease)
        archive.archive("a/b#1", "head-a")
        tours.upsert(tour())
        current = false
        assertNull(lease.get(ArchiveStore.STORAGE_KEY))
        assertNull(tours.get("a/b#1"))
        assertFalse(archive.isArchived("a/b#1"))
        archive.archive("a/b#2", "late")
        tours.remove("a/b#1")
        assertEquals(setOf("a/b#1"), ArchiveStore(disk).keys)
        assertNotNull(TourIndex(disk).get("a/b#1"))
        assertTrue(TourIndex(HomeMemory()).entries.isEmpty())
    }

    @Test
    fun ownerInvalidationClearsReactiveStateWithoutErasingTheOwnedDiskCopy() = runTest {
        val disk = HomeMemory()
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull()), 100) }, { error("unused") }, disk, now = { 100 })
        val owner = HomeState(sync, ArchiveStore(disk), TourIndex(disk), InboxLayout(disk))
        owner.sync.select("open")
        owner.archive.archive("a/b#1", "head-a")
        owner.tours.upsert(tour())
        owner.layout.updateSort(InboxSort.Number)
        owner.layout.updateGroupBy(InboxGrouping.Kind)
        owner.layout.toggle("a/b")
        owner.invalidate()
        assertNull(owner.sync.pulls)
        assertNull(owner.sync.fetchedAt)
        assertNull(owner.sync.error)
        assertFalse(owner.sync.updating)
        assertTrue(owner.archive.entries.isEmpty())
        assertTrue(owner.tours.entries.isEmpty())
        assertTrue(owner.layout.folded.isEmpty())
        assertEquals(InboxSort.Updated, owner.layout.sort)
        assertNotNull(disk.get(MyPullsSync.STORAGE_PREFIX + "open"))
        assertEquals(setOf("a/b#1"), ArchiveStore(disk).keys)
    }

    @Test
    fun sameSourceUndoCanPersistAfterTheListObserverHasDetached() = runTest {
        val disk = HomeMemory()
        val sync = MyPullsSync({ null }, { emptyList() }, disk)
        val archive = ArchiveStore(disk)
        archive.archive("a/b#1", "head-a")
        sync.invalidate()
        archive.restore("a/b#1")
        assertTrue(ArchiveStore(disk).keys.isEmpty())
    }

    @Test
    fun lateFreshDataAndErrorsCannotRestoreAnInvalidatedOwnersListOrDiskCopy() = runTest {
        val disk = HomeMemory()
        var current = true
        val delayed = CompletableDeferred<List<PullRow>>()
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull()), 0) }, {
            withContext(NonCancellable) { delayed.await() }
        }, GuardedKeyValueStore(disk) { current }, now = { 60_000 }, currentSource = { current })
        val request = backgroundScope.launch { sync.select("open") }
        runCurrent()
        val kept = disk.data.toMap()
        current = false
        sync.invalidate()
        delayed.complete(listOf(pull(2)))
        request.join()
        assertNull(sync.pulls)
        assertNull(sync.error)
        assertEquals(kept, disk.data)
    }

    @Test
    fun aLateCachedResponseCannotStartANewRequestForTheNextSource() = runTest {
        var current = true
        val delayed = CompletableDeferred<PullsSnapshot?>()
        var freshCalls = 0
        val sync = MyPullsSync({ delayed.await() }, { freshCalls++; listOf(pull(2)) }, currentSource = { current })
        val request = backgroundScope.launch { sync.select("open") }
        runCurrent()
        current = false
        sync.invalidate()
        delayed.complete(PullsSnapshot(listOf(pull()), 0))
        request.join()
        assertEquals(0, freshCalls)
        assertNull(sync.pulls)
    }

    @Test
    fun delayedJevClassificationCannotRefillAnInvalidatedOwnersList() = runTest {
        val delayed = CompletableDeferred<PullsSnapshot?>()
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull()), 100, jev = true) }, { error("unused") },
            now = { 100 }, peek = { _, _ -> delayed.await() })
        sync.select("open")
        sync.wantsJev = true
        val observer = backgroundScope.launch { sync.followJev() }
        runCurrent()
        sync.invalidate()
        delayed.complete(PullsSnapshot(listOf(pull().copy(jev = PullJev("fix", 1.0, 0.5))), 100, jev = true))
        observer.join()
        assertNull(sync.pulls)
        assertFalse(sync.jev)
        assertFalse(sync.sorting)
    }

    @Test
    fun invalidationDuringTheJevIntervalCannotStartClassificationAgainstANewSource() = runTest {
        var reads = 0
        val sync = MyPullsSync({ PullsSnapshot(listOf(pull()), 0, jev = true) }, { error("unused") },
            now = { testScheduler.currentTime }, peek = { _, _ ->
                reads++
                PullsSnapshot(listOf(pull()), 0, jev = true)
            })
        sync.select("open")
        sync.wantsJev = true
        val observer = backgroundScope.launch { sync.followJev(everyMs = 1_000, forMs = 10_000) }
        runCurrent()
        assertEquals(1, reads)
        sync.invalidate()
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        observer.join()
        assertEquals(1, reads)
        assertNull(sync.pulls)
    }

    @Test
    fun anUnknownTourSnapshotKeepsItsRowUntilAnAuthoritativeDoneResultArrives() = runTest {
        val disk = HomeMemory()
        val index = TourIndex(disk)
        index.upsert(tour().copy(writing = true, stops = 0, reached = 0))
        val before = disk.data.toMap()
        val unknown = WorkTask("github:a/b#1", "tour", "not-recognized", id = "task-1", startedAt = 100)
        index.reconcile(listOf(unknown), 200) { error("Unknown work has no authoritative result") }
        assertTrue(index.get("a/b#1")?.writing == true)
        assertEquals(before, disk.data)
        index.reconcile(listOf(unknown.copy(status = "done")), 300) { 4 }
        assertFalse(index.get("a/b#1")?.writing == true)
        assertEquals(4, index.get("a/b#1")?.stops)
    }

    @Test
    fun delayedTourResultsCannotRestoreRowsAfterAnOwnerChangeOrExplicitDismissal() = runTest {
        for (invalidate in listOf(false, true)) {
            val disk = HomeMemory()
            val index = TourIndex(disk)
            index.upsert(tour().copy(writing = true, stops = 0))
            val delayed = CompletableDeferred<Int?>()
            val work = WorkTask("github:a/b#1", "tour", "done", id = "task-1", startedAt = 100)
            val request = backgroundScope.launch { index.reconcile(listOf(work), 200) { delayed.await() } }
            runCurrent()
            if (invalidate) index.invalidate() else index.dismiss("a/b#1", listOf("task-1"))
            val kept = disk.data.toMap()
            delayed.complete(4)
            request.join()
            assertTrue(index.entries.isEmpty())
            assertEquals(kept, disk.data)
        }
    }

    @Test
    fun effectsKeepTheExistingActionShapeAndLoadErrorsStayRecoverable() = runTest {
        val actions = mutableListOf<Pair<String, String>>()
        val errors = mutableListOf<String?>()
        val effects = object : HomeEffects {
            override fun action(action: String, value: String) { actions += action to value }
            override fun loadError(message: String?) { errors += message }
        }
        val layout = InboxLayout(null, effects)
        layout.updateSort(InboxSort.Number)
        layout.updateSort(InboxSort.Number)
        layout.updateGroupBy(InboxGrouping.Kind)
        layout.toggle("private/repo")
        layout.toggle("private/repo")
        assertEquals(listOf("sort" to "number", "group" to "kind", "fold" to "repo", "unfold" to "repo"), actions)
        val sync = MyPullsSync({ null }, { error("Offline") }, effects = effects)
        sync.select("open")
        assertEquals(listOf<String?>("Offline"), errors)
        assertEquals("Offline", sync.error)
        assertFalse(sync.updating)
    }
}

private class HomeMemory : KeyValueStore {
    val data = mutableMapOf<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
}
private fun pull(number: Int = 1) = PullRow("a", "b", number, "Review", "dev", "", false, headSha = "head-a")
private fun tour() = TourEntry("a", "b", 1, "Review", 3, 1, "Start here", false, 100)
