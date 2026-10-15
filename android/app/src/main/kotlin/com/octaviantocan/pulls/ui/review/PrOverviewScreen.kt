package com.octaviantocan.pulls.ui.review

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.showsDescription
import com.octaviantocan.pulls.core.summaryKey
import com.octaviantocan.pulls.core.swipedTabOrNext
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.ai.BriefSection
import com.octaviantocan.pulls.ui.ai.ProvideAiText
import com.octaviantocan.pulls.ui.ai.SharedAnalysisBanner
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.rememberLiveTask
import com.octaviantocan.pulls.ui.ai.runCritique
import com.octaviantocan.pulls.ui.ai.shimmer
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CodePlace
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.IconSwap
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.pageSlide
import com.octaviantocan.pulls.ui.design.rememberPageSlide
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.SwipeRow
import com.octaviantocan.pulls.ui.home.compactCount
import com.octaviantocan.pulls.ui.design.hairlineTop

/**
 * The prototype's PR overview, under the review's header (the back button and the section capsule): the title,
 * who and when, the branches, a four-cell summary line, Summary / Commits / Conversation tabs, the AI's
 * status and the summary (the description until the AI writes one), the file list with its notes, and a
 * bottom bar with Ask, More and the one action that moves the review forward.
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
    onAsk: () -> Unit,
    onSheet: (String) -> Unit,
    onCite: (Cite) -> Unit,
    onOpenRepo: (owner: String, repo: String) -> Unit,
) {
    val c = LocalPullsColors.current
    val focusManager = LocalFocusManager.current
    // A tap on the empty page leaves a field the person was editing in place, which saves it (rule 83).
    Box(Modifier.fillMaxSize().background(c.base).pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
        Column(Modifier.fillMaxSize()) {
            // The description is the summary until the AI writes one, so they share a tab. The commits have a tab of their
            // own beside the conversation (rule 91). All three are there from the start, so the row never changes shape
            // when the conversation arrives (rule 53).
            val labels = listOf("Summary", "Commits", "Conversation")
            val shown = pane.coerceAtMost(labels.lastIndex)
            val commitAnchor = remember { mutableStateOf<Int?>(null) }
            val slide = rememberPageSlide(shown, order = { it })
            // A swipe left or right moves between the tabs, and left past the last one goes on to the Diff (rule 93).
            var drag by remember { mutableFloatStateOf(0f) }
            val swipeFrom = with(LocalDensity.current) { 64.dp.toPx() }
            val range = with(LocalDensity.current) { PullsMotion.FingerRange.toPx() }
            // The rows follow the finger toward a tab or the Diff, and stay put against the first tab.
            val swipe = rememberDraggableState { delta ->
                drag += delta
                slide.follow(if (shown == 0) minOf(delta, -slide.finger) else delta, range)
            }
            // Lazy, so a pull request with thousands of files only composes the rows on screen.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().draggable(
                    swipe, Orientation.Horizontal,
                    onDragStarted = { drag = 0f },
                    onDragStopped = {
                        val to = swipedTabOrNext(shown, labels.size, drag, swipeFrom)
                        if (to == labels.size) onDiff(null) else onPane(to)
                        drag = 0f
                        slide.release()
                    },
                ),
                state = scroll,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            ) {
                item(key = "head") {
                    Column {
                        val overview = data?.overview
                        EditableText(
                            head.title, canEdit = overview?.viewer?.canUpdate == true, textStyle = PullsType.xxl, saved = "Title updated",
                            onSave = { title -> session.repo.prAction(overview!!.id, "edit", title = title); session.reloadOverview() },
                        ) { title -> Text(title, style = PullsType.xxl, color = c.text) }
                        PrMetaLine(head.label, head.author, head.createdAt, head.owner, head.repo, onOpenRepo)
                        if (head.base.isNotBlank() || head.head.isNotBlank()) BranchRow(head)
                        if (isStale(session, data?.overview) && session.selection == null && !session.settings.autoRefresh) {
                            StaleBanner(session, { top.polls = 0; session.refresh() }, Modifier.padding(top = 12.dp))
                        }
                        SharedAnalysisBanner(session, Modifier.padding(top = 12.dp))
                        val overviewError = session.overviewError
                        if (data == null && overviewError != null) OverviewError(overviewError) { session.reloadOverview() }
                        StatsLine(session, data, onSheet, onCommits = { onPane(COMMITS_TAB) })
                        UnderlineTabs(labels, shown, onPane, Modifier.padding(top = 2.dp), spread = true)
                    }
                }
                // The tabs follow the finger at once; the rows under them leave and arrive as a page (rule 73).
                val rows = Modifier.pageSlide(slide)
                when (slide.shown.coerceAtMost(labels.lastIndex)) {
                    0 -> {
                        item(key = "summary") { Box(rows) { SummaryPane(session, top, data, onCite, onSheet) } }
                        filesItems(session, ui, onDiff, onSheet, rows)
                    }
                    COMMITS_TAB -> if (data != null) commitItems(session, data.overview, commitAnchor, rows) else waitingItem(session, rows)
                    else -> if (data != null) item(key = "conversation") { Box(rows.padding(top = 14.dp)) { Conversation(data.overview) } } else waitingItem(session, rows)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(112.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to c.base, 1f to c.base)))
        BottomBar(session, head, Modifier.align(Alignment.BottomCenter), onAsk = onAsk, onMore = { onSheet("more") }, onDiff = onDiff, onApprove = {
            top.reviewVerdict = "APPROVE"
            top.reviewOpen = true
        })
    }
}

/** Where the Commits tab sits among the tabs, for the Commits cell that opens it. */
private const val COMMITS_TAB = 1

/**
 * A tab whose rows come with the conversation, before it has arrived: bars where the rows will be, and no loading
 * screen (rule 51). When it could not load, the line under the title already says so and offers to try again.
 */
private fun LazyListScope.waitingItem(session: ReviewSession, rows: Modifier) {
    if (session.overviewError == null) item(key = "waiting") { RowsSkeleton(rows.padding(top = 14.dp), rows = 5) }
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
    BoxWithConstraints(Modifier.width(212.dp).height(44.dp).floatShadow(CircleShape).clip(CircleShape).background(c.raised).padding(2.dp)) {
        // Four equal segments either side of the divider's 5dp.
        val segment = (maxWidth - DIVIDER) / 4
        val x by animateDpAsState(segment * selected, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "section-capsule")
        Box(Modifier.offset(x = x).width(segment).fillMaxHeight().clip(CircleShape).background(c.paper2))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Segment("Overview", onClick = if (selected == 0) null else onOverview) { LuIcon(Lu.list, tint = c.text) }
            Segment("Changes", onClick = if (selected == 1) null else onChanges) { LuIcon(Lu.diff, tint = c.text) }
            Segment("Tour", onClick = if (selected == 2) null else onTour) {
                LuIcon(Lu.tour, tint = c.text)
                if (live) BreathingDot(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 10.dp), 8.dp)
            }
            Box(Modifier.padding(horizontal = 2.dp, vertical = 8.dp).width(1.dp).fillMaxHeight().background(c.border))
            Segment(corner.label, onClick = onCorner) {
                when (corner) {
                    CapsuleCorner.More -> LuIcon(Lu.more, tint = c.text)
                    CapsuleCorner.TextTools -> Text("Aa", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
                }
            }
        }
    }
}

/** The capsule's divider and the room either side of it. */
private val DIVIDER = 5.dp

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
    val adds = session.files.sumOf { it.additions }
    val dels = session.files.sumOf { it.deletions }
    val commits = overview?.let { it.commits.size + it.commitsHidden } ?: (session.prOpened ?: session.opened)?.commits?.size ?: 0
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).drawBehind { drawRect(c.borderMute, Offset.Zero, Size(size.width, 1.dp.toPx())) }.padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell("Changes", Modifier.width(104.dp)) {
            Text("+${compactCount(adds)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.green(c.dark), maxLines = 1)
            Text("−${compactCount(dels)}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = StatusColors.red(c.dark), maxLines = 1)
        }
        Cell("Commits", Modifier.width(76.dp), onClick = if (overview != null && commits > 0) onCommits else null) {
            Text("$commits", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        Cell("Files", Modifier.width(52.dp), onClick = if (session.files.isNotEmpty()) ({ onSheet("files") }) else null) {
            Text("${session.files.size}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        val checks = overview?.checksSummary
        Cell("Checks", Modifier.weight(1f), onClick = if (checks != null && checks.total > 0) ({ onSheet("checks") }) else null) {
            when {
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

/** Wide enough for "Description" in each half, and no wider: the capsule belongs to the text under it. */
private val SummarySwitchWidth = 240.dp

/**
 * What the pull request is and what the AI made of it: its progress, the summary, the brief and what needs a human.
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
        AnalysisBlock(session, onSheet)
        if (summary != null) {
            CapsuleTabs(
                listOf("Summary", "Description"), if (description) 1 else 0,
                { top.descriptionOver = if (it == 1) shown else null },
                Modifier.padding(top = 16.dp).width(SummarySwitchWidth),
            )
        }
        // The capsule's pill slides and the other text fades in beside it, like a tab (rule 67); the capsule stays put.
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
 * small square, which stops it (rule 87), and "Look for problems" is the one row that starts a review.
 */
@Composable
private fun NeedsHuman(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    if (session.key == null || !cliEngine(context)) return
    val critiqueTask = rememberLiveTask(session, "critique")
    val reviewing = critiqueTask?.running == true
    val findings = session.visibleFindings
    val rank = mapOf("bug" to 0, "risk" to 1)
    val shown = findings.sortedBy { rank[it.severity] ?: 2 }.filter { it.severity != "nit" }.ifEmpty { findings }.take(4)
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
        if (findings.isNotEmpty()) {
            // The findings sheet is also where findings are picked and posted as a review, so it is offered for a short list too.
            Text(
                if (findings.size > shown.size) "Show all ${findings.size}" else "Open the review", Modifier.fillMaxWidth().then(line(shown.size)).cueClickable(role = Role.Button) { onSheet("findings") }.padding(vertical = 11.dp),
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
                Modifier.fillMaxWidth().then(line(tail)).cueClickable(role = Role.Button) { session.runCritique(context, null) }.padding(vertical = 11.dp),
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
 * a short list of headings, and only the rows on screen are ever composed.
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
    sections.forEachIndexed { index, section ->
        val label = section.label
        val folded = label != null && ui.isFolded(label, total)
        if (label != null) item(key = "section:$index") {
            FoldHeading(label, section.files.size, first = index == 0, folded = folded, modifier = rows.padding(top = 4.dp)) { ui.folds[label] = !folded }
        }
        if (!folded) itemsIndexed(section.files, key = { _, file -> "file:${file.path}" }) { index, file ->
            val state = session.reviewState(file)
            val note = notes[file.path]?.takeIf { it.isNotBlank() }
            val toggle = { session.setReviewed(listOf(file.sha), state != ReviewState.Reviewed) }
            // The same gestures as the Inbox rows (rule 56): right opens it, as it opens a pull request, left marks it
            // reviewed (or not), a tap opens it and a long-press offers the rest.
            SwipeRow(
                file.path, onOpen = { onDiff(file.path) }, onArchive = toggle, archived = false, modifier = rows,
                startLabel = "Open", startIcon = Lu.arrow,
                endLabel = if (state == ReviewState.Reviewed) "Not reviewed" else "Reviewed", endIcon = Lu.check,
                onLongClick = { onSheet(FILE_SHEET + file.path) },
            ) {
                // The first row of a list has no line above it, so the heading over it has none under it (rule 75).
                FileRow(session, file, state, note, analyzing && note == null, ruled = index > 0, onToggle = toggle)
            }
        }
    }
}

/**
 * `.flr`: the checkbox on the first line, the file name, its folder cut from the front under it (a tap shows the whole
 * path), `+n −n` on the right, and the AI's note in two lines at most.
 */
@Composable
private fun FileRow(session: ReviewSession, file: ReviewFile, state: ReviewState, note: String?, noteLoading: Boolean, ruled: Boolean, onToggle: () -> Unit) {
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
            if (dir.isNotEmpty()) {
                val pathStyle = PullsType.sm.copy(fontFamily = PullsType.mono)
                val pathTap = Modifier.clip(RoundedCornerShape(PullsRadius.xs)).cueClickable(Cue.Toggle, role = Role.Button, onClickLabel = if (whole) "Shorten the path" else "Show the whole path") { whole = !whole }
                if (whole) Text("$dir/", pathTap, style = pathStyle, color = c.faint)
                else PathTail(dir, pathStyle, c.faint, pathTap)
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
 * maintain) a tap turns it into a field holding GitHub's Markdown and leaving it saves (rule 83); the page then shows
 * GitHub's own rendering again.
 */
@Composable
private fun DescriptionText(session: ReviewSession, data: PrOverviewData?, modifier: Modifier) {
    val c = LocalPullsColors.current
    val overview = data?.overview
    val html = overview?.bodyHTML.orEmpty()
    val source = overview?.body ?: (session.prOpened ?: session.opened)?.description.orEmpty()
    EditableText(
        source, canEdit = overview?.viewer?.canUpdate == true, textStyle = PullsType.read, saved = "Description updated",
        onSave = { body -> session.repo.prAction(overview!!.id, "edit", body = body); session.reloadOverview() },
        modifier = modifier, multiline = true, allowBlank = true, placeholder = "Describe the change in Markdown",
    ) { text ->
        // GitHub's own rendering while the text is the one it holds; the typed Markdown for the moment after a save.
        val markdown = remember(text, html, overview?.body) { if (html.isNotBlank() && text == overview?.body) htmlToMarkdown(html) else text }
        if (markdown.isNotBlank()) Markdown(markdown, style = PullsType.read.copy(color = c.text))
        else Text(if (overview?.viewer?.canUpdate == true) "Add a description" else "No description.", style = PullsType.read, color = c.muted)
    }
}

// --- Bottom bar ---

/** `.vbar`: the Ask / More pill and the main action: review the files, or approve once they are all reviewed. */
@Composable
private fun BottomBar(
    session: ReviewSession,
    head: PrHead,
    modifier: Modifier,
    onAsk: () -> Unit,
    onMore: () -> Unit,
    onDiff: (String?) -> Unit,
    onApprove: () -> Unit,
) {
    val c = LocalPullsColors.current
    val left = session.files.count { it.sha !in session.reviewed }
    val canApprove = session.pull != null && session.canWrite && head.isOpen
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            // The ⋯ gets more room on its right than Ask has on its left: the dots sit in the middle of a 44dp target.
            Modifier.height(52.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised).padding(start = 6.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                // The word alone: the speech bubble beside it was the generic AI glyph of rule 48.
                Modifier.height(44.dp).clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = "Ask", onClick = onAsk).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ask", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
            }
            Box(Modifier.width(1.dp).height(22.dp).background(c.border))
            FloatIcon(Lu.more, "More actions", onMore, flat = true, size = 44.dp)
        }
        val done = left == 0 && session.files.isNotEmpty()
        CtaButton(
            text = when {
                done && canApprove -> "Approve"
                done -> "Open changes"
                else -> "Review $left file${if (left == 1) "" else "s"}"
            },
            onClick = { if (done && canApprove) onApprove() else onDiff(null) },
            modifier = Modifier.weight(1f),
            container = c.primary,
            contentColor = Color.White,
            trailing = { IconSwap(if (done && canApprove) Lu.check else Lu.arrow) { LuIcon(it, tint = Color.White, size = 20.dp) } },
        )
    }
}
