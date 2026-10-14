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

    // --- The phone inbox: three chips over the same list ---

    /** The inbox chips, in order: what waits on you, what you wrote, everything. */
    val inboxFilters = listOf("waiting", "authored", "all")

    /** A pull request's identity in the archive and the tour index: `owner/repo#number`. */
    fun key(pull: PullRow): String = "${pull.owner}/${pull.repo}#${pull.number}"

    /**
     * Whether the next move is yours: it is open, and either it is not a draft or it is your own
     * (someone else's draft is not ready for you yet). Archiving is how the rest of the list is cleared.
     */
    fun waitingOnYou(pull: PullRow): Boolean = pull.state == "open" && (!pull.isDraft || pull.role == "authored")

    /** Whether [pull] is in the [filter] chip; archived pull requests only show under `all`. */
    fun inInbox(pull: PullRow, filter: String, archived: Set<String>): Boolean = when (filter) {
        "waiting" -> waitingOnYou(pull) && key(pull) !in archived
        "authored" -> pull.state == "open" && pull.role == "authored" && key(pull) !in archived
        else -> true
    }

    data class Inbox(
        /** The pull requests the chip and the search let through. */
        val pulls: List<PullRow>,
        /** Each chip's size under the search, so a count says what tapping it would show. */
        val counts: Map<String, Int>,
    )

    fun inbox(pulls: List<PullRow>, filter: String, archived: Set<String>, needle: String): Inbox {
        val searched = pulls.filter { matchesSearch(it, needle) }
        return Inbox(
            pulls = searched.filter { inInbox(it, filter, archived) },
            counts = inboxFilters.associateWith { chip -> searched.count { inInbox(it, chip, archived) } },
        )
    }

    /**
     * The login the list most often shows as author of the pull requests you wrote: the closest
     * thing the list has to "who am I", used for the avatar on the home screen.
     */
    fun viewerLogin(pulls: List<PullRow>): String? =
        pulls.filter { it.role == "authored" }.groupingBy { it.author }.eachCount().maxByOrNull { it.value }?.key?.takeIf { it.isNotBlank() }

    /** The label of a repository group: the repo name, with the owner when two groups share one. */
    fun groupLabels(groups: List<RepoGroup>): Map<String, String> {
        val repeated = groups.groupingBy { it.repo }.eachCount().filterValues { it > 1 }.keys
        return groups.associate { it.name to if (it.repo in repeated) it.name else it.repo }
    }
}
