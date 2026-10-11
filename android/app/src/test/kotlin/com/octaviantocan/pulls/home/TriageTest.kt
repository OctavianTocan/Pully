package com.octaviantocan.pulls.home

import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.data.asPulls
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of `components/home/triage.test.ts`. */
class TriageTest {
    private val now = java.time.Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()

    private fun pull(
        number: Int = 1,
        role: String = "authored",
        requestedFromMe: Boolean? = null,
        checks: String = "",
        reviewDecision: String = "",
        mergeable: String = "",
        isDraft: Boolean = false,
        repo: String = "app",
        title: String = "Fix the thing",
        updatedAt: String = "2026-10-05T00:00:00Z",
        labels: List<String> = emptyList(),
    ) = PullRow(
        owner = "acme", repo = repo, number = number, title = title, author = "octo", updatedAt = updatedAt, isDraft = isDraft,
        role = role, checks = checks, reviewDecision = reviewDecision, mergeable = mergeable, requestedFromMe = requestedFromMe,
        labels = labels, updatedAtMs = runCatching { java.time.Instant.parse(updatedAt).toEpochMilli() }.getOrNull(),
    )

    @Test
    fun needsMyReviewIsAPendingRequestFallingBackToTheRole() {
        assertTrue(Triage.inQueue(pull(requestedFromMe = true), "needs-review", now))
        assertFalse(Triage.inQueue(pull(role = "review-requested", requestedFromMe = false), "needs-review", now))
        assertTrue(Triage.inQueue(pull(role = "review-requested"), "needs-review", now))
    }

    @Test
    fun ciFailingAndChangesRequested() {
        assertTrue(Triage.inQueue(pull(checks = "failure"), "ci-failing", now))
        assertFalse(Triage.inQueue(pull(checks = "pending"), "ci-failing", now))
        assertTrue(Triage.inQueue(pull(reviewDecision = "changes-requested"), "changes-requested", now))
    }

    @Test
    fun readyToMergeIsApprovedGreenOrNoChecksMergeableNotDraft() {
        val ready = pull(reviewDecision = "approved", checks = "success", mergeable = "mergeable")
        assertTrue(Triage.inQueue(ready, "ready-to-merge", now))
        assertTrue(Triage.inQueue(ready.copy(checks = "none"), "ready-to-merge", now))
        assertFalse(Triage.inQueue(ready.copy(checks = "pending"), "ready-to-merge", now))
        assertFalse(Triage.inQueue(ready.copy(mergeable = "conflicting"), "ready-to-merge", now))
        assertFalse(Triage.inQueue(ready.copy(mergeable = "unknown"), "ready-to-merge", now))
        assertFalse(Triage.inQueue(ready.copy(isDraft = true), "ready-to-merge", now))
        assertFalse(Triage.inQueue(ready.copy(reviewDecision = ""), "ready-to-merge", now))
    }

    @Test
    fun staleAfterTwoWeeksWithoutActivity() {
        fun at(days: Int) = java.time.Instant.ofEpochMilli(now - days * 24L * 60 * 60_000).toString()
        assertTrue(Triage.inQueue(pull(updatedAt = at(Triage.STALE_AFTER_DAYS)), "stale", now))
        assertFalse(Triage.inQueue(pull(updatedAt = at(Triage.STALE_AFTER_DAYS - 1)), "stale", now))
        assertFalse(Triage.inQueue(pull(updatedAt = "not a date"), "stale", now))
    }

    private val pulls = listOf(
        pull(number = 1, role = "review-requested", requestedFromMe = true, checks = "failure"),
        pull(number = 2, role = "authored", checks = "failure", title = "Docs"),
        pull(number = 3, role = "authored", reviewDecision = "approved", checks = "success", mergeable = "mergeable", repo = "site"),
    )

    @Test
    fun countsEveryQueue() {
        assertEquals(
            mapOf("needs-review" to 1, "ci-failing" to 2, "changes-requested" to 0, "ready-to-merge" to 1, "stale" to 0, "all" to 3),
            Triage.countQueues(pulls, now),
        )
    }

    @Test
    fun countsQueuesUnderTheRoleAndRolesUnderTheQueue() {
        val result = Triage.filterPulls(pulls, "ci-failing", "authored", "", now)
        assertEquals(listOf(2), result.pulls.map { it.number })
        assertEquals(1, result.queueCounts["ci-failing"])
        assertEquals(1, result.queueCounts["ready-to-merge"])
        assertEquals(mapOf("all" to 2, "review-requested" to 1, "authored" to 1, "involved" to 0, "owned" to 0), result.roleCounts)
        assertEquals(listOf("all", "review-requested", "authored", "involved", "owned"), result.roleCounts.keys.toList())
    }

    @Test
    fun appliesTheSearchToEverything() {
        val result = Triage.filterPulls(pulls, "all", "all", "docs", now)
        assertEquals(listOf(2), result.pulls.map { it.number })
        assertEquals(1, result.queueCounts["all"])
        assertEquals(listOf(3), Triage.filterPulls(pulls, "all", "all", "acme/site", now).pulls.map { it.number })
    }

    @Test
    fun searchMatchesLabels() {
        val labelled = pulls + pull(number = 9, labels = listOf("Tracked"))
        assertEquals(listOf(9), Triage.filterPulls(labelled, "all", "all", "tracked", now).pulls.map { it.number })
    }

    @Test
    fun groupsByRepositoryInOrderOfFirstAppearance() {
        assertEquals(listOf("acme/app" to listOf(1, 2), "acme/site" to listOf(3)), Triage.groupByRepo(pulls).map { it.name to it.pulls.map { p -> p.number } })
    }

    @Test
    fun statusFollowsStateDraftAndMerged() {
        assertEquals("open", Triage.status(pull()))
        assertEquals("draft", Triage.status(pull(isDraft = true)))
        assertEquals("merged", Triage.status(pull().copy(state = "closed", merged = true)))
        assertEquals("closed", Triage.status(pull().copy(state = "closed")))
    }

    @Test
    fun parsesTheMyPullsShape() {
        val json = """{"pulls":[{"owner":"OctavianTocan","repo":"Dayflow","number":237,"title":"t","author":"a","isDraft":false,"updatedAt":"2026-10-11T17:46:12Z","commentsCount":2,"labels":["Tracked"],"state":"open","role":"authored","url":"https://github.com/x/y/pull/1","merged":false,"headSha":"4f93","requestedFromMe":false,"checks":"failure","mergeable":"mergeable","additions":2495,"deletions":344,"changedFiles":59,"reviewRequests":["bob"]},{"owner":"o","repo":"r","number":2,"title":"t2","author":"b","isDraft":true,"updatedAt":"2026-10-11T00:00:00Z","labels":[],"state":"closed","merged":true,"role":"owned"}],"fetchedAt":1,"refreshing":false}"""
        val rows = Json.parseToJsonElement(json).asPulls()
        val first = rows[0]
        assertEquals(237, first.number)
        assertEquals(false, first.requestedFromMe)
        assertEquals(2495, first.additions)
        assertEquals(344, first.deletions)
        assertEquals(59, first.changedFiles)
        assertEquals(2, first.commentsCount)
        assertEquals(listOf("Tracked"), first.labels)
        assertEquals(listOf("bob"), first.reviewRequests)
        assertEquals("4f93", first.headSha)
        assertEquals("https://github.com/x/y/pull/1", first.url)
        assertNull(rows[1].requestedFromMe)
        assertNull(rows[1].additions)
        assertEquals("merged", Triage.status(rows[1]))
    }
}
