package com.octaviantocan.pulls.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.FileLinks
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.TourSource
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.ai.AnalyzeSheet
import com.octaviantocan.pulls.ui.ai.AskSheet
import com.octaviantocan.pulls.ui.ai.LensPickerSheet
import com.octaviantocan.pulls.ui.ai.runCritique
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.EmptyState
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.TourEntry
import com.octaviantocan.pulls.ui.home.TourIndex
import com.octaviantocan.pulls.ui.home.deviceStore
import kotlinx.coroutines.delay

/** What the Ask sheet opens with: its title, the starter chips and a question to send at once. */
private data class AskRequest(val title: String, val suggestions: List<String>, val initial: String?)

private val PR_SUGGESTIONS = listOf("What changed, in a sentence?", "Anything risky?", "What do the tests cover?")
private val TOUR_SUGGESTIONS = listOf("Why was this change made?", "What should I check by hand?", "What is the riskiest part?")

/**
 * One review, as three pages stacked like the prototype's screens: the overview, one file's diff and
 * the tour. This owns the session and the sheets over them; the pages draw. Back pops a page, and
 * from the first page leaves the review. A citation anywhere (a finding, a tour source, the palette)
 * asks for a [Focus], and this opens the diff on it.
 */
@Composable
fun ReviewScreen(target: OpenTarget, nav: Nav, startOnTour: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val c = LocalPullsColors.current
    val session = remember(target) {
        ReviewSession(target, Repo(context), scope, AppSettings.get(context)).also { it.load() }
    }
    DisposableEffect(session) { onDispose { session.detach() } }
    // The title and number the phone already knows, for the page to show while the rest loads.
    val preview = remember(target) { pullPreview(context, target) }
    val ui = rememberDiffUi(session)
    val top = remember(session) { ReviewTopUi() }
    val data = remember(session.overview) { parsePrOverviewData(session.overview) }
    val tours = remember { TourIndex(deviceStore(context, "pulls-inbox")) }
    val pages = remember(session) { mutableStateListOf(if (startOnTour) PrView.Tour else PrView.Overview) }
    val overviewScroll = rememberLazyListState()
    var pane by rememberSaveable { mutableIntStateOf(0) }
    var diffPath by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf<AskRequest?>(null) }
    val opened = session.opened
    val head = prHead(session, data)

    SideEffect {
        ui.uncategorizedLabel = messages.t("group.uncategorized")
        ui.uncategorizedSummary = messages.t("group.uncategorizedSummary")
    }
    OverviewEffects(session, top, data)
    BackHandler(enabled = pages.size > 1) { pages.removeAt(pages.lastIndex) }

    fun orderedFiles() = fileSections(session, ui).flatMap { it.files }
    fun push(page: PrView) { if (pages.last() != page) pages.add(page) }
    fun back() { if (pages.size > 1) pages.removeAt(pages.lastIndex) else nav.back() }
    // The tour can be the first page (opened from the Tours tab), so the overview may not be below it.
    fun toOverview() {
        if (PrView.Overview !in pages) pages.add(0, PrView.Overview)
        while (pages.last() != PrView.Overview) pages.removeAt(pages.lastIndex)
    }
    // The capsule moves between sibling pages (overview, changes, tour) without stacking them, so Back still leaves one page at a time.
    fun switchTo(page: PrView) {
        val here = pages.last()
        if (here == page) return
        if (page == PrView.Overview) { toOverview(); return }
        pages.removeAll { it == page }
        if (here == PrView.Overview) pages.add(page) else pages[pages.lastIndex] = page
    }
    fun openDiff(path: String?) {
        val files = orderedFiles()
        diffPath = path ?: files.firstOrNull { it.sha !in session.reviewed }?.path ?: files.firstOrNull()?.path
        push(PrView.Diff)
    }
    fun openAsk(forTour: Boolean, initial: String? = null) {
        ask = AskRequest(
            if (forTour) "Ask about this tour" else "Ask about ${head?.label ?: "this pull request"}",
            if (forTour) TOUR_SUGGESTIONS else PR_SUGGESTIONS,
            initial,
        )
    }

    PrCommands(session, orderedFiles(), diffPath, onGo = { path -> diffPath = path; push(PrView.Diff) }, onAnalyze = { com.octaviantocan.pulls.ui.ai.runAnalyzeFromCommand(session, context) })

    // A citation or a finding asked for a line: open the diff on its file (the diff screen then scrolls to the line).
    val focus = session.focus
    LaunchedEffect(focus) {
        val wanted = focus ?: return@LaunchedEffect
        if (session.files.none { it.path == wanted.path }) {
            toaster.show("${wanted.path.substringAfterLast('/')} is not part of this change")
            session.focus = null
            return@LaunchedEffect
        }
        diffPath = wanted.path
        push(PrView.Diff)
    }
    // A refresh or a commit range failed while a diff is already on screen: the full-page error only shows before one is.
    val loadError = session.loadError
    LaunchedEffect(loadError) {
        if (loadError != null && session.opened != null) toaster.show("Couldn't update: $loadError")
    }
    // GitHub refused an action (close, ready, draft…) while no sheet was there to say so.
    LaunchedEffect(top.actionError) {
        val error = top.actionError ?: return@LaunchedEffect
        if (!top.mergeOpen) {
            toaster.show(messages.t("overview.actions.failed", "message" to error))
            top.actionError = null
        }
    }
    // The Tours tab lists a tour once it exists or is being written.
    val tour = session.saved?.tour
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

    Box(Modifier.fillMaxSize().background(c.base)) {
        when {
            opened == null && session.loadError != null -> Placeholder(onBack = nav.back) {
                EmptyState("warning-duotone", messages.t("pr.loadFailed"), hint = session.loadError) {
                    ActionButton({ session.refresh() }, variant = ButtonVariant.Primary) { Text(messages.t("common.retry")) }
                }
            }
            // No loading screen: the page's shape with what the phone knows, and bars for the rest.
            opened == null -> PrOpening(target, preview, onBack = nav.back)
            opened != null && head != null -> {
                BlurSwitch(pages.last()) { page ->
                    when (page) {
                        PrView.Overview -> PrOverviewScreen(
                            session, ui, top, data, head, overviewScroll, pane, { pane = it },
                            onBack = ::back,
                            onDiff = { path -> if (path == null && diffPath != null) switchTo(PrView.Diff) else openDiff(path) },
                            onTour = { switchTo(PrView.Tour) },
                            onAsk = { openAsk(forTour = false) },
                            onSheet = { sheet = it },
                            onCite = { cite -> session.focus = Focus(cite.path, cite.line) },
                            onOpenRepo = nav.openRepo,
                        )
                        PrView.Diff -> PrDiffScreen(
                            session, ui, diffPath, { diffPath = it },
                            onBack = ::back,
                            tourLive = tourRunning,
                            onOverview = ::toOverview,
                            onTour = { switchTo(PrView.Tour) },
                            onMore = { sheet = "more" },
                        )
                        PrView.Tour -> PrTourScreen(
                            session, head,
                            resumeAt = remember { pull?.let { tours.get("${it.owner}/${it.repo}#${it.number}")?.resumeAt } ?: 0 },
                            onReached = { stop, total, title -> recordReached(tours, pull, head, stop, total, title, tourRunning) },
                            onBack = ::back,
                            onOverview = ::toOverview,
                            onChanges = { if (diffPath == null) openDiff(null) else switchTo(PrView.Diff) },
                            onMore = { sheet = "more" },
                            onOpenSource = { source -> openSource(session, source, uri::openUri) },
                            onAsk = { question -> openAsk(forTour = true, initial = question) },
                        )
                    }
                }
                // Sheets live outside the pages so they survive a page change and a scrolled-away opener.
                when (sheet) {
                    "more" -> MoreSheet(session, top, data, head, onClose = { sheet = null }, onSheet = { sheet = it })
                    "checks" -> data?.overview?.let { ChecksSheet(it) { sheet = null } }
                    "commits" -> data?.overview?.let { CommitsSheet(session, it) { sheet = null } }
                    "findings" -> FindingsSheet(session) { sheet = null }
                    "edit" -> data?.overview?.let { DescriptionEditSheet(session, it) { sheet = null } }
                    "analyze" -> AnalyzeSheet(session, onClose = { sheet = null }, onModel = { sheet = "model" })
                    "model" -> ModelSheet(session, onClose = { sheet = null }, onEffort = { sheet = "effort" })
                    "effort" -> EffortSheet(session, onBack = { sheet = "model" }, onClose = { sheet = null })
                    "lens" -> LensPickerSheet(session, onDismiss = { sheet = null }) { lens ->
                        sheet = null
                        if (session.taskFor("critique")?.running == true) {
                            toaster.show("Already reviewing ${head.label}")
                        } else {
                            session.runCritique(context, lens)
                            toaster.show("Reviewing ${head.label}")
                        }
                    }
                }
                ask?.let { request -> AskSheet(session, request.title, request.suggestions, request.initial) { ask = null } }
                ReviewProgressSheet(session, top)
                ReviewSubmitSheet(session, top, canVerdict = head.isOpen)
                if (data != null && data.mergeability.allowed) MergeSheet(session, top, data)
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

/** A tour source: a line the pull request changed opens in the diff; a file it did not touch opens on GitHub. */
private fun openSource(session: ReviewSession, source: TourSource, openUri: (String) -> Unit) {
    if (session.files.any { it.path == source.path }) {
        session.focus = Focus(source.path, source.line)
        return
    }
    val repo = FileLinks.repoOf(session.target)
    val sha = session.opened?.headSha
    if (repo != null && !sha.isNullOrBlank()) runCatching { openUri(FileLinks.blob(repo.first, repo.second, sha, source.path) + if (source.line > 0) "#L${source.line}" else "") }
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
 * pending (`revalidateDelay`, at most 20 polls), and pull new commits in at once when the auto-refresh
 * setting is on and the PR head moved (the web's `checkStaleness`).
 */
@Composable
private fun OverviewEffects(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?) {
    val overview = data?.overview
    LaunchedEffect(overview, top.polls) {
        val wait = overview?.let(::overviewRevalidateDelayMs) ?: return@LaunchedEffect
        if (top.polls >= 20) return@LaunchedEffect
        delay(wait)
        session.reloadOverview()
        top.polls++
    }
    val stale = isStale(session, overview)
    LaunchedEffect(stale, session.settings.autoRefresh) {
        if (stale && session.settings.autoRefresh && !session.loading && session.selection == null) session.refresh()
    }
}
