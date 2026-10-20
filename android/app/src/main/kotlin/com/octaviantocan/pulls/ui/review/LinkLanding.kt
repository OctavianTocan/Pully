package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.snapshotFlow
import com.octaviantocan.pulls.core.LinkPlace
import com.octaviantocan.pulls.core.LinkPlaces
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.Toaster
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** A comment or review card a link pointed at, by its timeline id; [inline] opens its comments on the code too. */
class LinkSpot(val item: String, val inline: Boolean = false)

/** The page moves [landOn] makes, which the review screen owns. */
internal class LandingMoves(
    /** The Overview on its Summary tab, scrolled to the top: the description. */
    val summary: () -> Unit,
    /** The Overview on its Conversation tab. */
    val conversation: () -> Unit,
    /** The Diff, on the file it was on or the first one to read. */
    val changes: () -> Unit,
    /** One of the review's sheets by name: `commits`, `checks`. */
    val sheet: (String) -> Unit,
    /** The same place on GitHub, outside Pully, for what Pully cannot show. */
    val github: (String) -> Unit,
)

/**
 * Goes to the thing a GitHub link pointed at on this pull request, as GitHub would scroll its page to it: a comment or a
 * review on the Conversation, a comment on the code at its line in the Diff, a file and line (of some commits when the
 * link named them), a commit's changes, the commits or the checks. It waits for what each needs to arrive, and a thing
 * Pully does not have says so, with the way to it on GitHub ("It needs to take us to the thing. Seamless parity with
 * GitHub": Tavi, 20 Oct).
 */
internal suspend fun landOn(place: LinkPlace, session: ReviewSession, moves: LandingMoves, toaster: Toaster) {
    when (place) {
        LinkPlace.Description -> moves.summary()
        LinkPlace.Conversation -> moves.conversation()
        is LinkPlace.Comment -> spotOnTimeline(session, moves, toaster, "#issuecomment-${place.id}", "comment") { it is PrTimelineItem.Comment && it.url.endsWith("#issuecomment-${place.id}") }
        is LinkPlace.Review -> spotOnTimeline(session, moves, toaster, "#pullrequestreview-${place.id}", "review") { it is PrTimelineItem.Review && it.url.endsWith("#pullrequestreview-${place.id}") }
        is LinkPlace.CodeComment -> codeComment(place.id, session, moves, toaster)
        is LinkPlace.Files -> files(place, session, moves, toaster)
        is LinkPlace.Commits -> {
            val sha = place.sha
            if (sha == null) { moves.sheet("commits"); return }
            val overview = overviewOf(session) ?: return moves.sheet("commits")
            val oid = overview.commits.firstOrNull { it.oid.startsWith(sha, ignoreCase = true) }?.oid
            if (oid == null) {
                toaster.show("Pully can't find that commit", "GitHub", Cue.Error) { moves.github("${overview.url}/commits/$sha") }
                moves.sheet("commits")
                return
            }
            select(session, oid to oid)
            place.file?.let { files(it, session, moves, toaster) } ?: moves.changes()
        }
        is LinkPlace.Checks -> {
            val runId = place.runId
            session.linkedCheck = runId
            moves.sheet("checks")
            // A run Pully doesn't list (one of the checks past the sheet's, or an outside service's) says so.
            val overview = if (runId == null) null else overviewOf(session)
            if (runId != null && overview != null && overview.checks.none { isRun(it, runId) }) {
                toaster.show("Pully can't find that check", "GitHub", Cue.Error) { moves.github("${overview.url}/checks?check_run_id=$runId") }
            }
        }
    }
}

/** A comment or review card on the Conversation, found by [matches] on its GitHub link. */
private suspend fun spotOnTimeline(
    session: ReviewSession,
    moves: LandingMoves,
    toaster: Toaster,
    anchor: String,
    what: String,
    inline: Boolean = false,
    matches: (PrTimelineItem) -> Boolean,
) {
    var overview = overviewOf(session) ?: return
    moves.conversation()
    var item = overview.timeline.firstOrNull(matches)
    // A comment written since the page last read the pull request (an agent sending the link to what it just wrote):
    // the page reads it again and looks once more.
    if (item == null) {
        val before = session.overview
        session.reloadOverview()
        waitFor(FRESH_WAIT_MS) { session.overview?.takeIf { it !== before } }?.let { fresh ->
            parsePrOverviewData(fresh)?.overview?.let { overview = it; item = it.timeline.firstOrNull(matches) }
        }
    }
    val found = item
    if (found == null) {
        toaster.show("Pully can't find that $what", "GitHub", Cue.Error) { moves.github(overview.url + anchor) }
        return
    }
    session.spot = LinkSpot(found.id, inline)
}

/**
 * A comment on the code: its thread at its line in the Diff, opened. A thread whose code has since changed is on the
 * Conversation, under the review it came with.
 */
private suspend fun codeComment(id: Long, session: ReviewSession, moves: LandingMoves, toaster: Toaster) {
    fun find(data: com.octaviantocan.pulls.data.ReviewData?) = data?.threads?.firstOrNull { thread -> thread.comments.any { it.id == id } }
    var thread = find(waitFor(LANDING_WAIT_MS) { session.reviewData })
    if (thread == null) {
        val before = session.reviewData
        session.reloadReviews()
        thread = find(waitFor(FRESH_WAIT_MS) { session.reviewData?.takeIf { it !== before } })
    }
    val line = thread?.line
    if (thread != null && line != null && !thread.outdated && waitFor(LANDING_WAIT_MS) { session.opened } != null && session.files.any { it.path == thread.path }) {
        session.linkedThread = thread.rootId
        session.focus = Focus(thread.path, line, thread.side.ifBlank { "additions" })
        return
    }
    spotOnTimeline(session, moves, toaster, "#discussion_r$id", "comment", inline = true) { item ->
        item is PrTimelineItem.Review && item.comments.any { it.url.endsWith("#discussion_r$id") || it.url.endsWith("#r$id") }
    }
}

/** The files tab: some commits when the link named them, then one file at one line, or the Diff where it was. */
private suspend fun files(place: LinkPlace.Files, session: ReviewSession, moves: LandingMoves, toaster: Toaster) {
    place.commits?.let { named ->
        val overview = overviewOf(session)
        val (from, to) = LinkPlaces.commitRange(named)
        val commits = overview?.commits.orEmpty()
        val last = commits.indexOfFirst { it.oid.startsWith(to, ignoreCase = true) }
        // GitHub's `a..b` is what came after a up to b; Pully's range takes both ends in.
        val first = if (from == to) last else commits.indexOfFirst { it.oid.startsWith(from, ignoreCase = true) }.let { if (it >= 0) it + 1 else -1 }
        if (last >= 0 && first in 0..last) select(session, commits[first].oid to commits[last].oid)
        else toaster.show("Pully can't find those commits", cue = Cue.Error)
    }
    val anchor = place.file ?: return moves.changes()
    if (waitFor(LANDING_WAIT_MS) { session.opened } == null) return
    val path = LinkPlaces.fileFor(anchor, session.files.map { it.path })
    if (path == null) {
        toaster.show("That file isn't part of this change", cue = Cue.Error)
        moves.changes()
        return
    }
    session.focus = Focus(path, place.line ?: 0, if (place.left) "deletions" else "additions")
}

/** Narrows the diff to commits [range] and waits for their diff, so a file in it is looked for among their files. */
private suspend fun select(session: ReviewSession, range: Pair<String, String>) {
    if (waitFor(LANDING_WAIT_MS) { session.opened } == null) return
    if (session.selection == range) return
    val before = session.opened
    session.selectCommits(range)
    waitFor(LANDING_WAIT_MS) { session.opened?.takeIf { it !== before } }
}

private suspend fun overviewOf(session: ReviewSession): PrOverview? =
    waitFor(LANDING_WAIT_MS) { session.overview }?.let { parsePrOverviewData(it)?.overview }

private suspend fun <T : Any> waitFor(ms: Long, read: () -> T?): T? = withTimeoutOrNull(ms) { snapshotFlow(read).filterNotNull().first() }

/** How long a link waits for the part of the pull request its place is on, before it gives up quietly. */
private const val LANDING_WAIT_MS = 30_000L

/** How long a link to something the page has not seen waits for the pull request to be read again. */
private const val FRESH_WAIT_MS = 10_000L
