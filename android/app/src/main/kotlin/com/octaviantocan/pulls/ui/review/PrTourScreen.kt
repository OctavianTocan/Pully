package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.tourPreview
import com.octaviantocan.pulls.data.TourPreview
import com.octaviantocan.pulls.ui.home.stayOnPull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import com.octaviantocan.pulls.ui.home.SearchDim
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Tour
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.data.TourStop
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.ProvideAiText
import com.octaviantocan.pulls.ui.ai.Glossary
import com.octaviantocan.pulls.ui.ai.Sources
import com.octaviantocan.pulls.ui.ai.TourBlockView
import com.octaviantocan.pulls.ui.ai.TourEmpty
import com.octaviantocan.pulls.ui.ai.tourComing
import com.octaviantocan.pulls.ui.ai.startTour
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.pageSlide
import com.octaviantocan.pulls.ui.design.rememberPageSlide
import com.octaviantocan.pulls.ui.design.topEdgeStrength
import kotlinx.coroutines.launch
import com.octaviantocan.pulls.ui.design.LabelSwitch
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.core.swipeStep
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.rotate
import com.octaviantocan.pulls.ui.Command
import com.octaviantocan.pulls.ui.UseCommands

/**
 * The tour, under the review's header: a calm strip (the ring, the stop you are on, a chevron) that opens the sections
 * as a sheet, one white card per stop, and an Ask bar at the bottom (rule 97). A swipe to the right goes back to the
 * Diff ([onChanges]), the page before it. [onReached] reports the furthest stop the reader has scrolled to, which the
 * Tours tab shows as progress. The Ask bar sends a question to [onAsk], which continues the conversation in the Ask
 * sheet; [onOpenAsk] opens that sheet on the conversation as it is, and [onAskHistory] the earlier chats alone (N28, P5).
 * [onAskDim] says when the bar is focused and empty, for the header the page does not draw to dim with it (N29).
 */
@Composable
internal fun PrTourScreen(
    session: ReviewSession,
    head: PrHead,
    resumeAt: Int,
    onReached: (stop: Int, total: Int, title: String) -> Unit,
    onChanges: () -> Unit,
    onOpenSource: (TourSource) -> Unit,
    onAsk: (String) -> Unit,
    onOpenAsk: () -> Unit,
    onAskHistory: () -> Unit,
    onAskDim: (focused: Boolean, empty: Boolean) -> Unit = { _, _ -> },
    onProgress: () -> Unit = {},
) {
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val tour = session.tour
    val running = session.taskFor("tour")?.running == true
    val stops = tour?.stops.orEmpty()
    val n = stops.size
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var sections by remember { mutableStateOf(false) }
    // While the tour is written, its plan and each stop already written show under the progress, in the places the stops
    // will take, so nothing moves when they come and the page is never scrolled for the reader ("start creating some
    // previews of what it's gonna build under it while it does it ... without moving me", recording 15 Oct 15:37, 13:47
    // to 14:05; L26). The finished tour then takes their places without rising in again.
    val preview = if (tour == null) session.taskFor("tour")?.takeIf { it.running }?.let { tourPreview(it.activities) } else null
    val writing = tour == null && (running || tourComing(session))
    // The strip's sections: the tour's stops, or the plan's while it is written, so the picker is there from the plan on
    // and fills in as each section is written ("this should be already here and it should fill as the AI writes it":
    // recording 16 Oct 13:06, 07:15 to 07:28; O17).
    val titles = tour?.stops?.map { it.title } ?: preview?.planned?.map { it.first }.orEmpty()
    val shownCount = titles.size

    // The stop you are on: the last whose top has passed 64dp plus a fifth of the screen (at most 120dp), or the last at the end.
    val current by remember(shownCount) {
        derivedStateOf {
            val n = shownCount
            if (n == 0) 0 else {
                val info = listState.layoutInfo
                val threshold = with(density) { 64.dp.toPx() } + minOf(with(density) { 120.dp.toPx() }, info.viewportSize.height * 0.2f)
                val passed = info.visibleItemsInfo.filter { it.index in 1..n && it.offset <= threshold }
                when {
                    !listState.canScrollForward && listState.canScrollBackward -> n
                    passed.isNotEmpty() -> passed.last().index
                    else -> 1
                }
            }
        }
    }
    // The ring: how far the reading line has gone through the stops, moving with the scroll and full at the end.
    val reading by remember(n) {
        derivedStateOf {
            val info = listState.layoutInfo
            val line = with(density) { 64.dp.toPx() } + minOf(with(density) { 120.dp.toPx() }, info.viewportSize.height * 0.2f)
            val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 }
            tourProgress(
                count = n,
                stops = info.visibleItemsInfo.filter { it.index in 1..n }.map { Triple(it.index, it.offset, it.size) },
                firstVisible = listState.firstVisibleItemIndex,
                line = line,
                bottom = (info.viewportEndOffset - info.afterContentPadding).toFloat(),
                rest = last?.let { (it.offset + it.size + info.afterContentPadding - info.viewportEndOffset).toFloat().coerceAtLeast(0f) },
                lastHeight = last?.size?.toFloat() ?: 0f,
            )
        }
    }
    // While the tour is written the ring is how much of it is written.
    val progress = if (preview != null && shownCount > 0) preview.written.size.toFloat() / shownCount else reading
    LaunchedEffect(current, n) {
        if (n > 0 && current in 1..n) onReached(current, n, stops[current - 1].title)
    }
    // The stops folded to their titles, per tour, kept for the session (N15).
    val folds = remember(session.target, tour?.generatedAt) { TourFolds.of("${session.target}|${tour?.generatedAt}") }
    // Commands on the Tour lead with the tour's own actions (N14).
    UseCommands {
        if (tour == null) return@UseCommands emptyList()
        val group = "Tour"
        listOf(
            Command("tour.ask", "Ask about this tour", group, keywords = "question chat ai", suggested = true) { onOpenAsk() },
            Command("tour.diff", "Switch to the diff", group, keywords = "changes files code", suggested = true) { onChanges() },
            Command("tour.regenerate", "Regenerate tour", group, keywords = "rewrite again new", suggested = true) { startTour(session) },
            Command("tour.next", "Next stop", group, keywords = "section forward", suggested = true) {
                val next = (current + 1).coerceAtMost(n)
                folds.unfold(next - 1)
                scope.launch { listState.animateScrollToItem(next) }
            },
            Command("tour.previous", "Previous stop", group, keywords = "section back", suggested = true) {
                val previous = (current - 1).coerceAtLeast(1)
                folds.unfold(previous - 1)
                scope.launch { listState.animateScrollToItem(previous) }
            },
            Command("tour.fold", "Fold every stop", group, keywords = "collapse titles") { folds.foldAll(n) },
            Command("tour.unfold", "Unfold every stop", group, keywords = "expand open") { folds.unfoldAll() },
        )
    }
    // `tour.event`: started on the first stop shown, next as the reader reads on, finished at the last, skipped when the
    // page goes before then. Only stop numbers, never what a stop says.
    val readingLog = remember(tour?.generatedAt) { TourReading() }
    LaunchedEffect(current, n) { if (n > 0 && current in 1..n) readingLog.at(current, n) }
    DisposableEffect(readingLog) { onDispose { readingLog.leave() } }
    // Pick up where the reader left off, once for each tour. Coming back from a place the tour opened (a file, GitHub)
    // finds the list where it was, kept by the review's saveable state, and must not be moved to the top of its stop (J35).
    var resumedFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(tour?.generatedAt) {
        val at = tour?.generatedAt ?: return@LaunchedEffect
        if (resumedFor == at) return@LaunchedEffect
        resumedFor = at
        if (n > 1 && resumeAt in 2..n) listState.scrollToItem(resumeAt)
    }

    // A tour seen for the first time brings its stops onto the page one after another, rising into place (K14); one
    // already seen, or a card scrolled back into view, is simply there.
    var arrivedFor by rememberSaveable { mutableStateOf<String?>(null) }
    var previewed by rememberSaveable { mutableStateOf(false) }
    if (preview != null && !previewed) previewed = true
    val arriving = tour != null && tour.generatedAt != arrivedFor && !previewed
    LaunchedEffect(tour?.generatedAt) { if (tour != null) { delay(PullsMotion.SLOW.toLong()); arrivedFor = tour.generatedAt } }

    // A swipe to the right takes the cards back to the Diff, following the finger as the Diff's files do. The Tour is
    // the last page, so a swipe to the left goes nowhere and the cards stay put. A swipe on code in a card scrolls the code.
    val slide = rememberPageSlide(Unit, order = { 0 })
    var drag by remember { mutableFloatStateOf(0f) }
    val swipeFrom = with(density) { 64.dp.toPx() }
    val fling = with(density) { PullsMotion.Fling.toPx() }
    val range = with(density) { PullsMotion.FingerRange.toPx() }
    val swipe = rememberDraggableState { delta ->
        drag += delta
        slide.follow(delta, range, forward = false)
    }

    // Every mention in the tour opens its place, and its code is the diff's code view (rules 95 and 96).
    ProvideAiText(session, { place -> onOpenSource(TourSource(place.path, place.line, "")) }) {
        Box(Modifier.fillMaxSize().background(c.base)) {
            // The capsule header is the review's, over all three views (rule 93); the strip starts under it.
            Column(Modifier.fillMaxSize()) {
                if (shownCount > 0 && current in 1..shownCount) {
                    // The ring, the stop you are on and the chevron, each in its own column so the title never runs under
                    // the ring; no "2 of 4" (rule 97). Tapping it opens the sections.
                    Row(
                        Modifier.padding(top = 12.dp).fillMaxWidth()
                            .cueClickable(role = Role.Button, onClickLabel = "Sections") { sections = true }
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProgressRing(progress)
                        // One line with an ellipsis, or two whole lines: never a line cut in half. Reading on brings the next stop's
                        // name up from below and scrolling back brings the last one down, with the cards (rule 105).
                        LabelSwitch(current, Modifier.weight(1f), order = { it }, vertical = true) { at ->
                            Text(titles.getOrNull(at - 1).orEmpty(), Modifier.fillMaxWidth(), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        LuIcon(Lu.chevd, tint = c.muted, size = 20.dp)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 120.dp
                    LazyColumn(
                        // The cards dissolve under the strip once they move (a tour at rest is sharp) and into the page under the Ask pill (rule 74).
                        Modifier.fillMaxSize()
                            .draggable(
                                swipe, Orientation.Horizontal,
                                onDragStarted = { drag = 0f },
                                onDragStopped = { velocity ->
                                    // A throw counts as well as a long drag, and the cards leave with the view the way the
                                    // finger went (rule 105); a swipe that falls short puts them back.
                                    val back = swipeStep(drag, swipeFrom, velocity, fling) == BACKWARD
                                    drag = 0f
                                    if (back) {
                                        onChanges()
                                        slide.leave(BACKWARD, velocity)
                                    } else {
                                        slide.release(0, velocity)
                                    }
                                },
                            )
                            .pageSlide(slide)
                            .edgeFade(top = PullsMotion.EdgeTop, bottom = PullsMotion.EdgeBottom, topStrength = { listState.topEdgeStrength(with(density) { PullsMotion.EdgeTop.toPx() }) }),
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = bottom),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(key = "intro") { Intro(session, head, tour, running, preview) }
                        if (preview != null) itemsIndexed(preview.planned, key = { index, _ -> "stop:$index" }) { index, (title, _) ->
                            val written = preview.written[index]
                            if (written != null) StopCard(session, written, onOpenSource, folded = folds.folded(index), onFold = { folds.toggle(index) })
                            else PlannedStop(title, next = (0 until index).all { it in preview.written })
                        }
                        itemsIndexed(stops, key = { index, _ -> "stop:$index" }) { index, stop ->
                            Box(Modifier.arrive(index, play = arriving)) { StopCard(session, stop, onOpenSource, folded = folds.folded(index), onFold = { folds.toggle(index) }) }
                        }
                        // The tour ends with its last card: no closing line and no button back (rule 97); the header or a swipe leaves it.
                        if (tour != null && tour.glossary.isNotEmpty()) item(key = "glossary") { Card { Glossary(tour.glossary, Modifier) } }
                    }
                }
            }
            // The Ask bar is Home's search pill: the same face and size, and the page dims behind it while it waits (K12),
            // the review's header too (N29). There is nothing to ask about before the tour is written, so there is no
            // bar until then (M12). It rests short, reading Ask, and a tap opens it (Q5); open and empty, its button is the
            // earlier chats, and a question sent continues the conversation in the Ask sheet (N28). The earlier chats open
            // on their own sheet, without the conversation under them (P5).
            var askText by remember { mutableStateOf("") }
            var askFocused by remember { mutableStateOf(false) }
            LaunchedEffect(askFocused, askText.isEmpty(), tour != null) { onAskDim(tour != null && askFocused, askText.isEmpty()) }
            DisposableEffect(Unit) { onDispose { onAskDim(false, true) } }
            if (tour != null) SearchDim(askFocused, askText.isEmpty())
            if (writing) WritingLip(session, onProgress, Modifier.align(Alignment.BottomCenter))
            LaunchedEffect(session.key) { session.loadChatHistory() }
            if (tour != null) AskBar(
                "ask-tour", askText, { askText = it }, "Ask about this tour", onSend = onAsk,
                modifier = Modifier.align(Alignment.BottomCenter),
                onFocus = { askFocused = it },
                // Only when there are earlier chats to open (P4).
                onOpenHistory = if (session.key != null && !session.chatHistory.isNullOrEmpty()) onAskHistory else null,
            )
        }
    }

    if (sections) SectionsSheet(titles, current, writing = preview?.let { p -> titles.indices.filterNot { it in p.written }.toSet() }, onPick = { index ->
        sections = false
        // A folded stop picked from the sections opens, so the reader lands on what they asked for (N15).
        folds.unfold(index)
        scope.launch { listState.animateScrollToItem(index + 1) }
    }) { sections = false }
}

/**
 * How far through the tour's [count] stops the reader is, 0 to 1, moving with the scroll and never jumping: the stops
 * above the reading [line] plus the part of the one it crosses. [stops] are the stop cards on screen as (number from 1,
 * top, height). Once the last item ([lastHeight] tall) is on screen, [rest] is how far the list can still scroll, and
 * the line slides down to the [bottom] of the content as that runs out, so the ring is full exactly when the list
 * cannot scroll further. The slide is spread over the last item's height when that is shorter than the way down, so
 * the line does not leap when a short last card comes into view.
 */
internal fun tourProgress(count: Int, stops: List<Triple<Int, Int, Int>>, firstVisible: Int, line: Float, bottom: Float, rest: Float?, lastHeight: Float = 0f): Float {
    if (count <= 0) return 0f
    if (firstVisible > count) return 1f
    val way = (bottom - line).coerceAtLeast(0f)
    val reading = if (rest == null) line else {
        val window = minOf(way, lastHeight).coerceAtLeast(1f)
        line + way * (1f - (rest / window).coerceIn(0f, 1f))
    }
    val (number, top, height) = stops.lastOrNull { it.second <= reading } ?: return 0f
    val through = if (height <= 0) 1f else ((reading - top) / height).coerceIn(0f, 1f)
    return ((number - 1 + through) / count).coerceIn(0f, 1f)
}

/** `.sec`: a white 18dp card with a soft shadow. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)) { content() }
}

/** The tour's own card: its title and overview, a note when it was written for an older commit, and the way to make a new one. */
@Composable
private fun Intro(session: ReviewSession, head: PrHead, tour: Tour?, running: Boolean, preview: TourPreview? = null) {
    val c = LocalPullsColors.current
    Card {
        when {
            // No "Writing the tour" line on the page: the work log's sheet rests at the foot of the page as a lip while the
            // tour is written ([WritingLip]), so the page never looks finished before it is (recording 16 Oct 13:06, 05:38
            // to 06:00 and 07:08 to 07:15; O13). Until the plan comes, the card holds its place with a loader.
            tour == null && (running || tourComing(session)) -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (preview != null) {
                    Text(preview.title, style = PullsType.xxl, color = c.text)
                    if (preview.overview.isNotBlank()) Markdown(preview.overview, style = PullsType.read.copy(color = c.text))
                } else LoadingLines(title = true)
            }
            // A tour that failed says why; so does an analysis that was to write one. Another analysis's failure is not the tour's.
            tour == null -> TourEmpty(
                session,
                session.taskFor("tour")?.takeIf { it.status == "failed" }?.error
                    ?: session.taskFor("analyze")?.takeIf { it.status == "failed" && "tour" in it.then }?.error,
                Modifier,
            )
            else -> {
                val outdated = tour.headSha != null && session.opened?.headSha?.let { it != tour.headSha } == true
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tour.title, style = PullsType.xxl, color = c.text)
                    if (outdated) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.warning.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Written for an older commit.", Modifier.weight(1f), style = PullsType.sm, color = c.text)
                        if (!running) PillButton("Update", { startTour(session) })
                    }
                    Markdown(tour.overview, style = PullsType.read.copy(color = c.text))
                    if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.octaviantocan.pulls.ui.design.BreathingDot()
                        Text("Writing a new tour…", style = PullsType.sm, color = c.muted)
                    }
                    // No model name and no token counts here (rule 98); rewriting is a quiet icon at the card's corner, with no word.
                    else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        FloatIcon(Lu.redo, "Rewrite the tour", { startTour(session) }, Modifier.offset(x = 10.dp, y = 6.dp), flat = true, size = 40.dp, tint = c.muted)
                    }
                }
            }
        }
    }
}

/**
 * A stop the plan names that is not written yet, in a card the shape of the stop it will become: its title, and a
 * loader where its words will be, not the title and purpose greyed out ("instead of being greyed out, maybe just have a
 * loader": recording 16 Oct 13:06, 06:48 to 06:55; O16). The one being written next says so.
 */
@Composable
internal fun PlannedStop(title: String, next: Boolean) {
    val c = LocalPullsColors.current
    Card {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = PullsType.xxl, color = c.text)
            if (next) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.octaviantocan.pulls.ui.design.BreathingDot()
                Text("Writing this stop", style = PullsType.sm, color = c.muted)
            }
            LoadingLines()
        }
    }
}

/** Skeleton lines where words are still coming: a title bar first when [title], then three lines of text. */
@Composable
private fun LoadingLines(title: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title) com.octaviantocan.pulls.ui.design.Skeleton(Modifier.fillMaxWidth(0.7f).height(20.dp))
        com.octaviantocan.pulls.ui.design.Skeleton(Modifier.fillMaxWidth())
        com.octaviantocan.pulls.ui.design.Skeleton(Modifier.fillMaxWidth(0.92f))
        com.octaviantocan.pulls.ui.design.Skeleton(Modifier.fillMaxWidth(0.6f))
    }
}

/**
 * The tour's work log as a sheet resting at the foot of the page while the tour is written: the panel's top edge with its
 * handle, and the step it is on. Pulling it up or tapping it opens the work log's sheet; pulling that sheet down puts it
 * back here, so it can always be pulled up again ("there should be like a little lip at the bottom ... you can pull it
 * back up": recording 16 Oct 13:06, 05:38 to 06:00 and 07:08 to 07:15; O13).
 */
@Composable
internal fun WritingLip(session: ReviewSession, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val density = LocalDensity.current
    val task = session.taskFor("tour")?.takeIf { it.running } ?: session.taskFor("analyze")?.takeIf { it.running }
    val step = task?.activities?.lastOrNull { !com.octaviantocan.pulls.data.isTourPreview(it) && it.id != "tour-write" }
    var pulled by remember { mutableFloatStateOf(0f) }
    val lift = with(density) { 24.dp.toPx() }
    val shape = RoundedCornerShape(topStart = com.octaviantocan.pulls.ui.design.PullsRadius.sheet, topEnd = com.octaviantocan.pulls.ui.design.PullsRadius.sheet)
    Column(
        modifier.fillMaxWidth()
            .stayOnPull()
            .graphicsLayer { translationY = minOf(0f, pulled) / 3f }
            .floatShadow(shape, PullsShadow.Note)
            .clip(shape).background(c.paper)
            .draggable(
                rememberDraggableState { delta -> pulled += delta },
                Orientation.Vertical,
                onDragStarted = { pulled = 0f },
                onDragStopped = { velocity ->
                    // Up past a little way, or thrown up, opens the sheet; anything else leaves the lip where it is.
                    if (pulled < -lift || velocity < -with(density) { PullsMotion.Fling.toPx() }) onOpen()
                    pulled = 0f
                },
            )
            .cueClickable(role = Role.Button, onClickLabel = "Show the progress", onClick = onOpen)
            .navigationBarsPadding(),
    ) {
        Box(Modifier.padding(top = 10.dp, bottom = 12.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape).background(c.grabber))
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            com.octaviantocan.pulls.ui.design.BreathingDot()
            Column(Modifier.weight(1f)) {
                Text("Writing the tour", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
                if (step != null) Text(com.octaviantocan.pulls.ui.ai.stepTitle(step), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One stop: its title, why it matters, its blocks, the takeaway and where to look. A tap on the title or its chevron
 * folds the stop to the title alone, so a long tour scrolls past what was read ("Tour cards fold to their titles",
 * recording of 16 Oct 12:10, 09:07 to 09:16, N15); a tap again opens it.
 */
@Composable
private fun StopCard(session: ReviewSession, stop: TourStop, onOpenSource: (TourSource) -> Unit, folded: Boolean = false, onFold: () -> Unit = {}) {
    val c = LocalPullsColors.current
    val motion = rememberMotionEnabled()
    val turn by animateFloatAsState(if (folded) -90f else 0f, if (motion) tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut) else snap(), label = "stop-fold")
    Card {
        Column {
            Row(
                Modifier.fillMaxWidth().cueClickable(role = Role.Button, onClickLabel = if (folded) "Open ${stop.title}" else "Fold ${stop.title}", onClick = onFold),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stop.title, Modifier.weight(1f), style = PullsType.xxl, color = c.text)
                LuIcon(Lu.chevd, Modifier.padding(top = 7.dp).rotate(turn), tint = c.muted, size = 20.dp)
            }
            AnimatedVisibility(
                !folded,
                enter = if (motion) fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandVertically(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) else EnterTransition.None,
                exit = if (motion) fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + shrinkVertically(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) else ExitTransition.None,
            ) {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (stop.purpose.isNotBlank()) Text(stop.purpose, style = PullsType.read, color = c.text)
                    if (stop.error != null) com.octaviantocan.pulls.ui.ai.ErrorBox("This section could not be written", stop.error)
                    stop.blocks.forEach { block -> TourBlockView(session, block) }
                    if (stop.takeaway.isNotBlank()) Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentWash).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stop.takeaway, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.accentWashInk)
                    }
                    if (stop.sources.isNotEmpty()) Sources(stop.sources, onOpenSource)
                }
            }
        }
    }
}

/**
 * Which stops of each tour are folded to their titles, for as long as the app runs: in memory, never saved, so a tour
 * opened again in the same session finds its stops as they were left (N15). Keyed by the tour, so a new tour of the
 * same pull request starts open.
 */
internal class TourFolds private constructor() {
    private val folded = mutableStateMapOf<Int, Boolean>()

    fun folded(index: Int): Boolean = folded[index] == true
    fun toggle(index: Int) { folded[index] = !folded(index) }
    fun unfold(index: Int) { folded.remove(index) }
    fun foldAll(count: Int) { for (i in 0 until count) folded[i] = true }
    fun unfoldAll() = folded.clear()

    companion object {
        private val tours = HashMap<String, TourFolds>()

        /** The folds of the tour [key] names, made the first time it is asked for. */
        fun of(key: String): TourFolds = tours.getOrPut(key) { TourFolds() }
    }
}

/**
 * The tour's sections as a sheet (rule 97): one row per stop in a white card, ticked once you have read past it, a ring
 * in the accent on the one you are on and an empty ring on the rest. The rows are the kit's sheet rows, so a long
 * title wraps with the same padding as every other sheet. Tapping one closes the sheet and scrolls there.
 */
@Composable
internal fun SectionsSheet(titles: List<String>, current: Int, writing: Set<Int>? = null, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    PullsSheet(onClose, "Sections") {
        SheetCard(Modifier.padding(bottom = 16.dp)) {
            titles.forEachIndexed { index, title ->
                val number = index + 1
                SheetRow(title, { onPick(index) }, first = index == 0, icon = {
                    when {
                        // While the tour is written, a section still to come breathes until it is written (O17).
                        writing != null && index in writing -> com.octaviantocan.pulls.ui.design.BreathingDot()
                        writing != null -> LuIcon(Lu.okc, tint = c.accentTeal, size = 20.dp)
                        number < current -> LuIcon(Lu.okc, tint = c.accentTeal, size = 20.dp)
                        else -> Box(Modifier.size(15.dp).border(1.5.dp, if (number == current) c.accentTeal else c.faint, CircleShape))
                    }
                })
            }
        }
    }
}

/** How far the reader got through one tour on one visit, for `tour.event`. */
private class TourReading {
    private var furthest = 0
    private var finished = false

    fun at(stop: Int, total: Int) {
        if (furthest == 0) com.octaviantocan.pulls.data.Analytics.track("tour.event", "step" to "started", "index" to stop, "stops" to total)
        else if (stop > furthest && !finished) com.octaviantocan.pulls.data.Analytics.track("tour.event", "step" to "next", "index" to stop, "stops" to total)
        if (stop > furthest) furthest = stop
        if (stop == total && !finished) {
            finished = true
            com.octaviantocan.pulls.data.Analytics.track("tour.event", "step" to "finished", "index" to stop, "stops" to total)
        }
    }

    fun leave() {
        if (furthest > 0 && !finished) com.octaviantocan.pulls.data.Analytics.track("tour.event", "step" to "skipped", "index" to furthest)
    }
}

/** Fades a stop in and lifts it into place, [order] steps after the first, when [play] is set on its first showing (K14). */
@Composable
private fun Modifier.arrive(order: Int, play: Boolean): Modifier {
    val motion = rememberMotionEnabled()
    val shown = remember { Animatable(if (play && motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (shown.value >= 1f) return@LaunchedEffect
        delay(order.coerceAtMost(ARRIVE_STEPS) * PullsMotion.STAGGER * 2L)
        shown.animateTo(1f, tween(PullsMotion.MEDIUM, easing = PullsMotion.SmoothOut))
    }
    val rise = with(LocalDensity.current) { PullsMotion.SlideDistance.toPx() } * 2f
    return graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * rise }
}

/** After this many stops the rest arrive together, so a long tour is not a slow parade. */
private const val ARRIVE_STEPS = 6
