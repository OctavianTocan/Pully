package com.octaviantocan.pulls.ui.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `assessMergeability` and the merge box's state actions. */
class MergeabilityTest {
    private val base = parsePrOverviewData(Json.parseToJsonElement(OverviewFixture.json))!!.overview
    private val writer = base.copy(viewer = base.viewer.copy(canPush = true, canClose = true, canUpdate = true), mergeable = "mergeable", mergeStateStatus = "clean")

    @Test
    fun cleanIsReady() = assertEquals(PrMergeability(true, true), assessMergeability(writer))

    @Test
    fun withoutPushItIsNotAllowed() = assertEquals(PrMergeability(false, false), assessMergeability(base))

    @Test
    fun draftConflictsAndChecking() {
        assertEquals(PrMergeability(true, false, "draft"), assessMergeability(writer.copy(state = "draft")))
        assertEquals(PrMergeability(true, false, "conflicts"), assessMergeability(writer.copy(mergeable = "conflicting")))
        assertEquals(PrMergeability(true, false, "conflicts"), assessMergeability(writer.copy(mergeStateStatus = "dirty")))
        assertEquals(PrMergeability(true, false, "checking"), assessMergeability(writer.copy(mergeable = "unknown")))
    }

    @Test
    fun missingOrUnrecognizedMergeabilityNeverBecomesReadyEvenForAdmins() {
        val admin = writer.copy(viewer = writer.viewer.copy(canMergeAsAdmin = true))
        for (unknown in listOf("", "unknown", "new-state")) {
            assertEquals(PrMergeability(true, false, "checking"), assessMergeability(admin.copy(mergeable = unknown, mergeStateStatus = "blocked")))
            assertEquals(PrMergeability(true, false, "checking"), assessMergeability(admin.copy(mergeStateStatus = unknown)))
            assertEquals(3_000L, overviewRevalidateDelayMs(writer.copy(mergeable = unknown)))
        }
    }

    @Test
    fun conflictStatusIsVisibleWithoutMergePermissionAndIndependentOfChecks() {
        val green = base.copy(checksSummary = base.checksSummary.copy(failing = 0, pending = 0, passing = 2, state = "success"))
        assertEquals("conflicts", overviewMergeBlocker(green.copy(mergeable = "conflicting")))
        assertEquals("conflicts", overviewMergeBlocker(green.copy(mergeable = "mergeable", mergeStateStatus = "dirty")))
        assertEquals("conflicts", overviewMergeBlocker(green.copy(state = "draft", mergeable = "conflicting")))
        assertNull(overviewMergeBlocker(writer))
        assertNull(overviewMergeBlocker(green.copy(state = "merged", mergeable = "conflicting")))
    }

    @Test
    fun blockedAndBehindBypassForAdmins() {
        assertEquals(PrMergeability(true, false, "blocked"), assessMergeability(writer.copy(mergeStateStatus = "blocked")))
        assertEquals(PrMergeability(true, true, "behind", "bypass"), assessMergeability(writer.copy(mergeStateStatus = "behind", viewer = writer.viewer.copy(canMergeAsAdmin = true))))
    }

    @Test
    fun unstableWarns() = assertEquals(PrMergeability(true, true, warning = "unstable"), assessMergeability(writer.copy(mergeStateStatus = "unstable")))

    @Test
    fun closedIsNotAllowed() = assertEquals(PrMergeability(false, false), assessMergeability(writer.copy(state = "closed")))

    @Test
    fun stateActionsFollowAbilities() {
        assertEquals(listOf("draft", "close"), stateActions(writer).map { it.first })
        assertEquals(listOf("ready", "close"), stateActions(writer.copy(state = "draft")).map { it.first })
        assertEquals(listOf("reopen"), stateActions(writer.copy(state = "closed", viewer = writer.viewer.copy(canReopen = true))).map { it.first })
        assertEquals(emptyList(), stateActions(writer.copy(state = "merged")))
        assertEquals(emptyList(), stateActions(base))
    }

    @Test
    fun revalidatesWhileUnknownOrPending() {
        assertEquals(3_000L, overviewRevalidateDelayMs(writer.copy(mergeable = "unknown")))
        assertEquals(30_000L, overviewRevalidateDelayMs(writer.copy(checksSummary = writer.checksSummary.copy(pending = 2))))
        assertNull(overviewRevalidateDelayMs(writer))
        assertNull(overviewRevalidateDelayMs(writer.copy(state = "merged", mergeable = "unknown")))
    }
}

/** Port of `commit-selection.test.ts`, plus the phone's tap / long-press picking. */
class CommitSelectionTest {
    private fun commit(oid: String) = PrCommit(oid = oid, abbreviatedOid = oid.take(7), headline = oid)
    private val commits = listOf("aaaaaaa1111111111", "bbbbbbb2222222222", "ccccccc3333333333").map(::commit)

    @Test
    fun resolvesBothEndsByPrefix() = assertEquals(0..2, resolveCommitSelection(commits, CommitSelection("aaaaaaa", "ccccccc")))

    @Test
    fun normalizesAReversedRange() = assertEquals(1..2, resolveCommitSelection(commits, CommitSelection("ccccccc", "bbbbbbb")))

    @Test
    fun unknownEndIsNull() = assertNull(resolveCommitSelection(commits, CommitSelection("aaaaaaa", "ddddddd")))

    @Test
    fun shortensToAStablePrefix() = assertEquals(CommitSelection("aaaaaaa11111", "aaaaaaa11111"), selectCommit(commits[0].oid))

    @Test
    fun ordersARangeOldestFirst() {
        assertEquals(CommitSelection("aaaaaaa11111", "ccccccc33333"), selectCommitRange(commits, 2, 0))
        assertEquals(selectCommit(commits[1].oid), selectCommitRange(commits, 1))
    }

    @Test
    fun stepsAroundASingleCommit() {
        assertEquals(selectCommit(commits[0].oid) to selectCommit(commits[2].oid), adjacentCommits(commits, selectCommit(commits[1].oid)))
    }

    @Test
    fun noPreviousAtTheOldestNoNextAtTheNewest() {
        assertNull(adjacentCommits(commits, selectCommit(commits[0].oid)).first)
        assertNull(adjacentCommits(commits, selectCommit(commits[2].oid)).second)
    }

    @Test
    fun stepsOutOfARangeFromItsEnds() {
        assertEquals(selectCommit(commits[0].oid) to null, adjacentCommits(commits, CommitSelection("bbbbbbb", "ccccccc")))
    }

    @Test
    fun unknownSelectionHasNoNeighbours() = assertEquals(null to null, adjacentCommits(commits, CommitSelection("ddddddd", "ddddddd")))

    @Test
    fun tapPicksOneAndTapAgainClears() {
        val one = pickCommit(commits, null, null, 1, extend = false)
        assertEquals(selectCommit(commits[1].oid), one)
        assertNull(pickCommit(commits, one, 1, 1, extend = false))
        assertEquals(selectCommit(commits[2].oid), pickCommit(commits, one, 1, 2, extend = false))
    }

    @Test
    fun extendingSpansFromTheAnchor() {
        val one = selectCommit(commits[2].oid)
        assertEquals(CommitSelection("aaaaaaa11111", "ccccccc33333"), pickCommit(commits, one, 2, 0, extend = true))
        // Without an anchor the current selection's start is the other end.
        assertEquals(CommitSelection("bbbbbbb22222", "ccccccc33333"), pickCommit(commits, CommitSelection("bbbbbbb", "bbbbbbb"), null, 2, extend = true))
    }
}
