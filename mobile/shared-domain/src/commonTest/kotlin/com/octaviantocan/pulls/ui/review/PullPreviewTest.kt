package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.home.PullsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullPreviewTest {
    private val pull = OpenTarget.Pull("owner", "repo", 71)
    private val open = PullRow("owner", "repo", 71, "Old title", "author", "", false, state = "open")

    @Test
    fun newerClosedSnapshotReplacesOpenPreviewInEitherStorageOrder() {
        val old = PullsSnapshot(listOf(open), 1)
        val current = PullsSnapshot(listOf(open.copy(title = "Current title", state = "closed", isDraft = true)), 2)
        for (snapshots in listOf(listOf(old, current), listOf(current, old))) {
            val preview = pullPreview(pull, snapshots)!!
            assertEquals("closed", preview.state)
            assertEquals("Current title", preview.title)
        }
    }

    @Test
    fun aMergeCannotRegressToAnOpenOrDraftSnapshot() {
        val merged = PullsSnapshot(listOf(open.copy(state = "closed", merged = true, isDraft = true)), 1)
        val laterOpen = PullsSnapshot(listOf(open.copy(isDraft = true)), 2)
        assertEquals("merged", pullPreview(pull, listOf(laterOpen, merged))?.state)
        assertEquals("merged", pullPreview(pull, listOf(merged, laterOpen))?.state)
        val mergedStateOnly = PullsSnapshot(listOf(open.copy(state = "merged", isDraft = true)), 1)
        assertEquals("merged", pullPreview(pull, listOf(mergedStateOnly, laterOpen))?.state)
    }

    @Test
    fun freshOpenListCanReopenClosedCopyAndOnlyMatchingPullsAreUsed() {
        val closed = PullsSnapshot(listOf(open.copy(state = "closed")), 1)
        val reopened = PullsSnapshot(listOf(open.copy(title = "Reopened")), 2)
        assertEquals("open", pullPreview(pull, listOf(closed, reopened))?.state)
        assertEquals("Reopened", pullPreview(pull, listOf(closed, reopened))?.title)
        val other = PullsSnapshot(listOf(open.copy(number = 72, merged = true)), 3)
        assertNull(pullPreview(pull, listOf(other)))
    }
}
