package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ReviewThread
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.Spinner

/*
 * The diff rows of one file, as the Android prototype draws them: a number column, a sign column
 * and the code on a full-bleed row, tinted teal for additions and rose for deletions, the changed
 * words marked darker, 36dp "unchanged lines" bars between hunks, a 3dp accent bar on lines that
 * carry a thread or a selection, and the annotations (threads, AI notes, the selection's actions)
 * indented under their line. Tapping a line selects it; the screen's bottom bar comments on it.
 */

/** The prototype's `.dl`: 12.5 on 20 in the reader's code face (Geist Mono by default), scaled by the phone's code zoom so size and line height move together. */
private fun codeStyle(zoom: Float): TextStyle = TextStyle(
    fontFamily = PullsType.mono, fontSize = (12.5f * zoom).sp, lineHeight = (20f * zoom).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** The line-number column: wide enough for [digits] at [zoom], never under 26dp. */
internal fun numberWidth(digits: Int, zoom: Float): Dp = (maxOf(34f, digits * 8f + 10f) * zoom).coerceAtLeast(26f).dp

/** The `+`/`−` column beside the number. */
private fun signWidth(zoom: Float): Dp = (14f * zoom).coerceAtLeast(10f).dp

/** The number and sign columns together: where the code, and the annotations under it, start. */
internal fun gutterWidth(digits: Int, zoom: Float): Dp = numberWidth(digits, zoom) + signWidth(zoom)

/** Colors of one file body: the prototype's washes for the changed rows, ink for the rest. */
@Stable
private class Palette(c: PullsColors) {
    val bg = c.raised
    val fg = c.text
    val number = c.faint
    val added = c.diffAdd
    val removed = c.diffDel
    val addInk = c.diffAddInk
    val delInk = c.diffDelInk
    val addMark = c.diffAddMark
    val delMark = c.diffDelMark
    val selection = c.accentTeal.copy(alpha = 0.12f)
    val bar = c.accentTeal
    val separator = c.paper
    val buffer = c.paper2
    val rule = c.borderMute
}

/** The empty side of a split row, hatched with 45 degree stripes 8dp apart. */
private fun Modifier.hatch(bg: Color, stripe: Color): Modifier = this.background(bg).drawBehind {
    val step = 8.dp.toPx()
    val w = 1.4.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), w)
        x += step
    }
}

/** A shared sideways scroll for the code of one file (one per side in split view). */
internal class HScroll {
    var offset by mutableFloatStateOf(0f)
    var max = 0f
}

/** Threads, AI content and the draft at one place in the file. */
private class Annotations(val threads: MutableList<ReviewThread> = mutableListOf(), val ai: MutableList<AiAnchor> = mutableListOf(), var draft: DraftTarget? = null)

/**
 * One file as the diff screen shows it: a notice for a binary or oversized file, else the rows with
 * their annotations. Fills [emit] with lazy items; the screen scrolls them.
 */
internal fun fileBodyItems(emit: Emitter, session: ReviewSession, ui: DiffUi, file: ReviewFile) {
    val path = file.path
    val full = ui.fullFiles[path]
    when {
        file.isBinary -> emit.item("diff:fm:$path", "file-message", path) { FileMessage(t("file.binaryNotShown"), null) }
        file.truncated && full == null -> emit.item("diff:fm:$path", "file-message", path) { FileMessage(t("file.tooLarge"), file.takeIf { ui.canLoadFull(it) }?.let { f -> { ui.loadFullFile(f) } }) }
        else -> rowItems(emit, session, ui, file)
    }
}

private fun rowItems(emit: Emitter, session: ReviewSession, ui: DiffUi, file: ReviewFile) {
    val path = file.path
    val entries = ui.entries(file)
    val split = effectiveSplit(session.settings.layout, file, ui.widthDp)
    // Rows as line lists, to place anchors: a unified row is one line, a split row up to two.
    val splitRows = if (split) splitEntries(entries) else emptyList()
    val rowLines: List<List<DiffLine>> = if (split) splitRows.map { e -> if (e is SplitEntry.Pair) listOfNotNull(e.left, e.right) else emptyList() }
    else entries.map { e -> if (e is DiffEntry.Line) listOf(e.line) else emptyList() }

    // Outdated threads stay hidden; resolved ones show folded (tap to open).
    val threads = visibleThreads(session.reviewData?.threads.orEmpty(), path, session.settings.showThreads, includeResolved = true)
    val ai = aiAnchors(
        file,
        session.visibleFindings,
        session.saved?.analysis?.files?.firstOrNull { it.path == path },
        session.saved?.asks.orEmpty(),
    )
    val draft = ui.drafts[path]
    val wanted = threads.map { Anchor(it.side.ifBlank { "additions" }, it.line!!) } + ai.keys + listOfNotNull(draft?.let { Anchor(it.side, it.line) })
    val placed = placeAnchors(rowLines, wanted)
    val byRow = HashMap<Int, Annotations>()
    threads.forEach { th -> placed[Anchor(th.side.ifBlank { "additions" }, th.line!!)]?.let { byRow.getOrPut(it) { Annotations() }.threads += th } }
    ai.forEach { (anchor, content) -> placed[anchor]?.let { byRow.getOrPut(it) { Annotations() }.ai += content } }
    draft?.let { d -> placed[Anchor(d.side, d.line)]?.let { byRow.getOrPut(it) { Annotations() }.draft = d } }

    val digits = (file.patchLines.maxOfOrNull { maxOf(it.newLine ?: 0, it.oldLine ?: 0) } ?: 0)
        .coerceAtLeast(ui.fullFiles[path]?.let { maxOf(it.new?.count { ch -> ch == '\n' } ?: 0, it.old?.count { ch -> ch == '\n' } ?: 0) + 1 } ?: 0)
        .toString().length

    val count = if (split) splitRows.size else entries.size
    for (i in 0 until count) {
        val key = "diff:l:$path:$i"
        rowLines[i].forEach { line -> line.anchors().forEach { a -> emit.lines.putIfAbsent("$path|${a.side}|${a.line}", key) } }
        val threaded = byRow[i]?.threads?.isNotEmpty() == true
        if (split) {
            when (val row = splitRows[i]) {
                is SplitEntry.Gap -> emit.item(key, "gap", path) { GapRow(ui, file, row.gap, i == 0) }
                is SplitEntry.Pair -> emit.item(key, "split", path) { SplitRowView(ui, file, row, digits, threaded) }
            }
        } else {
            when (val entry = entries[i]) {
                is DiffEntry.Gap -> emit.item(key, "gap", path) { GapRow(ui, file, entry, i == 0) }
                is DiffEntry.Line -> emit.item(key, "line", path) { UnifiedRow(ui, file, entry.line, digits, threaded) }
            }
        }
        byRow[i]?.let { notes ->
            emit.item("diff:a:$path:$i", "annotation", path) { AnnotationRow(session, ui, file, notes, digits) }
        }
    }
}

// --- Body pieces ---

@Composable
private fun FileMessage(text: String, onLoad: (() -> Unit)?) {
    val c = LocalPullsColors.current
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, style = PullsType.sm, color = c.muted)
        if (onLoad != null) PillButton(t("file.loadFull"), onLoad)
    }
}

/** `.hunk`: a 36dp bar between hunks, "N unchanged lines"; a tap loads the file and opens them. */
@Composable
private fun GapRow(ui: DiffUi, file: ReviewFile, gap: DiffEntry.Gap, first: Boolean) {
    val c = LocalPullsColors.current
    val loading = ui.fullLoading[file.path] == true
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(c.paper)
            .drawBehind {
                val w = 1.dp.toPx()
                if (!first) drawRect(c.borderMute, Offset.Zero, Size(size.width, w))
                drawRect(c.borderMute, Offset(0f, size.height - w), Size(size.width, w))
            }
            .clickable(enabled = !file.isBinary && !loading, role = Role.Button, onClickLabel = t("file.loadFull")) { ui.loadFullFile(file, gap.id) },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) Spinner(14.dp, c.muted) else LuIcon(Lu.exp, tint = c.muted, size = 16.dp)
        Text("${gap.count} unchanged line${if (gap.count == 1) "" else "s"}", style = PullsType.sm, color = c.muted, maxLines = 1)
    }
}

/** A line's code as an annotated string: server spans, or the plain text while they load. */
@Composable
private fun codeText(ui: DiffUi, file: ReviewFile, text: String, emphasis: List<IntRange>?, emphasisBg: Color): AnnotatedString {
    val toks = ui.tokens[file.path]?.get(text)
    return remember(text, toks, emphasis, emphasisBg) {
        buildAnnotatedString {
            if (toks == null || toks.isEmpty()) append(text)
            else toks.forEach { tok -> if (tok.color == null) append(tok.text) else withStyle(SpanStyle(color = tok.color)) { append(tok.text) } }
            // `mark`: the changed words of a paired line, in the stronger wash.
            emphasis?.forEach { r -> if (r.last < length) addStyle(SpanStyle(background = emphasisBg), r.first, r.last + 1) }
            if (length == 0) append(" ")
        }
    }
}

/** One side of a line as the user acts on it: a context line is `additions` in unified view and on the right, `deletions` on the left. */
private data class Cell(val side: String, val number: Int?)

private fun DiffLine.cell(left: Boolean): Cell = if (left || kind == '-') Cell("deletions", oldLine) else Cell("additions", newLine)

/** Is this cell in the open draft or selection of its file, or the focused line? */
private fun DiffUi.isSelected(file: ReviewFile, cell: Cell): Boolean {
    val n = cell.number ?: return false
    val draft = drafts[file.path]
    if (draft != null && draft.side == cell.side && n in draft.range) return true
    val start = selectionStart[file.path]
    if (start != null && start.side == cell.side && start.line == n) return true
    val drag = dragSel
    if (drag != null && drag.path == file.path && drag.side == cell.side && n in drag.range) return true
    val m = mark
    return m != null && m.path == file.path && m.side == cell.side && m.line == n
}

/**
 * A tap on a line. It selects it (the bottom bar then comments on it); tapping the one selected line
 * again clears it; with a range started by a long press it completes that range.
 */
private fun DiffUi.tapLine(file: ReviewFile, cell: Cell) {
    val n = cell.number ?: return
    val start = selectionStart[file.path]
    if (start != null && start.side == cell.side) {
        selectionStart.remove(file.path)
        drafts[file.path] = selectRange(file.path, cell.side, start.line, n)
        return
    }
    val draft = drafts[file.path]
    if (draft != null && draft.side == cell.side && draft.line == n && draft.startLine == null) {
        drafts.remove(file.path)
        return
    }
    mark = null
    drafts[file.path] = DraftTarget(file.path, cell.side, n)
}

/** Long press: starts a range selection at this line; tap the last line to finish it. */
private fun DiffUi.onCodeLongPress(file: ReviewFile, cell: Cell) {
    val n = cell.number ?: return
    drafts.remove(file.path)
    selectionStart[file.path] = DraftTarget(file.path, cell.side, n)
}

@Composable
private fun UnifiedRow(ui: DiffUi, file: ReviewFile, line: DiffLine, digits: Int, threaded: Boolean) {
    val c = LocalPullsColors.current
    val p = remember(c) { Palette(c) }
    val scroll = remember(file.path) { ui.scrolls.getOrPut(file.path to 0) { HScroll() } }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        LineCell(ui, file, line, digits, p, scroll, Modifier.weight(1f), threaded = threaded)
    }
}

@Composable
private fun SplitRowView(ui: DiffUi, file: ReviewFile, row: SplitEntry.Pair, digits: Int, threaded: Boolean) {
    val c = LocalPullsColors.current
    val p = remember(c) { Palette(c) }
    val left = remember(file.path) { ui.scrolls.getOrPut(file.path to 1) { HScroll() } }
    val right = remember(file.path) { ui.scrolls.getOrPut(file.path to 2) { HScroll() } }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // A context line shows its old number on the left and its new number on the right.
        if (row.left != null) LineCell(ui, file, row.left, digits, p, left, Modifier.weight(1f), leftColumn = true, threaded = threaded)
        else Box(Modifier.weight(1f).fillMaxHeight().hatch(p.bg, p.buffer))
        Box(Modifier.width(2.dp).fillMaxHeight().background(p.bg))
        if (row.right != null) LineCell(ui, file, row.right, digits, p, right, Modifier.weight(1f), threaded = threaded)
        else Box(Modifier.weight(1f).fillMaxHeight().hatch(p.bg, p.buffer))
    }
}

/**
 * One line: the number and sign columns (tap to select, long-press then drag to take a range), and
 * the code, which scrolls sideways with the rest of its file or wraps with a hanging indent.
 */
@Composable
private fun LineCell(ui: DiffUi, file: ReviewFile, line: DiffLine, digits: Int, p: Palette, scroll: HScroll, modifier: Modifier, leftColumn: Boolean = false, threaded: Boolean = false) {
    val cell = line.cell(leftColumn)
    val selected = ui.isSelected(file, cell)
    val lineBg = when (line.kind) { '+' -> p.added; '-' -> p.removed; else -> p.bg }
    val rowBg = if (selected) p.selection.compositeOver(lineBg) else lineBg
    val ink = when (line.kind) { '+' -> p.addInk; '-' -> p.delInk; else -> p.number }
    val number = cell.number
    val zoom = ui.session.settings.codeZoom
    val wrap = ui.session.settings.codeWrap
    val style = codeStyle(zoom)
    val numberCol = numberWidth(digits, zoom)
    val signCol = signWidth(zoom)
    val emphasisBg = when (line.kind) { '+' -> p.addMark; '-' -> p.delMark; else -> Color.Transparent }
    val code = codeText(ui, file, line.text, ui.emphasis(file)[line], emphasisBg)
    val drag = rememberDraggableState { delta -> scroll.offset = (scroll.offset - delta).coerceIn(0f, scroll.max.coerceAtLeast(0f)) }
    val label = t("review.leaveComment")
    Row(
        modifier.fillMaxHeight().background(rowBg).drawBehind { if (selected || threaded) drawRect(p.bar, Offset.Zero, Size(3.dp.toPx(), size.height)) },
    ) {
        Row(
            Modifier.width(numberCol + signCol).fillMaxHeight()
                .onGloballyPositioned { co -> if (number != null) ui.gutterY["${file.path}|${cell.side}|$number"] = co.positionInRoot().y + co.size.height / 2f }
                // Drag line selection, touch style: long-press a line number, drag to extend, lift to select the range.
                .pointerInput(file.path, cell, file.isBinary) {
                    if (file.isBinary || number == null) return@pointerInput
                    var y = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            y = ui.gutterY["${file.path}|${cell.side}|$number"] ?: 0f
                            ui.drafts.remove(file.path)
                            ui.dragSel = DraftTarget(file.path, cell.side, number)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            y += amount.y
                            val to = ui.lineAt(file.path, cell.side, y) ?: return@detectDragGesturesAfterLongPress
                            ui.dragSel = selectRange(file.path, cell.side, number, to)
                        },
                        onDragEnd = { ui.dragSel?.let { ui.drafts[file.path] = it }; ui.dragSel = null },
                        onDragCancel = { ui.dragSel = null },
                    )
                }
                .clickable(enabled = !file.isBinary && number != null, role = Role.Button, onClickLabel = label) { ui.tapLine(file, cell) },
        ) {
            Text(number?.toString().orEmpty(), Modifier.width(numberCol).padding(end = 6.dp), style = style.copy(textAlign = TextAlign.End), color = ink, maxLines = 1)
            Text(when (line.kind) { '+' -> "+"; '-' -> "−"; else -> "" }, Modifier.width(signCol), style = style, color = ink, maxLines = 1)
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .let { if (wrap) it else it.draggable(drag, Orientation.Horizontal) }
                .combinedClickable(onLongClick = { ui.onCodeLongPress(file, cell) }) { ui.tapLine(file, cell) }
                .clipToBounds()
                .padding(start = 2.dp, end = if (wrap) 12.dp else 0.dp),
        ) {
            if (wrap) {
                // Wrapped: the row grows to fit and the number stays on the first visual line. The
                // indentation becomes a hanging indent, so continuations line up under the code
                // instead of the leading spaces taking a visual line of their own.
                val measurer = rememberTextMeasurer()
                val space = remember(style) { measurer.measure(" ", style).size.width }
                val density = LocalDensity.current
                val lead = line.text.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) 0 else it }
                val columns = line.text.take(lead).sumOf { if (it == '\t') 4L else 1L }.toInt()
                // Deep nesting would leave a sliver for the code: the indent stops at a third of the
                // column. Rows are measured intrinsically, so read the width from the window, not a
                // SubcomposeLayout (BoxWithConstraints throws under intrinsic measurement).
                val window = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                val column = if (ui.session.settings.layout == "split" && LocalConfiguration.current.screenWidthDp >= 640) window / 2f else window
                val indent = with(density) { minOf(space * columns.toFloat(), column / 3f).toSp() }
                Text(
                    if (lead > 0 && lead <= code.length) code.subSequence(lead, code.length) else code,
                    style = style.copy(color = p.fg, textIndent = TextIndent(firstLine = indent, restLine = indent)),
                    softWrap = true,
                )
            } else Text(
                code,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints(minHeight = constraints.minHeight, maxHeight = constraints.maxHeight))
                    val viewport = constraints.maxWidth
                    scroll.max = maxOf(scroll.max, (placeable.width - viewport + 16).toFloat())
                    layout(viewport, placeable.height) { placeable.place(-scroll.offset.toInt(), 0) }
                },
                style = style.copy(color = p.fg),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/** Threads, AI notes/findings/asks and the selection's actions under one row, indented to the code. */
@Composable
private fun AnnotationRow(session: ReviewSession, ui: DiffUi, file: ReviewFile, notes: Annotations, digits: Int) {
    val c = LocalPullsColors.current
    val gutter = gutterWidth(digits, ui.session.settings.codeZoom)
    Column(
        Modifier.fillMaxWidth().background(c.raised).padding(start = gutter, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        notes.threads.forEach { ReviewThreadCard(session, it) }
        notes.ai.forEach { AiAnnotationBlock(session, it, ui.mark?.findingId) }
        notes.draft?.let { draft -> DraftBox(session, draft, file.isBinary) { ui.drafts.remove(file.path) } }
    }
}
