package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.JEV_FILE_WORDS
import com.octaviantocan.pulls.sound.cueCombinedClickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import com.octaviantocan.pulls.ui.home.GroupHeading
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.DiffCounts
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.SheetSearch
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.design.matchesSearch
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.LocalToaster
import androidx.compose.ui.platform.LocalContext

/*
 * Files and Checks are lists in the raised cards every other sheet uses, one card under each heading (rule 79, J24): rows
 * at least 64dp tall with their whole title wrapping, a hairline between rows, and no instruction that names a key a
 * phone does not have. Both are built from the one row below.
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
    titleLines: Int = Int.MAX_VALUE,
    detailLines: Int = Int.MAX_VALUE,
) {
    val c = LocalPullsColors.current
    // The rows sit in a card, so the text starts 16dp in and the line between rows starts where the text does, as a
    // SheetRow's does (J24).
    val inset: Dp = if (leading != null) 16.dp + MARK_WIDTH + 12.dp else 16.dp
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = ROW_HEIGHT)
            .background(if (selected) c.active else Color.Transparent)
            .then(if (onClick != null) Modifier.cueCombinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick) else Modifier)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(inset.toPx(), 0f), Size(size.width - inset.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) Box(Modifier.width(MARK_WIDTH), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PullsType.row, color = if (selected) c.activeText else c.text, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = PullsType.sm, color = c.muted, maxLines = detailLines, overflow = TextOverflow.Ellipsis)
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

// --- Checks ---

private val BUCKET_ORDER = listOf("failing" to "Failing", "pending" to "Running", "passing" to "Passing", "skipped" to "Skipped")

/**
 * Every check, failing first, then running, then passing, built as the Files sheet is (K28, K29): "Find a check", a
 * fold heading over each state, and rows whose name takes at most two lines with the rest on one line under it.
 */
@Composable
internal fun ChecksSheet(overview: PrOverview, onClose: () -> Unit) {
    PullsSheet(onClose, "Checks", expandable = true) { ChecksList(overview.checks, overview.checksSummary.hidden, overview.url) }
}

@Composable
internal fun ColumnScope.ChecksList(checks: List<PrCheck>, hidden: Int, url: String) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    var query by remember { mutableStateOf("") }
    // The search, or the air in its place, keeps the first heading clear of the sheet's ✕ (K28, M7).
    if (checks.size > 1) SheetSearch(query, { query = it }, "Find a check", Modifier.padding(top = SheetTopGap, bottom = 6.dp))
    else Spacer(Modifier.height(SheetTopGap))
    if (checks.isEmpty()) Text("No checks have run.", Modifier.padding(vertical = 16.dp, horizontal = 4.dp), style = PullsType.row, color = c.muted)
    val searching = query.isNotBlank()
    val found = if (searching) checks.filter { matchesSearch(listOfNotNull(it.group, it.name).joinToString(" / "), query) } else checks
    if (searching && found.isEmpty()) {
        Text("No check matches “${query.trim()}”.", Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
    }
    var first = true
    BUCKET_ORDER.forEach { (bucket, label) ->
        val group = found.filter { it.bucket == bucket }
        if (group.isEmpty()) return@forEach
        // A plain heading as on every sheet, and the checks under it always shown: failing first (M7).
        SheetHeading(label, first = first)
        first = false
        run {
            SheetCard(gap = 6.dp) {
                group.forEachIndexed { index, check ->
                    // The name on top; where it runs, whether it is required and what it said share one line under it.
                    val detail = listOfNotNull(check.group, "Required".takeIf { check.required }, check.summary?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    ListRow(
                        AnnotatedString(check.name),
                        detail = detail.ifEmpty { null }?.let(::AnnotatedString),
                        first = index == 0,
                        onClick = check.url?.let { link -> { uri.openUri(link) } },
                        leading = { CheckMark(bucket) },
                        titleLines = 2,
                        detailLines = 1,
                    )
                }
            }
        }
    }
    if (hidden > 0 && !searching) SheetCard(Modifier.padding(top = 12.dp)) { ListRow("$hidden more on GitHub", first = true, onClick = { uri.openUri("$url/checks") }) }
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
 * field narrows them by name, the sections sit under plain headings and fold nothing, as on every sheet (M7), and
 * [current], the file the diff is on, is marked.
 */
@Composable
internal fun FilesSheet(session: ReviewSession, ui: DiffUi, current: String?, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val total = session.files.size
    com.octaviantocan.pulls.ui.TrackSearch("files", query, if (query.isBlank()) 0 else session.files.count { matchesSearch(it.path, query) })
    PullsSheet(onClose, "Files", expandable = true) {
        if (total > 1) SheetSearch(query, { query = it }, "Find a file", Modifier.padding(top = SheetTopGap, bottom = 6.dp))
        FilesList(
            fileSections(session, ui), query, current, state = session::reviewState, attention = session.attention,
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
        // A sheet's heading folds nothing (M7): every section is open, under a plain heading with its + and − (L20).
        val closed = false
        if (section.label != null) {
            val adds = section.files.sumOf { it.additions }
            val dels = section.files.sumOf { it.deletions }
            SheetHeading(section.label, first = first, extra = { SectionChanges(adds, dels) })
        }
        first = false
        // The files sit in a card under their heading.
        val files = if (budget > 0) section.files.take(budget) else emptyList()
        AnimatedVisibility(
            visible = !closed && files.isNotEmpty(),
            enter = expandVertically(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + fadeIn(tween(PullsMotion.FAST)),
            exit = shrinkVertically(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + fadeOut(tween(PullsMotion.FAST)),
        ) {
        SheetCard(gap = 6.dp) {
        files.forEachIndexed { index, file ->
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
                // Side by side, as the Inbox shows them (K27).
                trailing = { DiffCounts(file.additions, file.deletions) },
            )
        }
        }
        }
        if (!closed) budget -= section.files.size
    }
    if (budget < 0) {
        SheetCard { ListRow("Show all ${sections.sumOf { it.files.size }} files", first = true, onClick = { all = true }, trailing = { LuIcon(Lu.chevd, tint = c.faint, size = 18.dp) }) }
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
 * A section's heading that folds it: the one fold heading the Inbox uses (J18), with its name, how many files in
 * parentheses after it, and a chevron right beside them that turns as it folds (N1). Its + and − sit at the right edge
 * in the columns of the files' own (N35). It draws the one hairline under it ("there's no horizontal line under
 * Failing, Passing, Skipped, which is how these usually look": recording 15 Oct 15:37, 08:11; L12), and no background.
 * Pages only: a sheet heads its sections with the plain `SheetHeading` (rule 141).
 */
@Composable
internal fun FoldHeading(
    label: String,
    count: Int,
    first: Boolean,
    folded: Boolean,
    modifier: Modifier = Modifier,
    line: Boolean = true,
    background: Color = Color.Transparent,
    changes: Pair<Int, Int>? = null,
    onClick: (() -> Unit)?,
) = GroupHeading(
    label, count, first, folded, onFold = onClick, onOpen = null, modifier = modifier, line = line, background = background,
    extra = changes?.let { (adds, dels) -> { SectionChanges(adds, dels) } },
)

/**
 * A file section's + and − numbers on its heading, so a group says how big it is before it is opened ("I wish they
 * had ... the diff numbers", recording 15 Oct 15:37, 10:56 to 11:04; L20). They are the rows' own [DiffCounts], in the
 * same fixed columns, so every number of a list sits in one column whatever its digits (recording 16 Oct 12:10, 18:03
 * to 18:21; N35).
 */
@Composable
private fun SectionChanges(adds: Int, dels: Int) = DiffCounts(adds, dels)
