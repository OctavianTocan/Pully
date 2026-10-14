package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.sound.cued
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.home.DrawableIcon
import com.octaviantocan.pulls.ui.home.StatusColors

/**
 * The Commits tab (rule 91): every commit of the pull request, oldest first and numbered, so the order is never a
 * guess. A tap narrows the pull request to that commit, a long-press stretches the range from the last one tapped,
 * and "All commits" goes back to everything; the banner under the capsule says what is shown (rule 80). [anchor] is
 * where a long-press range starts, kept by the screen so it survives the rows scrolling away.
 */
internal fun LazyListScope.commitItems(session: ReviewSession, overview: PrOverview, anchor: MutableState<Int?>, rows: Modifier) {
    val commits = overview.commits
    val hidden = overview.commitsHidden
    item(key = "commits-head") {
        val c = LocalPullsColors.current
        val total = commits.size + hidden
        Row(rows.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$total commit${if (total == 1) "" else "s"}", style = PullsType.xl, color = c.text)
            Text("Oldest first", style = PullsType.sm, color = c.muted)
        }
    }
    item(key = "commits-all") {
        val selection = session.selection
        CommitRow(
            rows, number = null, title = "All commits", detail = "Every change in the pull request", checks = null, sha = null,
            selected = selection == null, ruled = false,
            onClick = { anchor.value = null; session.selectCommits(null) },
        )
    }
    if (hidden > 0) item(key = "commits-hidden") {
        val c = LocalPullsColors.current
        val uri = LocalUriHandler.current
        // GitHub sends the newest commits only, so the oldest ones are on GitHub alone; this row says how many, and opens them there.
        Row(
            rows.fillMaxWidth().hairline(c.borderMute).clip(RoundedCornerShape(PullsRadius.lg))
                .combinedClickableCued(onClick = { uri.openUri("${overview.url}/commits") })
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.width(NUMBER_WIDTH), contentAlignment = Alignment.Center) { DrawableIcon(R.drawable.ic_carbon_logo_github, tint = c.muted, size = 20.dp) }
            Text("Commits 1 to $hidden are on GitHub only", Modifier.weight(1f), style = PullsType.row, color = c.text)
        }
    }
    itemsIndexed(commits, key = { _, commit -> "commit:${commit.oid}" }) { index, commit ->
        val selection = session.selection?.let { CommitSelection(it.first, it.second) }
        val range = selection?.let { resolveCommitSelection(commits, it) }
        CommitRow(
            rows,
            number = index + 1 + hidden,
            title = commit.headline,
            detail = listOfNotNull(commit.authorLogin ?: commit.authorName, prTimeAgo(commit.committedDate).takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { null },
            checks = commit.checks,
            sha = commit.abbreviatedOid,
            selected = range != null && index in range,
            ruled = true,
            onClick = { val next = pickCommit(commits, selection, anchor.value, index, extend = false); anchor.value = index; session.selectCommits(next?.pair) },
            onLongClick = { session.selectCommits(pickCommit(commits, selection, anchor.value, index, extend = true)?.pair) },
        )
    }
}

private val NUMBER_WIDTH = 28.dp

/** One commit: its place in the order, the headline whole, who and when under it, and its short sha with a dot for its checks. */
@Composable
private fun CommitRow(
    modifier: Modifier,
    number: Int?,
    title: String,
    detail: String?,
    checks: String?,
    sha: String?,
    selected: Boolean,
    ruled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    Row(
        modifier.fillMaxWidth().then(if (ruled) Modifier.hairline(c.borderMute) else Modifier)
            .clip(RoundedCornerShape(PullsRadius.lg)).background(if (selected) c.active else Color.Transparent)
            .combinedClickableCued(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(NUMBER_WIDTH), contentAlignment = Alignment.TopCenter) {
            if (number != null) Text("$number", style = PullsType.row.copy(fontFeatureSettings = "tnum"), color = if (selected) c.activeText else c.faint, textAlign = TextAlign.Center)
            else LuIcon(Lu.commit, tint = if (selected) c.activeText else c.muted, size = 20.dp, modifier = Modifier.padding(top = 2.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = if (selected) c.activeText else c.text)
            if (detail != null) Text(detail, style = PullsType.sm, color = c.muted)
        }
        if (sha != null) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            commitTint(checks)?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
            Text(sha, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.faint)
        }
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

/** A row that only tints while it is held (rule 82), with the tap's sound. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCued(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    combinedClickable(role = Role.Button, onClick = cued(onClick = onClick), onLongClick = onLongClick)

private fun Modifier.hairline(color: Color): Modifier = drawBehind { drawRect(color, Offset.Zero, Size(size.width, 1.dp.toPx())) }
