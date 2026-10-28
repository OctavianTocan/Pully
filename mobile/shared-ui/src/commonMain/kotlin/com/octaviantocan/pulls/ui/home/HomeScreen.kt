package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.ui.design.sideScrollFade
import com.octaviantocan.pulls.sound.cueCombinedClickable
import kotlinx.coroutines.CoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.ExperimentalFoundationApi
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.NativeGestureFeedback
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.composed
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import com.octaviantocan.pulls.ui.design.fade
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.looksLikeQuestion
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.pullKey
import com.octaviantocan.pulls.core.pullWorkByKey
import com.octaviantocan.pulls.core.runningTaskId
import com.octaviantocan.pulls.data.JEV_RISK_WORDS
import com.octaviantocan.pulls.data.riskLevel
import com.octaviantocan.pulls.data.riskWords
import com.octaviantocan.pulls.data.PostedRow
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.SavedState
import com.octaviantocan.pulls.data.SyncTopics
import com.octaviantocan.pulls.data.followChanges
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.rememberSidePages
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.FieldHeight
import com.octaviantocan.pulls.ui.design.FieldText
import com.octaviantocan.pulls.ui.design.FootPill
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.IconSwap
import com.octaviantocan.pulls.sound.Emphasis
import androidx.compose.animation.animateColorAsState
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Toaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.OkDot
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.softBlur
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.topEdgeStrength
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.octaviantocan.pulls.core.parsePullKey

/** Keeps the tour in [state], the saved state of the server's pull request [key] (`github:owner/repo#n`), on the phone. */
private suspend fun keepTour(store: HomeTourCopies, key: String, state: SavedState) {
    val ref = parsePullKey(key) ?: return
    val json = state.tourJson ?: return
    store.put("${ref.owner}/${ref.repo}#${ref.number}", json)
}

private val CHIP_LABELS = mapOf("waiting" to "Waiting on you", "authored" to "Authored", "all" to "All")

/**
 * The home screen of the prototype: a floating header (settings, Inbox/Tours, profile), three chips,
 * your pull requests grouped by repository with swipe-to-review and swipe-to-archive, and a search
 * pill at the bottom. Tours lists the walkthroughs you opened or started on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav, ui: HomeUi, services: HomeScreenServices) {
    CompositionLocalProvider(LocalHomeScreenServices provides services, LocalNativeHomeActions provides services.native) {
        val guarded = remember(nav, services) { services.navigation(nav) }
        SourceOwnedScreen(services.data) { HomeScreenBody(guarded, ui) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenBody(nav: Nav, ui: HomeUi) {
    val services = LocalHomeScreenServices.current
    ui.bindSource(services.data)
    val c = LocalPullsColors.current
    val repo = services.repo
    val reviewSessions = services.reviewSessions
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val state = if (ui.showClosed) "closed" else "open"

    val home = services.home
    val archive = home.archive
    val tours = home.tours
    val layout = home.layout
    val sync = home.sync
    val observations = services.observations
    HomeReadEffect(observations, sync, state) { sync.select(state) }
    // Jev sorts every open row only while the Inbox is sorted by risk or grouped by kind, so its kinds come in then.
    val wantsJev = layout.sort.needsJev || layout.groupBy.needsJev
    HomeReadEffect(observations, sync, state, sync.fetchedAt, sync.jev, wantsJev) {
        sync.wantsJev = wantsJev
        sync.followJev()
    }
    // While the Inbox is in front the server says when its list changed, and refreshes it from GitHub meanwhile, so a
    // new or updated pull request shows without a reload (`sync-reads-what-changed`).
    if (services.capabilities.serverChanges) HomeReadEffect(observations, sync, repo) {
        repo.followChanges(inbox = true) { change -> if (change.touches(SyncTopics.INBOX)) sync.serverChanged() }
    }
    // Refreshing is quiet: no indicator. It runs on open and whenever the app comes back to the front.
    val reload: () -> Unit = { scope.launch { observations.read { sync.reload() } } }
    HomeLifecycle(onResume = {
        val last = sync.fetchedAt
        if (last != null && kotlin.time.Clock.System.now().toEpochMilliseconds() - last > 30_000) {
            services.effects.track("inbox.action", "action" to "refresh", "value" to "resume")
            reload()
        }
    })

    val now = rememberNow(60_000)
    val workTasks = if (services.capabilities.taskObservation) rememberWork(repo, observations) else emptyList()
    val work = remember(workTasks) { pullWorkByKey(workTasks) }
    // A tour the server started or finished while no page was open still gets its row, and the phone keeps it.
    val tourStore = services.tourCopies
    HomeReadEffect(observations, home, workTasks, services) {
        if (!services.capabilities.taskObservation || tourStore == null) return@HomeReadEffect
        tours.reconcile(workTasks, kotlin.time.Clock.System.now().toEpochMilliseconds()) { key ->
            try { repo.savedState(key).also { keepTour(tourStore, key, it) }.tour?.stops?.size } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
    // Every tour on the Tours list is kept on the phone, so it opens at once; one the phone lacks (listed before tours
    // were kept, or written on the web) is fetched here, one at a time, once per run.
    val tourAsked = remember(home) { HashSet<String>() }
    HomeReadEffect(observations, home, tours.entries, services) {
        if (!services.capabilities.taskObservation || tourStore == null) return@HomeReadEffect
        val missing = tours.entries.filter { !it.writing && it.stops > 0 && it.key !in tourAsked && !tourStore.contains(it.key) }
        for (entry in missing) {
            if (!services.data.current) break
            val key = "github:${entry.owner}/${entry.repo}#${entry.number}"
            try { keepTour(tourStore, key, repo.savedState(key)); tourAsked += entry.key } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { tourAsked += entry.key }
        }
    }
    var activityOpen by remember { mutableStateOf(false) }
    val everything = sync.pulls
    // A repository switched off in Profile is left out of the list and of the numbers on the chips.
    val settings = services.settings
    val hidden = settings.hiddenRepos
    // The chip last picked, on this phone or another signed in to the account, while the Inbox lists open pull requests (P1).
    LaunchedEffect(settings.inboxFilter) { if (!ui.showClosed) ui.filter = settings.inboxFilter }
    val pulls = remember(everything, hidden) { everything?.inShownRepos(hidden) }
    // A pull request opened from here shows its size at once (H29).
    LaunchedEffect(everything) { everything?.forEach { if (services.data.current) services.effects.size(it.owner, it.repo, it.number, it.additions, it.deletions, it.changedFiles) } }
    val needle = ui.search.trim().lowercase()
    // An archived pull request whose head commit moved since comes back, so only the still-current entries count.
    val archived = remember(pulls, archive.entries) { archive.active(pulls.orEmpty()) }
    val inbox = remember(pulls, ui.filter, archived, needle) { Triage.inbox(pulls.orEmpty(), ui.filter, archived, needle) }
    TrackHomeSearch(
        "home", needle,
        if (ui.tab == 0) inbox.pulls.size else tours.entries.count { "${it.owner}/${it.repo} ${it.prTitle} #${it.number}".lowercase().contains(needle) },
    )
    // Kind and Riskiest first need Jev; while the server has none the saved choice waits and the default stands in.
    val sort = layout.sortWhen(sync.jev)
    val groupBy = layout.groupByWhen(sync.jev)
    val sorted = remember(inbox.pulls, sort) { sort.apply(inbox.pulls) }
    val sections = remember(sorted, groupBy) { inboxSections(sorted, groupBy) }
    val rowRepos = remember(sorted, groupBy) { if (groupBy == InboxGrouping.Kind) repoLabels(sorted) else emptyMap() }
    val viewer = remember(everything) { Triage.viewerLogin(everything.orEmpty()) }
    // The diffs agents posted (plan 13): kept on the phone, asked for again on start, on return and on sign-in.
    val postedStore = services.data.posted
    HomeReadEffect(observations, services) { if (services.capabilities.postedDiffs) postedStore.refresh() }
    HomeLifecycle(onResume = { if (services.capabilities.postedDiffs) scope.launch { observations.read { postedStore.refresh() } } })
    val posted = remember(postedStore.rows, needle, services) { if (services.capabilities.postedDiffs) postedStore.rows.matching(needle) else emptyList() }
    var profileOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    // `useHomeNavigation.ts`: a "Go to" entry per pull request and repository. There is no Refresh: the Inbox refreshes itself.
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    com.octaviantocan.pulls.ui.UseCommands {
        val pullsGroup = messages.t("palette.group.pulls")
        val reposGroup = messages.t("palette.group.repos")
        val all = pulls.orEmpty()
        all.map { pull ->
            val ref = "${pull.owner}/${pull.repo}#${pull.number}"
            com.octaviantocan.pulls.ui.Command("jump.pull:$ref", messages.t("palette.goToPull", "ref" to ref, "title" to pull.title), pullsGroup) {
                nav.openTarget(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com"))
            }
        } +
            all.distinctBy { "${it.owner}/${it.repo}" }.map { pull ->
                com.octaviantocan.pulls.ui.Command("jump.repo:${pull.owner}/${pull.repo}", messages.t("palette.goToRepo", "repo" to "${pull.owner}/${pull.repo}"), reposGroup) {
                    nav.openRepo(pull.owner, pull.repo)
                }
            }
    }

    // The top of the Inbox starts loading once it has shown, so the pull requests he is most likely to open are there
    // when he taps them (O20).
    val warmTop = remember(sorted) { sorted.take(ReviewSession.WARM_TOP).map { OpenTarget.Pull(it.owner, it.repo, it.number, "github.com") } }
    HomeReadEffect(observations, warmTop, services) {
        if (!services.capabilities.reviewRoutes || reviewSessions == null || warmTop.isEmpty()) return@HomeReadEffect
        kotlinx.coroutines.delay(800)
        warmTop.forEach { if (services.data.current) reviewSessions.warm(repo, it) }
    }

    fun openPull(pull: PullRow) = nav.openFrom(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com"), if (needle.isEmpty()) "inbox" else "search", false)
    // A pasted link, or a number only one listed pull request has, opens straight from the search pill.
    val listed = remember(pulls, tours.entries) {
        pulls.orEmpty().map { ListedPull(it.owner, it.repo, it.number) } + tours.entries.map { ListedPull(it.owner, it.repo, it.number) }
    }
    val searchLink = remember(ui.search, listed) { HomeLinks.fromSearch(ui.search, listed) }
    fun openSearch() {
        when (val link = searchLink) {
            is HomeLink.Target -> {
                ui.search = ""
                link.place?.let { nav.openPlace(link.target, "search", it) } ?: nav.openFrom(link.target, "search", false)
            }
            is HomeLink.Repo -> { ui.search = ""; nav.openRepo(link.owner, link.repo) }
            null -> {}
        }
    }
    // A question typed in the search opens Ask over the whole inbox; Claude Code or Codex on the server answers it.
    val canAsk = services.capabilities.inboxAsk
    val inboxAsk = services.ask
    val openListed = remember(nav) { { pull: ListedPull -> nav.openTarget(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com")) } }
    fun askSearch() {
        if (!services.data.current) return
        if (!canAsk || inboxAsk == null) { services.unavailable(HomePreviewFeature.InboxAsk); return }
        val question = ui.search.trim()
        if (question.isEmpty()) return
        ui.search = ""
        ui.askOpen = true
        // A question still being answered keeps the new one in the field, to send once it is done.
        services.sendQuestion(question)
    }
    fun archivePull(pull: PullRow) {
        val key = Triage.key(pull)
        // Under All an archived row is still there, dimmed; swiping it again brings it back.
        if (key in archived) {
            archive.restore(key)
            toaster.show("Restored #${pull.number}")
            return
        }
        archive.archive(key, pull.headSha)
        toaster.show("Archived #${pull.number}", "Undo", Cue.Success) { archive.restore(key) }
    }

    // The pull request whose row is held: its actions are open (H17).
    var held by remember { mutableStateOf<PullRow?>(null) }
    var heldTour by remember { mutableStateOf<Pair<TourEntry, Boolean>?>(null) }
    var heldPosted by remember { mutableStateOf<PostedRow?>(null) }
    // The stack whose row was tapped: its layers are listed (rule 202).
    var stackOpen by remember { mutableStateOf<List<PullRow>?>(null) }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            // The Inbox and Tours sit side by side: a tab or a swipe moves both, and the chips and the activity strip belong
            // to the Inbox, so they go with it (H20, rule 105). The capsule's pill sits where the pages are (J15).
            val sides = rememberSidePages(ui.tab, 2, { ui.tab = it })
            InboxHeader(ui.tab, { ui.tab = it }, viewer, onSettings = nav.settings, onProfile = { profileOpen = true }, position = { sides.at })
            SidePages(sides, listOf(0, 1), Modifier.weight(1f).fillMaxWidth()) { tab ->
                if (tab == 0) Column(Modifier.fillMaxSize()) {
                    FilterChips(ui.filter, inbox.counts) { ui.filter = it; if (!ui.showClosed) settings.updateInboxFilter(it) }
                    ActivityStrip(workTasks, { activityOpen = true })
                    InboxList(
                        sections = sections, rowRepos = rowRepos, now = now, archived = archived, work = work,
                        loading = pulls == null && sync.error == null, failed = if (pulls == null) sync.error else null, more = sync.partial,
                        filter = ui.filter, searching = needle.isNotEmpty(),
                        sort = sort, groupBy = groupBy, folded = layout.folded, onFold = { section -> layout.toggle(section.key) },
                        onRetry = reload, onCommands = { services.commands() }, onSettings = services.navigation(nav).settings,
                        onOpen = ::openPull, onArchive = ::archivePull, onRepo = { group -> nav.openRepo(group.owner, group.repo) },
                        onHold = { held = it }, viewer = viewer,
                        onPress = services::warmReview,
                        posted = posted, onOpenPosted = { row -> nav.openFrom(OpenTarget.Posted(row.id), if (needle.isEmpty()) "inbox" else "search", false) },
                        onHoldPosted = { heldPosted = it },
                        onStack = { stackOpen = it },
                    )
                } else PullToCommands({ services.commands() }, Modifier.fillMaxSize().padding(top = 10.dp)) {
                    ToursList(
                        entries = tours.entries.filter { "${it.owner}/${it.repo}" !in hidden && (needle.isEmpty() || "${it.owner}/${it.repo} ${it.prTitle} #${it.number}".lowercase().contains(needle)) },
                        pulls = everything.orEmpty(),
                        tasks = workTasks,
                        onOpen = { entry -> nav.openFrom(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com"), if (needle.isEmpty()) "inbox" else "search", true) },
                        // From Tours a repository's heading opens its tours, not its pull requests (H26).
                        onRepo = { owner, name -> nav.openRepoTours(owner, name) },
                        folded = if (needle.isEmpty()) layout.folded else emptySet(),
                        onFold = if (needle.isEmpty()) layout::toggle else null,
                        onHold = { entry, stoppable -> heldTour = entry to stoppable },
                    )
                }
            }
        }
        SearchDim(searchFocused, ui.search.isEmpty())
        // `.search`: the list fades into the page under this pill (`edgeFade` on the lists), and nothing else sits behind it.
        // Sort sits beside the search under the thumb, so it never hides past the chips' edge (rule 114). Tours has no
        // order to choose, so the button leaves with the Inbox and the pill takes the row.
        SearchPill(
            "search", ui.search, { ui.search = it },
            if (ui.tab == 0) "Search or ask Pully" else "Search tours",
            openLabel = searchLink?.let { if (it is HomeLink.Repo) "Open ${it.owner}/${it.repo}" else "Open pull request" },
            onOpen = ::openSearch,
            modifier = Modifier.align(Alignment.BottomCenter),
            onFocus = { searchFocused = it },
            onAsk = if (ui.tab == 0 && canAsk) ::askSearch else null,
            // A closed Inbox chat is one tap away again (recording 26 Oct 2026, 06:43 to 06:58).
            onChat = if (ui.tab == 0 && canAsk && inboxAsk?.hasChat == true) ({ focusManager.clearFocus(); ui.askOpen = true }) else null,
            trailing = { SortButton(ui.tab == 0, sort, onClick = { sortOpen = true }) },
        )
        if (ui.askOpen && canAsk && inboxAsk != null) {
            inboxAsk.Content(listed, openListed, onClose = { ui.askOpen = false })
        }
        if (activityOpen) {
            ActivitySheet(workTasks, onOpen = { ref -> nav.openTarget(OpenTarget.Pull(ref.owner, ref.repo, ref.number, "github.com")) }, onClose = { activityOpen = false })
        }
        heldTour?.let { (entry, stoppable) ->
            TourActionsSheet(
                entry, stoppable,
                onOpen = { nav.openFrom(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com"), "inbox", true) },
                onDismiss = { dismissTour(entry, workTasks, tours, repo, scope, toaster, ready = { observations.ready }) { services.data.current } },
                onClose = { heldTour = null },
            )
        }
        held?.let { pull ->
            PullActionsSheet(
                pull, archived = Triage.key(pull) in archived,
                onTour = if (services.capabilities.startTour) ({ nav.openFrom(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com"), "inbox", true) }) else null,
                onRepo = { nav.openRepo(pull.owner, pull.repo) },
                onArchive = if (canArchive(pull, viewer, Triage.key(pull) in archived)) ({ archivePull(pull) }) else null,
                onClose = { held = null },
            )
        }
        stackOpen?.let { layers ->
            InboxStackSheet(
                layers, repo,
                onOpen = { bottom, number -> nav.openFrom(OpenTarget.Pull(bottom.owner, bottom.repo, number, "github.com"), if (needle.isEmpty()) "inbox" else "search", false) },
                onClose = { stackOpen = null },
            )
        }
        heldPosted?.let { row ->
            PostedActionsSheet(
                row,
                onOpen = { nav.openFrom(OpenTarget.Posted(row.id), "inbox", false) },
                onDelete = if (services.capabilities.deletePosted) ({
                    scope.launch {
                        try {
                            if (!services.deletePosted(row)) return@launch
                            toaster.show("Deleted ${row.title}", cue = Cue.Success)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            toaster.show(e.message ?: "Pully could not delete it", cue = Cue.Error)
                        }
                    }
                }) else null,
                onClose = { heldPosted = null },
            )
        }
        if (sortOpen) {
            SortSheet(sort, groupBy, jev = sync.jev, onPick = layout::updateSort, onGroup = layout::updateGroupBy, onClose = { sortOpen = false })
        }
        if (profileOpen) {
            ProfileSheet(
                viewer = viewer, showClosed = ui.showClosed,
                onShowClosed = { closed -> ui.showClosed = closed; ui.filter = if (closed) "all" else settings.inboxFilter },
                pulls = everything.orEmpty(), onClose = { profileOpen = false },
            )
        }
    }
}

/** `.hdr`: settings, the Inbox/Tours capsule, and the profile avatar, floating over the page. */
@Composable
fun InboxHeader(tab: Int, onTab: (Int) -> Unit, viewer: String?, onSettings: () -> Unit, onProfile: () -> Unit, position: (() -> Float)? = null, onRepositories: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onRepositories == null) FloatIcon(Lu.cog, "Settings", onSettings)
        else FloatIcon(Lu.list, t("personalBrowse.repositories"), onRepositories)
        CapsuleTabs(listOf("Inbox", "Tours"), tab, onTab, Modifier.weight(1f), position = position)
        Box(
            Modifier.size(44.dp).clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = "Profile", onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            if (viewer != null) GithubAvatar(viewer, size = 40.dp) else ProfileDot(size = 40.dp)
        }
    }
}

/** `.chips`: Waiting on you, Authored and All, each with the size of the list it would show. */
@Composable
fun FilterChips(filter: String, counts: Map<String, Int>, onSelect: (String) -> Unit) {
    // Chips that run past the edge dissolve into the page there, as every sideways row does (J14).
    val scroll = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).sideScrollFade(scroll).horizontalScroll(scroll).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Triage.inboxFilters.forEach { chip ->
            val label = CHIP_LABELS.getValue(chip)
            SoftChip(label, chip == filter, { onSelect(chip) }, modifier = Modifier.testTag("inbox.filter.$chip").semantics { contentDescription = label; stateDescription = "${counts[chip] ?: 0}"; selected = chip == filter }, count = "${counts[chip] ?: 0}")
        }
    }
}

/**
 * The Inbox's sort: a round button as tall as the search pill, beside it (rule 114). Its label names the order the
 * Inbox is in, and a tap offers the others. It is there while [shown], the Inbox, and makes room as it goes.
 */
@Composable
fun SortButton(shown: Boolean, sort: InboxSort, onClick: () -> Unit) {
    val motion = rememberMotionEnabled()
    AnimatedVisibility(
        shown,
        enter = if (!motion) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandHorizontally(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)),
        exit = if (!motion) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + shrinkHorizontally(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)),
    ) {
        FloatIcon(Lu.sort, "Sort, now ${sort.label}", onClick, Modifier.padding(start = 10.dp), size = FieldHeight, iconSize = 22.dp)
    }
}

/**
 * The ways the Inbox can be sorted, then what its headings are, the current ones ticked. A tap applies at once and
 * closes the sheet. Riskiest first and Kind are there only while the server sorts pull requests with Jev, and with Jev
 * off the grouping card would hold Repository alone, a choice of one, so it is left out (rule 1).
 */
@Composable
fun SortSheet(sort: InboxSort, groupBy: InboxGrouping, jev: Boolean, onPick: (InboxSort) -> Unit, onGroup: (InboxGrouping) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    PullsSheet(onClose, "Sort") {
        SortSheetBody(sort, groupBy, jev, onPick = { onPick(it); onClose() }, onGroup = { onGroup(it); onClose() })
    }
}

/** The Sort sheet's cards, apart from its window so a still can show them. */
@Composable
fun SortSheetBody(sort: InboxSort, groupBy: InboxGrouping, jev: Boolean, onPick: (InboxSort) -> Unit, onGroup: (InboxGrouping) -> Unit) {
    val c = LocalPullsColors.current
    SheetCard {
        InboxSort.offered(jev).forEachIndexed { index, option ->
            SheetRow(option.label, { onPick(option) }, first = index == 0, iconless = true) {
                if (option == sort) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
            }
        }
    }
    val groupings = InboxGrouping.offered(jev)
    if (groupings.size > 1) {
        Text("Group by", Modifier.padding(start = 16.dp, top = 20.dp), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.muted)
        SheetCard {
            groupings.forEachIndexed { index, option ->
                SheetRow(option.label, { onGroup(option) }, first = index == 0, iconless = true) {
                    if (option == groupBy) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxList(
    sections: List<InboxSection>,
    /** By `owner/repo`, the repository name a row shows before its author; empty while the headings are repositories. */
    rowRepos: Map<String, String>,
    now: Long,
    archived: Set<String>,
    work: Map<String, PullWork>,
    loading: Boolean,
    failed: String?,
    /** The rows so far of a first load: more are coming, so the list ends in skeleton rows and is never "empty". */
    more: Boolean = false,
    filter: String,
    searching: Boolean,
    sort: InboxSort,
    groupBy: InboxGrouping,
    folded: Set<String>,
    onFold: (InboxSection) -> Unit,
    onRetry: () -> Unit,
    onCommands: () -> Unit,
    onOpen: (PullRow) -> Unit,
    onArchive: (PullRow) -> Unit,
    onHold: (PullRow) -> Unit = {},
    onRepo: (Triage.RepoGroup) -> Unit,
    /** Who he is on GitHub: only his own rows archive (K20). */
    viewer: String? = null,
    /** A finger landed on a row, before it is a tap: its pull request starts loading (O20). */
    onPress: (PullRow) -> Unit = {},
    /** The diffs agents posted to the account (plan 13): a group of their own, first, in its own colour. */
    posted: List<PostedRow> = emptyList(),
    onOpenPosted: (PostedRow) -> Unit = {},
    onHoldPosted: (PostedRow) -> Unit = {},
    /** A stack's row was tapped or held: its layers, bottom first, for the Stack sheet (rule 202). */
    onStack: (List<PullRow>) -> Unit = {},
    onSettings: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
    // A section's rows, each stack's layers folded into one (rule 202).
    val rowsOf = remember(sections) { sections.associate { it.key to inboxItems(it.pulls) } }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A search shows every heading it found something in, folded or not (rule 101).
    fun isFolded(section: InboxSection) = !searching && section.key in folded
    // Jev's read shows on a row only while he sorted by risk or grouped by kind, the only times Jev is asked (M1).
    val jevAsked = sort.needsJev || groupBy.needsJev
    val sectionOfRow = remember(sections, posted) {
        posted.associate { postedKey(it) to POSTED_SECTION } + sections.flatMap { section -> rowsOf[section.key].orEmpty().map { it.key to section.key } }.toMap()
    }
    val postedShut = !searching && POSTED_SECTION in folded
    val postedLabel = "Posted by agents"
    // A new sort or grouping starts the list from its top, where the order begins.
    var laidOut by remember { mutableStateOf(sort to groupBy) }
    LaunchedEffect(sort, groupBy) {
        if (sort to groupBy != laidOut) state.scrollToItem(0)
        laidOut = sort to groupBy
    }
    fun open(section: InboxSection): (() -> Unit)? = section.repo?.let { repo -> { onRepo(repo) } }
    fun fold(section: InboxSection) {
        var at = if (posted.isEmpty() || section.key == POSTED_SECTION) 0 else 1 + if (postedShut) 0 else posted.size
        for (each in sections) {
            if (each.key == section.key) break
            at += 1 + if (isFolded(each)) 0 else rowsOf[each.key].orEmpty().size
        }
        onFold(section)
        // Folded from its pinned heading, the repository's rows leave from under it: bring its heading back to the top.
        if (at <= state.firstVisibleItemIndex) scope.launch { state.scrollToItem(at) }
    }
    // Pulling down past the top opens Commands; the list itself refreshes quietly.
    PullToCommands(onCommands, Modifier.fillMaxSize()) {
        PinnedHeadings(
            state,
            groupOf = { key -> sectionOfRow[key] },
            heading = { key, modifier ->
                if (key == POSTED_SECTION) {
                    GroupHeading(postedLabel, posted.size, first = true, folded = postedShut, onFold = if (searching) null else ({ fold(InboxSection(POSTED_SECTION, postedLabel, emptyList())) }), onOpen = null, modifier = modifier, line = true, tint = c.accentMagenta)
                }
                sections.firstOrNull { it.key == key }?.let { section ->
                    // The first heading keeps its own 6dp top when it pins, so it does not jump as it sticks (H13).
                    GroupHeading(section.label, section.pulls.size, first = posted.isEmpty() && sections.firstOrNull()?.key == key, folded = isFolded(section), onFold = if (searching) null else ({ fold(section) }), onOpen = open(section), modifier = modifier, line = true)
                }
            },
        ) { fade ->
        LazyColumn(
            Modifier.fillMaxSize().then(fade),
            state = state,
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            // What agents posted comes first, under a heading in its own colour: one group, no chip (Tavi, 16 Oct 12:47).
            // It is on the server, not GitHub, so it shows while the pull requests load or when they fail to.
            if (posted.isNotEmpty()) {
                item(key = groupKey(POSTED_SECTION)) {
                    GroupHeading(
                        postedLabel, posted.size, first = true, folded = postedShut,
                        onFold = if (searching) null else ({ fold(InboxSection(POSTED_SECTION, postedLabel, emptyList())) }), onOpen = null,
                        modifier = Modifier.animateItem(), tint = c.accentMagenta,
                    )
                }
                if (!postedShut) items(posted, key = { postedKey(it) }) { row ->
                    SwipeRow(
                        label = row.title, onOpen = { onOpenPosted(row) }, onArchive = null, archived = false,
                        modifier = Modifier.animateItem(), onLongClick = { onHoldPosted(row) },
                    ) { PostedRowContent(row, now) }
                }
            }
            when {
                loading || (more && sections.isEmpty() && posted.isEmpty()) -> item(key = "loading") { RowsSkeleton(Modifier.padding(top = 24.dp)) }
                failed != null -> item(key = "failed") {
                    ListNote(t("local.home.failed"), hint = failed) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                            FloatIcon(Lu.redo, "Load the list again", onRetry, flat = true, iconSize = 22.dp, tint = c.muted)
                            onSettings?.let { PillButton(t("common.settings"), it) }
                        }
                    }
                }
                sections.isEmpty() && posted.isEmpty() -> item(key = "empty") {
                    val title = when {
                        searching -> "Nothing matches"
                        filter == "waiting" -> "Nothing is waiting on you"
                        filter == "authored" -> "You have no open pull requests"
                        else -> t("local.home.empty")
                    }
                    ListNote(title, hint = if (!searching && filter == "all") t("connection.emptyInbox") else null)
                }
                else -> {
                    sections.forEachIndexed { index, section ->
                        val shut = isFolded(section)
                        item(key = groupKey(section.key)) {
                            GroupHeading(
                                section.label, section.pulls.size, first = index == 0 && posted.isEmpty(), folded = shut,
                                onFold = if (searching) null else ({ fold(section) }), onOpen = open(section), modifier = Modifier.animateItem(),
                            )
                        }
                        if (!shut) items(rowsOf[section.key].orEmpty(), key = { it.key }) { item ->
                            when (item) {
                                is InboxItem.Stacked -> SwipeRow(
                                    label = "Stack of ${item.layers.size}, #${item.bottom.number} ${item.bottom.title}",
                                    // The stack's row opens its list of layers, by a tap, a swipe or a hold alike (rule 202).
                                    onOpen = { onStack(item.layers) },
                                    onArchive = null,
                                    archived = false,
                                    modifier = Modifier.animateItem(),
                                    onLongClick = { onStack(item.layers) },
                                ) { StackRowContent(item.layers, now, repo = rowRepos["${item.bottom.owner}/${item.bottom.repo}"]) }
                                is InboxItem.Single -> {
                                    val pull = item.pull
                                    val key = Triage.key(pull)
                                    SwipeRow(
                                        label = "#${pull.number} ${pull.title}",
                                        onOpen = { onOpen(pull) },
                                        // No Archive swipe: a left swipe always goes on to Tours, and Archive is in the hold
                                        // actions (recording 16 Oct 09:50, 01:18 to 02:04; M4).
                                        onArchive = null,
                                        archived = key in archived,
                                        modifier = Modifier.animateItem(),
                                        onLongClick = { onHold(pull) },
                                        onPress = { onPress(pull) },
                                    ) { PullRowContent(pull, now, dimmed = key in archived, work = work[pullKey(pull.owner, pull.repo, pull.number)], repo = rowRepos["${pull.owner}/${pull.repo}"], risk = jevAsked) }
                                }
                            }
                        }
                    }
                    // More rows are still coming in: a few skeleton rows under them say so (Tavi, 26 Oct 2026).
                    if (more) item(key = "more") { RowsSkeleton(Modifier.animateItem(), rows = 3) }
                }
            }
            footer?.let { content -> item(key = "footer") { content() } }
        }
        }
    }
}

/**
 * Whether a row offers Archive: only on a pull request he wrote, since one of someone else's is theirs to deal with
 * (K20). One already archived can always be restored. With no known [viewer] nothing is his, so nothing archives.
 */
fun canArchive(pull: PullRow, viewer: String?, archived: Boolean): Boolean =
    archived || (viewer != null && pull.author.equals(viewer, ignoreCase = true))

/**
 * `.sw`: a row that follows the finger. Past 110dp to the right it opens the pull request; to the left it runs
 * [onArchive] when there is one. Short of that it springs back. Tapping opens, and both swipes are also offered to
 * screen readers as actions. A file row (rule 56) swipes right to open it, as this row does, and left to mark it
 * reviewed, so it passes its own labels, icons and [onLongClick] for its actions.
 *
 * [opensLeft] puts the open on a swipe to the left instead, for a page whose neighbour is on its left (Tours): the
 * swipe right is then left to the pages, so it goes back to the Inbox (recording 16 Oct 09:50, 01:18 to 02:04; M4).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeRow(
    label: String,
    onOpen: () -> Unit,
    onArchive: (() -> Unit)?,
    archived: Boolean,
    modifier: Modifier = Modifier,
    startLabel: String = "Review",
    endLabel: String = if (archived) "Restore" else "Archive",
    endIcon: LuGlyph = Lu.arch,
    startIcon: LuGlyph = Lu.arrow,
    onClick: () -> Unit = onOpen,
    onLongClick: (() -> Unit)? = null,
    opensLeft: Boolean = false,
    /** A finger landed on the row; it may still become a tap, a hold, a swipe or a scroll. */
    onPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val c = LocalPullsColors.current
    val density = LocalDensity.current
    var dx by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    val threshold = with(density) { 110.dp.toPx() }
    val ramp = with(density) { 60.dp.toPx() }
    val drag = rememberDraggableState { delta ->
        val armed = abs(dx) > threshold
        // With no [onArchive] the row only goes the way it opens: there is nothing on the other side to reveal (K20).
        dx = when {
            opensLeft -> (dx + delta).coerceIn(-width, 0f)
            onArchive == null -> (dx + delta).coerceIn(0f, width)
            else -> (dx + delta).coerceIn(-width, width)
        }
        // Felt the moment letting go would act, and again if the finger takes it back (H18).
        if (armed != abs(dx) > threshold) ControlFeedback.gesture(NativeGestureFeedback.Threshold)
    }
    val ink = c.shadow
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics { customActions = listOfNotNull(CustomAccessibilityAction(startLabel) { onOpen(); true }, onArchive?.let { CustomAccessibilityAction(endLabel) { it(); true } }) },
    ) {
        if (dx > 0f) {
            Row(
                Modifier.matchParentSize().background(c.primary).fade((dx / ramp).coerceIn(0f, 1f)).padding(start = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ink = Color.White // raw-value-ok: ink on the primary fill is white in both themes, like the send button
                LuIcon(startIcon, tint = ink, size = 20.dp)
                Text(startLabel, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = ink)
            }
        }
        if (dx < 0f && opensLeft) {
            Row(
                Modifier.matchParentSize().background(c.primary).fade((-dx / ramp).coerceIn(0f, 1f)).padding(end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                val ink = Color.White // raw-value-ok: ink on the primary fill is white in both themes, like the send button
                Text(startLabel, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = ink)
                LuIcon(startIcon, Modifier.graphicsLayer { scaleX = -1f }, tint = ink, size = 20.dp)
            }
        }
        if (dx < 0f && !opensLeft) {
            Row(
                Modifier.matchParentSize().background(c.cta).fade((-dx / ramp).coerceIn(0f, 1f)).padding(end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Text(endLabel, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.onCta)
                LuIcon(endIcon, tint = c.onCta, size = 22.dp)
            }
        }
        Box(
            Modifier
                .offset { IntOffset(dx.roundToInt(), 0) }
                // The row is displaced over the panel, so it blurs as it goes (rules 17 and 46) and casts a soft edge.
                .softBlur(SWIPE_BLUR * (abs(dx) / ramp).coerceIn(0f, 1f))
                .drawBehind {
                    val edge = 18.dp.toPx()
                    val strength = (abs(dx) / ramp).coerceIn(0f, 1f)
                    if (dx > 0f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = 0.14f * strength)), startX = -edge, endX = 0f), Offset(-edge, 0f), Size(edge, size.height))
                    if (dx < 0f) drawRect(Brush.horizontalGradient(listOf(ink.copy(alpha = 0.16f * strength), Color.Transparent), startX = size.width, endX = size.width + edge), Offset(size.width, 0f), Size(edge, size.height))
                }
                .background(c.base)
                .then(
                    if (onPress == null) Modifier
                    else Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); onPress() } },
                )
                // The tap is outside the swipe, so a drag the swipe has taken is never also a tap: a short sideways drag
                // used to open the file and leave its row pulled aside, which then slid back on return ("this plays an
                // animation for no reason when I come back", recording 15 Oct 15:37, 09:28 to 09:44; L16).
                .then(
                    // The hold is felt through the app's own vibration, which Settings can turn off (H18).
                    if (onLongClick != null) Modifier.cueCombinedClickable(role = Role.Button, onClickLabel = label, onLongClickLabel = "More actions", onLongClick = onLongClick, onClick = onClick)
                    else Modifier.cueClickable(role = Role.Button, onClickLabel = label, onClick = onClick),
                )
                .rowSwipe(
                    // A row with nothing on its left leaves a left swipe to the pages beside it, so the Inbox goes on
                    // to Tours from anywhere, a pull request row too (recording 15 Oct 15:37, 01:28 to 01:50; L1).
                    leftFree = onArchive == null && !opensLeft,
                    rightFree = opensLeft,
                    offset = { dx },
                    onDelta = { delta -> drag.dispatchRawDelta(delta) },
                    onStop = {
                        val end = dx
                        when {
                            opensLeft && end < -threshold -> {
                                animate(end, with(density) { -72.dp.toPx() }, animationSpec = tween(120)) { value, _ -> dx = value }
                                onOpen()
                                animate(dx, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                            }
                            end > threshold -> {
                                animate(end, with(density) { 72.dp.toPx() }, animationSpec = tween(120)) { value, _ -> dx = value }
                                onOpen()
                                animate(dx, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                            }
                            end < -threshold -> {
                                animate(end, -width, animationSpec = tween(200)) { value, _ -> dx = value }
                                onArchive?.invoke()
                                // Under All the row stays (dimmed): bring it back.
                                animate(dx, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                            }
                            else -> animate(end, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                        }
                    },
                ),
        ) { content() }
    }
}

/**
 * A row's sideways drag: it takes the gesture once the finger passes the touch slop sideways and feeds [onDelta] until
 * it lifts, then runs [onStop]. With [leftFree], a drag that starts to the left while the row is at rest ([offset] 0)
 * is not taken, so the pages around the list get it instead; [rightFree] does the same for a drag to the right.
 */
private fun Modifier.rowSwipe(leftFree: Boolean, rightFree: Boolean = false, offset: () -> Float, onDelta: (Float) -> Unit, onStop: suspend CoroutineScope.() -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    val stop by rememberUpdatedState(onStop)
    val delta by rememberUpdatedState(onDelta)
    pointerInput(leftFree, rightFree) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var taken = false
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                if (leftFree && over < 0f && offset() <= 0f) return@awaitHorizontalTouchSlopOrCancellation
                if (rightFree && over > 0f && offset() >= 0f) return@awaitHorizontalTouchSlopOrCancellation
                change.consume()
                taken = true
                delta(over)
            }
            if (start == null || !taken) return@awaitEachGesture
            horizontalDrag(start.id) { change ->
                delta(change.positionChange().x)
                change.consume()
            }
            scope.launch { stop() }
        }
    }
}

/** How much of the scrim dims the Inbox while the search waits for a letter: about half a sheet's. */
private const val SEARCH_DIM = 0.5f

/** The most a swiped row blurs: Row W's 1.3px, a little stronger so it reads on a phone. */
private val SWIPE_BLUR = 1.3.dp

/**
 * While a pill's field waits for its first letter the page behind it dims a little, and a tap on it puts the keyboard
 * away; once something is typed the page is what the field is for, so it is shown at full strength and takes taps.
 * The dim starts with the tap, not when the keyboard is up, and comes in quickly; it goes a little slower than it came,
 * as the pill settles back down (H12). Putting the keyboard away with Back lifts it too. Home's search and the Tour's
 * Ask share it (K12); draw it under the pill. An Ask bar passes [keepWhileTyping]: what is typed there is a question,
 * not a filter, so the page stays dimmed until the bar lets go (recording 26 Oct 2026, 07:15 to 07:30).
 */
@Composable
fun SearchDim(focused: Boolean, empty: Boolean, keepWhileTyping: Boolean = false) {
    val c = LocalPullsColors.current
    val focusManager = LocalFocusManager.current
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeShown by remember { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused) imeShown = false }
    SideEffect { if (focused && imeUp) imeShown = true }
    val waiting = focused && (empty || keepWhileTyping) && (imeUp || !imeShown)
    val dim by animateFloatAsState(
        if (waiting) 1f else 0f,
        if (waiting) tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut) else tween(PullsMotion.MEDIUM, easing = PullsMotion.InOut),
        label = "search-dim",
    )
    if (dim > 0f) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = dim * SEARCH_DIM }.background(c.scrim)
                .then(if (waiting) Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } } else Modifier),
        )
    }
}

/**
 * `.search`: Home's pill, the kit's one field in its page frame (N6). Typing filters the list at once. When the text is a
 * link or a number that names one pull request, an Open pill appears in it and the keyboard's action opens it, so no
 * separate field is needed. There is no Ask button (no "Ask" button "anywhere": recording 16 Oct 12:10, 05:03 to 05:47;
 * N8): with [onAsk], once the text ends in a question mark the ✕ turns into the send circle, which asks it, the one
 * fading into the other, and the keyboard's action asks whatever reads as a question. It is the app's one foot pill
 * ([FootPill]): Inbox and Tours share its [key], so only its words change between them, and [trailing] sits beside it (Q2).
 */
@Composable
fun SearchPill(
    key: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    openLabel: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    onAsk: (() -> Unit)? = null,
    /** With nothing typed, a chat button that opens the Inbox chat again. */
    onChat: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
    val askable = onAsk != null && openLabel == null && value.isNotBlank()
    val question = askable && looksLikeQuestion(value)
    val asking = askable && endsInQuestion(value)
    val ask = { focus.clearFocus(); onAsk?.invoke(); Unit }
    FootPill(key, modifier, search = true, button = value.isNotEmpty() || onChat != null, trailing = trailing) {
        FieldText(
            value, onChange, placeholder,
            Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            keyboardOptions = KeyboardOptions(imeAction = if (openLabel != null) ImeAction.Go else if (question) ImeAction.Send else ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }, onGo = { onOpen() }, onSend = { ask() }),
        )
        if (openLabel != null) {
            Box(
                Modifier.height(40.dp).clip(CircleShape).background(c.cta).cueClickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Open", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.onCta, maxLines = 1) }
        }
        if (value.isNotEmpty()) ClearOrSend(asking, onClear = { onChange("") }, onSend = ask)
        else if (onChat != null) FloatIcon(Lu.history, "Open your chat", onChat, size = 40.dp, iconSize = 20.dp, flat = true, tint = c.muted)
    }
}


/**
 * The button at the end of the search pill: ✕, which empties it, or, while [send], the primary send circle, which asks.
 * The circle's fill fades in or out as the glyph swaps (rule 73), so the ✕ turns into the send icon in place (N8).
 */
@Composable
private fun ClearOrSend(send: Boolean, onClear: () -> Unit, onSend: () -> Unit) {
    val c = LocalPullsColors.current
    val fill by animateColorAsState(if (send) c.primary else c.primary.copy(alpha = 0f), tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), label = "send-fill")
    FloatButton(if (send) onSend else onClear, if (send) "Ask Pully" else "Clear search", size = 40.dp, flat = true, emphasis = if (send) Emphasis.Normal else Emphasis.Subtle) {
        Box(Modifier.matchParentSize().background(fill))
        IconSwap(send) { on ->
            if (on) LuIcon(Lu.arrow, tint = Color.White, size = 20.dp) // raw-value-ok: the ink on the primary fill is white in both themes, as on SendButton
            else LuIcon(Lu.close, tint = c.muted, size = 18.dp)
        }
    }
}

/**
 * The Tours tab: the walkthroughs opened or started here, grouped by repository. A repository's heading opens it. A row
 * behaves like an Inbox row: tap or swipe right to open, swipe left to stop a tour that is being written or to remove
 * a finished one from the list.
 */
@Composable
fun ToursList(
    entries: List<TourEntry>,
    pulls: List<PullRow>,
    tasks: List<WorkTask>,
    onOpen: (TourEntry) -> Unit,
    onRepo: (owner: String, repo: String) -> Unit,
    folded: Set<String>,
    onFold: ((String) -> Unit)?,
    onHold: (TourEntry, stoppable: Boolean) -> Unit,
) {
    val c = LocalPullsColors.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
    if (entries.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No tours yet", style = PullsType.lg.copy(fontWeight = FontWeight.Medium), color = c.text, textAlign = TextAlign.Center)
            Text(
                "Open a pull request and tap its Tour button. Tours you open or start show up here.",
                style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center,
            )
        }
        return
    }
    val now = rememberNow(60_000)
    val byKey = remember(pulls) { pulls.associateBy(Triage::key) }
    val groups = entries.groupBy { "${it.owner}/${it.repo}" }
    val labels = Triage.groupLabels(groups.map { (name, list) -> Triage.RepoGroup(name, list.first().owner, list.first().repo, emptyList()) })
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val groupOfRow = groups.flatMap { (name, list) -> list.map { it.key to name } }.toMap()
    // The same headings as the Inbox: pinned while their rows scroll, and a tap folds them (a fold is kept for both tabs).
    fun fold(name: String) {
        val fold = onFold ?: return
        var at = 0
        for ((each, list) in groups) {
            if (each == name) break
            at += 1 + if (each in folded) 0 else list.size
        }
        fold(name)
        if (at <= state.firstVisibleItemIndex) scope.launch { state.scrollToItem(at) }
    }
    @Composable
    fun Heading(name: String, first: Boolean, modifier: Modifier, pinned: Boolean = false) {
        val list = groups[name] ?: return
        GroupHeading(
            labels[name] ?: name, list.size, first = first, folded = name in folded,
            onFold = onFold?.let { { fold(name) } }, onOpen = { onRepo(list.first().owner, list.first().repo) }, modifier = modifier,
            line = pinned || name in folded,
        )
    }
    PinnedHeadings(state, groupOf = { key -> groupOfRow[key] }, heading = { name, modifier -> Heading(name, first = groups.keys.firstOrNull() == name, modifier, pinned = true) }) { fade ->
    LazyColumn(
        Modifier.fillMaxSize().then(fade),
        state = state,
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        groups.entries.forEachIndexed { index, (name, list) ->
            item(key = groupKey(name)) { Heading(name, first = index == 0, Modifier.animateItem()) }
            if (name !in folded) items(list, key = { it.key }) { entry ->
                val stoppable = runningTaskId(tasks, pullKey(entry.owner, entry.repo, entry.number), "tour") != null
                SwipeRow(
                    label = entry.prTitle,
                    onOpen = { onOpen(entry) },
                    // Tours sits right of the Inbox, so its rows open on a swipe to the left and a swipe right goes back
                    // to the Inbox. No Remove swipe; holding the row offers it (recording 16 Oct 09:50, 01:18 to 02:04; M4).
                    onArchive = null,
                    archived = false,
                    modifier = Modifier.animateItem(),
                    opensLeft = true,
                    onLongClick = { onHold(entry, stoppable) },
                ) { TourRowContent(entry, byKey[entry.key], now) }
            }
        }
    }
    }
}

/**
 * A swipe left on a tour: one still being written is stopped, a finished one leaves the list (it stays saved on the
 * server). Its tasks are recorded as dismissed, so the server's task list does not bring the row back.
 */
fun dismissTour(entry: TourEntry, workTasks: List<WorkTask>, tours: TourIndex, repo: Repo, scope: CoroutineScope, toaster: Toaster, ready: () -> Boolean = { true }, currentSource: () -> Boolean) {
    if (!currentSource() || !ready()) return
    val source = runCatching { repo.source() }.getOrNull() ?: return
    val serverKey = pullKey(entry.owner, entry.repo, entry.number)
    val running = runningTaskId(workTasks, serverKey, "tour")
    val taskIds = workTasks.filter { it.kind == "tour" && it.key == serverKey }.map { it.id }
    if (running != null) {
        scope.launch {
            if (!currentSource() || !repo.isCurrent(source) || !ready()) return@launch
            try {
                repo.cancelTask(running, bestEffort = false)
                if (!currentSource() || !repo.isCurrent(source)) return@launch
                // A tour stopped before it had a stop has nothing to list; one that had keeps its row.
                if (entry.stops > 0) tours.upsert(entry.copy(writing = false)) else tours.dismiss(entry.key, taskIds)
                toaster.show("Stopped the tour for #${entry.number}")
            } catch (failure: kotlinx.coroutines.CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (currentSource() && repo.isCurrent(source)) toaster.show(failure.message ?: "Pully could not stop the tour", cue = Cue.Error)
            }
        }
    } else {
        tours.dismiss(entry.key, taskIds)
        toaster.show("Removed the tour for #${entry.number}", "Undo") { if (currentSource()) tours.upsert(entry) }
    }
}


/** What a list says when it has nothing to show, centred where the rows would be, with no icon (rule 48). */
@Composable
private fun ListNote(title: String, hint: String? = null, action: (@Composable () -> Unit)? = null) {
    val c = LocalPullsColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 4.dp)) { action() }
    }
}
