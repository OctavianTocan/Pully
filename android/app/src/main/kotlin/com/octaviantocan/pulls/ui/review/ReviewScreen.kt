package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.Command
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.UseCommands
import com.octaviantocan.pulls.ui.ai.TourSheet
import com.octaviantocan.pulls.ui.home.SearchDim
import com.octaviantocan.pulls.ui.home.PullToCommands
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.ai.AnalyzePage
import com.octaviantocan.pulls.ui.ai.AnalyzeSheet
import com.octaviantocan.pulls.ui.ai.AskReference
import com.octaviantocan.pulls.ui.ai.AskSheet
import com.octaviantocan.pulls.ui.ai.ChatHistorySheet
import com.octaviantocan.pulls.ui.ai.ReviewSheet
import com.octaviantocan.pulls.ui.ai.runCritique
import com.octaviantocan.pulls.ui.ai.followQueuedTour
import com.octaviantocan.pulls.ui.ai.tourComing
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.TourEntry
import com.octaviantocan.pulls.ui.home.TourIndex
import com.octaviantocan.pulls.data.TourStore
import com.octaviantocan.pulls.ui.home.deviceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.ui.design.PageBack
import com.octaviantocan.pulls.ui.design.PageMotion

/**
 * What the Ask sheet opens with: its title, the starter chips, a question to send at once, and what the next question is
 * about. [scroll] is where its conversation is, and [returning] that Back brought it back there.
 */
private data class AskRequest(
    val title: String,
    val suggestions: List<String>,
    val initial: String?,
    val reference: AskReference? = null,
    val scroll: ScrollState = ScrollState(0),
    val returning: Boolean = false,
)

/** An Ask sheet a mention was opened from; [over] when that place opened on the page under the sheet, the diff. */
private class AskBehind(val request: AskRequest, val over: Boolean)

private val PR_SUGGESTIONS = listOf("What changed, in a sentence?", "Anything risky?", "What do the tests cover?")
private val TOUR_SUGGESTIONS = listOf("Why was this change made?", "What should I check by hand?", "What is the riskiest part?")

/**
 * One review, as three pages stacked like the prototype's screens: the overview, one file's diff and
 * the tour. This owns the session and the sheets over them; the pages draw. Back pops a page, and
 * from the first page leaves the review. A citation anywhere (a finding, a tour source, the palette)
 * asks for a [Focus], and this opens the diff on it.
 */
@Composable
fun ReviewScreen(target: OpenTarget, nav: Nav, startOnTour: Boolean = false, openedFrom: String = "other", place: com.octaviantocan.pulls.ui.PlaceRequest? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val c = LocalPullsColors.current
    val session = remember(target) {
        ReviewSession(target, Repo(context), scope, AppSettings.get(context), TourStore.get(context), openedFrom).also { it.load() }
    }
    DisposableEffect(session) { onDispose { session.detach() } }
    // The title and number the phone already knows, for the page to show while the rest loads.
    val preview = remember(target) { pullPreview(context, target) }
    val ui = rememberDiffUi(session)
    val top = remember(session) { ReviewTopUi() }
    val observedState = session.prOpened?.let { opened ->
        normalizedPrState(opened.state).takeIf { !opened.stale || it == "merged" }
    }
    val data = remember(session.overview, session.overviewStored, session.prOpened?.headSha, observedState) {
        parsePrOverviewData(session.overview, fresh = !session.overviewStored, expectedHead = session.prOpened?.headSha, observedState = observedState)
    }
    val tours = remember { TourIndex(deviceStore(context, "pulls-inbox")) }
    val pages = remember(session) { mutableStateListOf(if (startOnTour) PrView.Tour else PrView.Overview) }
    val overviewScroll = rememberLazyListState()
    // The Tour's own state (how far it is scrolled, what is open in it) outlives the page while a place it opened is on
    // screen, so Back comes back to exactly where the reader was (J35).
    val kept = rememberSaveableStateHolder()
    var pane by rememberSaveable { mutableIntStateOf(0) }
    var diffPath by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var tourStart by remember(session) { mutableStateOf<com.octaviantocan.pulls.ui.ai.TourSheetPage>(com.octaviantocan.pulls.ui.ai.TourSheetPage.Main) }
    // The page the pull request's sheet opens on: its actions, or a review to submit when Commands asked for one (N14).
    var prStart by remember { mutableStateOf<PrPage>(PrPage.Actions) }
    // The Tour's Ask bar is focused and empty: the header dims with the page (N29).
    var tourAskDim by remember { mutableStateOf(false to true) }
    var ask by remember { mutableStateOf<AskRequest?>(null) }
    // An Ask bar's earlier-chats button: the Chats sheet alone, for the Tour (true) or the Overview (false); a chat picked
    // there opens Ask on it (P5).
    var chats by remember { mutableStateOf<Boolean?>(null) }
    // The pull request's Ask draft, one for the opening page and the Overview, which share one bar (Q2).
    val askDraft = remember { mutableStateOf("") }
    // The Ask sheet a mention in an answer was opened from, and where it was scrolled: Back from the place opens it again
    // there, as Back from a place the tour opened finds the tour (J35).
    var askBehind by remember { mutableStateOf<AskBehind?>(null) }
    // How the last change of view was made, for the ones the capsule's order cannot tell: Back and a back swipe (rule 105).
    val viewMotion = remember(session) { PageMotion() }
    val opened = session.opened
    // A tour the phone kept opens before the overview or the diff has arrived, named from what the phone knows.
    val head = (prHead(session, data) ?: storedTourHead(target, preview, session.tour).takeIf { pages.last() == PrView.Tour })
        // The branches come with the Inbox row, so they show with the title rather than when GitHub answers (J13).
        ?.let { if (it.base.isBlank() && it.head.isBlank() && preview != null) it.copy(base = preview.base, head = preview.head) else it }

    SideEffect {
        ui.uncategorizedLabel = messages.t("group.uncategorized")
        ui.uncategorizedSummary = messages.t("group.uncategorizedSummary")
    }
    OverviewEffects(session, top, data)
    // Leaving the diff by Back goes back to the Ask sheet it was opened from, if it was.
    fun pop() {
        if (pages.removeAt(pages.lastIndex) == PrView.Diff) askBehind?.let { ask = it.request }
        askBehind = null
    }
    // Back pops a view and the views move backward; a back swipe moves the view with the finger first.
    PageBack(viewMotion, enabled = pages.size > 1) { pop() }
    // Asked from the diff, a mention scrolls the same page, so Back opens the sheet again over it instead of leaving it.
    fun backToAsk(): Boolean {
        val behind = askBehind?.takeIf { it.over } ?: return false
        ask = behind.request
        askBehind = null
        return true
    }
    // motion-ok: no page moves; the sheet rises again from the bottom as every sheet does (rule 108).
    BackHandler(enabled = askBehind?.over == true) { backToAsk() }

    fun orderedFiles() = fileSections(session, ui).flatMap { it.files }
    fun push(page: PrView) { if (pages.last() != page) pages.add(page) }
    fun back() {
        if (backToAsk()) return
        if (pages.size > 1) {
            viewMotion.next(BACKWARD)
            pop()
        } else {
            nav.back()
        }
    }
    // The tour can be the first page (opened from the Tours tab), so the overview may not be below it.
    fun toOverview() {
        askBehind = null
        if (PrView.Overview !in pages) pages.add(0, PrView.Overview)
        while (pages.last() != PrView.Overview) pages.removeAt(pages.lastIndex)
    }
    // The capsule moves between sibling pages (overview, changes, tour) without stacking them, so Back still leaves one page at a time.
    fun switchTo(page: PrView) {
        val here = pages.last()
        if (here == page) return
        askBehind = null
        if (page == PrView.Overview) { toOverview(); return }
        pages.removeAll { it == page }
        if (here == PrView.Overview) pages.add(page) else pages[pages.lastIndex] = page
    }
    fun openDiff(path: String?) {
        // Review tapped before the diff came opens the Diff on its bars, and the file is chosen once the diff is in (L5).
        if (session.opened == null) { diffPath = path; push(PrView.Diff); return }
        val files = orderedFiles()
        diffPath = path ?: files.firstOrNull { it.sha !in session.reviewed }?.path ?: files.firstOrNull()?.path
        push(PrView.Diff)
    }
    fun toChanges() { if (diffPath == null) openDiff(null) else switchTo(PrView.Diff) }
    followQueuedTour(session)
    // The tour icon is the one way to a tour (rule 113): with none written it asks whether to write one, with the model
    // that would; while one is written it opens that sheet again on the progress, over the Tour (M12); otherwise it
    // opens the tour.
    fun openTour() {
        if (session.tour != null) { switchTo(PrView.Tour); return }
        if (tourComing(session)) switchTo(PrView.Tour)
        sheet = "tour"
    }
    // The sheet that shows the tour being written closes once the tour is there (M12).
    LaunchedEffect(session.tour != null) { if (session.tour != null && sheet == "tour") sheet = null }
    LaunchedEffect(pages.last(), session.tour?.headSha, session.tour?.generatedAt, session.opened?.headSha, tourComing(session)) {
        if (pages.last() != PrView.Tour || tourComing(session)) return@LaunchedEffect
        val stale = com.octaviantocan.pulls.ui.ai.staleTourIdentity(session.tour, session.opened?.headSha) ?: return@LaunchedEffect
        if (session.promptedStaleTours.add(stale)) {
            tourStart = com.octaviantocan.pulls.ui.ai.TourSheetPage.Stale(stale)
            sheet = "tourmore"
        }
    }
    fun openAsk(forTour: Boolean, initial: String? = null, reference: AskReference? = null) {
        askBehind = null
        ask = AskRequest(
            if (forTour) "Ask about this tour" else "Ask about ${head?.label ?: "this pull request"}",
            if (forTour) TOUR_SUGGESTIONS else PR_SUGGESTIONS,
            initial,
            reference,
        )
    }

    // The files in reading order only when a command runs: ordering a large pull request's files on every frame made the
    // Tour's ⋯ slow to open (N22).
    PrCommands(
        session, ::orderedFiles, diffPath, onGo = { path -> diffPath = path; push(PrView.Diff) },
        onAnalyze = { sheet = "analyze" },
        onDiff = pages.last() == PrView.Diff,
    )
    // On a pull request's Overview, Commands leads with its next steps (N14); the Tour and the Diff bring their own.
    UseCommands {
        val here = head
        if (pages.last() != PrView.Overview || here == null) return@UseCommands emptyList()
        val group = messages.t("palette.group.pr")
        val canReview = session.pull != null && session.canWrite
        buildList {
            add(Command("pr.reviewFiles", "Review files", group, keywords = "diff changes code", suggested = true) { openDiff(null) })
            add(Command("pr.ask", "Ask about this pull request", group, keywords = "question chat ai", suggested = true) { openAsk(forTour = false) })
            if (canReview) add(Command("pr.leaveComment", "Leave a comment", group, keywords = "review comment", suggested = true) { prStart = PrPage.Submit("COMMENT"); sheet = "more" })
            if (canReview && here.isOpen) add(Command("pr.approve", "Approve", group, keywords = "review approve lgtm", suggested = true) { prStart = PrPage.Submit("APPROVE"); sheet = "more" })
        }
    }

    // A link to a thing on this pull request (a comment, a file, the checks) goes there, once: when the page opens on it,
    // and when a link to it arrives while the page is already on screen. Back later finds the page where it was left.
    val github = com.octaviantocan.pulls.ui.rememberOpenOnGithub()
    val reopened = com.octaviantocan.pulls.ui.IncomingLinks.reopened
    val seenReopen = remember(session) { reopened }
    var going by remember(session) { mutableStateOf(place?.takeIf { !it.done }) }
    LaunchedEffect(reopened) {
        val link = reopened?.takeIf { it !== seenReopen && it.target.serverTarget == session.target.serverTarget } ?: return@LaunchedEffect
        link.place?.let { going = com.octaviantocan.pulls.ui.PlaceRequest(it) }
    }
    LaunchedEffect(going) {
        val request = going ?: return@LaunchedEffect
        val moves = LandingMoves(
            summary = { toOverview(); pane = 0; scope.launch { overviewScroll.animateScrollToItem(0) } },
            conversation = { toOverview(); pane = CONVERSATION_TAB },
            changes = ::toChanges,
            sheet = { sheet = it },
            github = github,
        )
        landOn(request.place, session, moves, toaster)
        request.done = true
    }
    // A citation or a finding asked for a line: open the diff on its file (the diff screen then scrolls to the line).
    val focus = session.focus
    LaunchedEffect(focus) {
        val wanted = focus ?: return@LaunchedEffect
        if (session.files.none { it.path == wanted.path }) {
            toaster.show("${wanted.path.substringAfterLast('/')} is not part of this change", cue = Cue.Error)
            session.focus = null
            return@LaunchedEffect
        }
        // A sheet that asked for the place (a finding, a mention in the review findings) gets out of its way.
        sheet = null
        diffPath = wanted.path
        push(PrView.Diff)
    }
    // A source tapped in a kept tour before the diff arrived opens now, the way it was asked for (on a sheet from the
    // Tour); a Diff opened on its bars gets its first file to read.
    LaunchedEffect(opened) {
        if (opened == null) return@LaunchedEffect
        session.waitingSource?.let { source ->
            session.waitingSource = null
            openSource(session, source, inSheet = session.waitingInSheet)
        }
        if (diffPath == null && pages.last() == PrView.Diff) {
            val files = orderedFiles()
            diffPath = files.firstOrNull { it.sha !in session.reviewed }?.path ?: files.firstOrNull()?.path
        }
    }
    // A refresh or a commit range failed while a diff is already on screen: the full-page error only shows before one is.
    val loadError = session.loadError
    LaunchedEffect(loadError) {
        if (loadError != null && session.opened != null) toaster.show("Couldn't update: $loadError", cue = Cue.Error)
    }
    // GitHub refused an action (close, ready, draft…) while no sheet was there to say so.
    LaunchedEffect(top.actionError) {
        val error = top.actionError ?: return@LaunchedEffect
        if (!top.mergeOpen) {
            toaster.show(messages.t("overview.actions.failed", "message" to error), cue = Cue.Error)
            top.actionError = null
        }
    }
    // The Tours tab lists a tour once it exists or is being written.
    val tour = session.tour
    val tourRunning = session.taskFor("tour")?.running == true
    val pull = session.pull
    LaunchedEffect(tour?.generatedAt, tourRunning, head?.title) {
        if (pull == null || head == null || (tour == null && !tourRunning)) return@LaunchedEffect
        val existing = tours.get("${pull.owner}/${pull.repo}#${pull.number}")
        val stops = tour?.stops?.size ?: 0
        tours.upsert(
            TourEntry(
                pull.owner, pull.repo, pull.number, head.title, stops,
                reached = minOf(existing?.reached ?: 0, stops), stopTitle = existing?.stopTitle.orEmpty(),
                writing = tourRunning, updatedAt = System.currentTimeMillis(), at = minOf(existing?.at ?: 0, stops),
            ),
        )
    }

    // The overview alone is enough for the page, so a huge pull request shows its description while its files still
    // come (the Files rows are bars until then). A page opened on the tour waits for the diff the tour reads, unless the
    // phone kept the tour from an earlier visit: then it shows at once and its links work once the diff is in.
    val waiting = head == null || (opened == null && pages.last() == PrView.Diff) || (opened == null && pages.last() == PrView.Tour && session.tour == null)
    Box(Modifier.fillMaxSize().background(c.base)) {
        when {
            waiting && session.loadError != null -> Placeholder(onBack = nav.back) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(messages.t("pr.loadFailed"), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                    session.loadError?.let { Text(it, style = PullsType.sm, color = c.muted) }
                    FloatIcon(Lu.redo, "Load the pull request again", { session.refresh() }, flat = true, iconSize = 22.dp, tint = c.muted)
                }
            }
            // No loading screen: the page's shape with what the phone knows, and bars for the rest.
            waiting -> PrOpening(target, preview, onBack = nav.back, onAsk = { question -> openAsk(false, initial = question) }, askDraft = askDraft)
            else -> {
                val view = pages.last()
                Column(Modifier.fillMaxSize()) {
                    // One header over the three views, drawn once: the same pixels on each while only what is under it slides
                    // (rule 93), and the capsule's pill slides to the view you go to. Its corner is Aa on the diff and ⋯ elsewhere;
                    // on the Tour, ⋯ is the tour's own sheet (N23).
                    Box(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                            FloatIcon(Lu.back, "Back", ::back, iconSize = 22.dp)
                            Spacer(Modifier.weight(1f))
                            SectionCapsule(
                                selected = view.ordinal, live = tourComing(session),
                                onOverview = ::toOverview, onChanges = ::toChanges, onTour = ::openTour,
                                onCorner = {
                                    tourStart = com.octaviantocan.pulls.ui.ai.TourSheetPage.Main
                                    sheet = when (view) { PrView.Diff -> "tools"; PrView.Tour -> "tourmore"; else -> "more" }
                                },
                                corner = if (view == PrView.Diff) CapsuleCorner.TextTools else CapsuleCorner.More,
                            )
                        }
                        // The Tour's dim for its focused Ask bar covers the header as well as the page ("the top bar stays
                        // bright", recording of 16 Oct 12:10, 14:15 to 14:26, N29); a tap on it lets go of the bar.
                        if (view == PrView.Tour) Box(Modifier.matchParentSize()) { SearchDim(tourAskDim.first, tourAskDim.second) }
                    }
                    // The views sit left to right as the capsule shows them, so going to one moves the page that way (rule 105).
                    // Pulling down past the top of any view opens Commands, as on the Inbox (J30).
                    PullToCommands({ Commands.open() }, Modifier.weight(1f).fillMaxWidth()) {
                    BlurSwitch(view, Modifier.fillMaxSize(), order = { it.ordinal }, motion = viewMotion) { page ->
                        when (page) {
                            PrView.Overview -> PrOverviewScreen(
                                session, ui, top, data, head, overviewScroll, pane, { pane = it },
                                onDiff = { path -> if (path == null) toChanges() else openDiff(path) },
                                onAsk = { question -> openAsk(forTour = false, initial = question) },
                                onAskHistory = { chats = false },
                                onSheet = { name ->
                                    if (name == "stack") session.stack?.let { Analytics.track("stack.open", "size" to it.entries.size) }
                                    sheet = name
                                },
                                onCite = { cite -> openSource(session, TourSource(cite.path, cite.line, "")) },
                                onOpenRepo = nav.openRepo,
                                askDraft = askDraft,
                            )
                            PrView.Diff -> PrDiffScreen(
                                session, ui, diffPath, commitLabel(session.selection, data?.overview), { diffPath = it },
                                onOverview = ::toOverview,
                                onFiles = { sheet = "files" },
                                onAsk = { reference -> openAsk(forTour = false, reference = reference) },
                            )
                            PrView.Tour -> kept.SaveableStateProvider(PrView.Tour.name) {
                                PrTourScreen(
                                    session, head,
                                    resumeAt = remember { pull?.let { tours.get("${it.owner}/${it.repo}#${it.number}")?.resumeAt } ?: 0 },
                                    onReached = { stop, total, title -> recordReached(tours, pull, head, stop, total, title, tourRunning) },
                                    onChanges = ::toChanges,
                                    onOpenSource = { source -> openSource(session, source, inSheet = true) },
                                    onAsk = { question -> openAsk(forTour = true, initial = question) },
                                    onOpenAsk = { openAsk(forTour = true) },
                                    onAskHistory = { chats = true },
                                    onAskDim = { focused, empty -> tourAskDim = focused to empty },
                                    onProgress = { sheet = "tour" },
                                    onRegenerate = {
                                        if (session.tour == null) sheet = "tour"
                                        else {
                                            tourStart = com.octaviantocan.pulls.ui.ai.TourSheetPage.Update
                                            sheet = "tourmore"
                                        }
                                    },
                                )
                            }
                        }
                    }
                    }
                }
                // An AI review with the lens picked, from the Review sheet or from its page in the pull request's sheet.
                fun review(lens: String?) {
                    if (session.taskFor("critique")?.running == true) {
                        toaster.show("Already reviewing ${head.label}")
                    } else {
                        session.runCritique(context, lens)
                        toaster.show("Reviewing ${head.label}")
                    }
                }
                // Sheets live outside the pages so they survive a page change and a scrolled-away opener.
                // A place the Tour opened: that file's diff alone, on a sheet over the Tour (L28), or a file the change does
                // not touch, read at the head commit in the same code view (N20).
                session.sourceSheet?.let { place ->
                    if (session.files.any { it.path == place.path }) FileDiffSheet(session, ui, place, onClose = { session.sourceSheet = null })
                    else FileViewSheet(session, ui, place, onClose = { session.sourceSheet = null })
                }
                when (sheet) {
                    "more" -> PrSheet(session, top, data, head, prStart, onClose = { sheet = null; prStart = PrPage.Actions }, onReview = ::review)
                    // ⋯ on the Tour: the tour's own sheet, drawn from what the phone holds so it opens at once; its last
                    // row goes on to the pull request's sheet (N22, N23).
                    "tourmore" -> TourSheet(
                        session, onClose = { sheet = null },
                        onAsk = { openAsk(forTour = true) }, onDiff = ::toChanges,
                        onPrActions = { sheet = "more" },
                        start = tourStart,
                    )
                    // Aa on the diff: the code's look, then the file's own actions and one row on to the pull request's ⋯,
                    // which slides in as a page of the same sheet (J27).
                    "tools" -> PrSheet(
                        session, top, data, head, PrPage.Tools, onClose = { sheet = null }, onReview = ::review,
                        ui = ui, file = orderedFiles().let { files -> files.firstOrNull { it.path == diffPath } ?: files.firstOrNull() },
                    )
                    "checks" -> data?.overview?.let { ChecksSheet(it, linked = session.linkedCheck) { sheet = null; session.linkedCheck = null } }
                    "commits" -> data?.overview?.let { CommitsSheet(session, ui, it, onOpen = ::toChanges) { sheet = null } }
                    "files" -> FilesSheet(session, ui, current = diffPath.takeIf { pages.last() == PrView.Diff }, onOpen = { path -> openDiff(path) }) { sheet = null }
                    "findings" -> FindingsSheet(session) { sheet = null }
                    "analyze" -> AnalyzeSheet(session, onClose = { sheet = null })
                    "tour" -> AnalyzeSheet(session, onClose = { sheet = null }, start = AnalyzePage.Tour, onTour = { switchTo(PrView.Tour) })
                    "model" -> AnalyzeSheet(session, onClose = { sheet = null }, start = AnalyzePage.Model)
                    "stack" -> session.stack?.let { stack -> StackSheet(stack, onOpen = { number -> session.pull?.let { nav.openFrom(OpenTarget.Pull(it.owner, it.repo, number, "github.com"), "stack", false) } }) { sheet = null } }
                    "review" -> ReviewSheet(session, onClose = { sheet = null }) { lens -> sheet = null; review(lens) }
                    // A long-press on a file row: "file:" and the file's path.
                    else -> sheet?.takeIf { it.startsWith(FILE_SHEET) }?.let { route ->
                        val path = route.removePrefix(FILE_SHEET)
                        session.files.firstOrNull { it.path == path }?.let { file ->
                            FileActionsSheet(
                                session, file,
                                onOpen = { openDiff(path) },
                                onAsk = { openAsk(forTour = false, reference = AskReference("the file ${path.substringAfterLast('/')}", path)) },
                            ) { sheet = null }
                        }
                    }
                }
                chats?.let { forTour -> ChatHistorySheet(session, onClose = { chats = null }) { openAsk(forTour) } }
                ask?.let { request ->
                    // A mention in an answer closes the sheet and opens its place, as a tour source does (rule 96), and Back
                    // brings the sheet back where it was. A file the change does not touch opens in the app's own viewer on
                    // a sheet over the Ask sheet, which stays (J35, N20).
                    AskSheet(session, request.title, request.suggestions, request.initial, request.reference, request.scroll, request.returning, onOpen = { place ->
                        if (session.opened == null || session.files.any { it.path == place.path }) {
                            ask = null
                            // Its question and what it was about are already sent: coming back must not send them again.
                            askBehind = AskBehind(request.copy(initial = null, reference = null, returning = true), over = pages.last() == PrView.Diff)
                        }
                        openSource(session, TourSource(place.path, place.line, ""))
                    }) { ask = null }
                }
                ReviewSubmitSheet(session, top, canVerdict = head.isOpen)
            }
        }
    }
}

/** A page with only a back button over [content]: while the pull request loads or when it cannot. */
@Composable
private fun Placeholder(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp) }
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/**
 * A tour source or a mention: a line the pull request changed opens in the diff; a file it did not touch opens in the
 * app's own viewer on a sheet, read at the head commit, never on GitHub ("In the code rows still open GitHub", recording
 * of 16 Oct 12:10, 10:01 to 10:07, N20).
 */
internal fun openSource(session: ReviewSession, source: TourSource, inSheet: Boolean = false) {
    // A kept tour shows before the diff: the place opens once the diff is in.
    if (session.opened == null) {
        session.waitingSource = source
        session.waitingInSheet = inSheet
        return
    }
    if (session.files.any { it.path == source.path }) {
        // From the Tour a changed file opens on a sheet, alone, and the Tour stays under it ("open in the same sort of diff
        // style but in a bottom sheet, and it's just that one file", recording 15 Oct 15:37, 14:53 to 15:16; L28).
        if (inSheet) session.sourceSheet = Focus(source.path, source.line) else session.focus = Focus(source.path, source.line)
        return
    }
    session.sourceSheet = Focus(source.path, source.line)
}

/** Remembers how far into the tour the reader has been, never going back. */
private fun recordReached(tours: TourIndex, pull: OpenTarget.Pull?, head: PrHead, stop: Int, total: Int, title: String, writing: Boolean) {
    if (pull == null) return
    val key = "${pull.owner}/${pull.repo}#${pull.number}"
    val existing = tours.get(key)
    if (existing != null && existing.reached >= stop && existing.at == stop && existing.stops == total && existing.writing == writing) return
    tours.upsert(
        TourEntry(
            pull.owner, pull.repo, pull.number, head.title, total,
            reached = maxOf(existing?.reached ?: 0, stop),
            stopTitle = if (existing == null || stop > existing.reached) title else existing.stopTitle,
            writing = writing, updatedAt = System.currentTimeMillis(), at = stop,
        ),
    )
}

/**
 * The overview's side effects: re-fetch while GitHub is still computing mergeability or checks are
 * pending (`revalidateDelay`, at most 20 polls), and again when the app comes back to the front, and pull
 * new commits in by themselves when the PR head moved (the web's `checkStaleness`), unless the auto-refresh
 * setting is off. There is no Refresh to press. Taking new commits is quiet and keeps the reader's place:
 * the diff stays on its file where it was scrolled, a load already on its way finishes first, and while one
 * commit is viewed the new ones wait until the reader goes back to all of them.
 */
@Composable
private fun OverviewEffects(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?) {
    val overview = data?.overview
    LaunchedEffect(data, top.polls) {
        val wait = data?.let(::overviewRevalidateDelayMs) ?: return@LaunchedEffect
        if (top.polls >= 20) return@LaunchedEffect
        delay(wait)
        session.reloadOverview()
        top.polls++
    }
    val observedState = session.prOpened?.takeUnless { it.stale }?.state
    LaunchedEffect(observedState) {
        if (observedState in listOf("open", "draft", "closed", "merged") && overview != null && observedState != overview.state) {
            session.reloadOverview()
        }
    }
    var away by remember { mutableStateOf(false) }
    var returns by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { away = true }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (away) {
            away = false
            top.polls = 0
            returns++
            session.reloadOverview()
        }
    }
    // A link to this pull request while it is on screen opens nothing new, so the page reads it again; one that came
    // before this page did is not read twice.
    val reopened = com.octaviantocan.pulls.ui.IncomingLinks.reopened
    val seen = remember(session) { reopened }
    LaunchedEffect(reopened) {
        if (reopened == null || reopened === seen || reopened.target.serverTarget != session.target.serverTarget) return@LaunchedEffect
        top.polls = 0
        session.reloadOverview()
    }
    val stale = isStale(session, overview)
    // A reload that fails leaves the page stale, so it is tried a few more times, and again when the app comes back.
    LaunchedEffect(stale, session.settings.autoRefresh, session.selection, returns) {
        if (!stale || !session.settings.autoRefresh || session.selection != null) return@LaunchedEffect
        for (wait in STALE_RETRY_MS) {
            snapshotFlow { session.loading }.first { !it }
            if (!isStale(session, overview)) return@LaunchedEffect
            session.refresh()
            delay(wait)
        }
    }
}

/** How long the page waits after each automatic reload of new commits before it tries again, while they have not loaded. */
private val STALE_RETRY_MS = listOf(2_000L, 8_000L, 30_000L)
