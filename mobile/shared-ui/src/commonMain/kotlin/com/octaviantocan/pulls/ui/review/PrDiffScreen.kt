package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.design.bleed
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.NativeGestureFeedback
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.githubRepository
import com.octaviantocan.pulls.core.softBreaks
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.core.FORWARD
import com.octaviantocan.pulls.core.swipeStep
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.AskReference
import com.octaviantocan.pulls.ui.ai.askSelectionOf
import com.octaviantocan.pulls.ui.ai.githubBlobUrl
import com.octaviantocan.pulls.ui.ai.lineRangeLabel
import com.octaviantocan.pulls.ui.ai.linesPhrase
import com.octaviantocan.pulls.ui.design.FieldStyle
import com.octaviantocan.pulls.ui.design.FieldText
import com.octaviantocan.pulls.ui.design.FootFree
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
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
import com.octaviantocan.pulls.ui.design.pageSlide
import com.octaviantocan.pulls.ui.design.rememberPageSlide
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactCount
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.octaviantocan.pulls.core.pageWay
import com.octaviantocan.pulls.ui.design.LabelSwitch
import com.octaviantocan.pulls.ui.design.rememberTextCopy
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import androidx.compose.runtime.DisposableEffect

/** What the code keeps clear under its last line: the bar's 52dp pill and the space around it (rule 92). */
private val BAR_ROOM = 84.dp

/** The counts line, and the air between it and the code (rule 40), which is see-through so code passing under it fades there. */
private val COUNTS_HEIGHT = 44.dp
private val COUNTS_AIR = 24.dp

/**
 * The prototype's diff under the review's header (the back button and the capsule, with Aa in its corner, rule 93):
 * one scrolling file, its AI note, then its counts and Reviewed box, which stay pinned at the top while the file is
 * shown and its rows scroll under them. Pinned under it is the bar: ‹, the file you are on and ›, which turns into the
 * comment field while lines are selected (rule 94). The files sit side by side (rule 107): [path] is the file shown,
 * [onPath] moves to another, and a swipe on the file does the same; past the first file it goes back to [onOverview],
 * and past the last there is nothing, so the page stays (K26).
 */
@Composable
fun PrDiffScreen(
    session: ReviewSession,
    ui: DiffUi,
    path: String?,
    commit: String?,
    onPath: (String) -> Unit,
    onOverview: () -> Unit,
    onFiles: () -> Unit,
    onAsk: ((AskReference) -> Unit)?,
) {
    val c = LocalPullsColors.current
    if (!LocalReviewCapabilities.current.canReadDiff) {
        Text("Diff reading is unavailable on this host.", Modifier.padding(16.dp), style = PullsType.read, color = c.muted)
        return
    }
    val files = fileSections(session, ui).flatMap { it.files }
    val index = files.indexOfFirst { it.path == path }.let { if (it < 0) 0 else it }
    val file = files.getOrNull(index)
    val width = diffWidthDp()
    SideEffect { if (ui.widthDp != width) ui.widthDp = width }
    // Each file the diff rests on counts once per visit, by its extension only, once its lines are in (`file.view`).
    val shown = file?.takeIf { !it.patchOmitted }
    val read = ui.captureFileRead()
    val shownPath = shown?.path
    LaunchedEffect(read, shownPath) {
        if (read != null && ui.isCurrentFileRead(read)) {
            session.files.firstOrNull { it.path == shownPath && !it.patchOmitted }?.let(session::fileShown)
        }
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        if (file == null && session.opened == null && session.loadError == null) {
            // Review was tapped before the diff came: bars where the code will be, no loading page (rule 51; L5).
            RowsSkeleton(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), rows = 8)
        } else if (file == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No files in this change.", style = PullsType.sm, color = c.muted) }
        } else {
            key(session, ui, ui.captureFileRead()) {
                FilePages(session, ui, files, index, commit, onPath, onOverview, onFiles, onAsk, Modifier.fillMaxSize().padding(top = 8.dp))
            }
        }
    }
}

/**
 * Where the row of files sits, by path. [current] is the file the page rests on; while the row moves, [other] is the
 * one beside it, on the right when [way] is forward and on the left when backward, and [at] is how far the row has
 * gone toward it, from 0 to [way]. A swipe puts the next or the previous file there; a jump from the arrows, the Files
 * sheet or a ticked Reviewed puts the file it goes to there however far apart the two are in the list, so a move is
 * always one page with both files in view and never a blank one.
 */
@Stable
private class FileSlide(start: String) {
    var current by mutableStateOf(start)
    var other by mutableStateOf<String?>(null)
    var way by mutableIntStateOf(FORWARD)
    var at by mutableFloatStateOf(0f)
    var glide: Job? = null

    /** The file the running glide is going to, so a file change that only names it again leaves it running. */
    var heading: String? = null

    /** A finger is moving the row, and how many glides are running; with neither, the row must rest on the file shown. */
    var held by mutableStateOf(false)
    var dragOwner: DiffFileRead? = null
    var gliding by mutableIntStateOf(0)

    /** Where [path] is drawn, in pages from the screen: 0 on it, 1 a page to the right. */
    fun place(path: String): Float = if (path == current) -at else way - at

    /** Rests at once on whichever of the two is nearer, as if the move had finished or never started. */
    fun snap() {
        other?.let { if (abs(at) > 0.5f) current = it }
        other = null
        at = 0f
    }

    /** Glides to [to] with the page spring, starting at [speed] pages a second, and rests there. [order] ranks the files. */
    suspend fun settle(to: String, speed: Float, motion: Boolean, order: (String) -> Int) {
        if (to != current && to != other) {
            snap()
            if (to != current) {
                other = to
                way = pageWay(order(current), order(to))
            }
        }
        if (motion) {
            val end = if (to == current) 0f else way.toFloat()
            animate(at, end, initialVelocity = speed, animationSpec = PullsMotion.PageSpring) { value, _ -> at = value }
        }
        current = to
        other = null
        at = 0f
    }
}

/**
 * The files side by side, as `SidePages` sets the Inbox and Tours (rule 107): the file on screen and the next one move
 * together the whole width, edge to edge with no gap, like one strip (M10), the next arriving from the edge as this one leaves, and nothing
 * fades. A drag moves both with the finger and letting go settles on the nearer, or goes on the way a throw went (rule
 * 105); past the first or the last file the page follows the finger alone and leaves with the view on that side. Each
 * file keeps its place in its list, so coming back to one finds it where it was left.
 */
@Composable
private fun FilePages(
    session: ReviewSession,
    ui: DiffUi,
    files: List<ReviewFile>,
    index: Int,
    commit: String?,
    onPath: (String) -> Unit,
    onOverview: () -> Unit,
    onFiles: () -> Unit,
    onAsk: ((AskReference) -> Unit)?,
    modifier: Modifier,
) {
    val c = LocalPullsColors.current
    val target = files[index]
    val owner = ui.captureFileRead(target)
    val drawnPath by rememberUpdatedState(target.path)
    val slide = remember { FileSlide(target.path) }
    val scope = rememberCoroutineScope()
    val actions = rememberDiffPageActions(session, ui, target, scope) { drawnPath == target.path }
    DisposableEffect(slide) { onDispose { slide.glide?.cancel() } }
    val motion = rememberMotionEnabled()
    // The gesture's callbacks outlive the composition that made them (a remembered function reference kept the file the
    // diff opened on, so a swipe back to that file read as "no change" and the row slid back: recording 17 Oct 14:45,
    // 03:34 to 03:47; Q7). They read the file shown, the list and the page's callbacks through these, never directly.
    val latest by rememberUpdatedState(files)
    val toPath by rememberUpdatedState(onPath)
    val toOverview by rememberUpdatedState(onOverview)
    val order = { p: String -> latest.indexOfFirst { it.path == p } }
    // Past the first file: the page that leaves with the whole view, back to the Overview.
    val edge = key(owner) { rememberPageSlide(Unit, order = { 0 }, full = true) }
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val swipeFrom = with(density) { 64.dp.toPx() }
    val fling = with(density) { PullsMotion.Fling.toPx() }
    val range = with(density) { PullsMotion.FingerRange.toPx() }
    val count = files.size

    fun glideTo(to: String, speed: Float = 0f) {
        if (!actions.current) return
        slide.glide?.cancel()
        slide.heading = to
        slide.glide = scope.launch {
            slide.gliding++
            try { slide.settle(to, speed, motion, order) } finally {
                slide.gliding--
                if (slide.heading == to) slide.heading = null
            }
        }
    }

    // The row never stops between two files (K10): whenever no finger holds it and no glide runs, it goes to the file
    // the bar names, from wherever a cut-off gesture or glide left it.
    val shownPath by rememberUpdatedState(target.path)
    LaunchedEffect(slide, owner) {
        snapshotFlow { !slide.held && slide.gliding == 0 && (slide.at != 0f || slide.other != null || slide.current != shownPath) }
            .collectLatest { stray ->
                if (!stray) return@collectLatest
                // A beat first, so a glide handing over to the next one, or a file change on its way, is not cut short.
                delay(120)
                glideTo(shownPath)
            }
    }

    // A new file starts with nothing selected, and the row glides to it unless the swipe that chose it already does.
    LaunchedEffect(target.path, owner) {
        if (!actions.current) return@LaunchedEffect
        ui.drafts.clear()
        ui.selectionStart.clear()
        ui.mark = null
        // A swipe already set the row gliding here as the finger let go: restarting it would stop it mid-way (O22).
        if (slide.glide?.isActive == true && slide.heading == target.path) return@LaunchedEffect
        if (slide.current != target.path || slide.at != 0f) glideTo(target.path)
    }

    // Syntax colours come from the server a file at a time. Asking for the files a swipe or an arrow away too, while this
    // one is read, has them coloured before they arrive instead of plain first; a left-out patch is fetched the same way.
    val dark = c.dark
    listOf(index, index + 1, index - 1, index + 2).mapNotNull { files.getOrNull(it) }.forEach { near ->
        val texts = ui.lineTexts(near)
        key(near.path) {
            val nearbyOwner = ui.captureFileRead(near)
            LaunchedEffect(nearbyOwner, texts, dark) { if (!near.isBinary) ui.highlight(near, texts, dark) }
            LaunchedEffect(nearbyOwner) {
                if (near.patchOmitted && ui.isCurrentFileRead(nearbyOwner, near)) session.loadPatches(patchesAround(session.files, near.path))
            }
        }
    }

    fun dragBy(delta: Float) {
        if (!actions.current || width <= 0) return
        slide.held = true
        slide.dragOwner = owner
        // A finger stops a glide where it is; one between two files far apart in the list rests on the nearer first.
        if (slide.glide?.isActive == true) {
            slide.glide?.cancel()
            val other = slide.other
            if (other != null && abs(order(other) - order(slide.current)) != 1) slide.snap()
        }
        var rest = delta
        if (edge.finger != 0f) {
            val before = edge.finger
            edge.follow(rest, range, back = before > 0f, forward = before < 0f)
            rest -= edge.finger - before
            if (edge.finger != 0f || rest == 0f) return
        }
        val at = order(slide.current)
        if (at < 0) return
        // Files touch, like one strip, so a file moves by its width alone and stays under the finger ("there's still a
        // gap": recording 16 Oct 09:50, 04:27 to 04:41; M10).
        val next = slide.at - rest / width
        val lo = if (at > 0) -1f else 0f
        val hi = if (at in 0 until latest.size - 1) 1f else 0f
        val held = next.coerceIn(lo, hi)
        slide.at = held
        when {
            held > 0f -> { slide.way = FORWARD; slide.other = latest[at + 1].path }
            held < 0f -> { slide.way = BACKWARD; slide.other = latest[at - 1].path }
            else -> slide.other = null
        }
        // Past the first file the page follows the finger alone, back toward the Overview; past the last it stays (K26).
        if (next < 0f && lo == 0f) edge.follow(-next * width, range, back = true, forward = false)
    }

    fun release(velocity: Float) {
        if (slide.dragOwner !== owner) return
        slide.dragOwner = null
        slide.held = false
        if (!actions.current) return
        if (edge.finger != 0f) {
            // A throw counts as well as a long drag, and the page goes on the finger's way with the whole view (rule 105).
            when (swipeStep(edge.finger, swipeFrom, velocity, fling)) {
                BACKWARD -> { toOverview(); scope.launch { edge.leave(BACKWARD, velocity) } }
                else -> scope.launch { edge.release(0, velocity) }
            }
            return
        }
        // A third of the way over is enough to go on to that file, and a throw that way goes on however short it was;
        // half the width and a hard fling was too much ("I have to like really like slide my finger very hard ... and
        // then it goes back to the same one", recording 15 Oct 15:37, 10:13 to 10:27; L18).
        val at = slide.at
        val forward = velocity < -fling || (velocity <= fling && at > FILE_SETTLE)
        val backward = velocity > fling || (velocity >= -fling && at < -FILE_SETTLE)
        val step = when {
            forward && at > 0f -> 1
            backward && at < 0f -> -1
            else -> 0
        }
        val to = latest.getOrNull(order(slide.current) + step)?.path ?: slide.current
        val speed = if (width > 0) (-velocity / width).takeIf { it.isFinite() } ?: 0f else 0f
        if (to == shownPath) {
            glideTo(to, speed)
        } else {
            // A file that changes under the finger is felt like any page that does (rule 109, J20).
            ControlFeedback.gesture(NativeGestureFeedback.Page)
            // The row goes on gliding the moment the finger lets go, before the new file is laid out, instead of waiting
            // for the screen to take the file up and standing still between the two meanwhile ("it stops a little in the
            // middle": recording 16 Oct 13:06, 09:36 to 09:43; O22).
            glideTo(to, speed)
            toPath(to)
        }
    }

    // While a move runs, the file it leaves and the one it goes to; a file that left the list is not drawn.
    val shown = listOfNotNull(slide.current, slide.other).mapNotNull { p -> files.firstOrNull { it.path == p } }.ifEmpty { listOf(target) }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val band = with(density) { PullsMotion.EdgeTop.toPx() }
    val countsPx = with(density) { COUNTS_HEIGHT.toPx() }
    val under = { ui.lists[target.path]?.underTop(0, band) ?: 0f }

    Box(modifier) {
        Box(
            Modifier.fillMaxSize().clipToBounds().onSizeChanged { width = it.width }.fileSwipe(
                owner = owner,
                enabled = { actions.current && ui.drafts[shownPath] == null && ui.dragSel == null && ui.selectionStart[shownPath] == null },
                onCode = { y -> onCodeRow(ui.lists[shownPath], y, countsPx) },
                codeHasRoom = { dx, across -> !session.settings.codeWrap && latest.firstOrNull { it.path == shownPath }?.let { codeHasRoom(ui, it, dx, across) } == true },
                onDrag = ::dragBy,
                onEnd = ::release,
            ),
        ) {
            Box(Modifier.fillMaxSize().pageSlide(edge)) {
                for (file in shown) {
                    key(file.path) {
                        Box(Modifier.fillMaxSize().graphicsLayer { translationX = slide.place(file.path) * size.width }) {
                            FilePage(session, ui, file, current = file.path == target.path, onAsk, tail = BAR_ROOM + bottom)
                        }
                    }
                }
            }
        }
        // The counts and the one Reviewed box stay put while the files slide under them; only their words change (K9).
        CountsRow(session, target, index, files, actions, { toPath(it) }, hairline = { 1f - under() })
        key(owner) { DiffBar(
            session, ui, target, index, count, commit, onAsk,
            onPrev = { actions.read { files.getOrNull(index - 1)?.let { toPath(it.path) } } },
            onNext = { actions.read { files.getOrNull(index + 1)?.let { toPath(it.path) } } },
            onFiles = { actions.read(onFiles) },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) }
    }
}

/**
 * One file's page: its note and its rows, under the counts line that [FilePages] keeps still over them. The rows
 * dissolve into the page under the counts line and under the bar at the bottom, instead of being cut by them (rule 74).
 * [current] is the file the bar names, the one a focus request scrolls.
 */
@Composable
private fun FilePage(
    session: ReviewSession,
    ui: DiffUi,
    file: ReviewFile,
    current: Boolean,
    onAsk: ((AskReference) -> Unit)?,
    tail: Dp,
) {
    val c = LocalPullsColors.current
    val density = LocalDensity.current
    val owner = ui.captureFileRead(file)
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val latestAsk by rememberUpdatedState(onAsk)
    val listState = remember(ui, ui.captureFileRead(), file.path) { ui.lists.getOrPut(file.path) { LazyListState() } }
    val note = session.saved?.analysis?.files?.firstOrNull { it.path == file.path }?.summary?.takeIf { it.isNotBlank() && capabilities.canReadResults }
    if (current) DiffFocus(session, ui, file, listState)
    val leading = if (note != null) 1 else 0
    val band = with(density) { PullsMotion.EdgeTop.toPx() }
    val counts = with(density) { COUNTS_HEIGHT.toPx() }
    val under = { listState.underTop(0, band) }

    LazyColumn(
        Modifier.fillMaxSize()
            .edgeFade(top = PullsMotion.EdgeTop, color = c.base, topStrength = under, topInset = { counts })
            .edgeFade(bottom = PullsMotion.EdgeBottom, color = c.raised)
            .pinchToZoomCode(session.settings, remember(listState, ui, file, density, ui.captureFileRead(file)) { KeepUnderFingers(listState, ui, file, density) }),
        state = listState,
        contentPadding = PaddingValues(top = COUNTS_HEIGHT + COUNTS_AIR),
    ) {
        if (note != null) {
            item(key = "note:${file.path}", contentType = "note") {
                val ask: (() -> Unit)? = if (capabilities.canAsk && onAsk != null) ({
                    if (readiness.ai(capabilities).canAsk && ui.isCurrentFileRead(owner, file))
                        latestAsk?.invoke(AskReference("the note on ${file.path.substringAfterLast('/')}", note))
                }) else null
                NoteCard(session, note, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp), ask)
            }
        }
        fileDiffItems(session, ui, file, leading = leading, tail = tail)
    }
}

/** How far a file's rows have gone under the counts line, 0 at rest and 1 once they are a [band] past item [pinnedAt]. */
private fun LazyListState.underTop(pinnedAt: Int, band: Float): Float =
    if (firstVisibleItemIndex > pinnedAt) 1f else (firstVisibleItemScrollOffset / band).coerceIn(0f, 1f)

/**
 * Is the row under [y] (in the list's own pixels) a line of code, which scrolls sideways itself? The counts line, the top
 * [covered] pixels, covers the code under it; below it, code that has scrolled into the air over the first row still counts.
 */
private fun onCodeRow(state: LazyListState?, y: Float, covered: Float): Boolean {
    val info = state?.layoutInfo ?: return false
    if (y < covered) return false
    val at = y - info.beforeContentPadding
    val rows = info.visibleItemsInfo.filter { at >= it.offset && at < it.offset + it.size }
    return rows.any { it.contentType == "line" || it.contentType == "split" }
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
    owner: DiffFileRead?,
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
    return pointerInput(owner) { awaitEachGesture { swipeGesture(isOn.value, codeAt.value, room.value, drag.value, end.value) } }
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
    try {
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
                // A thumb's swipe runs at a slant, so one more sideways than steeply down is the file's, decided the moment
                // the finger passes the slop either way, before the list takes it as a scroll (L18).
                maxOf(abs(dx), abs(dy)) <= slop -> Unit
                abs(dx) < abs(dy) * SWIPE_SLANT -> return
                else -> {
                    if (code && codeHasRoom(dx, down.position.x / size.width.coerceAtLeast(1))) return
                    taken = true
                    change.consume()
                    onDrag(dx)
                }
            }
        }
    } catch (cut: CancellationException) {
        // A gesture cut off mid-drag still lets go of the row, so it never stays between two files (K10).
        if (taken) onEnd(0f)
        throw cut
    }
    if (taken) onEnd(tracker.calculateVelocity().x)
}

/**
 * The file's counts and threads, and the one Reviewed box; reviewing a file moves on to the next one still to review.
 * It stays at the top of the diff, on the page's colour, while the files slide and scroll under it (K9): a new file's
 * counts come in the way the files went, as the bar's name does (rule 105). The air under it is see-through so the
 * code fades there (rule 74), and the hairline at its foot gives way to that fade by [hairline], 0 to 1.
 */
@Composable
private fun CountsRow(session: ReviewSession, file: ReviewFile, index: Int, files: List<ReviewFile>, actions: DiffPageActions, onPath: (String) -> Unit, hairline: () -> Float) {
    val c = LocalPullsColors.current
    val capabilities = LocalReviewCapabilities.current
    val threads = session.reviewData?.threads.orEmpty().count { it.path == file.path && !it.outdated }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(c.base).padding(horizontal = 16.dp).height(COUNTS_HEIGHT),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium)
            LabelSwitch(Triple(index, file, threads), Modifier.weight(1f), order = { it.first }, contentAlignment = Alignment.CenterStart) { (_, shown, count) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("+${compactCount(shown.additions)}", style = mono, color = StatusColors.green(c.dark), maxLines = 1)
                        Text("−${compactCount(shown.deletions)}", style = mono, color = StatusColors.red(c.dark), maxLines = 1)
                    }
                    Text(when (count) { 0 -> "No threads"; 1 -> "1 thread"; else -> "$count threads" }, style = PullsType.sm, color = c.muted, maxLines = 1)
                }
            }
            if (capabilities.canMarkReviewed) ReviewedBox(session, file) {
                actions.toggleReviewed(files, { ControlFeedback.play(it) }, onPath)
            }
        }
        // The code starts under a hairline, on its own white, with 24dp of air between it and this row (rule 40).
        Box(
            Modifier.fillMaxWidth().height(COUNTS_AIR).drawBehind {
                drawRect(c.borderMute, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()), alpha = hairline().coerceIn(0f, 1f))
            },
        )
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

/** `.note`: the AI's note about this file, from "Pully", its mentions links to their place (rule 96). A tap asks about it, with the note attached (rule 94). */
@Composable
private fun NoteCard(session: ReviewSession, note: String, modifier: Modifier, onAsk: (() -> Unit)?) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised)
            .then(if (onAsk != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Ask about this note", onClick = onAsk) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The name alone: an initial in a disc is not information (rule 1).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pully", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Spacer(Modifier.weight(1f))
            if (onAsk != null) Text("Ask about it", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
        }
        com.octaviantocan.pulls.ui.ai.AiMarkdown(session, note, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f, color = c.text))
    }
}

/**
 * `.dctl`: ‹, the file you are on and ›. While lines are selected the whole bar is the comment bar instead, and it comes
 * back when they are not (rule 94): ✕, the field that names the lines, and Send, nothing else (K11). The field posts
 * into the viewer's pending review; with no write access the bar only names the lines. What else lines offer (Ask,
 * Copy, Copy GitHub link) opens from holding a line.
 */
@Composable
private fun DiffBar(
    session: ReviewSession,
    ui: DiffUi,
    file: ReviewFile,
    index: Int,
    count: Int,
    commit: String?,
    onAsk: ((AskReference) -> Unit)?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFiles: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalPullsColors.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val owner = ui.captureFileRead(file)
    val capabilities by rememberUpdatedState(LocalReviewCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val selected = ui.drafts[file.path]
    // Tapping another line keeps what was typed (the comment follows the selection); a different view starts empty.
    var text by remember(session, owner, file.path) { mutableStateOf("") }
    LaunchedEffect(owner, selected == null) {
        if (owner?.let(ui::isCurrentFileRead) == true && selected == null) { text = ""; ui.lineActions = null }
    }
    val actions = remember(session, ui, owner, file.path, scope, readiness) {
        DiffCommentActions(session, ui, file, scope, { capabilities }, readiness) { cue, message ->
            toaster.show(message ?: if (cue == Cue.Success) "Comment added to your pending review" else "Couldn't add the comment", cue = cue)
        }
    }
    val busy = actions.busy
    val canComment = readiness.review(capabilities).canWriteComments && session.canWrite && session.pull != null && !file.isBinary
    // The one place the lines are named while they are selected: in the sentence, or alone when that is too long.
    val lines = selected?.let { s -> linesPhrase(s.startLine ?: s.line, s.line) }
    val linesLabel = selected?.let { s -> linesPhrase(s.startLine ?: s.line, s.line, label = true) }

    fun send() {
        val draft = selected ?: return
        actions.post(draft, text) {
            // A selection made while this write was in flight keeps its own input and focus.
            if (ui.drafts[file.path] === draft) {
                text = ""
                ui.drafts.remove(file.path)
                focus.clearFocus()
            }
        }
    }

    // In the foot bar's place, so the Overview's pill gives way to it without the bar sliding off with the page (Q2).
    FootFree("diff", modifier) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
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
                    FilePill(file, index, count, commit, onFiles, Modifier.weight(1f))
                    FloatIcon(Lu.next, "Next file", onNext, size = 48.dp, enabled = index < count - 1, iconSize = 22.dp, primary = true)
                }
            } else {
                // Lines are selected: the bar is one pill you type straight into, with everything it needs built in.
                Row(
                    Modifier.fillMaxWidth().height(52.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised).padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FloatIcon(Lu.close, "Deselect the lines", { if (ui.isCurrentFileRead(owner, file) && ui.drafts[file.path] === selected) { ui.drafts.remove(file.path); ui.selectionStart.remove(file.path) } }, flat = true, size = 40.dp, iconSize = 20.dp, tint = c.muted)
                    if (canComment) {
                        FieldText(
                            value = text,
                            onChange = { if (ui.isCurrentFileRead(owner, file) && capabilities.canWriteComments && session.canWrite) text = it },
                            placeholder = "",
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.weight(1f),
                            placeholderContent = { if (lines != null && linesLabel != null) FitText(listOf("Comment on $lines", linesLabel), FieldStyle, c.muted) },
                        )
                    } else {
                        Box(Modifier.weight(1f)) { if (linesLabel != null) FitText(listOf(linesLabel), FieldStyle, c.muted) }
                    }
                    if (canComment) SendButton({ send() }, "Send comment", enabled = text.isNotBlank() && !busy, busy = busy)
                }
            }
        }
    }
    }
    val held = ui.lineActions
    if (held != null && held.path == file.path) LineActionsSheet(session, ui, file, held, onAsk) { if (ui.isCurrentFileRead(owner, file) && ui.lineActions === held) ui.lineActions = null }
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
 * What else selected lines offer, from holding a line (K11): Ask about them, which opens Ask with their code
 * attached as a card over the field (as a file note is), and copying where they are. The bar names the lines, so the
 * sheet is named by the file alone.
 */
@Composable
private fun LineActionsSheet(session: ReviewSession, ui: DiffUi, file: ReviewFile, lines: DraftTarget, onAsk: ((AskReference) -> Unit)?, onClose: () -> Unit) {
    val copy = rememberTextCopy()
    val owner = ui.captureFileRead(file)
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val latestAsk by rememberUpdatedState(onAsk)
    val toaster = LocalToaster.current
    val selection = askSelectionOf(file.path, lines.side, lines.line, lines.startLine)
    val opened = session.opened
    val repo = githubRepository(session.target)
    val link = opened?.let { githubBlobUrl(repo?.first, repo?.second, it.refKind, it.headSha, it.baseSha, file.path, file.previousPath, selection) }
    val one = selection.startLine == selection.endLine
    fun current(): Boolean = ui.isCurrentFileRead(owner, file) && ui.lineActions === lines
    PullsSheet(onClose, file.path.substringAfterLast('/'), expandable = false) {
        SheetCard(Modifier.padding(bottom = 16.dp)) {
            if (onAsk != null && capabilities.canAsk) SheetRow(if (one) "Ask about this line" else "Ask about these lines", {
                if (!current() || !readiness.ai(capabilities).canAsk) return@SheetRow
                onClose()
                // The code itself, or where it is when the lines are not loaded.
                val code = selectedCode(ui.entries(file), selection.side, selection.startLine..selection.endLine).ifEmpty { lineRangeLabel(selection) }
                latestAsk?.invoke(AskReference("${linesPhrase(selection.startLine, selection.endLine)} of ${file.path}", code))
            }, first = true, iconless = true)
            SheetRow(t("ask.copyLocation"), { if (current()) { copy(lineRangeLabel(selection)); toaster.show("Location copied", cue = Cue.Success); onClose() } }, first = onAsk == null || !capabilities.canAsk, iconless = true)
            if (link != null) SheetRow(t("ask.copyLink"), { if (current()) { copy(link); toaster.show("Link copied", cue = Cue.Success); onClose() } }, iconless = true)
        }
    }
}

/**
 * The file you are on, between ‹ and ›: its name whole in the code face, and under it the end of its folder (rule 90)
 * and where it sits in the change. A long name takes the room instead of the folder. While the diff is narrowed to
 * [commit], that takes the folder's place in the link colour (K25). A tap opens every file, whole.
 */
@Composable
private fun FilePill(file: ReviewFile, index: Int, count: Int, commit: String?, onClick: () -> Unit, modifier: Modifier) {
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
            val dir = path.substringBeforeLast('/', "").takeIf { name.length <= SHORT_NAME && commit == null }.orEmpty()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The name in the face it has on the file rows, which fits a long one on a line where the code font would break it.
                // A long name wraps between its words, in lines of even length.
                Text(softBreaks(name), style = PullsType.sm.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, lineBreak = LineBreak.Heading), color = c.text, maxLines = 3)
                if (dir.isNotEmpty() || count > 1 || commit != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (commit != null) Text(commit, style = PullsType.sm, color = c.activeText, maxLines = 1, softWrap = false)
                        if (dir.isNotEmpty()) PathTail(dir, mono, c.muted, Modifier.weight(1f, fill = false))
                        val before = dir.isNotEmpty() || commit != null
                        if (count > 1) Text("${if (before) " · " else ""}${at + 1}/$count", style = mono.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** How far over (in pages) a file must be dragged for letting go to go on to it. */
private const val FILE_SETTLE = 0.3f

/** How much of the downward move a sideways one must at least be for the swipe to change file: about 55° from flat. */
private const val SWIPE_SLANT = 0.7f

/** A file name up to this long leaves room in the pill for the end of its folder. */
private const val SHORT_NAME = 22

/**
 * Brings a focus request into view: opens the file's gap when the line sits in unchanged text,
 * scrolls to the line with some context above it, and marks it for a moment. The request is
 * consumed once it has been shown, so coming back to the screen does not jump again.
 */
@Composable
private fun DiffFocus(session: ReviewSession, ui: DiffUi, file: ReviewFile, listState: LazyListState) =
    DiffFocusOn(session.focus, ui, file, listState) { target -> if (session.focus === target) session.focus = null }
