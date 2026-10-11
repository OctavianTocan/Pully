package com.octaviantocan.pulls.data

/** Same queues and roles as the web app's home (`triage.ts`). */
object Triage {
    const val STALE_AFTER_MS = 14L * 24 * 60 * 60 * 1000
    val queues = listOf("needs-review", "ci-failing", "changes-requested", "ready-to-merge", "stale", "all")
    val roles = listOf("all", "review-requested", "authored", "involved", "owned")

    fun inQueue(pull: PullRow, queue: String, now: Long): Boolean = when (queue) {
        "needs-review" -> pull.requestedFromMe || pull.role == "review-requested"
        "ci-failing" -> pull.checks == "failure"
        "changes-requested" -> pull.reviewDecision == "changes-requested"
        "ready-to-merge" -> !pull.isDraft && pull.reviewDecision == "approved" && (pull.checks == "success" || pull.checks == "none") && pull.mergeable == "mergeable"
        "stale" -> pull.updatedAtMs > 0 && now - pull.updatedAtMs >= STALE_AFTER_MS
        else -> true
    }

    fun visible(pulls: List<PullRow>, queue: String, role: String, needle: String, now: Long): List<PullRow> {
        return pulls.filter { pull ->
            (role == "all" || pull.role == role) &&
                inQueue(pull, queue, now) &&
                (needle.isBlank() || listOf(pull.owner, pull.repo, "${pull.owner}/${pull.repo}", pull.title, pull.author, "#${pull.number}")
                    .any { it.lowercase().contains(needle) })
        }
    }
}
