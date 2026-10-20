package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.layout.layout
import com.octaviantocan.pulls.ui.home.SearchDim
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import com.octaviantocan.pulls.data.JEV_FILE_WORDS
import com.octaviantocan.pulls.data.JEV_RISK_WORDS
import com.octaviantocan.pulls.data.PullJev
import com.octaviantocan.pulls.data.kindWords
import com.octaviantocan.pulls.data.riskLevel
import com.octaviantocan.pulls.data.riskWords
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.floatingBar
import com.octaviantocan.pulls.ui.design.rememberSidePages
import com.octaviantocan.pulls.ui.design.sideSwipe
import kotlin.math.roundToInt
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullSizes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.showsDescription
import com.octaviantocan.pulls.core.summaryKey
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.BriefSection
import com.octaviantocan.pulls.ui.ai.ProvideAiText
import com.octaviantocan.pulls.ui.ai.SharedAnalysisBanner
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.rememberLiveTask
import com.octaviantocan.pulls.ui.ai.shimmer
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.IconSwap
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.SwipeRow
import com.octaviantocan.pulls.ui.home.compactCount
import com.octaviantocan.pulls.ui.design.hairlineTop
import com.octaviantocan.pulls.ui.design.bleed
import com.octaviantocan.pulls.ui.design.edgeFade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset

/**
 * The prototype's PR overview, under the review's header (the back button and the section capsule): the title,
 * who and when, the branches, a four-cell summary line, Summary / Commits / Conversation tabs, the AI's
 * status and the summary (the description until the AI writes one), the file list with its notes, and the Ask bar at
 * the foot, the Tour's own (P2). [onAskHistory] opens the earlier chats.
 */
@Composable
internal fun PrOverviewScreen(
    session: ReviewSession,
    ui: DiffUi,
    top: ReviewTopUi,
    data: PrOverviewData?,
    head: PrHead,
    scroll: LazyListState,
    pane: Int,
    onPane: (Int) -> Unit,
    onDiff: (path: String?) -> Unit,
    onAsk: (question: String) -> Unit,
    onAskHistory: () -> Unit,
    onSheet: (String) -> Unit,
    onCite: (Cite) -> Unit,
    onOpenRepo: (owner: String, repo: String) -> Unit,
    askDraft: MutableState<String> = remember { mutableStateOf("") },
) {
    val c = LocalPullsColors.current
    val focusManager = LocalFocusManager.current
    // While the title or the description is edited the rest of the page steps back, so the field is plainly what the
    // page is about now (H35).
    val editFocus = remember { EditFocus() }
    val editing = editFocus.editing
    val stepBack by animateFloatAsState(if (editing != null) EDIT_DIM else 1f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "edit-dim")
    fun Modifier.backUnless(field: String) = graphicsLayer { alpha = if (editing == null || editing == field) 1f else stepBack }
    // While a field is edited, a tap anywhere else on the page only leaves the field, which saves it: nothing under it
    // opens, switches or swipes (J17, rule 83).
    fun Modifier.holdUnless(field: String) = then(if (editing != null && editing != field) Modifier.pointerInput(editing) { leaveOnTap { focusManager.clearFocus() } } else Modifier)
    CompositionLocalProvider(LocalEditFocus provides editFocus) {
    Box(Modifier.fillMaxSize().clipToBounds().background(c.base).pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        // The description is the summary until the AI writes one, so they share a tab; the commits open on a sheet from
        // their number (K29). Both tabs are there from the start, so the row never changes shape when the conversation
        // arrives (rule 53).
        val labels = OVERVIEW_TABS
        val shown = pane.coerceAtMost(labels.lastIndex)
        // The tabs are pages side by side under one head (J15): a swipe anywhere that has no sideways swipe of its own
        // moves them with the finger, the head's included (J21), and the tab stroke sits where the pages are. Past the
        // last tab there is nothing, so the page stays where it is (K8).
        val sides = rememberSidePages(shown, labels.size, onPane)
        // Each tab keeps its own list and where it was scrolled (J9); the Summary's is the review's, so coming back from
        // the Diff finds it where it was.
        val others = remember { List(labels.size - 1) { LazyListState() } }
        val lists = remember(scroll) { listOf(scroll) + others }
        // The head's parts, each measured whole whatever the scroll: the title as it wraps, the same title on one line,
        // the lines under it that scroll away, and the numbers with the tabs.
        var titleHeight by remember { mutableIntStateOf(0) }
        var lineHeight by remember { mutableIntStateOf(0) }
        var awayHeight by remember { mutableIntStateOf(0) }
        var pinnedHeight by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val headHeight = with(density) { HEAD_TOP.roundToPx() } + titleHeight + awayHeight + pinnedHeight
        // The head goes up until the title on one line, the numbers and the tabs are left, pinned at the top on the
        // page's colour (K6): first the meta, branch and Jev lines go up under the title, then the title folds to one
        // line ("only the branch line scrolls away", recording of 16 Oct 12:10, 20:34 to 21:03; N38).
        val titleFold = (titleHeight - lineHeight).coerceAtLeast(0)
        val maxLift = awayHeight + titleFold
        // How far the head has gone up with the tab in view: as far as that tab's list has scrolled past it.
        fun lifted(list: LazyListState) = if (list.firstVisibleItemIndex > 0) maxLift else minOf(list.firstVisibleItemScrollOffset, maxLift)
        val front by remember { derivedStateOf { lists[sides.at.roundToInt().coerceIn(0, labels.lastIndex)] } }
        // What has gone of the lines under the title, and of the title's own height as it folds to one line.
        fun awayCut() = minOf(lifted(front), awayHeight)
        fun titleCut() = (lifted(front) - awayHeight).coerceIn(0, titleFold)
        fun folded() = if (titleFold == 0) 0f else titleCut().toFloat() / titleFold
        // Once it starts folding, the title is a heading and not a field: a tap on it edits only at rest.
        val pinned by remember(awayHeight) { derivedStateOf { lifted(front) > awayHeight } }
        // The tabs beside the one in view sit at the same height under the head, so a swipe brings them in level with it:
        // the head where it is, and each tab where it was left once the head has gone (J9).
        LaunchedEffect(lists, maxLift) {
            snapshotFlow { lifted(front) }.collect { up ->
                lists.forEach { list ->
                    if (list === front) return@forEach
                    val theirs = lifted(list)
                    if (up < maxLift && theirs != up) list.requestScrollToItem(0, up)
                    else if (up >= maxLift && theirs < maxLift) list.requestScrollToItem(0, maxLift)
                }
            }
        }
        val spotScope = rememberCoroutineScope()
        val band = with(density) { PullsMotion.EdgeTop.toPx() }
        // The rows dissolve under the pinned tabs and under the bottom buttons instead of meeting a hard line or a block of
        // the page's colour (L7, L8, L10; rule 74). The top band starts where the head ends and only shows once rows go
        // under it, so a page at rest stays sharp.
        val fades = Modifier.edgeFade(
            top = PullsMotion.EdgeTop,
            bottom = PullsMotion.EdgeBottom,
            topStrength = {
                val list = front
                val scrolled = if (list.firstVisibleItemIndex > 0) Int.MAX_VALUE else list.firstVisibleItemScrollOffset
                ((scrolled.toFloat() - maxLift) / band).coerceIn(0f, 1f)
            },
            topInset = { (headHeight - lifted(front)).coerceAtLeast(0).toFloat() },
        )
        SidePages(sides, labels.indices.toList(), Modifier.fillMaxSize().clipToBounds().then(fades).holdUnless("description"), warm = true) { tab ->
            // Lazy, so a pull request with thousands of files only composes the rows on screen.
            LazyColumn(
                Modifier.fillMaxSize(),
                state = lists[tab],
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            ) {
                // The head is drawn over the lists, so this is the room it takes at the top of each.
                item(key = "head") { Spacer(Modifier.fillMaxWidth().height(with(density) { headHeight.toDp() })) }
                // The rows hold the description, so they step back only while the title is edited.
                val rows = Modifier.backUnless("description")
                when (tab) {
                    0 -> {
                        item(key = "summary") { Box(rows) { SummaryPane(session, top, data, onCite, onSheet) } }
                        filesItems(session, ui, onDiff, onSheet, rows)
                    }
                    else -> if (data != null) item(key = "conversation") {
                        Box(rows.padding(top = 14.dp)) {
                            // The comment a link pointed at lands under the pinned head, once this tab is the one in view.
                            Conversation(data.overview, session.spot) { y ->
                                spotScope.launch {
                                    snapshotFlow { front }.first { it === lists[tab] }
                                    val under = headHeight - maxLift + with(density) { (SPOT_GAP - 26.dp).roundToPx() }
                                    lists[tab].animateScrollToItem(1, (y - under).coerceAtLeast(0))
                                }
                            }
                        }
                    } else waitingItem(session, rows)
                }
            }
        }
        // The head: the title, who and when, the branch, the numbers and the tabs. It scrolls the tab in view when dragged,
        // and a sideways drag on it moves the tabs' pages like one on them (J21). As the tab scrolls, the lines under the
        // title go up under it and the title folds to one line, while the title, the numbers and the tabs stay pinned
        // (N38). Nothing of it is drawn above the page, so it never covers the buttons over it or reaches the status bar
        // (K6).
        Column(
            Modifier.fillMaxWidth()
                .background(c.base)
                .padding(start = 16.dp, end = 16.dp, top = HEAD_TOP)
                .scrollable(front, Orientation.Vertical, reverseDirection = true)
                .sideSwipe(sides),
        ) {
            val overview = data?.overview
            // The whole title at rest; as it folds it fades into the same title on one line, ending in … if it must
            // ("ellipsis allowed when pinned", N38), and the box loses the lines it no longer shows.
            Box(Modifier.clipToBounds().cut(::titleCut, keepTop = true)) {
                EditableText(
                    head.title, canEdit = overview?.viewer?.canEditText == true && !pinned, textStyle = PullsType.xxl, saved = "Title updated",
                    onSave = { title -> session.repo.prAction(overview!!.id, "edit", title = title); session.reloadOverview() },
                    modifier = Modifier.onSizeChanged { titleHeight = it.height }.graphicsLayer { alpha = 1f - folded() }.backUnless("title").holdUnless("title"),
                ) { title -> Text(title, style = PullsType.xxl, color = c.text) }
                Text(
                    head.title, Modifier.fillMaxWidth().onSizeChanged { lineHeight = it.height }.graphicsLayer { alpha = folded() }.clearAndSetSemantics { },
                    style = PullsType.xl, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // What scrolls away: it goes up under the title, cut at the title's edge.
            Column(Modifier.clipToBounds().cut(::awayCut, keepTop = false).onSizeChanged { awayHeight = it.height }.backUnless("").holdUnless("")) {
                PrMetaLine(head.label, head.author, head.createdAt, head.owner, head.repo, onOpenRepo)
                if (head.base.isNotBlank() || head.head.isNotBlank()) BranchRow(head.base, head.head)
                val mergeBlocker = overview?.let(::overviewMergeBlocker) ?: data?.mergeability?.blocker
                if (mergeBlocker != null) Text(
                    t("overview.merge.blocked.$mergeBlocker"),
                    Modifier.padding(top = 8.dp), style = PullsType.sm,
                    color = if (mergeBlocker == "checking") c.muted else c.warning,
                )
                StackMark(session.stack) { onSheet("stack") }
                JevLine(session.pullJev, session.attention)
                if (isStale(session, data?.overview) && session.selection == null && !session.settings.autoRefresh) {
                    StaleBanner(session, { top.polls = 0; session.refresh() }, Modifier.padding(top = 12.dp))
                }
                SharedAnalysisBanner(session, Modifier.padding(top = 12.dp))
                val overviewError = session.overviewError
                if (data == null && overviewError != null) OverviewError(overviewError) { session.reloadOverview() }
            }
            // Pinned under the title: the numbers, with Checks among them, and the tabs (N38).
            Column(Modifier.onSizeChanged { pinnedHeight = it.height }.backUnless("").holdUnless("")) {
                StatsLine(session, data, onSheet, onCommits = { onSheet("commits") })
                // The stroke sits where the pages are, so the tab and the page under it move as one (J15). The tabs sit
                // side by side from the left, a word's room apart, not spread over the width (K7).
                UnderlineTabs(labels, shown, onPane, Modifier.padding(top = 2.dp), position = { sides.at })
            }
        }
        // The Ask field is Home's search pill, and the page dims behind it while it waits for its first letter (rule 132).
        var askText by askDraft
        var askFocused by remember { mutableStateOf(false) }
        SearchDim(askFocused, askText.isEmpty())
        LaunchedEffect(session.key) { session.loadChatHistory() }
        ReviewBar(
            askText, { askText = it }, onAsk, Modifier.align(Alignment.BottomCenter),
            onFocus = { askFocused = it },
            onOpenHistory = if (session.key != null && !session.chatHistory.isNullOrEmpty()) onAskHistory else null,
            veil = Modifier.backUnless("").holdUnless(""),
        )
    }
    }
}

/**
 * What Jev made of the pull request, under the branch: its kind with how sure it is in words, its risk ("High risk" in
 * the amber the app gives warnings), and how many files need a look. Plain text at the meta size, with no mark and
 * nothing to tap (rules 1, 14 and 53). Absent until a review had Jev read the pull request.
 */
@Composable
internal fun JevLine(jev: PullJev?, attention: Map<String, String>) {
    val c = LocalPullsColors.current
    val needs = attention.values.count { it == "p0" }
    if (jev == null && needs == 0) return
    // Each part with its colour: only a high risk stands out.
    val parts = listOfNotNull(
        jev?.kindWords?.let { it to c.muted },
        jev?.let { it.riskWords to if (it.riskLevel == JEV_RISK_WORDS.lastIndex) c.amber else c.muted },
        if (needs > 0) (if (needs == 1) "1 file needs a look" else "$needs files need a look") to c.muted else null,
    )
    val words = buildAnnotatedString {
        parts.forEachIndexed { index, (text, color) ->
            if (index > 0) withStyle(SpanStyle(color = c.faint)) { append(" · ") }
            withStyle(SpanStyle(color = color)) { append(text) }
        }
    }
    Text(words, Modifier.padding(top = 8.dp), style = PullsType.sm, color = c.muted)
}

/** The air over the Overview's title, under the review's header. */
private val HEAD_TOP = 12.dp

/**
 * Takes [cut] pixels off what this draws, as the head's parts do while the page scrolls (N38): from the bottom when
 * [keepTop] (the title folding up), else from the top, the content going up under what is over it (the lines under the
 * title). Only its height changes; [cut] is read while laying out, so a scroll moves it without composing it again.
 */
private fun Modifier.cut(cut: () -> Int, keepTop: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shown = (placeable.height - cut()).coerceIn(0, placeable.height)
    layout(placeable.width, shown) { placeable.place(0, if (keepTop) 0 else shown - placeable.height) }
}

/** The Overview's tabs; the opening page draws the same row, so nothing moves when the page arrives (K22). */
internal val OVERVIEW_TABS = listOf("Summary", "Conversation")

/** The tab a link to a comment or a review opens on. */
internal const val CONVERSATION_TAB = 1

/** How far under the pinned head a comment a link pointed at lands. */
private val SPOT_GAP = 16.dp

/**
 * A tab whose rows come with the conversation, before it has arrived: bars where the rows will be, and no loading
 * screen (rule 51). When it could not load, the line under the title already says so and offers to try again.
 */
private fun LazyListScope.waitingItem(session: ReviewSession, rows: Modifier) {
    if (session.overviewError == null) item(key = "waiting") { RowsSkeleton(rows.padding(top = 14.dp), rows = 5) }
}

/** The diff did not load although the overview did: say why under Files, and offer to try again. */
@Composable
private fun FilesError(message: String, onRetry: () -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Couldn't load the changes", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(message, style = PullsType.sm, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FloatIcon(Lu.redo, "Load again", onRetry, flat = true, iconSize = 22.dp, tint = c.muted)
    }
}

/** The conversation, checks and merge state did not load: say why, and offer to try again. */
@Composable
private fun OverviewError(message: String, onRetry: () -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Couldn't load checks and conversation", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(message, style = PullsType.sm, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // A repeat is the try-again arrow, named for what it does (rule 87).
        FloatIcon(Lu.redo, "Load again", onRetry, flat = true, iconSize = 22.dp, tint = c.muted)
    }
}

/** What the capsule's one corner slot holds: ⋯ on the Overview and the Tour, Aa on the Diff (rule 93). */
internal enum class CapsuleCorner(val label: String) { More("More"), TextTools("Text and tools") }

/**
 * `.cap` with the page icons: Overview, Changes, Tour (a live dot while it is written), and a corner slot. The
 * review draws it once over its three pages, so it never moves or changes shape between them and a page of the pull
 * request never loses its way home; the paper pill under the page you are on slides to the next one (rule 67).
 * [selected] is 0 for the Overview, 1 for Changes and 2 for the Tour; [corner] is what [onCorner] opens.
 */
@Composable
internal fun SectionCapsule(
    selected: Int,
    live: Boolean,
    onOverview: () -> Unit,
    onChanges: () -> Unit,
    onTour: () -> Unit,
    onCorner: () -> Unit,
    corner: CapsuleCorner = CapsuleCorner.More,
) {
    val c = LocalPullsColors.current
    // One pill slides to the view you go to, the way the page goes (rules 67 and 105), instead of each segment lighting up.
    val x by animateDpAsState(CAPSULE_SEGMENT * selected, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "section-pill")
    Box(Modifier.width(CAPSULE_WIDTH).height(44.dp).floatShadow(CircleShape).clip(CircleShape).background(c.raised).padding(CAPSULE_INSET)) {
        Box(Modifier.offset(x = x).width(CAPSULE_SEGMENT).fillMaxHeight().clip(CircleShape).background(c.paper2))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Segment("Overview", onClick = if (selected == 0) null else onOverview) { LuIcon(Lu.list, tint = c.text) }
            Segment("Changes", onClick = if (selected == 1) null else onChanges) { LuIcon(Lu.diff, tint = c.text) }
            Segment("Tour", onClick = if (selected == 2) null else onTour) {
                LuIcon(Lu.tour, tint = c.text)
                if (live) BreathingDot(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 10.dp), 8.dp)
            }
            Box(Modifier.padding(horizontal = CAPSULE_RULE_GAP, vertical = 8.dp).width(CAPSULE_RULE).fillMaxHeight().background(c.border))
            Segment(corner.label, onClick = onCorner) {
                IconSwap(corner) { glyph ->
                    when (glyph) {
                        CapsuleCorner.More -> LuIcon(Lu.more, tint = c.text)
                        CapsuleCorner.TextTools -> Text("Aa", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
                    }
                }
            }
        }
    }
}

/** The capsule: 212dp wide with 2dp inside its edge, four equal segments and a 1dp rule with 2dp either side before the corner. */
private val CAPSULE_WIDTH = 212.dp
private val CAPSULE_INSET = 2.dp
private val CAPSULE_RULE = 1.dp
private val CAPSULE_RULE_GAP = 2.dp
private val CAPSULE_SEGMENT = (CAPSULE_WIDTH - CAPSULE_INSET * 2 - CAPSULE_RULE - CAPSULE_RULE_GAP * 2) / 4

@Composable
private fun RowScope.Segment(label: String, onClick: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
            .then(if (onClick != null) Modifier.cueClickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** `.cs`: Changes, Commits, Files and Checks as four label-over-value cells under a hairline. */
@Composable
private fun StatsLine(session: ReviewSession, data: PrOverviewData?, onSheet: (String) -> Unit, onCommits: () -> Unit) {
    val c = LocalPullsColors.current
    val overview = data?.overview
    // Until the diff lands its size is the one the list showed, so Changes comes with the rest of the line (H29).
    val known = (session.target as? OpenTarget.Pull)?.let { PullSizes.get(it.owner, it.repo, it.number) }
    val diffed = session.opened != null
    val adds = if (diffed) session.files.sumOf { it.additions } else known?.additions ?: 0
    val dels = if (diffed) session.files.sumOf { it.deletions } else known?.deletions ?: 0
    val commits = overview?.let { it.commits.size + it.commitsHidden } ?: (session.prOpened ?: session.opened)?.commits?.size
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).drawBehind { drawRect(c.borderMute, Offset.Zero, Size(size.width, 1.dp.toPx())) }.padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Before the diff arrives the sizes are bars; the rest of the line is the overview's (rule 51).
        val sized = diffed || known != null
        Cell("Changes", Modifier.width(104.dp)) {
            if (!sized) { Skeleton(Modifier.padding(vertical = 4.dp).width(72.dp)); return@Cell }
            Text("+${compactCount(adds)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.green(c.dark), maxLines = 1)
            Text("−${compactCount(dels)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.red(c.dark), maxLines = 1)
        }
        // Narrowed to some commits, the cell says which, in the link colour, and opens the sheet that goes back (K25).
        // Only the numbers, "3" or "12–15", so a range fits the cell; the Diff's pill says "Only commit 3".
        val narrowed = commitNumbers(session.selection, overview)
        Cell("Commits", Modifier.width(76.dp), onClick = if (overview != null && (commits ?: 0) > 0) onCommits else null) {
            // A bar until the number is known, not a 0 that turns into 50 (K22).
            if (commits == null) Skeleton(Modifier.padding(vertical = 4.dp).width(28.dp))
            else if (narrowed != null) Text(narrowed, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText, maxLines = 1, softWrap = false)
            else Text("$commits", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        Cell("Files", Modifier.width(52.dp), onClick = if (session.files.isNotEmpty()) ({ onSheet("files") }) else null) {
            val files = if (diffed) session.files.size else known?.files
            if (files == null) { Skeleton(Modifier.padding(vertical = 4.dp).width(28.dp)); return@Cell }
            Text("$files", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        val checks = overview?.checksSummary
        Cell("Checks", Modifier.weight(1f), onClick = if (checks != null && checks.total > 0) ({ onSheet("checks") }) else null) {
            when {
                checks == null && session.overviewError == null -> Skeleton(Modifier.padding(vertical = 4.dp).width(56.dp))
                checks == null || checks.total == 0 -> Text("None", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
                checks.failing > 0 -> {
                    LuIcon(Lu.xc, tint = StatusColors.red(c.dark), size = 18.dp)
                    Text("${checks.failing} failing", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.red(c.dark), maxLines = 1)
                }
                checks.pending > 0 -> {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.amber500))
                    Text("${checks.pending} running", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text, maxLines = 1)
                }
                else -> {
                    LuIcon(Lu.okc, tint = StatusColors.green(c.dark), size = 18.dp)
                    Text("Passing", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.green(c.dark), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Cell(label: String, modifier: Modifier, onClick: (() -> Unit)? = null, value: @Composable RowScope.() -> Unit) {
    val c = LocalPullsColors.current
    Column(
        modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(6.dp)).cueClickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = PullsType.sm, color = c.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), content = value)
    }
}

// --- Summary ---

/**
 * The line over the summary: one link to the other text, in the link colour, as a translated text offers its original.
 * The link alone says what is shown, so no word names the text in front of it ("if you have that, then you don't need
 * this": recording 16 Oct 09:50, 02:42 to 02:55; M6). The words swap in place as the texts do (K23).
 */
@Composable
private fun SourceLine(description: Boolean, onSwitch: () -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (description) "Show the AI summary" else "Show the description",
            Modifier.clip(RoundedCornerShape(PullsRadius.xs)).cueClickable(role = Role.Button, onClick = onSwitch),
            style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
        )
    }
}

/**
 * What the pull request is and what the AI made of it: the summary, the brief and what needs a human.
 * The files follow as list rows. The summary is the author's description until the AI writes one, which then takes
 * its place; a two-way capsule over it switches between the two, and the next summary the AI writes takes the place
 * again.
 */
@Composable
private fun SummaryPane(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    val analysis = session.saved?.analysis
    val summary = analysis?.overallSummary?.takeIf { session.hasAiResult && it.isNotBlank() }
    val shown = summaryKey(analysis?.generatedAt, summary)
    val description = showsDescription(shown, top.descriptionOver)
    // The summary and the brief name places in the code; each is a link there (rule 96).
    val cite = { place: CodePlace -> onCite(Cite(place.path, place.line)) }
    Column {
        if (summary != null) {
            // Which text this is, and a quiet link to the other: no pill switch over the text (K23).
            SourceLine(description) { top.descriptionOver = if (description) null else shown }
        }
        // The other text fades in where this one was (rule 67); the line over it stays put.
        BlurSwitch(description, order = { if (it) 1 else 0 }) { showDescription ->
            if (showDescription) DescriptionText(session, data, Modifier.padding(top = 12.dp))
            else if (summary != null) ProvideAiText(session, cite) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp)) { Markdown(summary, style = PullsType.read.copy(color = c.text)) }
            }
        }
        session.saved?.brief?.let { brief ->
            ProvideAiText(session, cite) {
                BriefSection(session, brief, onOpen = { target -> onCite(Cite(target.path, target.line ?: 0)) }, modifier = Modifier.padding(top = 16.dp))
            }
        }
        NeedsHuman(session, onCite, onSheet)
    }
}

/**
 * `What needs a human`: what the AI review found, most serious first, each pointing at its line. The heading has no
 * line under it, like the others (rule 75): a hairline sits between rows only. Looking for problems is the orb with a
 * small square, which stops it (rule 87), and "Look for problems" opens the review setup.
 */
@Composable
private fun NeedsHuman(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    if (session.key == null || !cliEngine(context)) return
    val critiqueTask = rememberLiveTask(session, "critique")
    val reviewing = critiqueTask?.running == true
    val findings = session.visibleFindings
    // Most serious first, nits last. The first few show, then "Show N more", which opens the rest in place (N5).
    val rank = mapOf("bug" to 0, "risk" to 1)
    val sorted = findings.sortedBy { rank[it.severity] ?: 2 }
    var all by rememberSaveable(session.key) { mutableStateOf(false) }
    val shown = sorted.take(firstFew(sorted.size, all))
    if (findings.isEmpty() && !reviewing && !session.hasAiResult) return
    // The first row has nothing above it; every later one has a hairline.
    fun line(index: Int): Modifier = if (index == 0) Modifier else Modifier.hairlineTop(c.borderMute)
    val tail = shown.size + if (findings.isNotEmpty()) 1 else 0
    val stop = guardedStop(session, listOf("critique"), reviewing) { critiqueTask?.let { session.cancelTask(it.id) } }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("What needs a human", Modifier.padding(bottom = 2.dp), style = PullsType.xl, color = c.text)
        shown.forEachIndexed { index, finding ->
            Text(
                buildAnnotatedString {
                    append(leadAndText(finding.title, firstLine(finding.body)))
                    append(" ")
                    appendCite("${finding.path.substringAfterLast('/')}:${finding.line}", c.activeText) { onCite(Cite(finding.path, finding.line)) }
                },
                Modifier.fillMaxWidth().then(line(index)).cueClickable(role = Role.Button, onClickLabel = finding.title) { session.focusFinding(finding) }.padding(vertical = 11.dp),
                style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.text,
            )
        }
        if (sorted.size > shown.size) ShowMore(sorted.size - shown.size) { all = true }
        if (findings.isNotEmpty()) {
            // The findings sheet is where findings are picked and posted as a review, so it is offered however long the list.
            Text(
                "Open the review", Modifier.fillMaxWidth().then(line(shown.size)).cueClickable(role = Role.Button) { onSheet("findings") }.padding(vertical = 11.dp),
                style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
            )
        }
        when {
            reviewing -> Row(
                Modifier.fillMaxWidth().then(line(tail)).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(TOUCH).clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = "Stop", onClick = { stop?.invoke() }),
                    contentAlignment = Alignment.Center,
                ) { Orb(size = 22.dp, stop = true) }
                Text("Looking for problems…", Modifier.weight(1f).shimmer(), style = PullsType.sm, color = c.muted)
            }
            findings.isEmpty() && session.saved?.critique != null -> Text(
                "The review found nothing that needs you.", Modifier.fillMaxWidth().then(line(tail)).padding(vertical = 11.dp), style = PullsType.sm, color = c.muted,
            )
            findings.isEmpty() -> Row(
                Modifier.fillMaxWidth().then(line(tail)).cueClickable(role = Role.Button) { onSheet("review") }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Look for problems", Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}


// --- Files ---

/**
 * The heading and one list row per file, grouped under the section headings when there are any. A heading folds its
 * files away; above [FOLD_ABOVE] files every section starts folded, so a pull request of thousands of files opens as
 * a short list of headings, and only the rows on screen are ever composed. A long list stops after its first few
 * sections, and a long section after its first few files, with "Show N more" under them (N5). Sections that share a
 * "Prefix › " say it once over them, in a line of its own (N34).
 */
private fun LazyListScope.filesItems(session: ReviewSession, ui: DiffUi, onDiff: (String?) -> Unit, onSheet: (String) -> Unit, rows: Modifier) {
    val sections = fileSections(session, ui)
    val analysis = session.saved?.analysis
    val notes = analysis?.files?.associate { it.path to it.summary }.orEmpty()
    val analyzing = session.taskFor("analyze")?.running == true
    val total = session.files.size
    item(key = "files-head") {
        val c = LocalPullsColors.current
        // The heading is the word alone: the ticks on the rows already say what is reviewed (rules 14 and 42).
        Text("Files", rows.fillMaxWidth().padding(top = 18.dp).hairlineTop(c.borderMute).padding(top = 14.dp, bottom = 2.dp), style = PullsType.xl, color = c.text)
    }
    // The overview came first: the files are bars until the diff arrives, or say why it did not.
    if (session.opened == null) {
        item(key = "files-waiting") {
            val error = session.loadError
            if (error == null) RowsSkeleton(rows.padding(top = 8.dp), rows = 4) else FilesError(error) { session.load() }
        }
        return
    }
    val shownSections = firstFewSections(sections, ui.shownAll[SHOWN_SECTIONS] == true)
    sections.take(shownSections).forEachIndexed { index, section ->
        val label = section.label
        val folded = label != null && ui.isFolded(label, total)
        // Sections that share a "Prefix › " are named once over them, and each row says only the rest (N34).
        val shared = section.heading
        if (shared != null && opensHeading(section, sections.getOrNull(index - 1))) item(key = "shared:$index") {
            val c = LocalPullsColors.current
            Text(
                shared, rows.fillMaxWidth().padding(top = if (index == 0) 10.dp else 18.dp, bottom = 2.dp).semantics { heading() },
                style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted,
            )
        }
        // The Inbox's fold heading (J18): run out over the list's side padding so it sits as the Inbox's does, its line
        // drawn only while folded; open, the first file's own line is the one under it (rule 75). It folds by its whole
        // name, so a fold is kept however the row is named.
        if (label != null) item(key = "section:$index") {
            FoldHeading(
                section.name ?: label, section.files.size, first = index == 0, folded = folded,
                modifier = rows.bleed(16.dp).padding(top = 4.dp).animateItem(), line = folded, background = LocalPullsColors.current.base,
                changes = section.files.sumOf { it.additions } to section.files.sumOf { it.deletions },
            ) { ui.folds[label] = !folded }
        }
        if (!folded) {
            val more = SHOWN_FILES + (label ?: "")
            val files = section.files.take(firstFew(section.files.size, ui.shownAll[more] == true))
            itemsIndexed(files, key = { _, file -> "file:${file.path}" }) { index, file ->
                val state = session.reviewState(file)
                val note = notes[file.path]?.takeIf { it.isNotBlank() }
                val toggle = { session.setReviewed(listOf(file.sha), state != ReviewState.Reviewed) }
                // The same gestures as the Inbox rows (rule 56): right opens it, as it opens a pull request, left marks it
                // reviewed (or not), a tap opens it and a long-press offers the rest.
                SwipeRow(
                    file.path, onOpen = { onDiff(file.path) }, onArchive = toggle, archived = false, modifier = rows.animateItem(),
                    startLabel = "Open", startIcon = Lu.arrow,
                    endLabel = if (state == ReviewState.Reviewed) "Not reviewed" else "Reviewed", endIcon = Lu.check,
                    onLongClick = { onSheet(FILE_SHEET + file.path) },
                ) {
                    // The first row of a list has no line above it, unless a heading is over it: then that line is the
                    // heading's underline (rule 75, J18).
                    FileRow(session, file, state, note, analyzing && note == null, ruled = index > 0 || label != null, mark = session.attention[file.path], onToggle = toggle)
                }
            }
            val hidden = section.files.size - files.size
            if (hidden > 0) item(key = "more:$index") { ShowMore(hidden, rows.animateItem()) { ui.shownAll[more] = true } }
        }
    }
    val hidden = sections.size - shownSections
    if (hidden > 0) item(key = "more-sections") { ShowMore(hidden, rows.padding(top = 4.dp).animateItem()) { ui.shownAll[SHOWN_SECTIONS] = true } }
}

/** The keys under which [DiffUi.shownAll] keeps that the reader opened the whole list: the sections, or one section's files. */
private const val SHOWN_SECTIONS = "files"
private const val SHOWN_FILES = "files:"

/**
 * `.flr`: the checkbox on the first line, the file name, its folder cut from the front under it (a tap shows the whole
 * path), `+n −n` on the right, and the AI's note in two lines at most. A file Jev rated starts the second line with its
 * word on it ([mark]: `p0` "Needs a look", `p1` "Worth a look", `p2` "Skim").
 */
@Composable
internal fun FileRow(session: ReviewSession, file: ReviewFile, state: ReviewState, note: String?, noteLoading: Boolean, ruled: Boolean, onToggle: () -> Unit, mark: String? = null) {
    val c = LocalPullsColors.current
    val reviewed = state == ReviewState.Reviewed
    val dir = file.path.substringBeforeLast('/', "")
    var whole by remember(file.path) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().then(if (ruled) Modifier.hairlineTop(c.borderMute) else Modifier).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PullsCheck(reviewed, onClick = onToggle, label = "Mark ${file.path} reviewed")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                file.path.substringAfterLast('/'),
                style = PullsType.base.copy(fontWeight = FontWeight.SemiBold),
                color = if (reviewed) c.muted else c.text,
            )
            val jevMark = mark?.takeIf { it in JEV_FILE_WORDS }
            val pathTap = Modifier.clip(RoundedCornerShape(PullsRadius.xs)).cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = if (whole) "Shorten the path" else "Show the whole path") { whole = !whole }
            if (whole && dir.isNotEmpty()) {
                // The whole path is one paragraph with Jev's word: it runs on after "Worth a look ·" and wraps under it,
                // never into a narrow column beside it ("almost a rule I personally have", recording of 16 Oct 12:10,
                // 19:49 to 20:16; N37).
                Text(
                    buildAnnotatedString {
                        if (jevMark != null) {
                            withStyle(SpanStyle(color = if (jevMark == "p0") c.amber else c.muted)) { append(JEV_FILE_WORDS.getValue(jevMark)) }
                            withStyle(SpanStyle(color = c.faint)) { append(" · ") }
                        }
                        withStyle(SpanStyle(fontFamily = PullsType.mono, color = c.faint)) { append("$dir/") }
                    },
                    Modifier.fillMaxWidth().then(pathTap), style = PullsType.sm, color = c.faint,
                )
            } else if (dir.isNotEmpty() || jevMark != null) {
                // Cut to one line, Jev's word and the path's tail fit side by side.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (jevMark != null) JevMark(jevMark, trailingDot = dir.isNotEmpty())
                    if (dir.isNotEmpty()) PathTail(dir, PullsType.sm.copy(fontFamily = PullsType.mono), c.faint, Modifier.weight(1f, fill = false).then(pathTap))
                }
            }
            when {
                state == ReviewState.Changed -> Text("Changed since you reviewed it", style = PullsType.sm, color = c.orange)
                // Its mentions are links to their place (rule 96), as in the note over the file's diff.
                note != null -> com.octaviantocan.pulls.ui.ai.AiMarkdown(session, note, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.4f, color = c.muted), maxLines = 2)
                noteLoading -> Skeleton(Modifier.fillMaxWidth(0.78f).padding(vertical = 4.dp))
            }
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium)
            Text("+${compactCount(file.additions)}", style = mono, color = StatusColors.green(c.dark), maxLines = 1, softWrap = false)
            Text("−${compactCount(file.deletions)}", style = mono, color = StatusColors.red(c.dark), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The description, in the summary's place. When the viewer may change this pull request (their own, or one they
 * maintain) a tap opens it on a sheet as GitHub's Markdown in a text field, and leaving the sheet saves (L13); the page
 * then shows GitHub's own rendering again.
 */
@Composable
private fun DescriptionText(session: ReviewSession, data: PrOverviewData?, modifier: Modifier) {
    val c = LocalPullsColors.current
    val overview = data?.overview
    val html = overview?.bodyHTML.orEmpty()
    val source = overview?.body ?: (session.prOpened ?: session.opened)?.description.orEmpty()
    EditableInSheet(
        source, canEdit = overview?.viewer?.canEditText == true, title = "Description", saved = "Description updated",
        onSave = { body -> session.repo.prAction(overview!!.id, "edit", body = body); session.reloadOverview() },
        modifier = modifier, placeholder = "Describe the change in Markdown",
    ) { text ->
        // GitHub's own rendering while the text is the one it holds; the typed Markdown for the moment after a save.
        val markdown = remember(text, html, overview?.body) { if (html.isNotBlank() && text == overview?.body) htmlToMarkdown(html) else text }
        if (markdown.isNotBlank()) Markdown(markdown, style = PullsType.read.copy(color = c.text))
        else Text(if (overview?.viewer?.canEditText == true) "Add a description" else "No description.", style = PullsType.read, color = c.muted)
    }
}

// --- Bottom bar ---

/**
 * `.vbar`: the Ask bar, the Tour's own, in the app's one foot pill (Q2), short and reading Ask until tapped (Q5). Nothing
 * beside it: the round arrow that went to the diff read as a second, highlighted Ask button ("this button should not
 * take you to the diff ... it should also not be highlighted. This is another ask button", recording of 17 Oct 13:26,
 * 00:25 to 00:48, P2). Open and empty, its button is the earlier chats when there are some; typed, it sends, and [onAsk] opens the Ask sheet on the question. The diff is the header's,
 * and Approve is in ⋯ and Commands. The opening page has it too, under the same key, so it is in place before the pull
 * request arrives (K22) and nothing in it changes when it does.
 */
@Composable
internal fun ReviewBar(
    text: String,
    onText: (String) -> Unit,
    onAsk: (question: String) -> Unit,
    modifier: Modifier,
    onFocus: (Boolean) -> Unit = {},
    onOpenHistory: (() -> Unit)? = null,
    veil: Modifier = Modifier,
) {
    AskBar(
        ASK_PR, text, onText, "Ask about this pull request", onAsk, modifier,
        onFocus = onFocus, onOpenHistory = onOpenHistory, veil = veil,
    )
}

/** The pull request's Ask bar, one bar on the opening page and the Overview (Q2). */
internal const val ASK_PR = "ask-pr"

/**
 * The numbers line as the opening page draws it, from what the Inbox row knew: the same four cells in the same places,
 * with bars where a number is not known yet, so the page arriving changes words and not shape (K22).
 */
@Composable
internal fun OpeningStats(additions: Int?, deletions: Int?, files: Int?) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).drawBehind { drawRect(c.borderMute, Offset.Zero, Size(size.width, 1.dp.toPx())) }.padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell("Changes", Modifier.width(104.dp)) {
            if (additions == null || deletions == null) { Skeleton(Modifier.padding(vertical = 4.dp).width(72.dp)); return@Cell }
            Text("+${compactCount(additions)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.green(c.dark), maxLines = 1)
            Text("−${compactCount(deletions)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.red(c.dark), maxLines = 1)
        }
        Cell("Commits", Modifier.width(76.dp)) { Skeleton(Modifier.padding(vertical = 4.dp).width(28.dp)) }
        Cell("Files", Modifier.width(52.dp)) {
            if (files == null) Skeleton(Modifier.padding(vertical = 4.dp).width(28.dp))
            else Text("$files", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        Cell("Checks", Modifier.weight(1f)) { Skeleton(Modifier.padding(vertical = 4.dp).width(56.dp)) }
    }
}
