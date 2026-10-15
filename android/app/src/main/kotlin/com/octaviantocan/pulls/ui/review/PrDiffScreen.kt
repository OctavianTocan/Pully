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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.octaviantocan.pulls.core.softBreaks
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.nextUnreviewed
import com.octaviantocan.pulls.core.swipedFile
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.AskReference
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PageSlide
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.SendButton
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.pageSlide
import com.octaviantocan.pulls.ui.design.rememberPageSlide
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.octaviantocan.pulls.core.pageWay
import com.octaviantocan.pulls.ui.design.LabelSwitch

/** What the code keeps clear under its last line: the bar's 52dp pill and the space around it (rule 92). */
private val BAR_ROOM = 84.dp

/**
 * The prototype's diff, under the review's header (whose capsule has Aa in its corner, rule 93): one scrolling file, the
 * AI's note about it, its counts and Reviewed box, and its rows. Pinned under it is the bar: ‹, the file you are on and ›,
 * which turns into the comment field while lines are selected (rule 94). [path] is the file shown; [onPath] moves to
 * another, and a swipe on the file does the same, past the ends into [onOverview] and [onTour]. [tools] is the Aa sheet.
 */
@Composable
internal fun PrDiffScreen(
    session: ReviewSession,
    ui: DiffUi,
    path: String?,
    overview: PrOverview?,
    onPath: (String) -> Unit,
    onOverview: () -> Unit,
    onTour: () -> Unit,
    tools: Boolean,
    onTools: (Boolean) -> Unit,
    onMore: () -> Unit,
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
        Column(Modifier.fillMaxSize()) {
            // The diff is narrowed to a commit: say so, with the way back, where the page cannot scroll it away (rule 80).
            CommitRangeBanner(session, overview, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            if (file == null || shown == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No files in this change.", style = PullsType.sm, color = c.muted) }
            } else {
                FileBody(
                    session, ui, shown, file, files, index, slide, onPath, onOverview, onTour, onFiles, onAsk,
                    Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        if (tools) TextAndToolsSheet(session, ui, file, onClose = { onTools(false) }, onActions = onMore)
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
            LazyColumn(Modifier.fillMaxSize().pageSlide(slide).pinchToZoomCode(session.settings), state = listState) {
                if (note != null) {
                    item(key = "note:${file.path}", contentType = "note") {
                        NoteCard(note, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) {
                            onAsk(AskReference("the note on ${file.path.substringAfterLast('/')}", note))
                        }
                    }
                }
                item(key = "counts:${file.path}", contentType = "counts") { CountsRow(session, file, files, onPath) }
                fileDiffItems(session, ui, file, leading = leading, tail = BAR_ROOM + bottom)
            }
        }
        DiffBar(
            session, ui, file, target, index, count,
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
    val reviewed = session.reviewState(file) == ReviewState.Reviewed
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
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).cueClickable(role = Role.Checkbox, onClickLabel = "Reviewed") {
                session.setReviewed(listOf(file.sha), !reviewed)
                if (!reviewed) {
                    // No "2 of 4" toast (rule 42): the last file of the change gets the success sound and nothing on screen.
                    if (files.all { it.sha in session.reviewed || it.path == file.path }) Sounds.play(Cue.Success)
                    // Reviewing a file moves on to the next one still to review, after a beat to see the tick land.
                    val shaOf = files.associate { it.path to it.sha }
                    nextUnreviewed(files.map { it.path }, file.path) { path -> shaOf[path] in session.reviewed }?.let { next ->
                        scope.launch { delay(220); onPath(next) }
                    }
                }
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            PullsCheck(reviewed, size = 20.dp)
            Text("Reviewed", style = PullsType.sm, color = c.text)
        }
    }
}

/** `.note`: the AI's note about this file, from "Pulls". A tap asks about it, with the note attached (rule 94). */
@Composable
private fun NoteCard(note: String, modifier: Modifier, onAsk: () -> Unit) {
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
        Text(note, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.text)
    }
}

/**
 * `.dctl`: ‹, the file you are on and ›. While lines are selected the whole bar is the comment field instead, and it
 * comes back when they are not (rule 94): the field posts into the viewer's pending review, and with no write access
 * it only names the selection.
 */
@Composable
private fun DiffBar(
    session: ReviewSession,
    ui: DiffUi,
    file: ReviewFile,
    target: ReviewFile,
    index: Int,
    count: Int,
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
    LaunchedEffect(selected == null) { if (selected == null) text = "" }
    var busy by remember { mutableStateOf(false) }
    // With no review pending a comment can be posted at once instead of starting one; the choice sticks for the screen.
    val hasPending = session.reviewData?.pendingReviewId != null
    var single by rememberSaveable { mutableStateOf(false) }
    val post = single && !hasPending
    val canComment = session.canWrite && session.pull != null && !file.isBinary
    val label = selected?.let { s ->
        val first = s.startLine?.takeIf { it < s.line } ?: s.line
        if (first == s.line) "line ${s.line}" else "lines $first–${s.line}"
    }

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
                // A line is selected: the bar is one pill you type straight into, with everything it needs built in.
                Row(
                    Modifier.fillMaxWidth().height(52.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised).padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FloatIcon(Lu.close, "Deselect line", { ui.drafts.remove(file.path); ui.selectionStart.remove(file.path) }, flat = true, size = 40.dp, iconSize = 20.dp, tint = c.muted)
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
                                    if (text.isEmpty()) Text("Comment on ${label ?: "a line"}", style = PullsType.base, color = c.muted, maxLines = 1)
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
                        SendButton({ send() }, "Send comment", enabled = text.isNotBlank() && !busy, busy = busy)
                    } else {
                        Text(label?.let { it.take(1).uppercase() + it.drop(1) } ?: "", Modifier.weight(1f), style = PullsType.base, color = c.muted, maxLines = 1)
                    }
                }
            }
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
