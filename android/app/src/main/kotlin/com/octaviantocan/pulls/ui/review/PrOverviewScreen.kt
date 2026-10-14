package com.octaviantocan.pulls.ui.review

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.StepMark
import com.octaviantocan.pulls.core.analyzeCard
import com.octaviantocan.pulls.core.swipedTab
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.TaskState
import com.octaviantocan.pulls.ui.ai.AiActivityLog
import com.octaviantocan.pulls.ui.ai.AnalyzeSplitButton
import com.octaviantocan.pulls.ui.ai.BriefSection
import com.octaviantocan.pulls.ui.ai.SharedAnalysisBanner
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.ui.ai.rememberLiveTask
import com.octaviantocan.pulls.ui.ai.runAnalyze
import com.octaviantocan.pulls.ui.ai.runCritique
import com.octaviantocan.pulls.ui.ai.startBrief
import com.octaviantocan.pulls.ui.ai.startTour
import com.octaviantocan.pulls.ui.design.BreathingDot
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.OkDot
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PendingDot
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsFonts
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.home.StatusColors
import com.octaviantocan.pulls.ui.home.compactAge
import com.octaviantocan.pulls.ui.home.compactCount

/**
 * The prototype's PR overview: a floating back button and section capsule over the page, the title,
 * who and when, the branches, a four-cell summary line, Summary / Description (/ Conversation) tabs,
 * the AI's status and summary, the file list with its notes, and a bottom bar with Ask, More and the
 * one action that moves the review forward.
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
    onBack: () -> Unit,
    onDiff: (path: String?) -> Unit,
    onTour: () -> Unit,
    onAsk: () -> Unit,
    onSheet: (String) -> Unit,
    onCite: (Cite) -> Unit,
    onOpenRepo: (owner: String, repo: String) -> Unit,
) {
    val c = LocalPullsColors.current
    val tourRunning = session.taskFor("tour")?.running == true
    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp)
                Spacer(Modifier.weight(1f))
                SectionCapsule(selected = 0, live = tourRunning, onOverview = {}, onChanges = { onDiff(null) }, onTour = onTour, onMore = { onSheet("more") })
            }
            val labels = buildList {
                add("Summary")
                add("Description")
                if (data != null) add("Conversation${data.overview.conversationCount.takeIf { it > 0 }?.let { " $it" }.orEmpty()}")
            }
            val shown = pane.coerceAtMost(labels.lastIndex)
            // A swipe left or right moves between the tabs; the files of the diff are never switched this way.
            var drag by remember { mutableFloatStateOf(0f) }
            val swipeFrom = with(LocalDensity.current) { 64.dp.toPx() }
            val swipe = rememberDraggableState { delta -> drag += delta }
            // Lazy, so a pull request with thousands of files only composes the rows on screen.
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().draggable(
                    swipe, Orientation.Horizontal,
                    onDragStarted = { drag = 0f },
                    onDragStopped = { onPane(swipedTab(shown, labels.size, drag, swipeFrom)); drag = 0f },
                ),
                state = scroll,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            ) {
                item(key = "head") {
                    Column {
                        Text(head.title, style = PullsType.xxl, color = c.text)
                        MetaLine(head, onOpenRepo)
                        if (head.base.isNotBlank() || head.head.isNotBlank()) BranchRow(head)
                        CommitRangeBanner(session, data?.overview, Modifier.padding(top = 12.dp))
                        if (isStale(session, data?.overview) && session.selection == null && !session.settings.autoRefresh) {
                            StaleBanner(session, { top.polls = 0; session.refresh() }, Modifier.padding(top = 12.dp))
                        }
                        SharedAnalysisBanner(session, Modifier.padding(top = 12.dp))
                        val overviewError = session.overviewError
                        if (data == null && overviewError != null) OverviewError(overviewError) { session.reloadOverview() }
                        StatsLine(session, data, onSheet)
                        UnderlineTabs(labels, shown, onPane, Modifier.padding(top = 2.dp), spread = true)
                    }
                }
                when (shown) {
                    0 -> {
                        item(key = "summary") { SummaryPane(session, onCite, onSheet) }
                        filesItems(session, ui, top, onDiff)
                    }
                    1 -> item(key = "description") { DescriptionPane(session, data, onEdit = { onSheet("edit") }) }
                    else -> item(key = "conversation") {
                        data?.let { Box(Modifier.padding(top = 14.dp)) { Conversation(it.overview) { oid -> session.selectCommits(selectCommit(oid).pair) } } }
                    }
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

/** The conversation, checks and merge state did not load: say why, and offer to try again. */
@Composable
private fun OverviewError(message: String, onRetry: () -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Couldn't load checks and conversation", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
            Text(message, style = PullsType.xs, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        PillButton("Retry", onRetry)
    }
}

/**
 * `.cap` with the page icons: Overview, Changes, Tour (a live dot while it is written), and More. The
 * Overview and the Tour wear it alike, so a page of the pull request never loses its way home.
 * [selected] is 0 for the Overview, 1 for Changes and 2 for the Tour.
 */
@Composable
internal fun SectionCapsule(selected: Int, live: Boolean, onOverview: () -> Unit, onChanges: () -> Unit, onTour: () -> Unit, onMore: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.width(212.dp).height(44.dp).floatShadow(CircleShape).clip(CircleShape).background(c.raised).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segment("Overview", selected = selected == 0, onClick = if (selected == 0) null else onOverview) { LuIcon(Lu.list, tint = c.text) }
        Segment("Changes", selected = selected == 1, onClick = if (selected == 1) null else onChanges) { LuIcon(Lu.diff, tint = c.text) }
        Segment("Tour", selected = selected == 2, onClick = if (selected == 2) null else onTour) {
            LuIcon(Lu.tour, tint = c.text)
            if (live) BreathingDot(Modifier.align(Alignment.TopEnd).padding(top = 7.dp, end = 10.dp), 8.dp)
        }
        Box(Modifier.padding(horizontal = 2.dp, vertical = 8.dp).width(1.dp).fillMaxHeight().background(c.border))
        Segment("More", selected = false, onClick = onMore) { LuIcon(Lu.more, tint = c.text) }
    }
}

@Composable
private fun RowScope.Segment(label: String, selected: Boolean, onClick: (() -> Unit)?, content: @Composable BoxScope.() -> Unit) {
    val c = LocalPullsColors.current
    Box(
        Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (selected) c.paper2 else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** `.meta`: `#241 · author · opened 6h ago · repo`, on one line. The author and repository are links. */
@Composable
private fun MetaLine(head: PrHead, onOpenRepo: (String, String) -> Unit) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val sep = @Composable { Text("·", style = PullsType.sm, color = c.muted) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(head.label, style = PullsType.num.copy(fontSize = PullsType.sm.fontSize), color = c.text, maxLines = 1)
        head.author?.let { login ->
            sep()
            Text(
                login, Modifier.weight(1f, fill = false).clickable(role = Role.Button, onClickLabel = "Open $login on GitHub") { uri.openUri("https://github.com/${login.removeSuffix("[bot]")}") },
                style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        head.createdAt?.let { iso ->
            val age = compactAge(prIsoMillis(iso), System.currentTimeMillis())
            if (age.isNotEmpty()) {
                sep()
                Text("opened ${if (age == "now") "just now" else "$age ago"}", style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false)
            }
        }
        if (head.owner != null && head.repo != null) {
            sep()
            Text(
                head.repo, Modifier.weight(1f, fill = false).clickable(role = Role.Button, onClickLabel = "Open ${head.repo}") { onOpenRepo(head.owner, head.repo) },
                style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** `.brow`: the head branch, an arrow, the base branch. A tap copies the name and the chip says so for a moment. */
@Composable
private fun BranchRow(head: PrHead) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { kotlinx.coroutines.delay(1200); copied = null } }
    @Composable
    fun Chip(name: String, modifier: Modifier) {
        val done = copied == name
        Row(
            modifier.clip(RoundedCornerShape(6.dp)).background(c.accentWash)
                .clickable(role = Role.Button, onClickLabel = "Copy $name") { copyText(context, name); copied = name }
                .padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (done) LuIcon(Lu.tick, tint = c.accentWashInk, size = 14.dp)
            Text(if (done) "Copied" else name, style = PullsType.sm.copy(fontFamily = PullsFonts.display, fontWeight = FontWeight.Medium), color = c.accentWashInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (head.head.isNotBlank()) Chip(head.head, Modifier.weight(1f, fill = false))
        if (head.head.isNotBlank() && head.base.isNotBlank()) LuIcon(Lu.to, tint = c.muted, size = 14.dp)
        if (head.base.isNotBlank()) Chip(head.base, Modifier)
    }
}

/** `.cs`: Changes, Commits, Files and Checks as four label-over-value cells under a hairline. */
@Composable
private fun StatsLine(session: ReviewSession, data: PrOverviewData?, onSheet: (String) -> Unit) {
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
        Cell("Commits", Modifier.width(76.dp), onClick = if (overview != null && commits > 0) ({ onSheet("commits") }) else null) {
            Text("$commits", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
        }
        Cell("Files", Modifier.width(52.dp)) { Text("${session.files.size}", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text) }
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
        modifier.then(if (onClick != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = PullsType.sm, color = c.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), content = value)
    }
}

// --- Summary ---

/** What the AI made of the pull request: its progress, the summary and what needs a human. The files follow as list rows. */
@Composable
private fun SummaryPane(session: ReviewSession, onCite: (Cite) -> Unit, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    val analysis = session.saved?.analysis
    val summary = analysis?.overallSummary?.takeIf { session.hasAiResult && it.isNotBlank() }
    Column {
        AnalysisBlock(session, onSheet)
        if (summary != null) {
            Box(Modifier.padding(top = 12.dp)) {
                Markdown(summary, style = PullsType.read.copy(color = c.text))
            }
        }
        session.saved?.brief?.let { brief ->
            BriefSection(session, brief, onOpen = { target -> onCite(Cite(target.path, target.line ?: 0)) }, modifier = Modifier.padding(top = 16.dp))
        }
        NeedsHuman(session, onCite, onSheet)
    }
}

@Composable
private fun StepDot(mark: StepMark) {
    val c = LocalPullsColors.current
    when (mark) {
        StepMark.Pending, StepMark.Stopped -> PendingDot()
        StepMark.Active -> BreathingDot()
        StepMark.Done -> OkDot()
        StepMark.Failed -> LuIcon(Lu.xc, tint = c.error, size = 14.dp)
    }
}

/** What the card says under its title while something runs: the latest step of the work log, or that the engine is starting. */
private fun liveLine(task: TaskState, engine: String): String =
    task.activities.lastOrNull()?.title ?: "Starting ${if (engine == "codex") "Codex" else "Claude Code"}…"

/**
 * `.anz`: the AI's progress. An orb and "Analyzing" with File notes, Summary, Tour and Brief beneath it, each
 * switching from a hollow ring to a breathing dot to a tick; tapping it opens the work log. The card is there
 * the instant Analyze is pressed, and a stopped or failed run keeps a way to start again. Before any analysis it
 * offers to start one: the button runs the last choice and its chevron opens the others.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisBlock(session: ReviewSession, onSheet: (String) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    if (session.key == null || !cliEngine(context)) return
    val analyzeTask = rememberLiveTask(session, "analyze")
    val tourTask = rememberLiveTask(session, "tour")
    val briefTask = rememberLiveTask(session, "brief")
    var logOpen by rememberSaveable { mutableStateOf(false) }
    val engine = remember { ServerStore.load(context).engine }

    if (analyzeTask == null && tourTask?.running != true && briefTask?.running != true) {
        if (session.hasAiResult) return
        // Quick and Deep read the model's efforts from its list; have it before the button is pressed.
        val repo = remember { Repo(context) }
        LaunchedEffect(engine) { if (ModelCatalogs.get(engine) == null) fetchCatalog(repo, engine) }
        val choice = session.settings.analyzeChoice
        AnalyzeCard(Modifier.padding(top = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Orb(done = true)
                Column(Modifier.weight(1f)) {
                    Text("Not analyzed yet", style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                    Text("${choice.title}: ${choice.summary().replaceFirstChar { it.lowercase() }}", style = PullsType.sm, color = c.muted)
                }
                AnalyzeSplitButton(choice.title, onRun = { session.runAnalyze(context) }, onMenu = { onSheet("analyze") })
            }
        }
        return
    }

    val card = analyzeCard(
        analyze = analyzeTask?.status,
        tour = tourTask?.status,
        brief = briefTask?.status,
        hasTour = session.saved?.tour != null,
        hasBrief = session.saved?.brief != null,
        then = analyzeTask?.then.orEmpty(),
    )
    val tasks = listOfNotNull(analyzeTask, tourTask, briefTask)
    val live = tasks.firstOrNull { it.running }
    AnalyzeCard(Modifier.padding(top = 16.dp).animateContentSize(), spacing = 10.dp) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Show the steps") { logOpen = !logOpen },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Orb(done = !card.working)
                Column(Modifier.weight(1f)) {
                    Text(card.title, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                    if (live != null) Text(liveLine(live, engine), style = PullsType.sm, color = c.muted)
                }
                if (card.working) PillButton("Stop", { tasks.forEach { task -> if (task.running) session.cancelTask(task.id) } })
                LuIcon(Lu.chev, Modifier.rotate(if (logOpen) 90f else 0f), tint = c.faint, size = 18.dp)
            }
            FlowRow(Modifier.padding(start = 42.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                card.steps.forEach { step ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StepDot(step.mark)
                        Text(step.label, style = PullsType.sm, color = if (step.mark == StepMark.Pending) c.muted else c.text, maxLines = 1)
                    }
                }
            }
        }
        if (card.restart) {
            val failed = analyzeTask?.status == "failed"
            Row(Modifier.padding(start = 42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (failed) (analyzeTask?.error ?: "The analysis did not finish.") else "You stopped it.",
                    Modifier.weight(1f, fill = false), style = PullsType.sm, color = if (failed) c.error else c.muted,
                )
                PillButton(if (failed) "Try again" else "Analyze again", { session.runAnalyze(context) })
            }
        }
        // A tour or brief that failed after a good analysis does not undo it; each can be run again on its own.
        if (!card.working) {
            listOf("The tour" to tourTask, "The brief" to briefTask).forEach { (name, found) ->
                val task = found?.takeIf { it.status == "failed" } ?: return@forEach
                Row(Modifier.padding(start = 42.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.error ?: "$name did not finish.", Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.error)
                    PillButton("Try again", { if (task.kind == "tour") startTour(session) else startBrief(session) })
                }
            }
        }
        if (logOpen) Column(Modifier.padding(start = 42.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tasks.forEach { task -> AiActivityLog(task.activities, task.running) }
        }
    }
}

/** `.anz`: the card the AI's progress sits in, raised on the page like the tour's cards. */
@Composable
private fun AnalyzeCard(modifier: Modifier = Modifier, spacing: androidx.compose.ui.unit.Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalPullsColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.fillMaxWidth().floatShadow(shape, PullsShadow.Note).clip(shape).background(c.raised).padding(16.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

/** `What needs a human`: what the AI review found, most serious first, each pointing at its line. */
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
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("What needs a human", Modifier.padding(bottom = 2.dp), style = PullsType.xl, color = c.text)
        shown.forEach { finding ->
            Text(
                buildAnnotatedString {
                    append(leadAndText(finding.title, firstLine(finding.body)))
                    append(" ")
                    appendCite("${finding.path.substringAfterLast('/')}:${finding.line}", c.activeText) { onCite(Cite(finding.path, finding.line)) }
                },
                Modifier.fillMaxWidth().hairlineTop(c.borderMute).clickable(role = Role.Button, onClickLabel = finding.title) { session.focusFinding(finding) }.padding(vertical = 11.dp),
                style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.text,
            )
        }
        if (findings.isNotEmpty()) {
            // The findings sheet is also where findings are picked and posted as a review, so it is offered for a short list too.
            Text(
                if (findings.size > shown.size) "Show all ${findings.size}" else "Open the review", Modifier.fillMaxWidth().hairlineTop(c.borderMute).clickable(role = Role.Button) { onSheet("findings") }.padding(vertical = 11.dp),
                style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText,
            )
        }
        when {
            reviewing -> Row(Modifier.fillMaxWidth().hairlineTop(c.borderMute).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BreathingDot()
                Text("Looking for problems…", Modifier.weight(1f), style = PullsType.sm, color = c.muted)
                PillButton("Stop", { critiqueTask?.let { session.cancelTask(it.id) } })
            }
            findings.isEmpty() && session.saved?.critique != null -> Text(
                "The review found nothing that needs you.", Modifier.fillMaxWidth().hairlineTop(c.borderMute).padding(vertical = 11.dp), style = PullsType.sm, color = c.muted,
            )
            findings.isEmpty() -> Row(
                Modifier.fillMaxWidth().hairlineTop(c.borderMute).clickable(role = Role.Button) { session.runCritique(context, null) }.padding(vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Look for problems", Modifier.weight(1f), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

private fun Modifier.hairlineTop(color: Color): Modifier = drawBehind { drawRect(color, Offset.Zero, Size(size.width, 1.dp.toPx())) }

// --- Files ---

/** The heading and one list row per file, grouped under the section headings when there are any. */
private fun LazyListScope.filesItems(session: ReviewSession, ui: DiffUi, top: ReviewTopUi, onDiff: (String?) -> Unit) {
    val sections = fileSections(session, ui)
    val analysis = session.saved?.analysis
    val notes = analysis?.files?.associate { it.path to it.summary }.orEmpty()
    val analyzing = session.taskFor("analyze")?.running == true
    item(key = "files-head") {
        val c = LocalPullsColors.current
        val reviewed = session.files.count { it.sha in session.reviewed }
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp).hairlineTop(c.borderMute).padding(top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Files", style = PullsType.xl, color = c.text)
            Text(
                "$reviewed of ${session.files.size} reviewed",
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "Review progress") { top.progressOpen = true },
                style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted,
            )
        }
    }
    sections.forEachIndexed { index, section ->
        if (section.label != null) item(key = "section:$index") {
            val c = LocalPullsColors.current
            Text(section.label, Modifier.padding(top = 16.dp, bottom = 4.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        }
        items(section.files, key = { "file:${it.path}" }) { file ->
            val state = session.reviewState(file)
            val note = notes[file.path]?.takeIf { it.isNotBlank() }
            FileRow(
                file, state, note, analyzing && note == null,
                onOpen = { onDiff(file.path) },
                onToggle = { session.setReviewed(listOf(file.sha), state != ReviewState.Reviewed) },
            )
        }
    }
}

/** `.fr`: a checkbox, the path with its folder faded, `+n −n`, a chevron, and the AI's note under the name. */
@Composable
private fun FileRow(file: ReviewFile, state: ReviewState, note: String?, noteLoading: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val c = LocalPullsColors.current
    val reviewed = state == ReviewState.Reviewed
    val dir = file.path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val base = file.path.substringAfterLast('/')
    Row(
        Modifier.fillMaxWidth().hairlineTop(c.borderMute).clickable(role = Role.Button, onClickLabel = file.path, onClick = onOpen).padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PullsCheck(reviewed, onClick = onToggle, label = "Mark ${file.path} reviewed")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = c.faint, fontWeight = FontWeight.Normal)) { append(dir) }
                        append(base)
                    },
                    Modifier.weight(1f),
                    style = PullsType.sm.copy(fontFamily = PullsFonts.display, fontWeight = FontWeight.Medium, textDecoration = if (reviewed) TextDecoration.LineThrough else null),
                    color = if (reviewed) c.muted else c.text,
                )
                val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium)
                Text("+${compactCount(file.additions)}", style = mono, color = StatusColors.green(c.dark), maxLines = 1, softWrap = false)
                Text("−${compactCount(file.deletions)}", style = mono, color = StatusColors.red(c.dark), maxLines = 1, softWrap = false)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            when {
                state == ReviewState.Changed -> Text("Changed since you reviewed it", style = PullsType.sm, color = c.orange)
                note != null -> Text(note, style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.4f), color = c.muted)
                noteLoading -> Skeleton(Modifier.fillMaxWidth(0.78f).padding(vertical = 4.dp))
            }
        }
    }
}

// --- Description ---

/** The description, with an Edit link when the viewer may change this pull request (their own, or one they maintain). */
@Composable
private fun DescriptionPane(session: ReviewSession, data: PrOverviewData?, onEdit: () -> Unit) {
    val c = LocalPullsColors.current
    val html = data?.overview?.bodyHTML.orEmpty()
    val markdown = remember(html, session.prOpened?.description) {
        if (html.isNotBlank()) htmlToMarkdown(html) else (session.prOpened ?: session.opened)?.description.orEmpty()
    }
    Column {
        if (data?.overview?.viewer?.canUpdate == true) {
            Box(
                Modifier.padding(top = 6.dp).defaultMinSize(minHeight = 40.dp).clip(RoundedCornerShape(PullsRadius.lg))
                    .clickable(role = Role.Button, onClickLabel = "Edit the description", onClick = onEdit),
                contentAlignment = Alignment.CenterStart,
            ) { Text(if (markdown.isBlank()) "Add a description" else "Edit description", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.activeText) }
        }
        Box(Modifier.padding(top = 8.dp)) {
            if (markdown.isNotBlank()) Markdown(markdown, style = PullsType.read.copy(color = c.text))
            else Text("No description.", style = PullsType.read, color = c.muted)
        }
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
            Modifier.height(52.dp).floatShadow(CircleShape, PullsShadow.Bar).clip(CircleShape).background(c.raised).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.height(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Ask", onClick = onAsk).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LuIcon(Lu.ask, tint = c.activeText, size = 20.dp)
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
            trailing = { LuIcon(if (done && canApprove) Lu.check else Lu.arrow, tint = Color.White, size = 20.dp) },
        )
    }
}
