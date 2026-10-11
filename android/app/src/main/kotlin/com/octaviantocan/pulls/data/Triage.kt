package com.octaviantocan.pulls.data

/**
 * Port of the web home's `components/home/triage.ts`: the work queues (most actionable
 * first; `all` is the unfiltered list), the roles, their counts, and grouping by repo.
 */
object Triage {
    /** A pull request with no activity for this many days counts as stale. */
    const val STALE_AFTER_DAYS = 14
    private const val DAY_MS = 24L * 60 * 60_000
    const val STALE_AFTER_MS = STALE_AFTER_DAYS * DAY_MS

    val queues = listOf("needs-review", "ci-failing", "changes-requested", "ready-to-merge", "stale", "all")

    /** `ROLE_ORDER`: the roles without `all`. */
    val roleOrder = listOf("review-requested", "authored", "involved", "owned")

    /** The role filter options, `all` first (`HomeQueueNav`'s `ROLES`). */
    val roles = listOf("all") + roleOrder

    /**
     * Whether a pull request belongs in a queue. `ready-to-merge` counts a head commit
     * without any checks as green, since nothing is blocking it.
     */
    fun inQueue(pull: PullRow, queue: String, now: Long): Boolean = when (queue) {
        "needs-review" -> pull.requestedFromMe ?: (pull.role == "review-requested")
        "ci-failing" -> pull.checks == "failure"
        "changes-requested" -> pull.reviewDecision == "changes-requested"
        "ready-to-merge" -> !pull.isDraft &&
            pull.reviewDecision == "approved" &&
            (pull.checks == "success" || pull.checks == "none") &&
            pull.mergeable == "mergeable"
        // `Date.parse` of a bad date is NaN, and every NaN comparison is false.
        "stale" -> pull.updatedAtMs != null && now - pull.updatedAtMs >= STALE_AFTER_MS
        else -> true
    }

    /** How many pull requests each queue holds. */
    fun countQueues(pulls: List<PullRow>, now: Long): Map<String, Int> =
        queues.associateWith { queue -> pulls.count { inQueue(it, queue, now) } }

    /** Whether its repo, title, author, number or a label contains the lower-cased [needle]. */
    fun matchesSearch(pull: PullRow, needle: String): Boolean {
        if (needle.isEmpty()) return true
        return (listOf(pull.owner, pull.repo, "${pull.owner}/${pull.repo}", pull.title, pull.author, "#${pull.number}") + pull.labels)
            .any { it.lowercase().contains(needle) }
    }

    data class Filtered(
        /** The pull requests every filter lets through. */
        val pulls: List<PullRow>,
        /** Each queue's size under the current role and search. */
        val queueCounts: Map<String, Int>,
        /** Each role's size under the current queue and search; `all` included. */
        val roleCounts: Map<String, Int>,
    )

    /**
     * Applies the queue, role and search filters, counting each queue and role as if
     * only the other filters applied, so every count says what tapping it would show.
     */
    fun filterPulls(pulls: List<PullRow>, queue: String, role: String, needle: String, now: Long): Filtered {
        val searched = pulls.filter { matchesSearch(it, needle) }
        val inRole = if (role == "all") searched else searched.filter { it.role == role }
        val inQueueOnly = searched.filter { inQueue(it, queue, now) }
        val roleCounts = linkedMapOf("all" to inQueueOnly.size).apply {
            roleOrder.forEach { r -> put(r, inQueueOnly.count { it.role == r }) }
        }
        return Filtered(
            pulls = inRole.filter { inQueue(it, queue, now) },
            queueCounts = countQueues(inRole, now),
            roleCounts = roleCounts,
        )
    }

    data class RepoGroup(val name: String, val owner: String, val repo: String, val pulls: List<PullRow>)

    /** Grouped by repository in order of first appearance; order inside a group is kept. */
    fun groupByRepo(pulls: List<PullRow>): List<RepoGroup> {
        val groups = LinkedHashMap<String, MutableList<PullRow>>()
        for (pull in pulls) groups.getOrPut("${pull.owner}/${pull.repo}") { mutableListOf() }.add(pull)
        return groups.map { (name, list) -> RepoGroup(name, list.first().owner, list.first().repo, list) }
    }

    /** The visible list only. */
    fun visible(pulls: List<PullRow>, queue: String, role: String, needle: String, now: Long): List<PullRow> =
        filterPulls(pulls, queue, role, needle, now).pulls

    /** The row's `PrStatusIcon` state: draft/open while open, merged/closed after. */
    fun status(pull: PullRow): String =
        if (pull.state == "open") (if (pull.isDraft) "draft" else "open") else if (pull.merged) "merged" else "closed"
}
