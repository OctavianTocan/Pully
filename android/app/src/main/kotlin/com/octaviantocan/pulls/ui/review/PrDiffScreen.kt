package com.octaviantocan.pulls.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.core.codeScrollAfterZoom
import com.octaviantocan.pulls.core.nextUnreviewed
import com.octaviantocan.pulls.core.rowTopAfterZoom
import com.octaviantocan.pulls.core.softBreaks
import com.octaviantocan.pulls.core.swipedFile
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.AskReference
import com.octaviantocan.pulls.ui.ai.askSelectionOf
import com.octaviantocan.pulls.ui.ai.githubBlobUrl
import com.octaviantocan.pulls.ui.ai.lineRangeLabel
import com.octaviantocan.pulls.ui.ai.linesPhrase
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PageSlide
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.hairlineTop
import com.octaviantocan.pulls.ui.design.pageSlide
import com.octaviantocan.pulls.ui.design.rememberPageSlide
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.octaviantocan.pulls.core.pageWay
import com.octaviantocan.pulls.ui.design.LabelSwitch

/** What the code keeps clear under its last line: the bar's 52dp pill and the space around it (rule 92). */
private val BAR_ROOM = 84.dp

/**
 * The prototype's diff under the review's header (the back button and the capsule, with Aa in its corner, rule 93):
 * one scrolling file, its AI note, its counts and Reviewed box, its rows and a Reviewed box again at its end. Pinned
 * under it is the bar: ‹, the file you are on and ›, which turns into the comment field while lines are selected
 * (rule 94). [path] is the file shown; [onPath] moves to another, and a swipe on the file does the same, past the ends
 * into [onOverview] and [onTour].
 */
@Composable
internal fun PrDiffScreen(
    session: ReviewSession,
    ui: DiffUi,
    path: String?,
    onPath: (String) -> Unit,
    onOverview: () -> Unit,
    onTour: () -> Unit,
    onFiles: () -> Unit,
    onAsk: (AskReference) -> Unit,
) {
    val c = LocalPullsColors.current
    val files = fileSections(session, ui).flatMap { it.files }
    val index = files.indexOfFirst { it.path == path }.let { if (it < 0) 0 else it }
    val file = files.getOrNull(index)
    val width = LocalConfiguration.current.screenWidthDp
    SideEffect { if (ui.widthDp != width) ui.widthDp = width }
    // Another file arrives as a page (rule 88): for a beat the rows still hold the one that is leaving.
    val slide = rememberPageSlide(file?.path, order = { p -> files.indexOfFirst { it.path == p } })
    val shown = files.firstOrNull { it.path == slide.shown } ?: file

    Box(Modifier.fillMaxSize().background(c.base)) {
        if (file == null || shown == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No files in this change.", style = PullsType.sm, color = c.muted) }
        } else {
            FileBody(
                session, ui, shown, file, files, index, slide, onPath, onOverview, onTour, onFiles, onAsk,
                Modifier.fillMaxSize().padding(top = 8.dp),
            )
        }
    }
}

/**
 * One file's note, counts and rows, scrolling as one (rule 92), with the bar over them. [file] is the file the rows
 * hold and [target] the one the bar names; they differ only while a swipe's page change runs.
 */
@Composable
private fun FileBody(
    session: ReviewSession,
    ui: DiffUi,
    file: ReviewFile,
    target: ReviewFile,
    files: List<ReviewFile>,
    index: Int,
    slide: PageSlide<String?>,
    onPath: (String) -> Unit,
    onOverview: () -> Unit,
    onTour: () -> Unit,
    onFiles: () -> Unit,
    onAsk: (AskReference) -> Unit,
    modifier: Modifier,
) {
    val c = LocalPullsColors.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val note = session.saved?.analysis?.files?.firstOrNull { it.path == file.path }?.summary?.takeIf { it.isNotBlank() }
    val dark = c.dark

    // A new file starts at its top with nothing selected.
    LaunchedEffect(file.path) {
        ui.drafts.clear()
        ui.selectionStart.clear()
        ui.mark = null
        if (session.focus?.path != file.path) listState.scrollToItem(0)
    }
    // Syntax colors for the rows on screen come from the server, a file at a time.
    val texts = remember(ui.entries(file)) { ui.entries(file).mapNotNull { (it as? DiffEntry.Line)?.line?.text } }
    LaunchedEffect(file.path, texts, dark) { if (!file.isBinary) ui.highlight(file, texts, dark) }
    DiffFocus(session, ui, file, listState)

    // A sideways swipe is the next or previous file, and past the ends the view either side (rule 93). The rows follow
    // the finger like the Overview's tabs do; a swipe that falls short puts them back.
    val density = LocalDensity.current
    val swipeFrom = with(density) { 64.dp.toPx() }
    val fling = with(density) { PullsMotion.Fling.toPx() }
    val range = with(density) { PullsMotion.FingerRange.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val count = files.size
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val leading = if (note != null) 2 else 1

    Box(modifier) {
        Box(
            // The code dissolves into the page under the bar at the bottom instead of being cut by it (rule 74).
            Modifier.fillMaxSize().edgeFade(bottom = PullsMotion.EdgeBottom, color = c.raised).fileSwipe(
                enabled = { ui.drafts[file.path] == null && ui.dragSel == null && ui.selectionStart[file.path] == null },
                onCode = { y -> onCodeRow(listState, y) },
                codeHasRoom = { dx, across -> !session.settings.codeWrap && codeHasRoom(ui, file, dx, across) },
                onDrag = { delta -> drag += delta; slide.follow(delta, range) },
                onEnd = { velocity ->
                    // A throw counts as well as a long drag, and the rows never come back toward the finger: they go on its
                    // way into the next file, or with the whole view when the swipe left the files (rule 105).
                    val to = swipedFile(index, count, drag, swipeFrom, velocity, fling)
                    val way = if (to == index) 0 else pageWay(index, to)
                    drag = 0f
                    when {
                        to < 0 -> onOverview()
                        to >= count -> onTour()
                        to != index -> onPath(files[to].path)
                    }
                    scope.launch { if (to < 0 || to >= count) slide.leave(way, velocity) else slide.release(way, velocity) }
                },
            ),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().pageSlide(slide).pinchToZoomCode(session.settings) { end -> keepUnderFingers(listState, ui, file, end, density) },
                state = listState,
            ) {
                if (note != null) {
                    item(key = "note:${file.path}", contentType = "note") {
                        NoteCard(session, note, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) {
                            onAsk(AskReference("the note on ${file.path.substringAfterLast('/')}", note))
                        }
                    }
                }
                item(key = "counts:${file.path}", contentType = "counts") { CountsRow(session, file, files, onPath) }
                // The Reviewed box again under the last line, so finishing a file moves on without scrolling back up.
                fileDiffItems(session, ui, file, leading = leading, tail = BAR_ROOM + bottom) { EndRow(session, file, files, onPath) }
            }
        }
        DiffBar(
            session, ui, file, target, index, count, onAsk,
            onPrev = { files.getOrNull(index - 1)?.let { onPath(it.path) } },
            onNext = { files.getOrNull(index + 1)?.let { onPath(it.path) } },
            onFiles = onFiles,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Is the row under [y] (in the list's own pixels) a line of code, which scrolls sideways itself? */
private fun onCodeRow(state: LazyListState, y: Float): Boolean {
    val info = state.layoutInfo
    val at = y - info.beforeContentPadding
    val row = info.visibleItemsInfo.firstOrNull { at >= it.offset && at < it.offset + it.size } ?: return false
    return row.contentType == "line" || row.contentType == "split"
}

/**
 * Can the code under the finger still scroll sideways the way a finger moving by [dx] pushes it? A file's lines scroll
 * together, one scroll per column, so this asks the column that was touched: [across] is how far across the screen the
 * finger came down, 0 to 1, and in split view the left half is the old side.
 */
private fun codeHasRoom(ui: DiffUi, file: ReviewFile, dx: Float, across: Float): Boolean {
    val column = if (!effectiveSplit(ui.session.settings.layout, file, ui.widthDp)) 0 else if (across < 0.5f) 1 else 2
    val scroll = ui.scrolls[file.path to column] ?: return false
    return if (dx < 0f) scroll.offset < scroll.max - 1f else scroll.offset > 1f
}

/**
 * A pinch has laid the code out at its new size: scroll the list so the row that was under the fingers is under them
 * again, at the same point of it, and scroll the code sideways so the same character is too (rule 93's pinch). A row of
 * code grows with the zoom; a note or a thread keeps its size. Wrapped code has no sideways scroll to fix.
 */
private fun keepUnderFingers(state: LazyListState, ui: DiffUi, file: ReviewFile, end: PinchEnd, density: Density) {
    val info = state.layoutInfo
    val before = info.beforeContentPadding
    val at = end.content.y - before
    info.visibleItemsInfo.firstOrNull { at >= it.offset && at < it.offset + it.size }?.let { row ->
        val grows = row.contentType == "line" || row.contentType == "split"
        val top = rowTopAfterZoom(end.screen.y - before, at - row.offset, end.scale, grows)
        state.requestScrollToItem(row.index, -top.roundToInt())
    }
    if (ui.session.settings.codeWrap || file.isBinary || end.from == end.to) return
    // In split view each half scrolls on its own and the right one starts past the 2dp between them.
    val width = info.viewportSize.width.toFloat()
    val split = effectiveSplit(ui.session.settings.layout, file, ui.widthDp)
    val right = split && end.content.x >= width / 2f
    val scroll = ui.scrolls[file.path to if (!split) 0 else if (right) 2 else 1] ?: return
    val digits = lineDigits(ui, file)
    val half = with(density) { 2.dp.toPx() }
    val cellStart = if (right) (width - half) / 2f + half else 0f
    fun codeStart(zoom: Float) = cellStart + with(density) { (gutterWidth(digits, zoom) + 2.dp).toPx() }
    scroll.offset = codeScrollAfterZoom(scroll.offset, codeStart(end.from), codeStart(end.to), end.content.x, end.screen.x, end.scale)
}

/**
 * The sideways swipe that changes file. It watches each touch before the rows do and takes it only once the finger
 * has gone sideways past the touch slop: off the code, or on code that has no room left to scroll that way, so the
 * code still scrolls first and a pinch, a long press or a scroll down the file stay theirs. The callbacks are read
 * fresh at each touch, so a file change mid-screen never leaves the gesture acting on the old file.
 */
@Composable
private fun Modifier.fileSwipe(
    enabled: () -> Boolean,
    onCode: (Float) -> Boolean,
    codeHasRoom: (dx: Float, across: Float) -> Boolean,
    onDrag: (Float) -> Unit,
    onEnd: (velocity: Float) -> Unit,
): Modifier {
    val isOn = rememberUpdatedState(enabled)
    val codeAt = rememberUpdatedState(onCode)
    val room = rememberUpdatedState(codeHasRoom)
    val drag = rememberUpdatedState(onDrag)
    val end = rememberUpdatedState(onEnd)
    return pointerInput(Unit) { awaitEachGesture { swipeGesture(isOn.value, codeAt.value, room.value, drag.value, end.value) } }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.swipeGesture(
    enabled: () -> Boolean,
    onCode: (Float) -> Boolean,
    codeHasRoom: (dx: Float, across: Float) -> Boolean,
    onDrag: (Float) -> Unit,
    onEnd: (velocity: Float) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // How fast the finger was going when it let go, so a throw changes the file however short it was.
    val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
    if (!enabled()) return
    val code = onCode(down.position.y)
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    var dx = 0f
    var dy = 0f
    var taken = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.count { it.pressed } > 1) break
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) break
        val delta = change.positionChange()
        tracker.addPosition(change.uptimeMillis, change.position)
        if (taken) {
            change.consume()
            onDrag(delta.x)
            continue
        }
        dx += delta.x
        dy += delta.y
        when {
            // Held still past a long press: that is a selection, not a swipe.
            change.uptimeMillis - down.uptimeMillis > longPress && abs(dx) <= slop -> return
            abs(dy) > slop && abs(dy) >= abs(dx) -> return
            abs(dx) > slop && abs(dx) > abs(dy) -> {
                if (code && codeHasRoom(dx, down.position.x / size.width.coerceAtLeast(1))) return
                taken = true
                change.consume()
                onDrag(dx)
            }
        }
    }
    if (taken) onEnd(tracker.calculateVelocity().x)
}

/** The file's counts and threads, and the Reviewed box; reviewing a file moves on to the next one still to review. */
@Composable
private fun CountsRow(session: ReviewSession, file: ReviewFile, files: List<ReviewFile>, onPath: (String) -> Unit) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val threads = session.reviewData?.threads.orEmpty().count { it.path == file.path && !it.outdated }
    // The code starts under a hairline, on its own white, with 24dp of air between it and this row (rule 40).
    Row(
        Modifier.fillMaxWidth().drawBehind { drawRect(c.borderMute, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx())) }
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("+${compactCount(file.additions)}", style = mono, color = StatusColors.green(c.dark), maxLines = 1)
            Text("−${compactCount(file.deletions)}", style = mono, color = StatusColors.red(c.dark), maxLines = 1)
        }
        Text(when (threads) { 0 -> "No threads"; 1 -> "1 thread"; else -> "$threads threads" }, style = PullsType.sm, color = c.muted, maxLines = 1)
        Spacer(Modifier.weight(1f))
        ReviewedBox(session, file) { toggleReviewed(session, file, files, scope, onPath) }
    }
}

/** Under the last line, under a hairline: the same Reviewed box as at the top, at the same place on the right. */
@Composable
private fun EndRow(session: ReviewSession, file: ReviewFile, files: List<ReviewFile>, onPath: (String) -> Unit) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().hairlineTop(c.borderMute).padding(start = 16.dp, end = 16.dp, top = 12.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        ReviewedBox(session, file) { toggleReviewed(session, file, files, scope, onPath) }
    }
}

/** The box and the word, one target that ticks or unticks the file. */
@Composable
private fun ReviewedBox(session: ReviewSession, file: ReviewFile, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).cueClickable(role = Role.Checkbox, onClickLabel = "Reviewed", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        PullsCheck(session.reviewState(file) == ReviewState.Reviewed, size = 20.dp)
        Text("Reviewed", style = PullsType.sm, color = c.text)
    }
}

/** Ticks [file] or unticks it. Ticking moves on to the next file still to review, after a beat to see the tick land. */
private fun toggleReviewed(session: ReviewSession, file: ReviewFile, files: List<ReviewFile>, scope: CoroutineScope, onPath: (String) -> Unit) {
    val reviewed = session.reviewState(file) == ReviewState.Reviewed
    session.setReviewed(listOf(file.sha), !reviewed)
    if (reviewed) return
    // No "2 of 4" toast (rule 42): the last file of the change gets the success sound and nothing on screen.
    if (files.all { it.sha in session.reviewed || it.path == file.path }) Sounds.play(Cue.Success)
    val shaOf = files.associate { it.path to it.sha }
    nextUnreviewed(files.map { it.path }, file.path) { path -> shaOf[path] in session.reviewed }?.let { next ->
        scope.launch { delay(220); onPath(next) }
    }
}

/** `.note`: the AI's note about this file, from "Pulls", its mentions links to their place (rule 96). A tap asks about it, with the note attached (rule 94). */
@Composable
private fun NoteCard(session: ReviewSession, note: String, modifier: Modifier, onAsk: () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised)
            .cueClickable(role = Role.Button, onClickLabel = "Ask about this note", onClick = onAsk)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The name alone: an initial in a disc is not information (rule 1).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pulls", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Spacer(Modifier.weight(1f))
            Text("Ask about it", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
        }
        com.octaviantocan.pulls.ui.ai.AiMarkdown(session, note, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f, color = c.text))
    }
}

/**
 * `.dctl`: ‹, the file you are on and ›. While lines are selected the whole bar is the comment bar instead, and it comes
 * back when they are not (rule 94): ✕, the field that names the lines, the "In review" or "Post now" toggle, ⋯ and Send.
 * The field posts into the viewer's pending review; with no write access the bar only names the lines. Nothing floats
 * over the selected lines: what else they offer (Ask, copy where they are) is behind the ⋯.
 */
@Composable
private fun DiffBar(
    session: ReviewSession,
    ui: DiffUi,
    file: ReviewFile,
    target: ReviewFile,
    index: Int,
    count: Int,
    onAsk: (AskReference) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFiles: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val selected = ui.drafts[file.path]
    // Tapping another line keeps what was typed (the comment follows the selection); deselecting or posting clears it.
    var text by remember(file.path) { mutableStateOf("") }
    var actions by remember { mutableStateOf(false) }
    LaunchedEffect(selected == null) { if (selected == null) { text = ""; actions = false } }
    var busy by remember { mutableStateOf(false) }
    // With no review pending a comment can be posted at once instead of starting one; the choice sticks for the screen.
    val hasPending = session.reviewData?.pendingReviewId != null
    var single by rememberSaveable { mutableStateOf(false) }
    val post = single && !hasPending
    val canComment = session.canWrite && session.pull != null && !file.isBinary
    // The one place the lines are named while they are selected: in the sentence, or alone when that is too long.
    val lines = selected?.let { s -> linesPhrase(s.startLine ?: s.line, s.line) }
    val linesLabel = selected?.let { s -> linesPhrase(s.startLine ?: s.line, s.line, label = true) }

    fun send() {
        val draft = selected ?: return
        val pull = session.pull ?: return
        val head = session.opened?.headSha ?: return
        val body = text.trim()
        if (body.isEmpty() || busy) return
        busy = true
        scope.launch {
            runCatching {
                session.repo.comment(pull, draft.path, draft.side, draft.line, head, body, if (post) "single" else "review", draft.startLine?.takeIf { it < draft.line })
            }.onSuccess {
                text = ""
                ui.drafts.remove(file.path)
                focus.clearFocus()
                session.reloadReviews()
                toaster.show(if (post) "Comment posted" else "Comment added to your pending review", cue = Cue.Success)
            }.onFailure { toaster.show(it.message ?: "Couldn't add the comment", cue = Cue.Error) }
            busy = false
        }
    }

    Row(
        modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = selected != null,
            transitionSpec = {
                fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) togetherWith fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut))
            },
            label = "diff-bar",
        ) { selecting ->
            if (!selecting) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FloatIcon(Lu.prev, "Previous file", onPrev, size = 48.dp, enabled = index > 0, iconSize = 22.dp)
                    FilePill(target, index, count, onFiles, Modifier.weight(1f))
                    FloatIcon(Lu.next, "Next file", onNext, size = 48.dp, enabled = index < count - 1, iconSize = 22.dp, primary = true)
                }
            } else {
                // Lines are selected: the bar is one pill you type straight into, with everything it needs built in.
                Row(
                    Modifier.fillMaxWidth().height(52.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised).padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FloatIcon(Lu.close, "Deselect the lines", { ui.drafts.remove(file.path); ui.selectionStart.remove(file.path) }, flat = true, size = 40.dp, iconSize = 20.dp, tint = c.muted)
                    if (canComment) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            enabled = !busy,
                            textStyle = PullsType.base.copy(color = c.text),
                            cursorBrush = SolidColor(c.accentTeal),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (text.isEmpty() && lines != null && linesLabel != null) FitText(listOf("Comment on $lines", linesLabel), PullsType.base, c.muted)
                                    inner()
                                }
                            },
                        )
                        if (!hasPending) {
                            Box(
                                Modifier.defaultMinSize(minHeight = 40.dp).clip(CircleShape)
                                    .cueClickable(role = Role.Switch, onClickLabel = if (single) "Add comments to a review instead" else "Post comments at once instead") { single = !single }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (single) "Post now" else "In review", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = if (single) c.activeText else c.muted, maxLines = 1)
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f)) { if (linesLabel != null) FitText(listOf(linesLabel), PullsType.base, c.muted) }
                    }
                    FloatIcon(Lu.more, "More for these lines", { actions = true }, flat = true, size = 40.dp, iconSize = 20.dp, tint = c.muted)
                    if (canComment) SendButton({ send() }, "Send comment", enabled = text.isNotBlank() && !busy, busy = busy)
                }
            }
        }
    }
    if (actions && selected != null) LineActionsSheet(session, ui, file, selected, onAsk) { actions = false }
}

/** The first of [choices] that fits its room on one line, else the last: a shorter wording instead of a cut one. */
@Composable
private fun FitText(choices: List<String>, style: TextStyle, color: Color) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val width = constraints.maxWidth
        val shown = remember(choices, width, style) {
            if (width == Constraints.Infinity) choices.first()
            else choices.firstOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= width } ?: choices.last()
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * What else selected lines offer, from the ⋯ of the comment bar: Ask about them, which opens Ask with their code
 * attached as a card over the field (as a file note is), and copying where they are. The bar names the lines, so the
 * sheet is named by the file alone.
 */
@Composable
private fun LineActionsSheet(session: ReviewSession, ui: DiffUi, file: ReviewFile, lines: DraftTarget, onAsk: (AskReference) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val selection = askSelectionOf(file.path, lines.side, lines.line, lines.startLine)
    val opened = session.opened
    val repo = FileLinks.repoOf(session.target)
    val link = opened?.let { githubBlobUrl(repo?.first, repo?.second, it.refKind, it.headSha, it.baseSha, file.path, file.previousPath, selection) }
    val one = selection.startLine == selection.endLine
    PullsSheet(onClose, file.path.substringAfterLast('/'), expandable = false) {
        SheetCard(Modifier.padding(bottom = 16.dp)) {
            SheetRow(if (one) "Ask about this line" else "Ask about these lines", {
                onClose()
                // The code itself, or where it is when the lines are not loaded.
                val code = selectedCode(ui.entries(file), selection.side, selection.startLine..selection.endLine).ifEmpty { lineRangeLabel(selection) }
                onAsk(AskReference("${linesPhrase(selection.startLine, selection.endLine)} of ${file.path}", code))
            }, first = true, iconless = true)
            SheetRow(t("ask.copyLocation"), { copyText(context, lineRangeLabel(selection)); toaster.show("Location copied", cue = Cue.Success); onClose() }, iconless = true)
            if (link != null) SheetRow(t("ask.copyLink"), { copyText(context, link); toaster.show("Link copied", cue = Cue.Success); onClose() }, iconless = true)
        }
    }
}

/**
 * The file you are on, between ‹ and ›: its name whole in the code face, and under it the end of its folder (rule 90)
 * and where it sits in the change. A long name takes the room instead of the folder. A tap opens every file, whole.
 */
@Composable
private fun FilePill(file: ReviewFile, index: Int, count: Int, onClick: () -> Unit, modifier: Modifier) {
    val c = LocalPullsColors.current
    val mono = PullsType.sm.copy(fontFamily = PullsType.mono)
    Column(
        modifier.defaultMinSize(minHeight = 52.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised)
            .cueClickable(role = Role.Button, onClickLabel = "Choose a file", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The next file's name comes in from the right and the previous one's from the left, as the code does (rule 105).
        LabelSwitch(index to file.path, order = { it.first }) { (at, path) ->
            val name = path.substringAfterLast('/')
            val dir = path.substringBeforeLast('/', "").takeIf { name.length <= SHORT_NAME }.orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The name in the face it has on the file rows, which fits a long one on a line where the code font would break it.
                // A long name wraps between its words, in lines of even length.
                Text(softBreaks(name), style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading), color = c.text, maxLines = 3)
                if (dir.isNotEmpty() || count > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dir.isNotEmpty()) PathTail(dir, mono, c.muted, Modifier.weight(1f, fill = false))
                        if (count > 1) Text("${if (dir.isNotEmpty()) " · " else ""}${at + 1}/$count", style = mono.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** A file name up to this long leaves room in the pill for the end of its folder. */
private const val SHORT_NAME = 22

/**
 * Brings a focus request into view: opens the file's gap when the line sits in unchanged text,
 * scrolls to the line with some context above it, and marks it for a moment. The request is
 * consumed once it has been shown, so coming back to the screen does not jump again.
 */
@Composable
private fun DiffFocus(session: ReviewSession, ui: DiffUi, file: ReviewFile, listState: LazyListState) {
    val focus = session.focus
    LaunchedEffect(focus, file.path) {
        val target = focus ?: return@LaunchedEffect
        if (target.path != file.path) return@LaunchedEffect
        if (target.line <= 0) {
            listState.scrollToItem(0)
            if (session.focus === target) session.focus = null
            return@LaunchedEffect
        }
        ui.mark = LineMark(file.path, target.side, target.line, target.findingId)
        val anchor = "${file.path}|${target.side}|${target.line}"
        delay(48)
        if (ui.lineKey[anchor] == null && !file.isBinary) {
            // The line sits in an unmodified gap: load the full file and open every gap.
            ui.loadFullFile(file)
            repeat(250) { if (ui.fullLoading[file.path] != true) return@repeat; delay(20) }
            if (ui.fullFiles[file.path] != null) ui.expandedGaps[file.path] = null
        }
        var key: String? = null
        repeat(60) { if (key == null) { key = ui.lineKey[anchor]; if (key == null) delay(16) } }
        val at = key?.let { ui.indexOf[it] }
        if (at != null) listState.animateScrollToItem(at, scrollOffset = -240)
        if (session.focus === target) session.focus = null
    }
    // Consuming the request changes the key above and cancels that effect, so the mark fades on its own timer.
    val mark = ui.mark
    LaunchedEffect(mark) {
        if (mark == null) return@LaunchedEffect
        delay(2500)
        if (ui.mark == mark) ui.mark = null
    }
}
