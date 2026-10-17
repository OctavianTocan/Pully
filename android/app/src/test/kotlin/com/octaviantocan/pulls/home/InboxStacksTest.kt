package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.PullStack
import com.octaviantocan.pulls.ui.home.InboxItem
import com.octaviantocan.pulls.ui.home.inboxItems
import com.octaviantocan.pulls.ui.home.inboxStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The layers of one stack in an Inbox section are one row, where the first of them stood (rule 202). */
class InboxStacksTest {
    private fun pull(number: Int, stack: String? = null, position: Int = 0, repo: String = "app", base: String = "") = PullRow(
        "acme", repo, number, "Pull $number", "octo", "", false, baseRef = base,
        stack = stack?.let { PullStack(it, position, size = 3, native = false, below = null) },
    )

    @Test
    fun foldsTheLayersOfAStackIntoOneRowBottomFirst() {
        val rows = inboxItems(listOf(pull(9), pull(243, "s", 3), pull(5), pull(241, "s", 1), pull(242, "s", 2)))
        assertEquals(listOf("acme/app#9", "stack:acme/app/s", "acme/app#5"), rows.map { it.key })
        val stack = assertIs<InboxItem.Stacked>(rows[1])
        assertEquals(listOf(241, 242, 243), stack.layers.map { it.number })
    }

    @Test
    fun aStackWithOneLayerHereStaysThatPullRequestsRow() {
        val rows = inboxItems(listOf(pull(242, "s", 2), pull(9)))
        assertEquals(listOf("acme/app#242", "acme/app#9"), rows.map { it.key })
    }

    @Test
    fun theSameStackIdInAnotherRepositoryIsAnotherStack() {
        val rows = inboxItems(listOf(pull(1, "s", 1), pull(2, "s", 1, repo = "web")))
        assertEquals(2, rows.size)
        assertIs<InboxItem.Single>(rows[0])
    }

    @Test
    fun theSheetShowsTheBaseOnlyWhenTheRealBottomIsHere() {
        assertEquals("main", inboxStack(listOf(pull(241, "s", 1, base = "main"), pull(242, "s", 2))).base)
        assertEquals("", inboxStack(listOf(pull(242, "s", 2, base = "stack/one"), pull(243, "s", 3))).base)
        assertEquals(listOf(false, false), inboxStack(listOf(pull(241, "s", 1), pull(242, "s", 2))).entries.map { it.current })
    }
}
