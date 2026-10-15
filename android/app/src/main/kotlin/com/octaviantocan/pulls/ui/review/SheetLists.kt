package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.JEV_FILE_WORDS
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.SheetSearch
import com.octaviantocan.pulls.ui.design.matchesSearch
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.LocalToaster
import androidx.compose.ui.platform.LocalContext

/*
 * Files and Checks are plain lists on the sheet's own paper (rule 79): no panel inside the panel, rows at
 * least 64dp tall with their whole title wrapping, a hairline between rows, and no instruction that names a key a phone
 * does not have. Both are built from the one row below.
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
    under: (@Composable () -> Unit)? = null,
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
            under?.invoke()
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
                },
            )
        }
        first = false
    }
    if (hidden > 0) ListRow("$hidden more on GitHub", Modifier.padding(top = 8.dp), onClick = { uri.openUri("$url/checks") })
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

/**
 * The changed files in the order the page lists them, with their reviewed mark; a row opens the file's diff. A search
 * field narrows them by name, the sections fold like the Overview's (they share the folds), and [current], the file
 * the diff is on, is marked and its section open.
 */
@Composable
internal fun FilesSheet(session: ReviewSession, ui: DiffUi, current: String?, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val total = session.files.size
    PullsSheet(onClose, "Files", expandable = true) {
        if (total > 1) SheetSearch(query, { query = it }, "Find a file", Modifier.padding(top = 8.dp, bottom = 6.dp))
        FilesList(
            fileSections(session, ui), query, current, state = session::reviewState, attention = session.attention,
            // The section of the file the diff is on starts open, whatever the size of the change.
            folded = { section -> ui.folds[section.label.orEmpty()] ?: (total > FOLD_ABOVE && section.files.none { it.path == current }) },
            onFold = { section, fold -> ui.folds[section.label.orEmpty()] = fold },
            onToggle = { file, reviewed -> session.setReviewed(listOf(file.sha), reviewed) },
            onOpen = { path -> onOpen(path); onClose() },
        )
    }
}

/** What a long-press on a file row offers (rule 56): open it, mark it, copy its path, or ask about it. */
@Composable
internal fun FileActionsSheet(session: ReviewSession, file: ReviewFile, onOpen: () -> Unit, onAsk: () -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val reviewed = session.reviewState(file) == ReviewState.Reviewed
    val name = file.path.substringAfterLast('/')
    PullsSheet(onClose, name) {
        Text(file.path, Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp), style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
        SheetCard {
            // The four are words; a speech bubble on Ask carried no fact, and one glyph short of a column looks broken (rule 48).
            SheetRow("Open", { onClose(); onOpen() }, first = true, iconless = true)
            SheetRow(if (reviewed) "Mark as not reviewed" else "Mark reviewed", { session.setReviewed(listOf(file.sha), !reviewed); onClose() }, iconless = true)
            SheetRow("Copy path", { copyText(context, file.path); toaster.show("Copied $name", cue = Cue.Success); onClose() }, iconless = true)
            SheetRow("Ask about this file", { onClose(); onAsk() }, iconless = true)
        }
    }
}

/** Without a search, a list this long shows its first rows and a row that shows the rest, so a huge change opens at once. */
private const val FIRST_ROWS = 300

@Composable
internal fun ColumnScope.FilesList(
    sections: List<FileSection>,
    query: String,
    current: String?,
    state: (ReviewFile) -> ReviewState,
    folded: (FileSection) -> Boolean,
    onFold: (FileSection, Boolean) -> Unit,
    onToggle: (ReviewFile, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    attention: Map<String, String> = emptyMap(),
) {
    val c = LocalPullsColors.current
    val searching = query.isNotBlank()
    val shown = if (searching) sections.map { s -> s.copy(files = s.files.filter { matchesSearch(it.path, query) }) }.filter { it.files.isNotEmpty() } else sections
    if (searching && shown.isEmpty()) {
        Text("No file matches “${query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
        return
    }
    var all by remember(sections) { mutableStateOf(false) }
    var budget = if (searching || all) Int.MAX_VALUE else FIRST_ROWS
    var first = true
    shown.forEach { section ->
        // A search opens every section it found something in; otherwise a heading folds its files away.
        val closed = section.label != null && !searching && folded(section)
        if (section.label != null) {
            FoldHeading(section.label, section.files.size, first, closed, onClick = if (searching) null else ({ onFold(section, !closed) }))
        }
        first = false
        if (closed || budget <= 0) return@forEach
        section.files.take(budget).forEachIndexed { index, file ->
            val review = state(file)
            val reviewed = review == ReviewState.Reviewed
            val dir = file.path.substringBeforeLast('/', "")
            val mark = attention[file.path]?.takeIf { it in JEV_FILE_WORDS }
            ListRow(
                AnnotatedString(file.path.substringAfterLast('/')),
                // The folder cut from the front (rule 90), so a deep path never takes two lines.
                under = if (dir.isEmpty() && review != ReviewState.Changed && mark == null) null else ({
                    if (dir.isNotEmpty() || mark != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (mark != null) JevMark(mark, trailingDot = dir.isNotEmpty())
                            if (dir.isNotEmpty()) PathTail(dir, PullsType.sm, c.muted, Modifier.weight(1f, fill = false))
                        }
                    }
                    if (review == ReviewState.Changed) Text("Changed since you reviewed it", style = PullsType.sm, color = c.orange)
                }),
                first = index == 0,
                selected = file.path == current,
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
        budget -= section.files.size
    }
    if (budget < 0) {
        ListRow("Show all ${sections.sumOf { it.files.size }} files", onClick = { all = true }, trailing = { LuIcon(Lu.chevd, tint = c.faint, size = 18.dp) })
    }
}

/**
 * Jev's word on a file, starting its second line at the meta size (rule 14): "Needs a look" in the amber the app gives
 * warnings for a file that needs a human (`p0`), "Worth a look" and "Skim" in the muted ink for the rest (`p1`, `p2`).
 * With [trailingDot] a faint dot parts it from the folder that follows on the same line.
 */
@Composable
internal fun JevMark(priority: String, trailingDot: Boolean) {
    val c = LocalPullsColors.current
    val words = JEV_FILE_WORDS[priority] ?: return
    Text(words, style = PullsType.sm, color = if (priority == "p0") c.amber else c.muted, maxLines = 1, softWrap = false)
    if (trailingDot) Text(" · ", style = PullsType.sm, color = c.faint, maxLines = 1, softWrap = false)
}

/**
 * A section's heading that folds it: its name, how many files, and a chevron that points down while it is open. It
 * is quiet like every heading (rule 75); the whole line is the target.
 */
@Composable
internal fun FoldHeading(label: String, count: Int, first: Boolean, folded: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)?) {
    val c = LocalPullsColors.current
    val turn by animateFloatAsState(if (folded) -90f else 0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "fold")
    Row(
        modifier.fillMaxWidth().padding(top = if (first) 4.dp else 12.dp).clip(RoundedCornerShape(PullsRadius.md))
            .then(if (onClick != null) Modifier.cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = if (folded) "Show $label" else "Hide $label", onClick = onClick) else Modifier)
            .heightIn(min = TOUCH).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, Modifier.weight(1f, fill = false), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
            Text("$count", style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.faint)
        }
        if (onClick != null) LuIcon(Lu.chevd, Modifier.rotate(turn), tint = c.faint, size = 18.dp)
    }
}

