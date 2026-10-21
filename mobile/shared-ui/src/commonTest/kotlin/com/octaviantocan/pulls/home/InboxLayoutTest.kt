package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.ui.home.InboxLayout
import com.octaviantocan.pulls.ui.home.InboxSort
import com.octaviantocan.pulls.ui.home.KeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

private class LayoutTestStore : KeyValueStore {
    val data = HashMap<String, String>()
    override fun get(key: String): String? = data[key]
    override fun set(key: String, value: String) { data[key] = value }
}

/** The Inbox's sort, its folded repositories, and which repository heading stays pinned while the list scrolls. */
class SharedInboxLayoutTest {
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

}
