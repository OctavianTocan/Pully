package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.ui.home.ArchiveStore
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.TourEntry
import com.octaviantocan.pulls.ui.home.TourIndex
import com.octaviantocan.pulls.ui.home.compactAge
import com.octaviantocan.pulls.ui.home.compactCount
import com.octaviantocan.pulls.ui.home.tourState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class InboxTestStore : KeyValueStore {
    val data = HashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
}

/** The inbox's three chips, the archive and the tour index. */
class InboxTest {
    private fun pull(
        number: Int,
        role: String = "owned",
        author: String = "devin",
        isDraft: Boolean = false,
        state: String = "open",
        repo: String = "app",
        title: String = "Fix the thing",
    ) = PullRow(owner = "acme", repo = repo, number = number, title = title, author = author, updatedAt = "2026-10-05T00:00:00Z", isDraft = isDraft, role = role, state = state)

    private val pulls = listOf(
        pull(1, role = "review-requested"),
        pull(2, role = "authored", author = "tavi"),
        pull(3, role = "authored", author = "tavi", isDraft = true),
        pull(4, role = "owned", isDraft = true),
        pull(5, role = "owned", state = "closed"),
        pull(6, role = "authored", author = "tavi", repo = "web", title = "Retry on 502"),
    )

    @Test
    fun waitingOnYouIsOpenAndReadyOrYours() {
        assertTrue(Triage.waitingOnYou(pull(1, role = "review-requested")))
        assertTrue(Triage.waitingOnYou(pull(2, role = "authored", isDraft = true)), "your own draft still waits on you")
        assertFalse(Triage.waitingOnYou(pull(3, role = "owned", isDraft = true)), "someone else's draft is not ready")
        assertFalse(Triage.waitingOnYou(pull(4, state = "closed")))
    }

    @Test
    fun chipsFilterAndCountTheSameList() {
        val inbox = Triage.inbox(pulls, "waiting", emptySet(), "")
        assertEquals(listOf(1, 2, 3, 6), inbox.pulls.map { it.number })
        assertEquals(mapOf("waiting" to 4, "authored" to 3, "all" to 6), inbox.counts)
        assertEquals(listOf(2, 3, 6), Triage.inbox(pulls, "authored", emptySet(), "").pulls.map { it.number })
        assertEquals(6, Triage.inbox(pulls, "all", emptySet(), "").pulls.size)
    }

    @Test
    fun archivedPullsLeaveWaitingAndAuthoredButStayUnderAll() {
        val archived = setOf(Triage.key(pulls[1]))
        val waiting = Triage.inbox(pulls, "waiting", archived, "")
        assertEquals(listOf(1, 3, 6), waiting.pulls.map { it.number })
        assertEquals(3, waiting.counts.getValue("waiting"))
        assertEquals(2, waiting.counts.getValue("authored"))
        assertEquals(6, waiting.counts.getValue("all"))
        assertTrue(Triage.inbox(pulls, "all", archived, "").pulls.any { it.number == 2 })
    }

    @Test
    fun searchNarrowsTheListAndEveryCount() {
        val inbox = Triage.inbox(pulls, "waiting", emptySet(), "retry")
        assertEquals(listOf(6), inbox.pulls.map { it.number })
        assertEquals(mapOf("waiting" to 1, "authored" to 1, "all" to 1), inbox.counts)
    }

    @Test
    fun theViewerIsWhoWroteMostOfYourOwnPulls() {
        assertEquals("tavi", Triage.viewerLogin(pulls))
        assertNull(Triage.viewerLogin(listOf(pull(1, role = "review-requested"))))
    }

    @Test
    fun groupLabelsDropTheOwnerUnlessTwoReposShareAName() {
        val groups = Triage.groupByRepo(listOf(pull(1, repo = "app"), pull(2, repo = "web")))
        assertEquals(mapOf("acme/app" to "app", "acme/web" to "web"), Triage.groupLabels(groups))
        val twins = Triage.groupByRepo(listOf(pull(1, repo = "app"), pull(2, repo = "app").copy(owner = "other")))
        assertEquals(mapOf("acme/app" to "acme/app", "other/app" to "other/app"), Triage.groupLabels(twins))
    }

    @Test
    fun archiveSurvivesARestartAndUndoRestores() {
        val storage = InboxTestStore()
        val store = ArchiveStore(storage)
        store.archive("acme/app#2")
        store.archive("acme/app#3")
        assertTrue(store.isArchived("acme/app#2"))
        assertEquals(setOf("acme/app#2", "acme/app#3"), ArchiveStore(storage).keys)
        store.restore("acme/app#2")
        assertEquals(setOf("acme/app#3"), ArchiveStore(storage).keys)
    }

    @Test
    fun archiveKeepsTheNewestEntriesOnly() {
        val store = ArchiveStore(InboxTestStore())
        repeat(ArchiveStore.LIMIT + 5) { store.archive("acme/app#$it") }
        assertEquals(ArchiveStore.LIMIT, store.keys.size)
        assertFalse(store.isArchived("acme/app#0"))
        assertTrue(store.isArchived("acme/app#${ArchiveStore.LIMIT + 4}"))
    }

    @Test
    fun anArchivedPullRequestComesBackWhenItsHeadMoves() {
        val storage = InboxTestStore()
        val store = ArchiveStore(storage)
        val two = pull(2).copy(headSha = "aaa")
        val three = pull(3).copy(headSha = "bbb")
        store.archive(Triage.key(two), two.headSha)
        store.archive(Triage.key(three), three.headSha)
        assertEquals(setOf("acme/app#2", "acme/app#3"), store.active(listOf(two, three)))
        // New commits on #2 bring it back; #3 and the unchanged archive file are untouched.
        assertEquals(setOf("acme/app#3"), ArchiveStore(storage).active(listOf(two.copy(headSha = "ccc"), three)))
        // Archiving it again records the new head.
        store.archive(Triage.key(two), "ccc")
        assertEquals(setOf("acme/app#2", "acme/app#3"), ArchiveStore(storage).active(listOf(two.copy(headSha = "ccc"), three)))
    }

    @Test
    fun anArchivedPullRequestWithAnUnknownHeadStaysArchived() {
        val store = ArchiveStore(InboxTestStore())
        store.archive("acme/app#2")
        store.archive("acme/app#3", "bbb")
        // No head recorded for #2; the list does not know #3's head.
        assertEquals(setOf("acme/app#2", "acme/app#3"), store.active(listOf(pull(2).copy(headSha = "zzz"), pull(3))))
    }

    @Test
    fun anArchiveWrittenBeforeHeadsWereKeptStillLoads() {
        val storage = InboxTestStore()
        storage.set(ArchiveStore.STORAGE_KEY, "acme/app#2\nacme/app#3")
        val store = ArchiveStore(storage)
        assertEquals(setOf("acme/app#2", "acme/app#3"), store.keys)
        assertEquals(setOf("acme/app#2", "acme/app#3"), store.active(listOf(pull(2).copy(headSha = "aaa"))))
    }

    @Test
    fun aTourReopensWhereYouLeftIt() {
        val storage = InboxTestStore()
        val index = TourIndex(storage)
        val entry = TourEntry("acme", "app", 1, "First", stops = 7, reached = 5, stopTitle = "Five", writing = false, updatedAt = 1, at = 2)
        index.upsert(entry)
        assertEquals(2, TourIndex(storage).get("acme/app#1")?.at)
        assertEquals(2, entry.resumeAt)
        // Never opened since `at` was kept (or out of range): the furthest stop.
        assertEquals(5, entry.copy(at = 0).resumeAt)
        assertEquals(5, entry.copy(at = 9).resumeAt)
    }

    @Test
    fun tourIndexKeepsOneEntryPerPullNewestFirstAndRoundTrips() {
        val storage = InboxTestStore()
        val index = TourIndex(storage)
        val older = TourEntry("acme", "app", 1, "First", stops = 7, reached = 3, stopTitle = "Three lines", writing = false, updatedAt = 100)
        val newer = TourEntry("acme", "web", 2, "Second", stops = 0, reached = 0, stopTitle = "", writing = true, updatedAt = 200)
        index.upsert(older)
        index.upsert(newer)
        index.upsert(older.copy(reached = 5, updatedAt = 300))
        assertEquals(listOf("acme/app#1", "acme/web#2"), index.entries.map { it.key })
        assertEquals(5, index.get("acme/app#1")?.reached)
        assertEquals(index.entries, TourIndex(storage).entries)
        index.remove("acme/web#2")
        assertEquals(listOf("acme/app#1"), TourIndex(storage).entries.map { it.key })
    }

    @Test
    fun aTourIsDoneOnceTheLastStopIsReached() {
        val entry = TourEntry("acme", "app", 1, "First", stops = 4, reached = 3, stopTitle = "", writing = false, updatedAt = 1)
        assertFalse(entry.done)
        assertTrue(entry.copy(reached = 4).done)
        assertFalse(entry.copy(reached = 4, writing = true).done)
        assertFalse(entry.copy(stops = 0, reached = 0).done)
    }

    @Test
    fun aTourLeftMidWriteStopsClaimingToBeWriting() {
        val entry = TourEntry("acme", "app", 1, "First", stops = 0, reached = 0, stopTitle = "", writing = true, updatedAt = 1_000)
        assertTrue(entry.writingAt(1_000 + 60_000))
        assertEquals("Writing", tourState(entry, 1_000 + 60_000))
        val later = 1_000 + TourEntry.WRITING_STALE_MS
        assertFalse(entry.writingAt(later))
        assertEquals("Not started", tourState(entry, later))
        assertFalse(entry.copy(writing = false).writingAt(1_000))
    }

    @Test
    fun aFinishedTourWhoseRewriteExpiredShowsAsDone() {
        val rewriting = TourEntry("acme", "app", 1, "First", stops = 4, reached = 4, stopTitle = "Last", writing = true, updatedAt = 1_000)
        val later = 1_000 + TourEntry.WRITING_STALE_MS
        assertFalse(rewriting.doneAt(1_000 + 60_000))
        assertTrue(rewriting.doneAt(later))
        assertEquals("Read", tourState(rewriting, later))
        assertEquals("Last", tourState(rewriting.copy(reached = 2), later))
    }

    @Test
    fun agesAndCountsStayShort() {
        val now = 1_000_000_000_000L
        fun ago(seconds: Long) = compactAge(now - seconds * 1000, now)
        assertEquals("now", ago(5))
        assertEquals("43m", ago(43 * 60))
        assertEquals("6h", ago(6 * 3600 + 59))
        assertEquals("2d", ago(2 * 86_400))
        assertEquals("3w", ago(21 * 86_400))
        assertEquals("5mo", ago(150 * 86_400))
        assertEquals("2y", ago(800 * 86_400))
        assertEquals("", compactAge(null, now))
        assertEquals("now", compactAge(now + 5_000, now), "a clock a little ahead reads as now")
        assertEquals(listOf("0", "640", "1.2k", "9.9k", "12k"), listOf(0, 640, 1234, 9999, 12_345).map(::compactCount))
    }

    @Test
    fun aDamagedStoreReadsAsEmpty() {
        val storage = InboxTestStore()
        storage.set(TourIndex.STORAGE_KEY, "{not json")
        assertEquals(emptyList(), TourIndex(storage).entries)
        assertEquals(emptySet(), ArchiveStore(InboxTestStore()).keys)
    }

    private val key = "github:acme/app#7"
    private fun tour(status: String, id: String, startedAt: Long = 1, title: String? = "Seven") = WorkTask(key, "tour", status, title, startedAt, id)

    @Test
    fun aTourTheServerStartedGetsItsRowWhileNoPageIsOpen() = runBlocking {
        val index = TourIndex(InboxTestStore())
        index.reconcile(listOf(tour("running", "t1")), now = 1_000) { null }
        val row = index.get("acme/app#7")
        assertEquals("Seven", row?.prTitle)
        assertTrue(row?.writing == true)
    }

    @Test
    fun aTourThatFinishedInTheBackgroundGetsItsStopCountAndStopsWriting() = runBlocking {
        val index = TourIndex(InboxTestStore())
        index.reconcile(listOf(tour("running", "t1")), now = 1_000) { null }
        index.reconcile(listOf(tour("done", "t1")), now = 2_000) { 5 }
        val row = index.get("acme/app#7")
        assertEquals(5, row?.stops)
        assertFalse(row?.writing == true)
    }

    @Test
    fun aTourThatEndedWithoutAResultLeavesNoEmptyRow() = runBlocking {
        val index = TourIndex(InboxTestStore())
        index.reconcile(listOf(tour("running", "t1")), now = 1_000) { null }
        index.reconcile(listOf(tour("failed", "t1")), now = 2_000) { null }
        assertNull(index.get("acme/app#7"))
    }

    @Test
    fun aRowTheReviewerRemovedDoesNotComeBackWhileTheServerStillListsItsTask() = runBlocking {
        val storage = InboxTestStore()
        val index = TourIndex(storage)
        index.reconcile(listOf(tour("done", "t1")), now = 1_000) { 4 }
        index.dismiss("acme/app#7", listOf("t1"))
        index.reconcile(listOf(tour("done", "t1")), now = 2_000) { 4 }
        assertNull(index.get("acme/app#7"))
        // Remembered across launches, and a newer tour of the same pull still counts.
        val again = TourIndex(storage)
        again.reconcile(listOf(tour("done", "t1")), now = 3_000) { 4 }
        assertNull(again.get("acme/app#7"))
        again.reconcile(listOf(tour("done", "t2", startedAt = 9)), now = 4_000) { 6 }
        assertEquals(6, again.get("acme/app#7")?.stops)
    }

    @Test
    fun aLongWriteIsKeptFromExpiringAndTasksOfOtherSourcesAreIgnored() = runBlocking {
        val index = TourIndex(InboxTestStore())
        index.reconcile(listOf(tour("running", "t1")), now = 1_000) { null }
        val later = 1_000 + TourEntry.WRITING_STALE_MS / 2
        index.reconcile(listOf(tour("running", "t1"), WorkTask("local:abc", "tour", "running", id = "x")), now = later) { null }
        assertTrue(index.get("acme/app#7")?.writingAt(later + 60_000) == true)
        assertEquals(1, index.entries.size)
    }
}
