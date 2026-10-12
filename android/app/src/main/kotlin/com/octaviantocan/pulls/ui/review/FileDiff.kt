package com.octaviantocan.pulls.ui.review

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ReviewThread
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.FileStatus
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewCheckbox
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.delay

/*
 * Port of `FileDiff.vue` with what `@pierre/diffs` draws inside it: the sticky file header,
 * the "changed since reviewed" banner, the AI file summary, unified and split rows with
 * pierre-theme spans and washes, "N unmodified lines" separators that load the full file,
 * gutter comments and line-range selection, and annotations under their lines (review
 * threads, AI hunk notes, critique findings, ask threads, the draft composer).
 */

/** Pierre's diff text: 13px on 20px lines, monospace. */
private val CODE = TextStyle(
    fontFamily = PullsType.mono, fontSize = 13.sp, lineHeight = 20.sp,
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None),
)
private val PAGE_PAD = 12.dp

/** Pierre colors not in the highlight theme: `--diffs-added-*`, `--diffs-deleted-*`, `--diffs-modified-*`. */
private fun additionBase(c: PullsColors) = Color(if (c.dark) 0xFF5ECC71 else 0xFF0DBE4E)
private fun deletionBase(c: PullsColors) = Color(if (c.dark) 0xFFFF6762 else 0xFFFF2E3F)
private fun selectionBase(c: PullsColors) = Color(if (c.dark) 0xFF69B1FF else 0xFF009FFF)

/** Colors of one file body, resolved from the server's pierre theme (washes) and the app tokens. */
@Stable
private class Palette(val c: PullsColors, theme: DiffTheme?) {
    val bg = theme?.background ?: c.base
    val fg = theme?.foreground ?: c.text
    val number = theme?.gutter ?: c.faint
    val added = (theme?.added ?: additionBase(c).copy(alpha = 0.10f)).compositeOver(bg)
    val removed = (theme?.removed ?: deletionBase(c).copy(alpha = 0.10f)).compositeOver(bg)
    val addedGutter = additionBase(c).copy(alpha = if (c.dark) 0.15f else 0.09f).compositeOver(bg)
    val removedGutter = deletionBase(c).copy(alpha = if (c.dark) 0.15f else 0.09f).compositeOver(bg)
    val separator = (if (c.dark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.04f)).compositeOver(bg)
    val buffer = (if (c.dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)).compositeOver(bg)
    val annotation = (if (c.dark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.015f)).compositeOver(bg)
    val selection = selectionBase(c).copy(alpha = if (c.dark) 0.25f else 0.18f)
    val addBar = additionBase(c)
    val delBar = deletionBase(c)
}

/** Pierre's `[data-content-buffer]`: the empty side of a split row, hatched with 45° stripes 8dp apart. */
private fun Modifier.hatch(bg: Color, stripe: Color): Modifier = this.background(bg).drawBehind {
    val step = 8.dp.toPx()
    val w = 1.4.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(stripe, Offset(x, size.height), Offset(x + size.height, 0f), w)
        x += step
    }
}

/** The bordered card sides every body item of a file draws (`border-x border-base`). */
private fun Modifier.fileFrame(border: Color): Modifier = this
    .padding(horizontal = PAGE_PAD)
    .drawWithContent {
        drawContent()
        val w = 1.dp.toPx()
        drawRect(border, Offset.Zero, Size(w, size.height))
        drawRect(border, Offset(size.width - w, 0f), Size(w, size.height))
    }
    .clipToBounds()

/** A shared sideways scroll for the code of one file (one per side in split view). */
internal class HScroll {
    var offset by mutableFloatStateOf(0f)
    var max = 0f
}


/** Emits one file: sticky header, banner, summary, rows with their annotations, footer. */
internal fun fileItems(emit: Emitter, session: ReviewSession, ui: DiffUi, file: ReviewFile) {
    val path = file.path
    emit.sticky("diff:fh:$path", "file-header", path) { FileHeader(session, ui, file) }
    if (ui.isFileCollapsed(file)) {
        emit.sticky("diff:fx:$path", "file-release") { Spacer(Modifier.height(0.dp)) }
        return
    }
    val status = session.reviewState(file)
    if (status == ReviewState.Changed) emit.item("diff:fc:$path", "file-changed", path) { ChangedBanner(session, file) }
    session.saved?.analysis?.files?.firstOrNull { it.path == path }?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
        emit.item("diff:fs:$path", "file-summary", path) { FramedBox { FileSummary(summary) } }
    }
    val full = ui.fullFiles[path]
    when {
        file.isBinary -> emit.item("diff:fm:$path", "file-message", path) { FileMessage(t("file.binaryNotShown")) }
        file.truncated && full == null -> emit.item("diff:fm:$path", "file-message", path) { FileMessage(t("file.tooLarge")) }
        else -> rowItems(emit, session, ui, file)
    }
    emit.item("diff:ff:$path", "file-footer", path) { FileFooter() }
    emit.sticky("diff:fx:$path", "file-release") { Spacer(Modifier.height(0.dp)) }
}

/** Threads, AI content and the draft at one place in the file. */
private class Annotations(val threads: MutableList<ReviewThread> = mutableListOf(), val ai: MutableList<AiAnchor> = mutableListOf(), var draft: DraftTarget? = null)

private fun rowItems(emit: Emitter, session: ReviewSession, ui: DiffUi, file: ReviewFile) {
    val path = file.path
    val entries = ui.entries(file)
    val split = effectiveSplit(session.settings.layout, file, ui.widthDp - 2 * PAGE_PAD.value.toInt())
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
        if (split) {
            when (val row = splitRows[i]) {
                is SplitEntry.Gap -> emit.item(key, "gap", path) { GapRow(ui, file, row.gap, i == 0, split = true) }
                is SplitEntry.Pair -> emit.item(key, "split", path) { SplitRowView(session, ui, file, row, digits) }
            }
        } else {
            when (val entry = entries[i]) {
                is DiffEntry.Gap -> emit.item(key, "gap", path) { GapRow(ui, file, entry, i == 0) }
                is DiffEntry.Line -> emit.item(key, "line", path) { UnifiedRow(session, ui, file, entry.line, digits) }
            }
        }
        byRow[i]?.let { notes ->
            val kind = rowLines[i].lastOrNull()?.kind ?: ' '
            emit.item("diff:a:$path:$i", "annotation", path) { AnnotationRow(session, ui, file, notes, digits, kind) }
        }
    }
}

// --- Header ---

@Composable
private fun FileHeader(session: ReviewSession, ui: DiffUi, file: ReviewFile) {
    val c = LocalPullsColors.current
    val collapsed = ui.isFileCollapsed(file)
    val status = session.reviewState(file)
    val wide = ui.widthDp >= 640
    val actionsOpen = ui.actionsOpen[file.path] == true
    val resolved = session.reviewData?.threads.orEmpty().count { it.path == file.path && it.resolved }
    val shape = if (collapsed) RoundedCornerShape(8.dp) else RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
    val dark = c.dark

    // Spans for the rows of this file, requested while it is open on screen.
    if (!collapsed && !file.isBinary) {
        val texts = remember(ui.entries(file)) { ui.entries(file).mapNotNull { (it as? DiffEntry.Line)?.line?.text } }
        LaunchedEffect(file.path, texts, dark) { ui.highlight(file, texts, dark) }
    }

    Column(Modifier.fillMaxWidth().background(c.base).padding(horizontal = PAGE_PAD).padding(top = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(c.base).borderShape(c.border, collapsed)
                .clickable(role = Role.Button, onClickLabel = t(if (collapsed) "file.expand" else "file.collapse")) { ui.setFileCollapsed(file.path, !collapsed) },
        ) {
            val meta: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (resolved > 0) Text(t("file.resolved", "n" to resolved), style = PullsType.xs, color = c.fade, maxLines = 1)
                    if (!file.isBinary) DiffStats(file.additions, file.deletions) else Text(t("file.binary"), style = PullsType.xs, color = c.fade, maxLines = 1)
                    FileStatus(file.status)
                }
            }
            val caret: @Composable () -> Unit = {
                IconButton(if (collapsed) "caret-right" else "caret-down", t(if (collapsed) "file.expand" else "file.collapse"), { ui.setFileCollapsed(file.path, !collapsed) }, compact = true)
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReviewCheckbox(status, { value -> session.setReviewed(listOf(file.sha), value) }, label = t("file.markReviewed"))
                    FilePath(file.path, Modifier.weight(1f), dim = true)
                    Spacer(Modifier.width(8.dp))
                    meta()
                    FileActions(session, ui, file)
                    caret()
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ReviewCheckbox(status, { value -> session.setReviewed(listOf(file.sha), value) }, label = t("file.markReviewed"))
                    FilePath(file.path, Modifier.weight(1f), dim = true)
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    meta()
                    IconButton("dots-three-bold", t("file.moreActions"), { ui.actionsOpen[file.path] = !actionsOpen }, compact = true, active = actionsOpen)
                    caret()
                }
            }
            if (!wide && actionsOpen) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    ui.fullErrors[file.path]?.let { Text(t("file.loadFailed"), Modifier.weight(1f).padding(start = 8.dp), style = PullsType.xs, color = c.error) }
                    FileActions(session, ui, file)
                }
            }
        }
    }
}

/** Outline that leaves the bottom open while the body continues under it. */
private fun Modifier.borderShape(color: Color, closed: Boolean): Modifier = drawWithContent {
    drawContent()
    val w = 1.dp.toPx()
    val r = 8.dp.toPx()
    if (closed) {
        drawRoundRect(color, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r), style = Stroke(w))
    } else {
        val p = Path().apply {
            moveTo(w / 2, size.height)
            lineTo(w / 2, r)
            quadraticTo(w / 2, w / 2, r, w / 2)
            lineTo(size.width - r, w / 2)
            quadraticTo(size.width - w / 2, w / 2, size.width - w / 2, r)
            lineTo(size.width - w / 2, size.height)
        }
        drawPath(p, color, style = Stroke(w))
    }
}

/** `FileHeaderLinks` + the "Load full file content" button. */
@Composable
private fun FileActions(session: ReviewSession, ui: DiffUi, file: ReviewFile) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1500); copied = null } }
    val opened = session.opened
    val repo = FileLinks.repoOf(session.target)
    val github = repo != null && opened?.refKind.orEmpty().startsWith("github")
    val sha = if (file.status == "removed") opened?.baseSha else opened?.headSha
    val blob = if (repo != null && !sha.isNullOrBlank()) FileLinks.blob(repo.first, repo.second, sha, file.path) else null
    val wide = ui.widthDp >= 640
    if (wide) ui.fullErrors[file.path]?.let { Text(t("file.loadFailed"), style = PullsType.xs, color = c.error) }
    IconButton(if (copied == "path") "check" else "copy", t("overview.file.copyPath"), { copy(context, file.path); copied = "path" }, compact = true)
    val url = opened?.url.orEmpty()
    if (github && url.isNotBlank()) {
        IconButton(if (copied == "link") "check" else "link", t("overview.file.copyLink"), {
            copy(context, FileLinks.fileDiff(url, opened?.refKind == "github-pr", file.path)); copied = "link"
        }, compact = true)
        if (blob != null) IconButton("arrow-square-out", t("overview.file.openAtHead"), {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(blob))) }
        }, compact = true)
    }
    val loading = ui.fullLoading[file.path] == true
    if (ui.canLoadFull(file) || loading) {
        if (loading) Box(Modifier.width(44.dp).height(44.dp), contentAlignment = Alignment.Center) { com.octaviantocan.pulls.ui.design.SpinIcon(tint = c.fade, size = 16.dp) }
        else IconButton("file-text-duotone", t("file.loadFull"), { ui.loadFullFile(file) }, compact = true)
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("pulls.review", text))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

// --- Body pieces ---

@Composable
private fun FramedBox(content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    Box(Modifier.fillMaxWidth().fileFrame(c.border).drawWithContent {
        drawContent()
        drawRect(c.border, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    }) { content() }
}

/** `border-b border-orange:20 bg-orange:10 text-orange-700 dark:text-orange-400`. */
@Composable
private fun ChangedBanner(session: ReviewSession, file: ReviewFile) {
    val c = LocalPullsColors.current
    val fg = Color(if (c.dark) 0xFFFB923C else 0xFFC2410C)
    Row(
        Modifier.fillMaxWidth().fileFrame(c.border).background(c.orange.copy(alpha = 0.10f))
            .drawWithContent { drawContent(); drawRect(c.orange.copy(alpha = 0.2f), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon("warning-circle-duotone", tint = fg, size = 16.dp)
        Text(t("file.changedSinceReviewed"), Modifier.weight(1f), style = PullsType.sm, color = fg)
        ActionButton({ session.setReviewed(listOf(file.sha), true) }, small = true) { Text(t("file.markReviewed")) }
    }
}

@Composable
private fun FileMessage(text: String) {
    val c = LocalPullsColors.current
    Box(Modifier.fillMaxWidth().fileFrame(c.border).padding(16.dp)) { Text(text, style = PullsType.sm, color = c.fade) }
}

/** `rounded-b-xl` bottom edge of the file card. */
@Composable
private fun FileFooter() {
    val c = LocalPullsColors.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = PAGE_PAD).height(12.dp).drawWithContent {
            val w = 1.dp.toPx()
            val r = 12.dp.toPx()
            val p = Path().apply {
                moveTo(w / 2, 0f)
                lineTo(w / 2, size.height - r)
                quadraticTo(w / 2, size.height - w / 2, r, size.height - w / 2)
                lineTo(size.width - r, size.height - w / 2)
                quadraticTo(size.width - w / 2, size.height - w / 2, size.width - w / 2, size.height - r)
                lineTo(size.width - w / 2, 0f)
            }
            drawPath(p, c.border, style = Stroke(w))
        },
    )
}

/** Pierre's `line-info` separator: 32px, `bg-separator`, an expand glyph and "N unmodified lines". */
@Composable
private fun GapRow(ui: DiffUi, file: ReviewFile, gap: DiffEntry.Gap, first: Boolean, split: Boolean = false) {
    val c = LocalPullsColors.current
    val p = remember(c, ui.theme) { Palette(c, ui.theme) }
    val loading = ui.fullLoading[file.path] == true
    val label = "${gap.count} unmodified line${if (gap.count == 1) "" else "s"}"
    @Composable
    fun Pill(modifier: Modifier, text: String?) {
        Row(
            modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(p.separator)
                .clickable(enabled = !file.isBinary && !loading, role = Role.Button, onClickLabel = t("file.loadFull")) { ui.loadFullFile(file, gap.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading && text != null) com.octaviantocan.pulls.ui.design.SpinIcon(Modifier.padding(start = 8.dp), tint = p.number, size = 14.dp)
            if (text != null) Text(text, Modifier.padding(horizontal = 8.dp), style = PullsType.xs.copy(fontSize = 13.sp), color = p.number, maxLines = 1)
        }
    }
    // `margin-block: 8px`, none on the first/last separator of a file; one pill per column in split view.
    Row(
        Modifier.fillMaxWidth().fileFrame(c.border).background(p.bg).padding(horizontal = 8.dp).padding(top = if (first) 0.dp else 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Pill(Modifier.weight(1f), label)
        if (split) Pill(Modifier.weight(1f), null)
    }
}

/** A line's code as an annotated string: server spans, or the plain text while they load. */
@Composable
private fun codeText(ui: DiffUi, file: ReviewFile, text: String, fg: Color): AnnotatedString {
    val toks = ui.tokens[file.path]?.get(text)
    return remember(text, toks, fg) {
        buildAnnotatedString {
            if (toks == null || toks.isEmpty()) append(text)
            else toks.forEach { tok -> if (tok.color == null) append(tok.text) else withStyle(SpanStyle(color = tok.color)) { append(tok.text) } }
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
    val m = mark
    return m != null && m.path == file.path && m.side == cell.side && m.line == n
}

/** Gutter tap: opens the composer on the line, or closes a pending range selection on it. */
private fun DiffUi.onGutter(file: ReviewFile, cell: Cell) {
    val n = cell.number ?: return
    val start = selectionStart.remove(file.path)
    drafts[file.path] = if (start != null && start.side == cell.side && start.line != n)
        DraftTarget(file.path, cell.side, maxOf(start.line, n), minOf(start.line, n))
    else DraftTarget(file.path, cell.side, n)
}

/** Code tap: completes a range started by a long press, or stretches the open draft on the same side. */
private fun DiffUi.onCodeTap(file: ReviewFile, cell: Cell) {
    val n = cell.number ?: return
    val start = selectionStart[file.path]
    if (start != null && start.side == cell.side) {
        selectionStart.remove(file.path)
        drafts[file.path] = selectRange(file.path, cell.side, start.line, n)
        return
    }
    val draft = drafts[file.path] ?: return
    if (draft.side != cell.side || n == draft.line) return
    drafts[file.path] = selectRange(file.path, cell.side, minOf(draft.range.first, n), maxOf(draft.range.last, n))
}

/** Long press: starts a range selection at this line. */
private fun DiffUi.onCodeLongPress(file: ReviewFile, cell: Cell) {
    val n = cell.number ?: return
    drafts.remove(file.path)
    selectionStart[file.path] = DraftTarget(file.path, cell.side, n)
}

@Composable
private fun UnifiedRow(session: ReviewSession, ui: DiffUi, file: ReviewFile, line: DiffLine, digits: Int) {
    val c = LocalPullsColors.current
    val p = remember(c, ui.theme) { Palette(c, ui.theme) }
    val scroll = remember(file.path) { ui.scrolls.getOrPut(file.path to 0) { HScroll() } }
    Row(Modifier.fillMaxWidth().fileFrame(c.border).height(IntrinsicSize.Min)) {
        LineCell(ui, file, line, digits, p, scroll, Modifier.weight(1f))
    }
}

@Composable
private fun SplitRowView(session: ReviewSession, ui: DiffUi, file: ReviewFile, row: SplitEntry.Pair, digits: Int) {
    val c = LocalPullsColors.current
    val p = remember(c, ui.theme) { Palette(c, ui.theme) }
    val left = remember(file.path) { ui.scrolls.getOrPut(file.path to 1) { HScroll() } }
    val right = remember(file.path) { ui.scrolls.getOrPut(file.path to 2) { HScroll() } }
    Row(Modifier.fillMaxWidth().fileFrame(c.border).height(IntrinsicSize.Min)) {
        // A context line shows its old number on the left and its new number on the right.
        if (row.left != null) LineCell(ui, file, row.left, digits, p, left, Modifier.weight(1f), leftColumn = true)
        else Box(Modifier.weight(1f).fillMaxHeight().hatch(p.bg, p.buffer))
        Box(Modifier.width(2.dp).fillMaxHeight().background(p.bg))
        if (row.right != null) LineCell(ui, file, row.right, digits, p, right, Modifier.weight(1f))
        else Box(Modifier.weight(1f).fillMaxHeight().hatch(p.bg, p.buffer))
    }
}

/**
 * One line: pierre's `bars` indicator, the number gutter (tap to comment), and the code,
 * which scrolls sideways with the rest of its file. Long press starts a range selection.
 */
@Composable
private fun LineCell(ui: DiffUi, file: ReviewFile, line: DiffLine, digits: Int, p: Palette, scroll: HScroll, modifier: Modifier, leftColumn: Boolean = false) {
    val cell = line.cell(leftColumn)
    val selected = ui.isSelected(file, cell)
    val (lineBg, gutterBg, bar) = when (line.kind) {
        '+' -> Triple(p.added, p.addedGutter, p.addBar)
        '-' -> Triple(p.removed, p.removedGutter, p.delBar)
        else -> Triple(p.bg, p.bg, null)
    }
    val number = cell.number
    val numberColor = when (line.kind) { '+' -> p.addBar; '-' -> p.delBar; else -> p.number }
    val gutterWidth = maxOf(44, digits * 8 + 20).dp
    val code = codeText(ui, file, line.text, p.fg)
    val drag = rememberDraggableState { delta -> scroll.offset = (scroll.offset - delta).coerceIn(0f, scroll.max.coerceAtLeast(0f)) }
    Row(modifier.fillMaxHeight().background(lineBg)) {
        Box(
            Modifier.width(gutterWidth).fillMaxHeight().background(if (selected) p.selection.compositeOver(gutterBg) else gutterBg)
                .clickable(enabled = !file.isBinary && number != null, role = Role.Button, onClickLabel = t("review.leaveComment")) { ui.onGutter(file, cell) },
            contentAlignment = Alignment.TopEnd,
        ) {
            if (bar != null) Box(Modifier.align(Alignment.TopStart).width(4.dp).fillMaxHeight().background(bar))
            Text(number?.toString().orEmpty(), Modifier.padding(end = 8.dp), style = CODE.copy(textAlign = TextAlign.End), color = numberColor, maxLines = 1)
        }
        Box(
            Modifier.weight(1f).fillMaxHeight().background(if (selected) p.selection else Color.Transparent)
                .draggable(drag, Orientation.Horizontal)
                .combinedClickable(onLongClick = { ui.onCodeLongPress(file, cell) }) { ui.onCodeTap(file, cell) }
                .clipToBounds()
                .padding(start = 8.dp),
        ) {
            Text(
                code,
                Modifier.layout { measurable, constraints ->
                    val placeable = measurable.measure(Constraints(minHeight = constraints.minHeight, maxHeight = constraints.maxHeight))
                    val viewport = constraints.maxWidth
                    scroll.max = maxOf(scroll.max, (placeable.width - viewport + 16).toFloat())
                    layout(viewport, placeable.height) { placeable.place(-scroll.offset.toInt(), 0) }
                },
                style = CODE.copy(color = p.fg),
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

/** Threads, AI notes/findings/asks and the draft under one row (`data-annotation-slot`). */
@Composable
private fun AnnotationRow(session: ReviewSession, ui: DiffUi, file: ReviewFile, notes: Annotations, digits: Int, kind: Char) {
    val c = LocalPullsColors.current
    val p = remember(c, ui.theme) { Palette(c, ui.theme) }
    // Pierre's annotation slot sits in the code column: the gutter (with the line's change bar)
    // runs on beside it, and the cards start where the code does.
    val gutterWidth = maxOf(44, digits * 8 + 20).dp
    val bar = when (kind) { '+' -> p.addBar; '-' -> p.delBar; else -> null }
    val gutterBg = when (kind) { '+' -> p.addedGutter; '-' -> p.removedGutter; else -> p.bg }
    // The gutter is drawn behind (no intrinsic measuring: wrapped button rows inside must keep their height).
    Column(
        Modifier.fillMaxWidth().fileFrame(c.border).drawBehind {
            val g = gutterWidth.toPx()
            drawRect(gutterBg, Offset.Zero, Size(g, size.height))
            if (bar != null) drawRect(bar, Offset.Zero, Size(4.dp.toPx(), size.height))
            drawRect(p.annotation, Offset(g, 0f), Size(size.width - g, size.height))
        }.padding(start = gutterWidth).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        notes.threads.forEach { ReviewThreadCard(session, it) }
        notes.ai.forEach { AiAnnotationBlock(session, it, ui.mark?.findingId) }
        notes.draft?.let { draft -> DraftBox(session, draft, file.isBinary) { ui.drafts.remove(file.path) } }
    }
}
