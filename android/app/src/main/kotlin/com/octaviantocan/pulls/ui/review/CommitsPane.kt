package com.octaviantocan.pulls.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetSearch
import com.octaviantocan.pulls.ui.design.matchesSearch
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.home.DrawableIcon
import com.octaviantocan.pulls.ui.home.StatusColors

/**
 * The commits of the pull request on a sheet built as Files is (K29): "Find a commit", the commits under a plain heading
 * for each day, oldest first and numbered so the order is never a guess (rule 91). A tap opens the diff of that commit
 * alone (K25, [onOpen]); a long-press stretches the range from the last one tapped and leaves the sheet open; "All
 * commits" goes back to everything. The range is held by [ui], so it survives the sheet closing.
 */
@Composable
internal fun CommitsSheet(session: ReviewSession, ui: DiffUi, overview: PrOverview, onOpen: () -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val commits = overview.commits
    val hidden = overview.commitsHidden
    var query by remember { mutableStateOf("") }
    val selection = session.selection?.let { CommitSelection(it.first, it.second) }
    val range = selection?.let { resolveCommitSelection(commits, it) }
    PullsSheet(onClose, "Commits", expandable = true) {
        if (commits.size > 1) SheetSearch(query, { query = it }, "Find a commit", Modifier.padding(top = SheetTopGap, bottom = 6.dp))
        else Spacer(Modifier.height(SheetTopGap))
        val searching = query.isNotBlank()
        // A range held on the sheet is named at the top with the way to see it, since the rows alone barely showed it
        // ("Commits four to six ... I wish I could, like, see them", 06:51 to 07:03; L11).
        if (range != null && range.first != range.last) {
            SheetCard(gap = 6.dp, modifier = Modifier.padding(bottom = 6.dp)) {
                ListRow(
                    "Commits ${range.first + 1 + hidden} to ${range.last + 1 + hidden}",
                    detail = "${range.last - range.first + 1} commits chosen · tap to see their changes",
                    first = true, selected = true,
                    onClick = { onClose(); onOpen() },
                    leading = { LuIcon(Lu.diff, tint = c.activeText, size = 20.dp) },
                )
            }
        }
        if (!searching) {
            SheetCard(gap = 6.dp) {
                ListRow(
                    "All commits", detail = if (commits.size > 1) "Every change · hold a commit to pick several" else "Every change in the pull request",
                    first = true, selected = selection == null,
                    onClick = { ui.commitAnchor = null; session.selectCommits(null); onClose() },
                    leading = { LuIcon(Lu.commit, tint = if (selection == null) c.activeText else c.muted, size = 20.dp) },
                )
                // GitHub sends the newest commits only, so the oldest ones are on GitHub alone; this row says how many.
                if (hidden > 0) ListRow(
                    "Commits 1 to $hidden are on GitHub only",
                    onClick = { uri.openUri("${overview.url}/commits") },
                    leading = { DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.muted, size = 20.dp) },
                )
            }
        }
        val days = commitDays(commits, java.time.LocalDate.now(), java.time.ZoneId.systemDefault())
            .map { (label, at) -> label to at.filter { i -> !searching || commitMatches(commits[i], query) } }
            .filter { it.second.isNotEmpty() }
        if (searching && days.isEmpty()) {
            Text("No commit matches “${query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
        }
        days.forEachIndexed { d, (label, at) ->
            // A plain heading per day, every day open: a sheet's heading folds nothing and counts nothing (M7).
            SheetHeading(label, first = searching && d == 0)
            run {
                SheetCard(gap = 6.dp) {
                    at.forEachIndexed { row, index ->
                        val commit = commits[index]
                        val picked = range != null && index in range
                        ListRow(
                            AnnotatedString(commit.headline),
                            detail = listOfNotNull(commit.authorLogin ?: commit.authorName, prTimeAgo(commit.committedDate).takeIf { it.isNotEmpty() })
                                .joinToString(" · ").ifEmpty { null }?.let(::AnnotatedString),
                            first = row == 0,
                            selected = picked,
                            onClick = {
                                ui.commitAnchor = index
                                val picked = pickCommit(commits, selection, null, index, extend = false)
                                // Tapping the one commit already chosen goes back to all of them, as All commits does
                                // ("if I click on it again, it would have just deselected it", recording 15 Oct 15:37,
                                // 07:29 to 07:39; L11).
                                session.selectCommits(picked?.pair)
                                onClose()
                                if (picked != null) onOpen()
                            },
                            onLongClick = { session.selectCommits(pickCommit(commits, selection, ui.commitAnchor, index, extend = true)?.pair) },
                            leading = { Text("${index + 1 + hidden}", style = PullsType.row.copy(fontFeatureSettings = "tnum"), color = if (picked) c.activeText else c.faint, textAlign = TextAlign.Center) },
                            trailing = {
                                commitTint(commit.checks)?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
                                Text(commit.abbreviatedOid, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.faint, maxLines = 1, softWrap = false)
                            },
                            titleLines = 2,
                            detailLines = 1,
                        )
                    }
                }
            }
        }
    }
}


/** Does [commit] match a search: its headline, its sha or who wrote it. */
internal fun commitMatches(commit: PrCommit, query: String): Boolean =
    matchesSearch(listOfNotNull(commit.headline, commit.abbreviatedOid, commit.oid, commit.authorLogin, commit.authorName).joinToString(" "), query)

/**
 * The commits by the day they were committed, oldest first, as (heading, indexes into [commits]): "Today",
 * "Yesterday", the weekday within a week, else the date. Commits with no date go with the day before them.
 */
internal fun commitDays(commits: List<PrCommit>, today: java.time.LocalDate, zone: java.time.ZoneId): List<Pair<String, List<Int>>> {
    val days = mutableListOf<Pair<String, MutableList<Int>>>()
    commits.forEachIndexed { index, commit ->
        val day = prIsoMillis(commit.committedDate)?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val label = day?.let { dayLabel(it, today) } ?: days.lastOrNull()?.first ?: "Undated"
        if (days.lastOrNull()?.first == label) days.last().second.add(index) else days.add(label to mutableListOf(index))
    }
    return days
}

private fun dayLabel(day: java.time.LocalDate, today: java.time.LocalDate): String {
    val ago = java.time.temporal.ChronoUnit.DAYS.between(day, today)
    val locale = java.util.Locale.ENGLISH
    return when {
        ago == 0L -> "Today"
        ago == 1L -> "Yesterday"
        ago in 2..6 -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        day.year == today.year -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale))
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}

/** The colour of a commit's checks, or null when it has none. */
@Composable
private fun commitTint(checks: String?): Color? {
    val c = LocalPullsColors.current
    return when (checks) {
        "success" -> StatusColors.green(c.dark)
        "failure" -> StatusColors.red(c.dark)
        "pending" -> StatusColors.amber500
        else -> null
    }
}
