package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.home.SearchDim
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.ai.AskReference
import com.octaviantocan.pulls.ui.ai.tourComing
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.LocalFirstBeside
import com.octaviantocan.pulls.ui.design.readableWidth
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.ui.design.PageMotion

private typealias AskRequest = ReviewFrameAsk

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
fun ReviewScreen(target: OpenTarget, nav: Nav, services: ReviewFrameServices, startOnTour: Boolean = false, openedFrom: String = "other", place: com.octaviantocan.pulls.ui.PlaceRequest? = null, initialAIResult: String? = null) {
    androidx.compose.runtime.CompositionLocalProvider(LocalOverviewServices provides services.overview) {
        ReviewSessionRoute(target, services.cache) {
            ReviewScreenContent(target, nav, services, startOnTour, openedFrom, place, initialAIResult)
        }
    }
}

@Composable
private fun ReviewScreenContent(target: OpenTarget, nav: Nav, services: ReviewFrameServices, startOnTour: Boolean, openedFrom: String, place: com.octaviantocan.pulls.ui.PlaceRequest?, initialAIResult: String?) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val c = LocalPullsColors.current
    val reviewPolicy by androidx.compose.runtime.rememberUpdatedState(LocalReviewCapabilities.current)
    val aiPolicy by androidx.compose.runtime.rememberUpdatedState(com.octaviantocan.pulls.ui.ai.LocalAiCapabilities.current)
    val session = remember(target) {
        services.createSession(target, scope, openedFrom).also { it.load() }
    }
    DisposableEffect(session) { onDispose { session.detach() } }
    val frameOwner = rememberReviewFrameOwner(session, services.current, services.canReadTour)
    // The title and number the phone already knows, for the page to show while the rest loads.
    val preview = remember(target) { services.preview(target) }
    val ui = rememberDiffUi(session)
    val top = remember(session) { ReviewTopUi() }
    SideEffect { top.reviewCapabilities = reviewPolicy }
    val observedState = session.prOpened?.let { opened ->
        normalizedPrState(opened.state).takeIf { !opened.stale || it == "merged" }
    }
    val data = remember(session.overview, session.overviewStored, session.prOpened?.headSha, observedState) {
        parsePrOverviewData(session.overview, fresh = !session.overviewStored, expectedHead = session.prOpened?.headSha, observedState = observedState)
    }
    val tours = services.tours
    val pages = remember(session) { mutableStateListOf(if (startOnTour && frameOwner.canReadTour) PrView.Tour else PrView.Overview) }
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
    var askDim by remember { mutableStateOf(false to true) }
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
    val overviewReopened = services.reopened?.invoke()
    val overviewReopen = overviewReopened?.overview
    OverviewEffects(session, top, data, overviewReopen)
    // Leaving the diff by Back goes back to the Ask sheet it was opened from, if it was.
    fun pop() {
        // On iOS one edge swipe on the Changes page reaches both the file swipe (to the Overview) and the page Back, and
        // the second must not take the last page away: an empty stack crashed the review.
        if (pages.size <= 1) return
        if (pages.removeAt(pages.lastIndex) == PrView.Diff) askBehind?.let { ask = it.request }
        askBehind = null
    }
    // Back pops a view and the views move backward; a back swipe moves the view with the finger first.
    services.back.Page(viewMotion, enabled = pages.size > 1) { pop() }
    // Asked from the diff, a mention scrolls the same page, so Back opens the sheet again over it instead of leaving it.
    fun backToAsk(): Boolean {
        val behind = askBehind?.takeIf { it.over } ?: return false
        ask = behind.request
        askBehind = null
        return true
    }
    // motion-ok: no page moves; the sheet rises again from the bottom as every sheet does (rule 108).
    services.back.Priority(enabled = askBehind?.over == true) { backToAsk() }

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
        if (!frameOwner.canReadDiff) return
        // Review tapped before the diff came opens the Diff on its bars, and the file is chosen once the diff is in (L5).
        if (session.opened == null) { diffPath = path; push(PrView.Diff); return }
        val files = orderedFiles()
        diffPath = path ?: files.firstOrNull { it.sha !in session.reviewed }?.path ?: files.firstOrNull()?.path
        push(PrView.Diff)
    }
    fun toChanges() {
        if (!frameOwner.canReadDiff) return
        if (diffPath == null) openDiff(null) else switchTo(PrView.Diff)
    }
    services.actions?.FollowQueuedTour(session)
    // The tour icon is the one way to a tour (rule 113): with none written it asks whether to write one, with the model
    // that would; while one is written it opens that sheet again on the progress, over the Tour (M12); otherwise it
    // opens the tour.
    fun openTour() {
        if (!frameOwner.canReadTour) return
        if (session.tour != null) { switchTo(PrView.Tour); return }
        if (tourComing(session)) switchTo(PrView.Tour)
        sheet = "tour"
    }
    // The sheet that shows the tour being written closes once the tour is there (M12).
    LaunchedEffect(session.tour != null) { if (session.tour != null && sheet == "tour") sheet = null }
    LaunchedEffect(pages.last(), session.tour?.headSha, session.tour?.generatedAt, session.opened?.headSha, tourComing(session)) {
        if (!services.current() || services.actions == null || !aiPolicy.canTour || pages.last() != PrView.Tour || tourComing(session)) return@LaunchedEffect
        val stale = com.octaviantocan.pulls.ui.ai.staleTourIdentity(session.tour, session.opened?.headSha) ?: return@LaunchedEffect
        if (session.promptedStaleTours.add(stale)) {
            tourStart = com.octaviantocan.pulls.ui.ai.TourSheetPage.Stale(stale)
            sheet = "tourmore"
        }
    }
    fun openAsk(forTour: Boolean, initial: String? = null, reference: AskReference? = null) {
        if (!frameOwner.canAsk || services.actions == null) return
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
    services.commands?.Effect(
        session, ::orderedFiles, diffPath, onGo = { path -> if (services.current()) { diffPath = path; push(PrView.Diff) } },
        onAnalyze = { if (services.current()) sheet = "analyze" }, onDiff = pages.last() == PrView.Diff,
        head = head, onOverview = pages.last() == PrView.Overview, onReviewFiles = { openDiff(null) },
        onAsk = { openAsk(forTour = false) },
        onReview = { verdict -> if (services.current()) { prStart = PrPage.Submit(verdict); sheet = "more" } },
    )

    // A link to a thing on this pull request (a comment, a file, the checks) goes there, once: when the page opens on it,
    // and when a link to it arrives while the page is already on screen. Back later finds the page where it was left.
    val github = services.overview.native::openOnGithub
    val reopened = overviewReopened
    val seenReopen = remember(session) { reopened }
    fun openResult(kind: String) {
        if (!services.current() || !aiPolicy.canReadResults) return
        when (kind) {
            "tour" -> switchTo(PrView.Tour)
            "critique" -> { toOverview(); sheet = "findings" }
            "analyze", "brief" -> { toOverview(); pane = 0; scope.launch { overviewScroll.animateScrollToItem(0) } }
        }
    }
    LaunchedEffect(session, initialAIResult) { initialAIResult?.let(::openResult) }
    var going by remember(session) { mutableStateOf(place?.takeIf { !it.done }) }
    LaunchedEffect(reopened) {
        val link = reopened?.takeIf { it !== seenReopen && it.target.serverTarget == session.target.serverTarget } ?: return@LaunchedEffect
        link.place?.let { going = it }
        link.aiResult?.let(::openResult)
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
                writing = tourRunning, updatedAt = services.nowMillis(), at = minOf(existing?.at ?: 0, stops),
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
                    // In the readable column on a wide window, over the code as well as the words, so it never moves (rule 207).
                    Box(Modifier.readableWidth()) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                            // Beside the list in an unfolded window the list is where Back would go, so it is left out (rule 210).
                            if (!LocalFirstBeside.current) FloatIcon(Lu.back, "Back", ::back, iconSize = 22.dp)
                            Spacer(Modifier.weight(1f))
                            SectionCapsule(
                                selected = view.ordinal, live = tourComing(session),
                                onOverview = ::toOverview, onChanges = if (frameOwner.canReadDiff) ::toChanges else null, onTour = if (frameOwner.canReadTour) ::openTour else null,
                                onCorner = if (services.actions == null) null else ({
                                    tourStart = com.octaviantocan.pulls.ui.ai.TourSheetPage.Main
                                    sheet = when (view) { PrView.Diff -> "tools"; PrView.Tour -> "tourmore"; else -> "more" }
                                }),
                                corner = if (view == PrView.Diff) CapsuleCorner.TextTools else CapsuleCorner.More,
                            )
                        }
                        // The Tour's dim for its focused Ask bar covers the header as well as the page ("the top bar stays
                        // bright", recording of 16 Oct 12:10, 14:15 to 14:26, N29); a tap on it lets go of the bar.
                        // The Overview's Ask bar dims it the same way (recording 26 Oct 2026, 07:22 to 07:27).
                        if (view == PrView.Tour || view == PrView.Overview) Box(Modifier.matchParentSize()) { SearchDim(askDim.first, askDim.second, keepWhileTyping = true) }
                    }
                    // The views sit left to right as the capsule shows them, so going to one moves the page that way (rule 105).
                    // Pulling down past the top of any view opens Commands, as on the Inbox (J30).
                    ReviewFramePull(services.commands, Modifier.weight(1f).fillMaxWidth()) {
                    BlurSwitch(view, Modifier.fillMaxSize(), order = { it.ordinal }, motion = viewMotion) { page ->
                        when (page) {
                            PrView.Overview -> Box(Modifier.fillMaxSize().readableWidth()) { PrOverviewScreen(
                                session, ui, top, data, head, overviewScroll, pane, { pane = it },
                                onDiff = { path -> if (path == null) toChanges() else openDiff(path) },
                                onAsk = { question -> openAsk(forTour = false, initial = question) },
                                onAskHistory = { chats = false },
                                onSheet = { name ->
                                    if (name == "stack") session.stack?.let { services.track("stack.open", arrayOf("size" to it.entries.size)) }
                                    sheet = name
                                },
                                onCite = { cite -> openSource(session, TourSource(cite.path, cite.line, "")) },
                                onOpenRepo = { owner, repo -> if (services.current()) nav.openRepo(owner, repo) },
                                askDraft = askDraft,
                                onAskDim = { focused, empty -> askDim = focused to empty },
                            ) }
                            // The code takes the whole width: turning the phone sideways is how a long line is read whole.
                            PrView.Diff -> PrDiffScreen(
                                session, ui, diffPath, commitLabel(session.selection, data?.overview), { diffPath = it },
                                onOverview = ::toOverview,
                                onFiles = { sheet = "files" },
                                onAsk = if (aiPolicy.canAsk && services.actions != null) ({ reference -> openAsk(forTour = false, reference = reference) }) else null,
                            )
                            PrView.Tour -> kept.SaveableStateProvider(PrView.Tour.name) { Box(Modifier.fillMaxSize().readableWidth()) {
                                PrTourScreen(
                                    session, head,
                                    resumeAt = remember { pull?.let { tours.get("${it.owner}/${it.repo}#${it.number}")?.resumeAt } ?: 0 },
                                    onReached = { stop, total, title -> recordReached(tours, pull, head, stop, total, title, tourRunning, services.nowMillis()) },
                                    onChanges = if (reviewPolicy.canReadDiff) ::toChanges else null,
                                    onOpenSource = { source -> openSource(session, source, inSheet = true) },
                                    onAsk = if (aiPolicy.canAsk && services.actions != null) ({ question -> openAsk(forTour = true, initial = question) }) else null,
                                    onOpenAsk = if (aiPolicy.canAsk && services.actions != null) ({ openAsk(forTour = true) }) else null,
                                    onAskHistory = if (aiPolicy.canAsk && services.actions != null) ({ chats = true }) else null,
                                    onAskDim = { focused, empty -> askDim = focused to empty },
                                    onProgress = if (aiPolicy.canTour && services.actions != null) ({ sheet = "tour" }) else null,
                                    onRegenerate = if (aiPolicy.canTour && services.actions != null) ({
                                        if (session.tour == null) sheet = "tour"
                                        else {
                                            tourStart = com.octaviantocan.pulls.ui.ai.TourSheetPage.Update
                                            sheet = "tourmore"
                                        }
                                    }) else null,
                                ) }
                            }
                        }
                    }
                    }
                }
                // An AI review with the lens picked, from the Review sheet or from its page in the pull request's sheet.
                fun review(lens: String?) {
                    val actions = services.actions ?: return
                    frameOwner.critique(lens, { actions.critique(session, it) }) { running ->
                        toaster.show(if (running) "Already reviewing ${head.label}" else "Reviewing ${head.label}")
                    }
                }
                // Sheets live outside the pages so they survive a page change and a scrolled-away opener.
                // A place the Tour opened: that file's diff alone, on a sheet over the Tour (L28), or a file the change does
                // not touch, read at the head commit in the same code view (N20).
                session.sourceSheet?.let { place ->
                    if (session.files.any { it.path == place.path }) FileDiffSheet(session, ui, place, onClose = { session.sourceSheet = null })
                    else FileViewSheet(session, ui, place, onClose = { session.sourceSheet = null })
                }
                when (val name = sheet) {
                    "checks" -> data?.overview?.let { ChecksSheet(it, linked = session.linkedCheck) { sheet = null; session.linkedCheck = null } }
                    "commits" -> data?.overview?.let { CommitsSheet(session, ui, it, onOpen = ::toChanges) { sheet = null } }
                    "files" -> FilesSheet(session, ui, current = diffPath.takeIf { pages.last() == PrView.Diff }, onOpen = { path -> openDiff(path) }) { sheet = null }
                    "stack" -> session.stack?.let { stack -> StackSheet(stack, onOpen = { number -> if (services.current()) session.pull?.let { nav.openFrom(OpenTarget.Pull(it.owner, it.repo, number, "github.com"), "stack", false) } }) { sheet = null } }
                    else -> if (name?.startsWith(FILE_SHEET) == true) {
                        val path = name.removePrefix(FILE_SHEET)
                        session.files.firstOrNull { it.path == path }?.let { file ->
                            FileActionsSheet(session, file, onOpen = { openDiff(path) }, onAsk = {
                                openAsk(forTour = false, reference = AskReference("the file ${path.substringAfterLast('/')}", path))
                            }) { sheet = null }
                        }
                    } else if (name != null) services.actions?.Sheet(session, ui, top, data, head, ReviewFrameSheet(
                        name, prStart, tourStart,
                        orderedFiles().let { files -> files.firstOrNull { it.path == diffPath } ?: files.firstOrNull() },
                        onClose = { sheet = null; prStart = PrPage.Actions }, onAsk = { openAsk(forTour = true) },
                        onChanges = ::toChanges, onPrActions = { sheet = "more" }, onTour = { if (frameOwner.canReadTour) switchTo(PrView.Tour) }, onReview = ::review,
                    ))
                }
                chats?.let { forTour -> services.actions?.History(session, onClose = { chats = null }) { openAsk(forTour) } }
                ask?.let { request ->
                    // A mention leaves the original Ask draft/scroll behind the same shared page, and Back restores it.
                    services.actions?.Ask(session, request, onOpen = { path, line ->
                        if (!services.current()) return@Ask
                        if (session.opened == null || session.files.any { it.path == path }) {
                            ask = null
                            askBehind = AskBehind(request.copy(initial = null, reference = null, returning = true), over = pages.last() == PrView.Diff)
                        }
                        openSource(session, TourSource(path, line, ""))
                    }) { ask = null }
                }
                services.actions?.Submit(session, top, canVerdict = head.isOpen)
            }
        }
    }
}

/** A page with only a back button over [content]: while the pull request loads or when it cannot. */
@Composable
private fun Placeholder(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().readableWidth().statusBarsPadding()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) { if (!LocalFirstBeside.current) FloatIcon(Lu.back, "Back", onBack, iconSize = 22.dp) }
        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** Remembers how far into the tour the reader has been, never going back. */
private fun recordReached(tours: TourIndex, pull: OpenTarget.Pull?, head: PrHead, stop: Int, total: Int, title: String, writing: Boolean, nowMillis: Long) {
    if (pull == null) return
    val key = "${pull.owner}/${pull.repo}#${pull.number}"
    val existing = tours.get(key)
    if (existing != null && existing.reached >= stop && existing.at == stop && existing.stops == total && existing.writing == writing) return
    tours.upsert(
        TourEntry(
            pull.owner, pull.repo, pull.number, head.title, total,
            reached = maxOf(existing?.reached ?: 0, stop),
            stopTitle = if (existing == null || stop > existing.reached) title else existing.stopTitle,
            writing = writing, updatedAt = nowMillis, at = stop,
        ),
    )
}

@Composable
private fun ReviewFramePull(commands: ReviewFrameCommands?, modifier: Modifier, content: @Composable () -> Unit) {
    if (commands == null) Box(modifier) { content() } else commands.Pull(modifier, content)
}
