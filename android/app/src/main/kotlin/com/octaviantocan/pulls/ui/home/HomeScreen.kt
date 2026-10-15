package com.octaviantocan.pulls.ui.home

import kotlinx.coroutines.CoroutineScope
import com.octaviantocan.pulls.data.PullSizes
import android.content.Context
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
import androidx.compose.foundation.combinedClickable
import com.octaviantocan.pulls.sound.Haptics
import com.octaviantocan.pulls.sound.cued
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.looksLikeQuestion
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.pullKey
import com.octaviantocan.pulls.core.pullWorkByKey
import com.octaviantocan.pulls.core.runningTaskId
import com.octaviantocan.pulls.ui.ai.cliEngine
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.SavedState
import com.octaviantocan.pulls.data.TourStore
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.FloatIcon
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.octaviantocan.pulls.core.parsePullKey

/** Keeps the last list per state on this device, like the web's `localStorage` copy. */
internal fun deviceStore(context: Context, name: String = "pulls-home"): KeyValueStore {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    return object : KeyValueStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }
}

/** Keeps the tour in [state], the saved state of the server's pull request [key] (`github:owner/repo#n`), on the phone. */
private suspend fun keepTour(store: TourStore, key: String, state: SavedState) {
    val ref = parsePullKey(key) ?: return
    val json = state.tourJson ?: return
    withContext(Dispatchers.IO) { store.put("${ref.owner}/${ref.repo}#${ref.number}", json) }
}

/** The inbox's tab, chip and search. They live above the screens so a review does not reset them. */
@Stable
class HomeUi {
    /** 0 Inbox, 1 Tours. */
    var tab by mutableIntStateOf(0)
    var filter by mutableStateOf("waiting")
    var showClosed by mutableStateOf(false)
    var search by mutableStateOf("")
    /** The Ask sheet over the inbox is open; it stays open across a pull request opened from it. */
    var askOpen by mutableStateOf(false)
}

private val CHIP_LABELS = mapOf("waiting" to "Waiting on you", "authored" to "Authored", "all" to "All")

/**
 * The home screen of the prototype: a floating header (settings, Inbox/Tours, profile), three chips,
 * your pull requests grouped by repository with swipe-to-review and swipe-to-archive, and a search
 * pill at the bottom. Tours lists the walkthroughs you opened or started on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav, ui: HomeUi) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val repo = remember { Repo(context) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val state = if (ui.showClosed) "closed" else "open"

    val storage = remember { deviceStore(context, "pulls-inbox") }
    val archive = remember { ArchiveStore(storage) }
    val tours = remember { TourIndex(storage) }
    val layout = remember { InboxLayout(storage) }
    val sync = remember {
        MyPullsSync(
            cached = { selected -> repo.myPullsCached(selected)?.asSnapshot() },
            fresh = { selected -> repo.myPulls(selected) },
            storage = deviceStore(context),
            peek = { selected -> repo.myPullsCached(selected, revalidate = false)?.asSnapshot() },
        )
    }
    LaunchedEffect(state) { sync.select(state) }
    // Jev sorts the list after the server answered it, so its kinds come in while the Inbox is open.
    LaunchedEffect(state, sync.fetchedAt, sync.jev) { sync.followJev() }
    // Refreshing is quiet: no indicator. It runs on open and whenever the app comes back to the front.
    val reload: () -> Unit = { scope.launch { sync.reload() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val last = sync.fetchedAt
        if (last != null && System.currentTimeMillis() - last > 30_000) reload()
    }

    val now = rememberNow(60_000)
    val workTasks = rememberWork(repo)
    val work = remember(workTasks) { pullWorkByKey(workTasks) }
    // A tour the server started or finished while no page was open still gets its row, and the phone keeps it.
    val tourStore = remember { TourStore.get(context) }
    LaunchedEffect(workTasks) {
        tours.reconcile(workTasks, System.currentTimeMillis()) { key ->
            try { repo.savedState(key).also { keepTour(tourStore, key, it) }.tour?.stops?.size } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
    // Every tour on the Tours list is kept on the phone, so it opens at once; one the phone lacks (listed before tours
    // were kept, or written on the web) is fetched here, one at a time, once per run.
    val tourAsked = remember { HashSet<String>() }
    LaunchedEffect(tours.entries) {
        val missing = tours.entries.filter { !it.writing && it.stops > 0 && it.key !in tourAsked && !withContext(Dispatchers.IO) { tourStore.has(it.key) } }
        for (entry in missing) {
            tourAsked += entry.key
            val key = "github:${entry.owner}/${entry.repo}#${entry.number}"
            try { keepTour(tourStore, key, repo.savedState(key)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
        }
    }
    var activityOpen by remember { mutableStateOf(false) }
    val everything = sync.pulls
    // A repository switched off in Profile is left out of the list and of the numbers on the chips.
    val settings = remember { AppSettings.get(context) }
    val hidden = settings.hiddenRepos
    val pulls = remember(everything, hidden) { everything?.inShownRepos(hidden) }
    // A pull request opened from here shows its size at once (H29).
    LaunchedEffect(everything) { everything?.forEach { PullSizes.put(it.owner, it.repo, it.number, it.additions, it.deletions, it.changedFiles) } }
    val needle = ui.search.trim().lowercase()
    // An archived pull request whose head commit moved since comes back, so only the still-current entries count.
    val archived = remember(pulls, archive.entries) { archive.active(pulls.orEmpty()) }
    val inbox = remember(pulls, ui.filter, archived, needle) { Triage.inbox(pulls.orEmpty(), ui.filter, archived, needle) }
    // Kind and Riskiest first need Jev; while the server has none the saved choice waits and the default stands in.
    val sort = layout.sortWhen(sync.jev)
    val groupBy = layout.groupByWhen(sync.jev)
    val sorted = remember(inbox.pulls, sort) { sort.apply(inbox.pulls) }
    val sections = remember(sorted, groupBy) { inboxSections(sorted, groupBy) }
    val rowRepos = remember(sorted, groupBy) { if (groupBy == InboxGrouping.Kind) repoLabels(sorted) else emptyMap() }
    val viewer = remember(everything) { Triage.viewerLogin(everything.orEmpty()) }
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

    fun openPull(pull: PullRow) = nav.openTarget(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com"))
    // A pasted link, or a number only one listed pull request has, opens straight from the search pill.
    val listed = remember(pulls, tours.entries) {
        pulls.orEmpty().map { ListedPull(it.owner, it.repo, it.number) } + tours.entries.map { ListedPull(it.owner, it.repo, it.number) }
    }
    val searchLink = remember(ui.search, listed) { HomeLinks.fromSearch(ui.search, listed) }
    fun openSearch() {
        when (val link = searchLink) {
            is HomeLink.Target -> { ui.search = ""; nav.openTarget(link.target) }
            is HomeLink.Repo -> { ui.search = ""; nav.openRepo(link.owner, link.repo) }
            null -> {}
        }
    }
    // A question typed in the search opens Ask over the whole inbox; Claude Code or Codex on the server answers it.
    val canAsk = remember { cliEngine(context) }
    val inboxAsk = remember { InboxAsk(repo, scope, settings) }
    val openListed = remember(nav) { { pull: ListedPull -> nav.openTarget(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com")) } }
    fun askSearch() {
        val question = ui.search.trim()
        if (question.isEmpty()) return
        ui.search = ""
        ui.askOpen = true
        // A question still being answered keeps the new one in the field, to send once it is done.
        if (inboxAsk.accepting) inboxAsk.ask(question) else inboxAsk.input = question
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
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            InboxHeader(ui.tab, { ui.tab = it }, viewer, onSettings = nav.settings, onProfile = { profileOpen = true })
            // The Inbox and Tours sit side by side: a tab or a swipe moves both, and the chips and the activity strip belong
            // to the Inbox, so they go with it (H20, rule 105).
            SidePages(ui.tab, listOf(0, 1), { ui.tab = it }, Modifier.weight(1f).fillMaxWidth()) { tab ->
                if (tab == 0) Column(Modifier.fillMaxSize()) {
                    FilterChips(ui.filter, inbox.counts) { ui.filter = it }
                    ActivityStrip(workTasks, { activityOpen = true })
                    InboxList(
                        sections = sections, rowRepos = rowRepos, now = now, archived = archived, work = work,
                        loading = pulls == null && sync.error == null, failed = if (pulls == null) sync.error else null,
                        filter = ui.filter, searching = needle.isNotEmpty(),
                        sort = sort, groupBy = groupBy, folded = layout.folded, onFold = { section -> layout.toggle(section.key) },
                        onRetry = reload, onCommands = { Commands.open() },
                        onOpen = ::openPull, onArchive = ::archivePull, onRepo = { group -> nav.openRepo(group.owner, group.repo) },
                        onHold = { held = it },
                    )
                } else Box(Modifier.fillMaxSize().padding(top = 10.dp)) {
                    ToursList(
                        entries = tours.entries.filter { "${it.owner}/${it.repo}" !in hidden && (needle.isEmpty() || "${it.owner}/${it.repo} ${it.prTitle} #${it.number}".lowercase().contains(needle)) },
                        pulls = everything.orEmpty(),
                        tasks = workTasks,
                        onOpen = { entry -> nav.openTour(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com")) },
                        // From Tours a repository's heading opens its tours, not its pull requests (H26).
                        onRepo = { owner, name -> nav.openRepoTours(owner, name) },
                        folded = if (needle.isEmpty()) layout.folded else emptySet(),
                        onFold = if (needle.isEmpty()) layout::toggle else null,
                        onDismiss = { entry -> dismissTour(entry, workTasks, tours, repo, scope, toaster) },
                    )
                }
            }
        }
        // While the search waits for its first letter the list dims a little, and a tap on it puts the keyboard away; once
        // something is typed the matches are what the search is for, so they are shown at full strength and take taps.
        // The dim starts with the tap, not when the keyboard is up, and comes in quickly; it goes a little slower than it
        // came, as the pill settles back down (H12). Putting the keyboard away with Back lifts it too.
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        var imeShown by remember { mutableStateOf(false) }
        LaunchedEffect(searchFocused) { if (!searchFocused) imeShown = false }
        SideEffect { if (searchFocused && imeUp) imeShown = true }
        val waiting = searchFocused && ui.search.isEmpty() && (imeUp || !imeShown)
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
        // `.search`: the list fades into the page under this pill (`edgeFade` on the lists), and nothing else sits behind it.
        Column(
            Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Sort sits beside the search under the thumb, so it never hides past the chips' edge (rule 114). Tours has no
            // order to choose, so the button leaves with the Inbox and the pill takes the row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchPill(
                    ui.search, { ui.search = it },
                    if (ui.tab == 0) "Search or ask Pulls" else "Search tours",
                    openLabel = searchLink?.let { if (it is HomeLink.Repo) "Open ${it.owner}/${it.repo}" else "Open pull request" },
                    onOpen = ::openSearch,
                    modifier = Modifier.weight(1f),
                    onFocus = { searchFocused = it },
                    onAsk = if (ui.tab == 0 && canAsk) ::askSearch else null,
                )
                SortButton(ui.tab == 0, sort, onClick = { sortOpen = true })
            }
        }
        if (ui.askOpen) {
            InboxAskSheet(inboxAsk, listed, openListed, onClose = { ui.askOpen = false })
        }
        if (activityOpen) {
            ActivitySheet(workTasks, onOpen = { ref -> nav.openTarget(OpenTarget.Pull(ref.owner, ref.repo, ref.number, "github.com")) }, onClose = { activityOpen = false })
        }
        held?.let { pull ->
            PullActionsSheet(
                pull, archived = Triage.key(pull) in archived,
                onTour = { nav.openTour(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com")) },
                onRepo = { nav.openRepo(pull.owner, pull.repo) },
                onArchive = { archivePull(pull) },
                onClose = { held = null },
            )
        }
        if (sortOpen) {
            SortSheet(sort, groupBy, jev = sync.jev, onPick = layout::updateSort, onGroup = layout::updateGroupBy, onClose = { sortOpen = false })
        }
        if (profileOpen) {
            ProfileSheet(
                viewer = viewer, showClosed = ui.showClosed,
                onShowClosed = { closed -> ui.showClosed = closed; ui.filter = if (closed) "all" else "waiting" },
                pulls = everything.orEmpty(), onClose = { profileOpen = false },
            )
        }
    }
}

/** `.hdr`: settings, the Inbox/Tours capsule, and the profile avatar, floating over the page. */
@Composable
internal fun InboxHeader(tab: Int, onTab: (Int) -> Unit, viewer: String?, onSettings: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatIcon(Lu.cog, "Settings", onSettings)
        CapsuleTabs(listOf("Inbox", "Tours"), tab, onTab, Modifier.weight(1f))
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
internal fun FilterChips(filter: String, counts: Map<String, Int>, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Triage.inboxFilters.forEach { chip ->
            SoftChip(CHIP_LABELS.getValue(chip), chip == filter, { onSelect(chip) }, count = "${counts[chip] ?: 0}")
        }
    }
}

/**
 * The Inbox's sort: a round button as tall as the search pill, beside it (rule 114). Its label names the order the
 * Inbox is in, and a tap offers the others. It is there while [shown], the Inbox, and makes room as it goes.
 */
@Composable
internal fun SortButton(shown: Boolean, sort: InboxSort, onClick: () -> Unit) {
    val motion = rememberMotionEnabled()
    AnimatedVisibility(
        shown,
        enter = if (!motion) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + expandHorizontally(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)),
        exit = if (!motion) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + shrinkHorizontally(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)),
    ) {
        FloatIcon(Lu.sort, "Sort, now ${sort.label}", onClick, Modifier.padding(start = 10.dp), size = 54.dp, iconSize = 22.dp)
    }
}

/**
 * The ways the Inbox can be sorted, then what its headings are, the current ones ticked. A tap applies at once and
 * closes the sheet. Riskiest first and Kind are there only while the server sorts pull requests with Jev, and with Jev
 * off the grouping card would hold Repository alone, a choice of one, so it is left out (rule 1).
 */
@Composable
internal fun SortSheet(sort: InboxSort, groupBy: InboxGrouping, jev: Boolean, onPick: (InboxSort) -> Unit, onGroup: (InboxGrouping) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    PullsSheet(onClose, "Sort") {
        SortSheetBody(sort, groupBy, jev, onPick = { onPick(it); onClose() }, onGroup = { onGroup(it); onClose() })
    }
}

/** The Sort sheet's cards, apart from its window so a still can show them. */
@Composable
internal fun SortSheetBody(sort: InboxSort, groupBy: InboxGrouping, jev: Boolean, onPick: (InboxSort) -> Unit, onGroup: (InboxGrouping) -> Unit) {
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
internal fun InboxList(
    sections: List<InboxSection>,
    /** By `owner/repo`, the repository name a row shows before its author; empty while the headings are repositories. */
    rowRepos: Map<String, String>,
    now: Long,
    archived: Set<String>,
    work: Map<String, PullWork>,
    loading: Boolean,
    failed: String?,
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
) {
    val c = LocalPullsColors.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A search shows every heading it found something in, folded or not (rule 101).
    fun isFolded(section: InboxSection) = !searching && section.key in folded
    val sectionOfRow = remember(sections) { sections.flatMap { section -> section.pulls.map { Triage.key(it) to section.key } }.toMap() }
    // A new sort or grouping starts the list from its top, where the order begins.
    var laidOut by remember { mutableStateOf(sort to groupBy) }
    LaunchedEffect(sort, groupBy) {
        if (sort to groupBy != laidOut) state.scrollToItem(0)
        laidOut = sort to groupBy
    }
    fun open(section: InboxSection): (() -> Unit)? = section.repo?.let { repo -> { onRepo(repo) } }
    fun fold(section: InboxSection) {
        var at = 0
        for (each in sections) {
            if (each.key == section.key) break
            at += 1 + if (isFolded(each)) 0 else each.pulls.size
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
                sections.firstOrNull { it.key == key }?.let { section ->
                    // The first heading keeps its own 6dp top when it pins, so it does not jump as it sticks (H13).
                    GroupHeading(section.label, section.pulls.size, first = sections.firstOrNull()?.key == key, folded = isFolded(section), onFold = if (searching) null else ({ fold(section) }), onOpen = open(section), modifier = modifier, line = true)
                }
            },
        ) { fade ->
        LazyColumn(
            Modifier.fillMaxSize().then(fade),
            state = state,
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            when {
                loading -> item(key = "loading") { RowsSkeleton(Modifier.padding(top = 24.dp)) }
                failed != null -> item(key = "failed") {
                    ListNote(t("local.home.failed"), hint = failed) { FloatIcon(Lu.redo, "Load the list again", onRetry, flat = true, iconSize = 22.dp, tint = c.muted) }
                }
                sections.isEmpty() -> item(key = "empty") {
                    val title = when {
                        searching -> "Nothing matches"
                        filter == "waiting" -> "Nothing is waiting on you"
                        filter == "authored" -> "You have no open pull requests"
                        else -> t("local.home.empty")
                    }
                    ListNote(title)
                }
                else -> {
                    sections.forEachIndexed { index, section ->
                        val shut = isFolded(section)
                        item(key = groupKey(section.key)) {
                            GroupHeading(
                                section.label, section.pulls.size, first = index == 0, folded = shut,
                                onFold = if (searching) null else ({ fold(section) }), onOpen = open(section), modifier = Modifier.animateItem(),
                            )
                        }
                        if (!shut) items(section.pulls, key = { Triage.key(it) }) { pull ->
                            val key = Triage.key(pull)
                            SwipeRow(
                                label = "#${pull.number} ${pull.title}",
                                onOpen = { onOpen(pull) },
                                onArchive = { onArchive(pull) },
                                archived = key in archived,
                                modifier = Modifier.animateItem(),
                                onLongClick = { onHold(pull) },
                            ) { PullRowContent(pull, now, dimmed = key in archived, work = work[pullKey(pull.owner, pull.repo, pull.number)], repo = rowRepos["${pull.owner}/${pull.repo}"]) }
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * `.sw`: a row that follows the finger. Past 110dp to the right it opens the pull request; to the left
 * it archives it (on the Tours tab: stops or removes the tour). Short of that it springs back. Tapping opens, and
 * both swipes are also offered to screen readers as actions. A file row (rule 56) swipes right to open it, as this row
 * does, and left to mark it reviewed, so it passes its own labels, icons and [onLongClick] for its actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SwipeRow(
    label: String,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    archived: Boolean,
    modifier: Modifier = Modifier,
    startLabel: String = "Review",
    endLabel: String = if (archived) "Restore" else "Archive",
    endIcon: LuGlyph = Lu.arch,
    startIcon: LuGlyph = Lu.arrow,
    onClick: () -> Unit = onOpen,
    onLongClick: (() -> Unit)? = null,
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
        dx = (dx + delta).coerceIn(-width, width)
        // Felt the moment letting go would act, and again if the finger takes it back (H18).
        if (armed != abs(dx) > threshold) Haptics.threshold()
    }
    val ink = c.shadow
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .semantics { customActions = listOf(CustomAccessibilityAction(startLabel) { onOpen(); true }, CustomAccessibilityAction(endLabel) { onArchive(); true }) },
    ) {
        if (dx > 0f) {
            Row(
                Modifier.matchParentSize().background(c.primary).alpha((dx / ramp).coerceIn(0f, 1f)).padding(start = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ink = Color.White // raw-value-ok: ink on the primary fill is white in both themes, like the send button
                LuIcon(startIcon, tint = ink, size = 20.dp)
                Text(startLabel, style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = ink)
            }
        }
        if (dx < 0f) {
            Row(
                Modifier.matchParentSize().background(c.cta).alpha((-dx / ramp).coerceIn(0f, 1f)).padding(end = 20.dp),
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
                .draggable(drag, Orientation.Horizontal, onDragStopped = {
                    val end = dx
                    when {
                        end > threshold -> {
                            animate(end, with(density) { 72.dp.toPx() }, animationSpec = tween(120)) { value, _ -> dx = value }
                            onOpen()
                            animate(dx, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                        }
                        end < -threshold -> {
                            animate(end, -width, animationSpec = tween(200)) { value, _ -> dx = value }
                            onArchive()
                            // Under All the row stays (dimmed): bring it back.
                            animate(dx, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                        }
                        else -> animate(end, 0f, animationSpec = tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) { value, _ -> dx = value }
                    }
                })
                .then(
                    if (onLongClick != null) Modifier.combinedClickable(
                        role = Role.Button, onClickLabel = label, onClick = cued(onClick = onClick), onLongClickLabel = "More actions",
                        // The hold is felt through the app's own vibration, which Settings can turn off (H18).
                        hapticFeedbackEnabled = false, onLongClick = { Haptics.hold(); onLongClick() },
                    )
                    else Modifier.cueClickable(role = Role.Button, onClickLabel = label, onClick = onClick),
                ),
        ) { content() }
    }
}

/** How much of the scrim dims the Inbox while the search waits for a letter: about half a sheet's. */
private const val SEARCH_DIM = 0.5f

/** The most a swiped row blurs: Row W's 1.3px, a little stronger so it reads on a phone. */
private val SWIPE_BLUR = 1.3.dp

/**
 * `.pr`: number and title on top; author, checks, diff size and age on a second line. Under a kind's heading, rows from
 * several repositories mix, so the second line starts with the [repo] name, muted like the author it comes before. A
 * stacked pull request says where it sits under its number, "2/10" as GitHub counts it (H16), so the second line has
 * room for the name: one line, always across (H15), cut only when it cannot fit at all.
 */
@Composable
internal fun PullRowContent(pull: PullRow, now: Long, dimmed: Boolean, work: PullWork?, repo: String? = null) {
    val c = LocalPullsColors.current
    val title = buildAnnotatedString {
        // The draft mark is muted at the title's own size, not a smaller run (rule 14).
        if (pull.isDraft) withStyle(SpanStyle(color = c.muted)) { append("[${t("local.home.draft")}] ") }
        append(pull.title)
    }
    val requested = pull.requestedFromMe ?: (pull.role == "review-requested")
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .alpha(if (dimmed) 0.55f else 1f)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.width(52.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("#${pull.number}", style = PullsType.num.copy(fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
            StackTag(pull)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = PullsType.row, color = c.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (requested) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accentTeal))
                    // One line, across (H15): a name never stacks its letters in a narrow column.
                    Text(if (repo != null) "$repo · ${pull.author}" else pull.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    WorkMark(work)
                }
                Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) { CheckMark(pull) }
                Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { DiffNumbers(pull) }
                Text(compactAge(pull.updatedAtMs, now), Modifier.width(34.dp), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/**
 * A stacked pull request's place in its stack, under its number: the layers glyph and "2/10", its layer and how many
 * there are, counted from the bottom as GitHub does (H16). The PR page's mark and Stack sheet show the whole stack.
 */
@Composable
private fun StackTag(pull: PullRow) {
    val stack = pull.stack ?: return
    val c = LocalPullsColors.current
    val place = stackPlace(stack)
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = place?.let { "Layer ${stack.position} of ${stack.size} in a stack" } ?: "In a stack" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        LuIcon(Lu.layers, tint = c.muted, size = 14.dp)
        place?.let { Text(it, style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1, softWrap = false) }
    }
}

/** "2/10": a layer's place in its stack, or null when the size is not known. */
internal fun stackPlace(stack: com.octaviantocan.pulls.data.PullStack): String? =
    if (stack.size >= stack.position && stack.size > 1 && stack.position > 0) "${stack.position}/${stack.size}" else null

/** The CI mark: a red cross when checks fail or the branch conflicts, a green tick when they pass, an amber dot while they run. */
@Composable
private fun CheckMark(pull: PullRow) {
    val c = LocalPullsColors.current
    when {
        pull.checks == "failure" || pull.mergeable == "conflicting" ->
            LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = if (pull.checks == "failure") t("pulls.checks.failure") else t("triage.conflict"))
        pull.checks == "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
        pull.checks == "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.amber500))
    }
}

/** `.dx`: `+180` in green over a 56dp column, `−32` in red over 40dp, in the mono face. */
@Composable
private fun DiffNumbers(pull: PullRow) {
    val c = LocalPullsColors.current
    val adds = pull.additions ?: return
    val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("+${compactCount(adds)}", Modifier.width(56.dp), style = mono, color = c.success, maxLines = 1, softWrap = false)
        Text("−${compactCount(pull.deletions ?: 0)}", Modifier.width(40.dp), style = mono, color = c.error, maxLines = 1, softWrap = false)
    }
}

/**
 * `.search`: a 54dp white pill. Typing filters the list at once. When the text is a link or a number that names one
 * pull request, an Open pill appears in it and the keyboard's action opens it, so no separate field is needed. With
 * [onAsk], any other text can be asked instead: Ask sits in the pill as a quiet word, and once the text reads as a
 * question it fills like Open and the keyboard's action asks it.
 */
@Composable
internal fun SearchPill(value: String, onChange: (String) -> Unit, placeholder: String, openLabel: String?, onOpen: () -> Unit, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit = {}, onAsk: (() -> Unit)? = null) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
    val askable = onAsk != null && openLabel == null && value.isNotBlank()
    val question = askable && looksLikeQuestion(value)
    Row(
        modifier.fillMaxWidth().height(54.dp).floatShadow(CircleShape, PullsShadow.Search).clip(CircleShape).background(c.raised).padding(start = 20.dp, end = if (value.isEmpty()) 20.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LuIcon(Lu.search, tint = c.muted, size = 20.dp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = PullsType.lg.copy(color = c.text),
            cursorBrush = SolidColor(c.accentTeal),
            keyboardOptions = KeyboardOptions(imeAction = if (openLabel != null) ImeAction.Go else if (question) ImeAction.Send else ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }, onGo = { onOpen() }, onSend = { focus.clearFocus(); onAsk?.invoke() }),
            modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = PullsType.lg, color = c.muted, maxLines = 1)
                    inner()
                }
            },
        )
        if (openLabel != null) {
            Box(
                Modifier.height(40.dp).clip(CircleShape).background(c.cta).cueClickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Open", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.onCta, maxLines = 1) }
        }
        if (askable && onAsk != null) {
            val ask = { focus.clearFocus(); onAsk() }
            Box(
                Modifier.height(40.dp).clip(CircleShape).then(if (question) Modifier.background(c.cta) else Modifier)
                    .cueClickable(role = Role.Button, onClickLabel = "Ask Pulls", onClick = ask).padding(horizontal = if (question) 16.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Ask", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = if (question) c.onCta else c.activeText, maxLines = 1) }
        }
        if (value.isNotEmpty()) FloatIcon(Lu.close, "Clear search", { onChange("") }, flat = true, size = 40.dp, iconSize = 18.dp, tint = c.muted)
    }
}

/**
 * The Tours tab: the walkthroughs opened or started here, grouped by repository. A repository's heading opens it. A row
 * behaves like an Inbox row: tap or swipe right to open, swipe left to stop a tour that is being written or to remove
 * a finished one from the list.
 */
@Composable
internal fun ToursList(
    entries: List<TourEntry>,
    pulls: List<PullRow>,
    tasks: List<WorkTask>,
    onOpen: (TourEntry) -> Unit,
    onRepo: (owner: String, repo: String) -> Unit,
    folded: Set<String>,
    onFold: ((String) -> Unit)?,
    onDismiss: (TourEntry) -> Unit,
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
                    onArchive = { onDismiss(entry) },
                    archived = false,
                    modifier = Modifier.animateItem(),
                    endLabel = if (stoppable) "Stop" else "Remove",
                    endIcon = Lu.close,
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
internal fun dismissTour(entry: TourEntry, workTasks: List<WorkTask>, tours: TourIndex, repo: Repo, scope: CoroutineScope, toaster: Toaster) {
    val serverKey = pullKey(entry.owner, entry.repo, entry.number)
    val running = runningTaskId(workTasks, serverKey, "tour")
    val taskIds = workTasks.filter { it.kind == "tour" && it.key == serverKey }.map { it.id }
    if (running != null) {
        scope.launch { repo.cancelTask(running) }
        // A tour stopped before it had a stop has nothing to list; one that had keeps its row.
        if (entry.stops > 0) tours.upsert(entry.copy(writing = false)) else tours.dismiss(entry.key, taskIds)
        toaster.show("Stopped the tour for #${entry.number}")
    } else {
        tours.dismiss(entry.key, taskIds)
        toaster.show("Removed the tour for #${entry.number}", "Undo") { tours.upsert(entry) }
    }
}

/**
 * A Tours row is the pull request row with the tour's ring leading it, rule 36's second anatomy: the ring says
 * how far you read, or the orb turns while the tour is written. When the pull request is not in the Inbox its
 * author's column says where the tour stands instead.
 */
@Composable
internal fun TourRowContent(entry: TourEntry, pull: PullRow?, now: Long) {
    val c = LocalPullsColors.current
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.padding(end = 4.dp).size(22.dp), contentAlignment = Alignment.Center) {
            when {
                entry.writingAt(now) -> Orb(size = 22.dp)
                // A tour read to the end is a filled tick, so nothing has to say "Read" (H27).
                entry.doneAt(now) -> OkDot(size = 20.dp)
                else -> ProgressRing(entry.reached, entry.stops, size = 22.dp)
            }
        }
        Text("#${entry.number}", Modifier.width(52.dp), style = PullsType.num.copy(fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.prTitle, style = PullsType.row, color = c.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // One line, so it sits level with the checks, the numbers and the age (H28).
                Text(pull?.author ?: tourState(entry, now), Modifier.weight(1f), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                if (pull != null) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) { CheckMark(pull) }
                    Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { DiffNumbers(pull) }
                }
                Text(compactAge(pull?.updatedAtMs ?: entry.updatedAt, now), Modifier.width(34.dp), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** Where a tour stands, for a row whose pull request is not in the Inbox: no counts (rules 11 and 14). A finished one says nothing: its tick does (H27). */
internal fun tourState(entry: TourEntry, now: Long): String = when {
    entry.writingAt(now) -> "Writing"
    entry.doneAt(now) -> ""
    entry.reached > 0 -> entry.stopTitle.ifBlank { "Reading" }
    else -> "Not started"
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

/** A small turning arrow beside work the phone is doing quietly (refreshing, loading more). */
@Composable
fun Spinner(size: Dp = 16.dp, tint: Color = LocalPullsColors.current.fade) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    LuIcon(Lu.redo, Modifier.rotate(angle), tint = tint, size = size)
}
