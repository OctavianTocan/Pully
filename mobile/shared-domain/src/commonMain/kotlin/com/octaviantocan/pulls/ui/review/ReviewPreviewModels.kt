package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.home.PullsSnapshot

/**
 * What the phone already knows about a pull request before its page has loaded, so the page can show its title and
 * number at once and fill in the rest as it arrives. [files], [additions] and [deletions] are `null` when unknown.
 */
data class PullPreview(
    val title: String,
    val author: String? = null,
    val isDraft: Boolean = false,
    val files: Int? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    /** The branches it merges, `main` and `feature/x`, so the Overview shows them before GitHub answers (J13). */
    val base: String = "",
    val head: String = "",
    val state: String = if (isDraft) "draft" else "unknown",
)

/** Newer list facts replace older ones; a recorded merge remains final across both list caches. */
fun pullPreview(pull: OpenTarget.Pull, snapshots: List<PullsSnapshot>): PullPreview? {
    val row = snapshots.flatMap { snapshot ->
        snapshot.pulls.filter { it.owner.equals(pull.owner, true) && it.repo.equals(pull.repo, true) && it.number == pull.number }
            .map { it to snapshot.fetchedAt }
    }.maxWithOrNull(compareBy<Pair<PullRow, Long>> { it.first.merged || it.first.state == "merged" }.thenBy { it.second }.thenBy { it.first.state == "closed" })?.first ?: return null
    val state = normalizedPrState(row.state)
    return PullPreview(
        row.title, row.author.takeIf { it.isNotBlank() }, row.isDraft, row.changedFiles, row.additions, row.deletions, row.baseRef, row.headRef,
        when {
            row.merged || state == "merged" -> "merged"
            state == "closed" -> "closed"
            state == "open" && row.isDraft -> "draft"
            else -> state
        },
    )
}
