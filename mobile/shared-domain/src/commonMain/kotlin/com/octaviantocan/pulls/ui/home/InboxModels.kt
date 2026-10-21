package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.ui.design.matchesSearch

/** One repository in Profile's list: how many of your pull requests it has right now, and whether the Inbox shows it. */
data class RepoChoice(val owner: String, val repo: String, val count: Int, val shown: Boolean) {
    val key: String get() = "$owner/$repo"
}

/**
 * Every repository your pull requests are in, and every one switched off even when it has none right now
 * (so it can be switched back on), by name. [query] keeps those whose `owner/repo` contains it.
 */
fun repoChoices(pulls: List<PullRow>, hidden: Set<String>, query: String = ""): List<RepoChoice> {
    val counts = pulls.groupingBy { "${it.owner}/${it.repo}" }.eachCount()
    val keys = counts.keys + hidden
    return keys
        .filter { matchesSearch(it, query) }
        .sortedBy { it.lowercase() }
        .mapNotNull { key ->
            val owner = key.substringBefore('/', "")
            val repo = key.substringAfter('/', "")
            if (owner.isEmpty() || repo.isEmpty()) null else RepoChoice(owner, repo, counts[key] ?: 0, key !in hidden)
        }
}

/** The pull requests the Inbox shows: all of them but those in a switched-off repository. */
fun List<PullRow>.inShownRepos(hidden: Set<String>): List<PullRow> =
    if (hidden.isEmpty()) this else filterNot { "${it.owner}/${it.repo}" in hidden }

/**
 * One row of the Tours tab: a pull request whose tour you opened or asked for on this phone.
 * [stops] is 0 while the tour is still being written; [reached] is the furthest stop you scrolled
 * to (1-based), with that stop's title in [stopTitle]; [at] is the stop you were on when you left,
 * where the tour reopens (0: never opened, use [reached]).
 */
data class TourEntry(
    val owner: String,
    val repo: String,
    val number: Int,
    val prTitle: String,
    val stops: Int,
    val reached: Int,
    val stopTitle: String,
    val writing: Boolean,
    val updatedAt: Long,
    val at: Int = 0,
) {
    val key: String get() = "$owner/$repo#$number"
    val done: Boolean get() = !writing && stops > 0 && reached >= stops

    /** The stop to reopen on: the one you left, else the furthest you got. */
    val resumeAt: Int get() = if (at in 1..stops) at else reached

    /**
     * Whether it is still being written. Only an open review of the pull request updates the entry, so one
     * that was left mid-write must not claim to be writing for ever.
     */
    fun writingAt(now: Long): Boolean = writing && now - updatedAt < WRITING_STALE_MS

    /** [done], but a rewrite that has expired no longer keeps a finished tour from counting as done. */
    fun doneAt(now: Long): Boolean = !writingAt(now) && stops > 0 && reached >= stops

    companion object {
        const val WRITING_STALE_MS = 15 * 60_000L
    }
}

/**
 * How the Inbox orders its rows. The groups then follow their first row, so a sort orders them too. [needsJev]: offered
 * only while the server sorts pull requests with Jev.
 */
enum class InboxSort(val id: String, val label: String, val needsJev: Boolean = false) {
    Updated("updated", "Recently updated"),
    LeastUpdated("least-updated", "Least recently updated"),
    Number("number", "PR number"),
    Size("size", "Largest change"),
    Risk("risk", "Riskiest first", needsJev = true),
    ;

    /** [pulls] in this order. Sorting is stable, so rows that tie keep the order the server sent. */
    fun apply(pulls: List<PullRow>): List<PullRow> = when (this) {
        Updated -> pulls.sortedByDescending { it.updatedAtMs ?: Long.MIN_VALUE }
        LeastUpdated -> pulls.sortedBy { it.updatedAtMs ?: Long.MAX_VALUE }
        Number -> pulls.sortedByDescending { it.number }
        Size -> pulls.sortedByDescending { (it.additions ?: 0) + (it.deletions ?: 0) }
        // A row Jev has not sorted yet has no risk to compare, so it waits at the end rather than passing for trivial.
        Risk -> pulls.sortedWith(compareBy<PullRow> { it.jev == null }.thenByDescending { it.jev?.risk ?: 0.0 })
    }

    companion object {
        fun of(id: String?): InboxSort = entries.firstOrNull { it.id == id } ?: Updated

        /** The sorts the Sort sheet lists. */
        fun offered(jev: Boolean): List<InboxSort> = entries.filter { jev || !it.needsJev }
    }
}

/** What the Inbox's headings are: the repositories (the default), or, while Jev is on, the kinds of change. */
enum class InboxGrouping(val id: String, val label: String, val needsJev: Boolean = false) {
    Repo("repo", "Repository"),
    Kind("kind", "Kind", needsJev = true),
    ;

    companion object {
        fun of(id: String?): InboxGrouping = entries.firstOrNull { it.id == id } ?: Repo

        /** The groupings the Sort sheet lists. */
        fun offered(jev: Boolean): List<InboxGrouping> = entries.filter { jev || !it.needsJev }
    }
}

/** Jev's kinds as the Inbox groups them, in this order, each with its heading. */
val JEV_KINDS: List<Pair<String, String>> = listOf(
    "fix" to "Fixes",
    "feature" to "Features",
    "refactor" to "Refactors",
    "performance" to "Performance",
    "tests" to "Tests",
    "docs" to "Docs",
    "dependencies" to "Dependencies",
    "infra" to "Infra",
)

/** The heading of the rows Jev has not sorted yet (or sorted into a kind this app does not know), after the kinds. */
const val UNSORTED_LABEL = "Not sorted yet"

/**
 * One heading of the Inbox and its rows. [key] is what folds it (in [InboxLayout.folded]) and pins it: a repository's
 * `owner/repo`, or `kind:<kind>` for a kind. [repo] is the repository a tap on the heading's name opens; a kind's name
 * opens nothing.
 */
data class InboxSection(val key: String, val label: String, val pulls: List<PullRow>, val repo: Triage.RepoGroup? = null)

/** [sorted] under the headings [grouping] asks for, every heading keeping the order of its rows. Empty kinds are left out. */
fun inboxSections(sorted: List<PullRow>, grouping: InboxGrouping): List<InboxSection> = when (grouping) {
    InboxGrouping.Repo -> {
        val groups = Triage.groupByRepo(sorted)
        val labels = Triage.groupLabels(groups)
        groups.map { InboxSection(it.name, labels[it.name] ?: it.repo, it.pulls, it) }
    }
    InboxGrouping.Kind -> {
        val known = JEV_KINDS.map { it.first }.toSet()
        val byKind = sorted.groupBy { pull -> pull.jev?.kind?.takeIf { it in known } }
        JEV_KINDS.mapNotNull { (kind, label) -> byKind[kind]?.let { InboxSection("kind:$kind", label, it) } } +
            listOfNotNull(byKind[null]?.let { InboxSection("kind:none", UNSORTED_LABEL, it) })
    }
}

/** By `owner/repo`, the name a row under a kind shows before its author: what its repository's heading would say. */
fun repoLabels(pulls: List<PullRow>): Map<String, String> = Triage.groupLabels(Triage.groupByRepo(pulls))

/** Where a tour stands, for a row whose pull request is not in the Inbox: no counts (rules 11 and 14). A finished one says nothing: its tick does (H27). */
fun tourState(entry: TourEntry, now: Long): String = when {
    entry.writingAt(now) -> "Writing"
    entry.doneAt(now) -> ""
    entry.reached > 0 -> entry.stopTitle.ifBlank { "Reading" }
    else -> "Not started"
}

/** Whether the search, trimmed, ends in a question mark (more than the mark alone): the ✕ is then the send circle (N8). */
fun endsInQuestion(text: String): Boolean {
    val trimmed = text.trim()
    return trimmed.length > 1 && (trimmed.endsWith("?") || trimmed.endsWith("？"))
}
