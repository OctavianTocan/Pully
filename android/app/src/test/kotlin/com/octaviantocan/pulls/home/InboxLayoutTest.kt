package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.ui.home.InboxLayout
import com.octaviantocan.pulls.ui.home.InboxSort
import com.octaviantocan.pulls.ui.home.KeyValueStore
import com.octaviantocan.pulls.ui.home.Pinned
import com.octaviantocan.pulls.ui.home.groupKey
import com.octaviantocan.pulls.ui.home.groupOfKey
import com.octaviantocan.pulls.ui.home.pinnedGroup
import com.octaviantocan.pulls.ui.home.cutUnderPinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class LayoutTestStore : KeyValueStore {
    val data = HashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
}

/** The Inbox's sort, its folded repositories, and which repository heading stays pinned while the list scrolls. */
class InboxLayoutTest {
    private fun pull(number: Int, repo: String = "app", updatedAtMs: Long? = null, additions: Int? = null, deletions: Int? = null) =
        PullRow(owner = "acme", repo = repo, number = number, title = "Change $number", author = "devin", updatedAt = "", isDraft = false, updatedAtMs = updatedAtMs, additions = additions, deletions = deletions)

    private val pulls = listOf(
        pull(10, repo = "api", updatedAtMs = 300, additions = 5, deletions = 5),
        pull(42, repo = "app", updatedAtMs = 100, additions = 900, deletions = 100),
        pull(7, repo = "web", updatedAtMs = null, additions = null, deletions = null),
        pull(23, repo = "app", updatedAtMs = 200, additions = 1, deletions = 0),
    )

    @Test fun sortsByUpdatedNewestFirstWithUnknownTimesLast() {
        assertEquals(listOf(10, 23, 42, 7), InboxSort.Updated.apply(pulls).map { it.number })
        assertEquals(listOf(42, 23, 10, 7), InboxSort.LeastUpdated.apply(pulls).map { it.number })
    }

    @Test fun sortsByNumberAndBySize() {
        assertEquals(listOf(42, 23, 10, 7), InboxSort.Number.apply(pulls).map { it.number })
        assertEquals(listOf(42, 10, 23, 7), InboxSort.Size.apply(pulls).map { it.number })
    }

    @Test fun repositoriesFollowTheirFirstRowAfterASort() {
        val byNumber = Triage.groupByRepo(InboxSort.Number.apply(pulls)).map { it.name }
        assertEquals("acme/app", byNumber.first())
        assertEquals("acme/web", byNumber.last())
    }

    @Test fun anUnknownSavedSortFallsBackToUpdated() {
        assertEquals(InboxSort.Updated, InboxSort.of(null))
        assertEquals(InboxSort.Updated, InboxSort.of("nonsense"))
        assertEquals(InboxSort.Number, InboxSort.of("number"))
    }

    @Test fun theSortAndTheFoldsSurviveARestart() {
        val store = LayoutTestStore()
        val layout = InboxLayout(store)
        assertEquals(InboxSort.Updated, layout.sort)
        layout.updateSort(InboxSort.Number)
        layout.toggle("acme/app")
        layout.toggle("acme/web")
        layout.toggle("acme/web")

        val again = InboxLayout(store)
        assertEquals(InboxSort.Number, again.sort)
        assertTrue(again.isFolded("acme/app"))
        assertFalse(again.isFolded("acme/web"))
    }

    @Test fun aLayoutWithoutStorageStillWorks() {
        val layout = InboxLayout(null)
        layout.toggle("acme/app")
        assertTrue(layout.isFolded("acme/app"))
    }

    @Test fun headingKeysRoundTrip() {
        assertEquals("acme/app", groupOfKey(groupKey("acme/app")))
        assertNull(groupOfKey("acme/app#4"))
        assertNull(groupOfKey(4))
    }

    private val groups = mapOf("acme/app#1" to "acme/app", "acme/app#2" to "acme/app", "acme/web#3" to "acme/web")
    private fun pinned(vararg visible: Pair<Any, Int>, height: Int = 40) = pinnedGroup(visible.toList(), height) { groups[it] }

    @Test fun aHeadingHalfUnderThePinnedOneSettlesUnderOrBelowIt() {
        // More than half under the pinned heading: on under it, where it pins; less: back to just below it (K19, L2).
        assertEquals(10, cutUnderPinned(listOf(Triple(groupKey("a"), -30, 40), Triple(groupKey("b"), 10, 40)), 40))
        assertEquals(-10, cutUnderPinned(listOf(Triple(groupKey("a"), -10, 40), Triple(groupKey("b"), 30, 40)), 40))
        assertEquals(-20, cutUnderPinned(listOf(Triple("a#1", -50, 60), Triple(groupKey("b"), 20, 40)), 40))
    }

    @Test fun nothingSettlesWithoutAPinnedHeadingOrACutOne() {
        assertNull(cutUnderPinned(listOf(Triple(groupKey("a"), 0, 40), Triple(groupKey("b"), 40, 40)), 40))
        assertNull(cutUnderPinned(listOf(Triple(groupKey("a"), -10, 40), Triple("a#1", 30, 60)), 40))
        assertNull(cutUnderPinned(listOf(Triple(groupKey("a"), -10, 40), Triple(groupKey("b"), 30, 40)), 0))
        assertNull(cutUnderPinned(emptyList(), 40))
    }

    @Test fun nothingIsPinnedWhileAHeadingIsInView() {
        assertNull(pinned(groupKey("acme/app") to 0, "acme/app#1" to 40))
        assertNull(pinned("update" to 0, groupKey("acme/app") to 60))
        assertNull(pinned())
    }

    @Test fun theRowsRepositoryIsPinnedOnceItsHeadingScrollsAway() {
        assertEquals(Pinned("acme/app", 0), pinned(groupKey("acme/app") to -10, "acme/app#1" to 30, "acme/app#2" to 90))
        assertEquals(Pinned("acme/app", 0), pinned("acme/app#2" to -20, groupKey("acme/web") to 200, "acme/web#3" to 240))
    }

    @Test fun theNextHeadingSlidesUnderThePinnedOne() {
        // The pinned heading stays whole while the next one arrives under it (L2).
        assertEquals(Pinned("acme/app", 0), pinned("acme/app#2" to -20, groupKey("acme/web") to 25, "acme/web#3" to 65))
        assertEquals(Pinned("acme/app", 0), pinned(groupKey("acme/app") to -20, groupKey("acme/web") to 20))
    }
}
