package com.octaviantocan.pulls.ui.home

import android.content.Context
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
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsMotion
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
import com.octaviantocan.pulls.ui.update.UpdateFloat
import com.octaviantocan.pulls.ui.update.WhatsNewSheet
import com.octaviantocan.pulls.ui.update.updateFloatPadding
import com.octaviantocan.pulls.update.Updates
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Keeps the last list per state on this device, like the web's `localStorage` copy. */
internal fun deviceStore(context: Context, name: String = "pulls-home"): KeyValueStore {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    return object : KeyValueStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }
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
        )
    }
    LaunchedEffect(state) { sync.select(state) }
    // Refreshing is quiet: no indicator. It runs on open and whenever the app comes back to the front.
    val reload: () -> Unit = { scope.launch { sync.reload() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val last = sync.fetchedAt
        if (last != null && System.currentTimeMillis() - last > 30_000) reload()
    }

    val now = rememberNow(60_000)
    val workTasks = rememberWork(repo)
    val work = remember(workTasks) { pullWorkByKey(workTasks) }
    // A tour the server started or finished while no page was open still gets its row.
    LaunchedEffect(workTasks) {
        tours.reconcile(workTasks, System.currentTimeMillis()) { key ->
            try { repo.savedState(key).tour?.stops?.size } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
    }
    var activityOpen by remember { mutableStateOf(false) }
    val everything = sync.pulls
    // A repository switched off in Profile is left out of the list and of the numbers on the chips.
    val settings = remember { AppSettings.get(context) }
    val hidden = settings.hiddenRepos
    val pulls = remember(everything, hidden) { everything?.inShownRepos(hidden) }
    val needle = ui.search.trim().lowercase()
    // An archived pull request whose head commit moved since comes back, so only the still-current entries count.
    val archived = remember(pulls, archive.entries) { archive.active(pulls.orEmpty()) }
    val inbox = remember(pulls, ui.filter, archived, needle) { Triage.inbox(pulls.orEmpty(), ui.filter, archived, needle) }
    val groups = remember(inbox.pulls, layout.sort) { Triage.groupByRepo(layout.sort.apply(inbox.pulls)) }
    val labels = remember(groups) { Triage.groupLabels(groups) }
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

    var searchFocused by remember { mutableStateOf(false) }
    var whatsNewOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            InboxHeader(ui.tab, { ui.tab = it }, viewer, onSettings = nav.settings, onProfile = { profileOpen = true })
            if (ui.tab == 0) FilterChips(ui.filter, inbox.counts, layout.sort, onSort = { sortOpen = true }) { ui.filter = it }
            if (ui.tab == 0) ActivityStrip(workTasks, { activityOpen = true })
            BlurSwitch(ui.tab, Modifier.weight(1f).fillMaxWidth().padding(top = if (ui.tab == 0) 0.dp else 10.dp), order = { it }) { tab ->
                if (tab == 0) {
                    InboxList(
                        groups = groups, labels = labels, now = now, archived = archived, work = work,
                        loading = pulls == null && sync.error == null, failed = if (pulls == null) sync.error else null,
                        filter = ui.filter, searching = needle.isNotEmpty(),
                        sort = layout.sort, folded = layout.folded, onFold = { group -> layout.toggle(group.name) },
                        onRetry = reload, onCommands = { Commands.open() },
                        onOpen = ::openPull, onArchive = ::archivePull, onRepo = { group -> nav.openRepo(group.owner, group.repo) },
                    )
                } else {
                    ToursList(
                        entries = tours.entries.filter { "${it.owner}/${it.repo}" !in hidden && (needle.isEmpty() || "${it.owner}/${it.repo} ${it.prTitle} #${it.number}".lowercase().contains(needle)) },
                        pulls = everything.orEmpty(),
                        tasks = workTasks,
                        onOpen = { entry -> nav.openTour(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com")) },
                        onRepo = { owner, name -> nav.openRepo(owner, name) },
                        folded = if (needle.isEmpty()) layout.folded else emptySet(),
                        onFold = if (needle.isEmpty()) layout::toggle else null,
                        onDismiss = { entry ->
                            // A tour still being written is stopped; a finished one leaves the list (it stays saved on the server).
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
                        },
                    )
                }
            }
        }
        // While the search waits for its first letter the list dims a little, and a tap on it puts the keyboard away; once
        // something is typed the matches are what the search is for, so they are shown at full strength and take taps.
        val waiting = searchFocused && ui.search.isEmpty() && WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val dim by animateFloatAsState(if (waiting) 1f else 0f, tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), label = "search-dim")
        if (dim > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = dim * SEARCH_DIM }.background(c.scrim)
                    .then(if (waiting) Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } } else Modifier),
            )
        }
        // `.search`: the list fades into the page under this pill (`edgeFade` on the lists), and nothing else sits behind it.
        // A new version of the app floats just above it, and steps aside while the search has the keyboard.
        Column(
            Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UpdateFloat(visible = !searchFocused, onWhatsNew = { whatsNewOpen = true })
            SearchPill(
                ui.search, { ui.search = it },
                if (ui.tab == 0) "Search or ask Pulls" else "Search tours",
                openLabel = searchLink?.let { if (it is HomeLink.Repo) "Open ${it.owner}/${it.repo}" else "Open pull request" },
                onOpen = ::openSearch,
                onFocus = { searchFocused = it },
                onAsk = if (ui.tab == 0 && canAsk) ::askSearch else null,
            )
        }
        if (whatsNewOpen) {
            WhatsNewSheet(remember { Updates.changes(context) }, onClose = { whatsNewOpen = false }, offer = true)
        }
        if (ui.askOpen) {
            InboxAskSheet(inboxAsk, listed, openListed, onClose = { ui.askOpen = false })
        }
        if (activityOpen) {
            ActivitySheet(workTasks, onOpen = { ref -> nav.openTarget(OpenTarget.Pull(ref.owner, ref.repo, ref.number, "github.com")) }, onClose = { activityOpen = false })
        }
        if (sortOpen) {
            SortSheet(layout.sort, onPick = layout::updateSort, onClose = { sortOpen = false })
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

/** `.chips`: Waiting on you, Authored and All, each with the size of the list it would show, then the sort. */
@Composable
internal fun FilterChips(filter: String, counts: Map<String, Int>, sort: InboxSort, onSort: () -> Unit, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Triage.inboxFilters.forEach { chip ->
            SoftChip(CHIP_LABELS.getValue(chip), chip == filter, { onSelect(chip) }, count = "${counts[chip] ?: 0}")
        }
        SortChip(sort, onSort, Modifier.padding(start = 4.dp))
    }
}

/**
 * The sort, after the scope chips: a chip of the same size and edge with the arrows in front, so it reads as an order
 * and not one more filter. It says what the Inbox is sorted by (rule 81), and a tap offers the others.
 */
@Composable
internal fun SortChip(sort: InboxSort, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(
        modifier.height(36.dp).clip(CircleShape).border(1.dp, c.border, CircleShape)
            .cueClickable(role = Role.Button, onClickLabel = "Change the sort", onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LuIcon(Lu.sort, tint = c.muted, size = 16.dp)
        Text(sort.label, style = PullsType.sm, color = c.text, maxLines = 1)
    }
}

/** The ways the Inbox can be sorted, the current one ticked. A tap sorts at once and closes the sheet. */
@Composable
internal fun SortSheet(sort: InboxSort, onPick: (InboxSort) -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    PullsSheet(onClose, "Sort") {
        SheetCard {
            InboxSort.entries.forEachIndexed { index, option ->
                SheetRow(option.label, { onPick(option); onClose() }, first = index == 0, iconless = true) {
                    if (option == sort) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxList(
    groups: List<Triage.RepoGroup>,
    labels: Map<String, String>,
    now: Long,
    archived: Set<String>,
    work: Map<String, PullWork>,
    loading: Boolean,
    failed: String?,
    filter: String,
    searching: Boolean,
    sort: InboxSort,
    folded: Set<String>,
    onFold: (Triage.RepoGroup) -> Unit,
    onRetry: () -> Unit,
    onCommands: () -> Unit,
    onOpen: (PullRow) -> Unit,
    onArchive: (PullRow) -> Unit,
    onRepo: (Triage.RepoGroup) -> Unit,
) {
    val c = LocalPullsColors.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp + updateFloatPadding()
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // A search shows every repository it found something in, folded or not (rule 101).
    fun isFolded(group: Triage.RepoGroup) = !searching && group.name in folded
    val groupOfRow = remember(groups) { groups.flatMap { group -> group.pulls.map { Triage.key(it) to group.name } }.toMap() }
    // A new sort starts the list from its top, where the order begins.
    var sorted by remember { mutableStateOf(sort) }
    LaunchedEffect(sort) {
        if (sort != sorted) state.scrollToItem(0)
        sorted = sort
    }
    fun fold(group: Triage.RepoGroup) {
        var at = 0
        for (each in groups) {
            if (each.name == group.name) break
            at += 1 + if (isFolded(each)) 0 else each.pulls.size
        }
        onFold(group)
        // Folded from its pinned heading, the repository's rows leave from under it: bring its heading back to the top.
        if (at <= state.firstVisibleItemIndex) scope.launch { state.scrollToItem(at) }
    }
    // Pulling down past the top opens Commands; the list itself refreshes quietly.
    PullToCommands(onCommands, Modifier.fillMaxSize()) {
        PinnedHeadings(
            state,
            groupOf = { key -> groupOfRow[key] },
            heading = { name, modifier ->
                groups.firstOrNull { it.name == name }?.let { group ->
                    GroupHeading(labels[name] ?: group.repo, group.pulls.size, first = false, folded = isFolded(group), onFold = if (searching) null else ({ fold(group) }), onOpen = { onRepo(group) }, modifier = modifier, line = true)
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
                groups.isEmpty() -> item(key = "empty") {
                    val title = when {
                        searching -> "Nothing matches"
                        filter == "waiting" -> "Nothing is waiting on you"
                        filter == "authored" -> "You have no open pull requests"
                        else -> t("local.home.empty")
                    }
                    ListNote(title)
                }
                else -> {
                    groups.forEachIndexed { index, group ->
                        val shut = isFolded(group)
                        item(key = groupKey(group.name)) {
                            GroupHeading(
                                labels[group.name] ?: group.repo, group.pulls.size, first = index == 0, folded = shut,
                                onFold = if (searching) null else ({ fold(group) }), onOpen = { onRepo(group) }, modifier = Modifier.animateItem(),
                            )
                        }
                        if (!shut) items(group.pulls, key = { Triage.key(it) }) { pull ->
                            val key = Triage.key(pull)
                            SwipeRow(
                                label = "#${pull.number} ${pull.title}",
                                onOpen = { onOpen(pull) },
                                onArchive = { onArchive(pull) },
                                archived = key in archived,
                                modifier = Modifier.animateItem(),
                            ) { PullRowContent(pull, now, dimmed = key in archived, work = work[pullKey(pull.owner, pull.repo, pull.number)]) }
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
    val drag = rememberDraggableState { delta -> dx = (dx + delta).coerceIn(-width, width) }
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
                    if (onLongClick != null) Modifier.combinedClickable(role = Role.Button, onClickLabel = label, onClick = cued(onClick = onClick), onLongClickLabel = "More actions", onLongClick = onLongClick)
                    else Modifier.cueClickable(role = Role.Button, onClickLabel = label, onClick = onClick),
                ),
        ) { content() }
    }
}

/** How much of the scrim dims the Inbox while the search waits for a letter: about half a sheet's. */
private const val SEARCH_DIM = 0.5f

/** The most a swiped row blurs: Row W's 1.3px, a little stronger so it reads on a phone. */
private val SWIPE_BLUR = 1.3.dp

/** `.pr`: number and title on top; author, checks, diff size and age on a second line. */
@Composable
internal fun PullRowContent(pull: PullRow, now: Long, dimmed: Boolean, work: PullWork?) {
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
        Text("#${pull.number}", Modifier.width(52.dp), style = PullsType.num.copy(fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = PullsType.row, color = c.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (requested) Box(Modifier.size(7.dp).clip(CircleShape).background(c.accentTeal))
                    // A name wraps whole inside its column rather than being cut (rule 90).
                    Text(pull.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted)
                    WorkMark(work)
                }
                Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) { CheckMark(pull) }
                Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { DiffNumbers(pull) }
                Text(compactAge(pull.updatedAtMs, now), Modifier.width(34.dp), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

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
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp + updateFloatPadding()
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
    PinnedHeadings(state, groupOf = { key -> groupOfRow[key] }, heading = { name, modifier -> Heading(name, first = false, modifier, pinned = true) }) { fade ->
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
 * A Tours row is the pull request row with the tour's ring leading it, rule 36's second anatomy: the ring says
 * how far you read, or the orb turns while the tour is written. When the pull request is not in the Inbox its
 * author's column says where the tour stands instead.
 */
@Composable
private fun TourRowContent(entry: TourEntry, pull: PullRow?, now: Long) {
    val c = LocalPullsColors.current
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.padding(end = 4.dp).size(22.dp)) {
            if (entry.writingAt(now)) Orb(size = 22.dp) else ProgressRing(entry.reached, entry.stops, size = 22.dp)
        }
        Text("#${entry.number}", Modifier.width(52.dp), style = PullsType.num.copy(fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.prTitle, style = PullsType.row, color = c.text)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pull?.author ?: tourState(entry, now), Modifier.weight(1f), style = PullsType.sm, color = c.muted)
                if (pull != null) {
                    Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) { CheckMark(pull) }
                    Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { DiffNumbers(pull) }
                }
                Text(compactAge(pull?.updatedAtMs ?: entry.updatedAt, now), Modifier.width(34.dp), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** Where a tour stands, for a row whose pull request is not in the Inbox: no counts (rules 11 and 14). */
internal fun tourState(entry: TourEntry, now: Long): String = when {
    entry.writingAt(now) -> "Writing"
    entry.doneAt(now) -> "Read"
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
