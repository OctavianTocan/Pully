package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.cued
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount

/*
 * Commits, Files and Checks are plain lists on the sheet's own paper (rule 79): no panel inside the panel, rows at
 * least 64dp tall with their whole title wrapping, a hairline between rows, and no instruction that names a key a phone
 * does not have. All three are built from the one row below.
 */

private val ROW_HEIGHT = 64.dp
private val MARK_WIDTH = 24.dp

/**
 * One row of a list on a sheet. The title wraps whole and [detail] sits under it; [leading] is a 24dp mark and
 * [trailing] the facts on the right. A tap or long-press only tints the row while it is held (rule 82). [selected]
 * paints the row as part of what the screen is narrowed to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListRow(
    title: AnnotatedString,
    modifier: Modifier = Modifier,
    detail: AnnotatedString? = null,
    first: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val inset: Dp = if (leading != null) MARK_WIDTH + 12.dp + 4.dp else 4.dp
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = ROW_HEIGHT).clip(RoundedCornerShape(PullsRadius.lg))
            .background(if (selected) c.active else Color.Transparent)
            .then(if (onClick != null) Modifier.combinedClickable(role = Role.Button, onClick = cued(onClick = onClick), onLongClick = onLongClick) else Modifier)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(inset.toPx(), 0f), Size(size.width - inset.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) Box(Modifier.width(MARK_WIDTH), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PullsType.row, color = if (selected) c.activeText else c.text)
            if (detail != null) Text(detail, style = PullsType.sm, color = c.muted)
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
    }
}

@Composable
internal fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    first: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = ListRow(AnnotatedString(title), modifier, detail?.let(::AnnotatedString), first, selected, onClick, onLongClick, leading, trailing)

/** A heading over a run of rows: one word and how many, in the same quiet face as every other heading. */
@Composable
private fun ListHeading(text: String, count: Int, first: Boolean) {
    val c = LocalPullsColors.current
    Row(Modifier.fillMaxWidth().padding(top = if (first) 4.dp else 18.dp, bottom = 2.dp, start = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        Text("$count", style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.faint)
    }
}

// --- Commits ---

/**
 * The pull request's commits, oldest first. A tap shows only that commit and closes the sheet; a long-press extends
 * the range from the last one tapped. The page keeps a bar with the way back to all changes (rule 80).
 */
@Composable
internal fun CommitsSheet(session: ReviewSession, overview: PrOverview, onClose: () -> Unit) {
    val selection = session.selection?.let { CommitSelection(it.first, it.second) }
    PullsSheet(onClose, "Commits", expandable = true) {
        CommitsList(overview.commits, overview.commitsHidden, overview.url, selection) { next, close ->
            session.selectCommits(next?.pair)
            if (close) onClose()
        }
    }
}

/** The rows of [CommitsSheet]. [onSelect] gets the new selection (null for all commits) and whether the sheet should close. */
@Composable
internal fun ColumnScope.CommitsList(commits: List<PrCommit>, hidden: Int, url: String, selection: CommitSelection?, onSelect: (CommitSelection?, Boolean) -> Unit) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val range = selection?.let { resolveCommitSelection(commits, it) }
    var anchor by remember(commits) { mutableStateOf<Int?>(null) }
    ListRow(
        "All commits", first = true, selected = selection == null,
        onClick = { onSelect(null, true) },
        trailing = {
            Text("${commits.size + hidden}", style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
            if (selection == null) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
        },
    )
    if (hidden > 0) ListRow("$hidden earlier commits on GitHub", onClick = { uri.openUri("$url/commits") }, trailing = { LuIcon(Lu.ext, tint = c.faint, size = 18.dp) })
    commits.forEachIndexed { index, commit ->
        ListRow(
            commit.headline,
            detail = listOfNotNull(commit.authorLogin ?: commit.authorName, prTimeAgo(commit.committedDate).takeIf { it.isNotEmpty() }).joinToString(" · ").ifEmpty { null },
            selected = range != null && index in range,
            onClick = { val next = pickCommit(commits, selection, anchor, index, extend = false); anchor = index; onSelect(next, true) },
            onLongClick = { onSelect(pickCommit(commits, selection, anchor, index, extend = true), false) },
            leading = { LuIcon(Lu.commit, tint = commitTint(commit.checks), size = 20.dp) },
            trailing = {
                Box(
                    Modifier.defaultMinSize(minHeight = 44.dp).then(if (commit.url.isNotBlank()) Modifier.openLink { uri.openUri(commit.url) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Text(commit.abbreviatedOid, style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.faint) }
            },
        )
    }
}

@Composable
private fun commitTint(checks: String?): Color {
    val c = LocalPullsColors.current
    return when (checks) {
        "success" -> StatusColors.green(c.dark)
        "failure" -> StatusColors.red(c.dark)
        "pending" -> StatusColors.amber500
        else -> c.faint
    }
}

/** A link inside a row: it opens, and does not also pick the commit. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.openLink(onOpen: () -> Unit): Modifier = combinedClickable(role = Role.Button, onClick = cued(onClick = onOpen))

// --- Checks ---

private val BUCKET_ORDER = listOf("failing" to "Failing", "pending" to "Running", "passing" to "Passing", "skipped" to "Skipped")

/** Every check, failing first, then running, then passing; a row opens the check on GitHub. */
@Composable
internal fun ChecksSheet(overview: PrOverview, onClose: () -> Unit) {
    PullsSheet(onClose, "Checks", expandable = true) { ChecksList(overview.checks, overview.checksSummary.hidden, overview.url) }
}

@Composable
internal fun ColumnScope.ChecksList(checks: List<PrCheck>, hidden: Int, url: String) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    if (checks.isEmpty()) Text("No checks have run.", Modifier.padding(vertical = 16.dp, horizontal = 4.dp), style = PullsType.row, color = c.muted)
    var first = true
    BUCKET_ORDER.forEach { (bucket, label) ->
        val group = checks.filter { it.bucket == bucket }
        if (group.isEmpty()) return@forEach
        ListHeading(label, group.size, first)
        group.forEachIndexed { index, check ->
            ListRow(
                buildAnnotatedString {
                    check.group?.let { withStyle(SpanStyle(color = c.muted)) { append("$it / ") } }
                    append(check.name)
                },
                detail = check.summary?.let(::AnnotatedString),
                first = index == 0,
                onClick = check.url?.let { link -> { uri.openUri(link) } },
                leading = { CheckMark(bucket) },
                trailing = {
                    if (check.required) Text("Required", style = PullsType.sm, color = c.muted)
                    if (check.url != null) LuIcon(Lu.ext, tint = c.faint, size = 18.dp)
                },
            )
        }
        first = false
    }
    if (hidden > 0) ListRow("$hidden more on GitHub", Modifier.padding(top = 8.dp), onClick = { uri.openUri("$url/checks") }, trailing = { LuIcon(Lu.ext, tint = c.faint, size = 18.dp) })
}

/** The state of one check as a mark: a red cross, an amber dot that breathes, a green tick, a grey dash. */
@Composable
private fun CheckMark(bucket: String) {
    val c = LocalPullsColors.current
    when (bucket) {
        "failing" -> LuIcon(Lu.xc, tint = StatusColors.red(c.dark), size = 22.dp)
        "pending" -> Box(Modifier.size(10.dp).clip(CircleShape).background(StatusColors.amber500))
        "passing" -> LuIcon(Lu.okc, tint = StatusColors.green(c.dark), size = 22.dp)
        else -> Box(Modifier.size(width = 10.dp, height = 2.dp).clip(CircleShape).background(c.faint))
    }
}

// --- Files ---

/** The changed files in the order the page lists them, with their reviewed mark; a row opens the file's diff. */
@Composable
internal fun FilesSheet(session: ReviewSession, ui: DiffUi, onOpen: (String) -> Unit, onClose: () -> Unit) {
    PullsSheet(onClose, "Files", expandable = true) {
        FilesList(
            fileSections(session, ui), state = session::reviewState,
            onToggle = { file, reviewed -> session.setReviewed(listOf(file.sha), reviewed) },
            onOpen = { path -> onOpen(path); onClose() },
        )
    }
}

@Composable
internal fun ColumnScope.FilesList(sections: List<FileSection>, state: (ReviewFile) -> ReviewState, onToggle: (ReviewFile, Boolean) -> Unit, onOpen: (String) -> Unit) {
    val c = LocalPullsColors.current
    var first = true
    sections.forEach { section ->
        if (section.label != null) ListHeading(section.label, section.files.size, first)
        section.files.forEachIndexed { index, file ->
            val review = state(file)
            val reviewed = review == ReviewState.Reviewed
            ListRow(
                file.path.substringAfterLast('/'),
                detail = buildFileDetail(file.path.substringBeforeLast('/', ""), review),
                first = index == 0,
                onClick = { onOpen(file.path) },
                leading = { PullsCheck(reviewed, onClick = { onToggle(file, !reviewed) }, label = "Mark ${file.path} reviewed") },
                trailing = {
                    Column(horizontalAlignment = Alignment.End) {
                        val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium)
                        Text("+${compactCount(file.additions)}", style = mono, color = StatusColors.green(c.dark), maxLines = 1, softWrap = false)
                        Text("−${compactCount(file.deletions)}", style = mono, color = StatusColors.red(c.dark), maxLines = 1, softWrap = false)
                    }
                },
            )
        }
        first = false
    }
}

private fun buildFileDetail(dir: String, state: ReviewState): String? = when {
    state == ReviewState.Changed -> if (dir.isEmpty()) "Changed since you reviewed it" else "$dir · changed since you reviewed it"
    dir.isEmpty() -> null
    else -> dir
}
