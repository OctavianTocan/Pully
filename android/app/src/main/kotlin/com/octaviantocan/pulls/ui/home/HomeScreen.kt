package com.octaviantocan.pulls.ui.home

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.core.ListedPull
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.core.PullWork
import com.octaviantocan.pulls.core.WorkTask
import com.octaviantocan.pulls.core.pullKey
import com.octaviantocan.pulls.core.pullWorkByKey
import com.octaviantocan.pulls.core.runningTaskId
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.EmptyState
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuGlyph
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.ProgressRing
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.edgeBlur
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.softBlur
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.topEdgeStrength
import com.octaviantocan.pulls.update.AppUpdater
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
}

private val CHIP_LABELS = mapOf("waiting" to "Waiting on you", "authored" to "Authored", "all" to "All")

/**
 * The home screen of the prototype: a floating header (settings, Inbox/Tours, profile), three chips,
 * your pull requests grouped by repository with swipe-to-review and swipe-to-archive, and a search
 * pill at the bottom. Tours lists the walkthroughs you opened or started on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav, updateNotice: String?, ui: HomeUi) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val repo = remember { Repo(context) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val state = if (ui.showClosed) "closed" else "open"

    val storage = remember { deviceStore(context, "pulls-inbox") }
    val archive = remember { ArchiveStore(storage) }
    val tours = remember { TourIndex(storage) }
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
    val pulls = sync.pulls
    val needle = ui.search.trim().lowercase()
    // An archived pull request whose head commit moved since comes back, so only the still-current entries count.
    val archived = remember(pulls, archive.entries) { archive.active(pulls.orEmpty()) }
    val inbox = remember(pulls, ui.filter, archived, needle) { Triage.inbox(pulls.orEmpty(), ui.filter, archived, needle) }
    val groups = remember(inbox.pulls) { Triage.groupByRepo(inbox.pulls) }
    val labels = remember(groups) { Triage.groupLabels(groups) }
    val viewer = remember(pulls) { Triage.viewerLogin(pulls.orEmpty()) }
    var profileOpen by remember { mutableStateOf(false) }

    // `useHomeNavigation.ts`: refresh, and a "Go to" entry per pull request and repository.
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    com.octaviantocan.pulls.ui.UseCommands {
        val pullsGroup = messages.t("palette.group.pulls")
        val reposGroup = messages.t("palette.group.repos")
        val all = sync.pulls.orEmpty()
        listOf(com.octaviantocan.pulls.ui.Command("home.refresh", messages.t("keys.refresh"), pullsGroup) { reload() }) +
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

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            InboxHeader(ui.tab, { ui.tab = it }, viewer, onSettings = nav.settings, onProfile = { profileOpen = true })
            if (ui.tab == 0) FilterChips(ui.filter, inbox.counts) { ui.filter = it }
            if (ui.tab == 0) ActivityStrip(workTasks, { activityOpen = true })
            // A hairline under the chips, as in the design: the Tours list has its own under each heading.
            if (ui.tab == 0) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(1.dp).background(c.borderMute))
            BlurSwitch(ui.tab, Modifier.weight(1f).fillMaxWidth().padding(top = if (ui.tab == 0) 0.dp else 10.dp), order = { it }) { tab ->
                if (tab == 0) {
                    InboxList(
                        groups = groups, labels = labels, now = now, archived = archived, work = work,
                        loading = pulls == null && sync.error == null, failed = if (pulls == null) sync.error else null,
                        filter = ui.filter, searching = needle.isNotEmpty(), updateNotice = updateNotice,
                        onRetry = reload, onCommands = { Commands.open() },
                        onOpen = ::openPull, onArchive = ::archivePull, onRepo = { group -> nav.openRepo(group.owner, group.repo) },
                    )
                } else {
                    ToursList(
                        entries = tours.entries.filter { needle.isEmpty() || "${it.owner}/${it.repo} ${it.prTitle} #${it.number}".lowercase().contains(needle) },
                        tasks = workTasks,
                        onOpen = { entry -> nav.openTour(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com")) },
                        onRepo = { owner, name -> nav.openRepo(owner, name) },
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
        // `.fade` and `.search`: the list dissolves into the page under a floating search pill.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to c.base, 1f to c.base)))
        SearchPill(
            ui.search, { ui.search = it },
            if (ui.tab == 0) "Search or ask Pulls" else "Search tours",
            openLabel = searchLink?.let { if (it is HomeLink.Repo) "Open ${it.owner}/${it.repo}" else "Open pull request" },
            onOpen = ::openSearch,
            modifier = Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
        if (activityOpen) {
            ActivitySheet(workTasks, onOpen = { ref -> nav.openTarget(OpenTarget.Pull(ref.owner, ref.repo, ref.number, "github.com")) }, onClose = { activityOpen = false })
        }
        if (profileOpen) {
            ProfileSheet(
                nav = nav, viewer = viewer, showClosed = ui.showClosed,
                onShowClosed = { closed -> ui.showClosed = closed; ui.filter = if (closed) "all" else "waiting" },
                updating = sync.updating, fetchedAt = sync.fetchedAt, error = if (pulls != null) sync.error else null,
                now = now, onRefresh = reload, onClose = { profileOpen = false },
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
    updateNotice: String?,
    onRetry: () -> Unit,
    onCommands: () -> Unit,
    onOpen: (PullRow) -> Unit,
    onArchive: (PullRow) -> Unit,
    onRepo: (Triage.RepoGroup) -> Unit,
) {
    val c = LocalPullsColors.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
    val state = rememberLazyListState()
    val band = with(LocalDensity.current) { 28.dp.toPx() }
    // Pulling down past the top opens Commands; the list itself refreshes quietly.
    PullToCommands(onCommands, Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().edgeBlur(top = 28.dp, bottom = 130.dp, topStrength = { state.topEdgeStrength(band) }),
            state = state,
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            if (updateNotice != null) item(key = "update") { UpdateBanner(updateNotice, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
            when {
                loading -> item(key = "loading") { RowsSkeleton(Modifier.padding(top = 24.dp)) }
                failed != null -> item(key = "failed") {
                    EmptyState("warning-duotone", t("local.home.failed"), Modifier.fillMaxWidth(), hint = failed) {
                        PillButton(t("common.retry"), onRetry)
                    }
                }
                groups.isEmpty() -> item(key = "empty") {
                    val title = when {
                        searching -> "Nothing matches"
                        filter == "waiting" -> "Nothing is waiting on you"
                        filter == "authored" -> "You have no open pull requests"
                        else -> t("local.home.empty")
                    }
                    EmptyState("git-pull-request-duotone", title, Modifier.fillMaxWidth().padding(top = 32.dp))
                }
                else -> {
                    groups.forEachIndexed { index, group ->
                        item(key = "group:${group.name}") {
                            GroupHeading(labels[group.name] ?: group.repo, first = index == 0, onClick = { onRepo(group) }, modifier = Modifier.animateItem())
                        }
                        items(group.pulls, key = { Triage.key(it) }) { pull ->
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
                    item(key = "hint") {
                        Text(
                            "Swipe a row right to review, left to archive.",
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
                            style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** `.grp`: the repository name over its rows; later groups start under a hairline. */
@Composable
private fun GroupHeading(label: String, first: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    Text(
        label,
        modifier
            .fillMaxWidth()
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .cueClickable(role = Role.Button, onClickLabel = "Open $label", onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = if (first) 16.dp else 18.dp, bottom = 6.dp),
        style = PullsType.sm.copy(fontWeight = FontWeight.Medium),
        color = c.muted,
    )
}

/**
 * `.sw`: a row that follows the finger. Past 110dp to the right it opens the pull request; to the left
 * it archives it (on the Tours tab: stops or removes the tour). Short of that it springs back. Tapping opens, and
 * both swipes are also offered to screen readers as actions.
 */
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
                LuIcon(Lu.arrow, tint = ink, size = 20.dp)
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
                .cueClickable(role = Role.Button, onClickLabel = label, onClick = onOpen),
        ) { content() }
    }
}

/** The most a swiped row blurs: Row W's 1.3px, a little stronger so it reads on a phone. */
private val SWIPE_BLUR = 2.dp

/** `.pr`: number and title on top; author, checks, diff size and age on a second line. */
@Composable
internal fun PullRowContent(pull: PullRow, now: Long, dimmed: Boolean, work: PullWork?) {
    val c = LocalPullsColors.current
    val title = buildAnnotatedString {
        if (pull.isDraft) withStyle(SpanStyle(fontSize = 13.sp, color = c.muted)) { append("[${t("local.home.draft")}] ") }
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
                    Text(pull.author, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * pull request, an Open pill appears in it and the keyboard's action opens it, so no separate field is needed.
 */
@Composable
internal fun SearchPill(value: String, onChange: (String) -> Unit, placeholder: String, openLabel: String?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val focus = LocalFocusManager.current
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
            keyboardOptions = KeyboardOptions(imeAction = if (openLabel != null) ImeAction.Go else ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }, onGo = { onOpen() }),
            modifier = Modifier.weight(1f),
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
    tasks: List<WorkTask>,
    onOpen: (TourEntry) -> Unit,
    onRepo: (owner: String, repo: String) -> Unit,
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
    val groups = entries.groupBy { "${it.owner}/${it.repo}" }
    val labels = Triage.groupLabels(groups.map { (name, list) -> Triage.RepoGroup(name, list.first().owner, list.first().repo, emptyList()) })
    val state = rememberLazyListState()
    val band = with(LocalDensity.current) { 28.dp.toPx() }
    LazyColumn(
        Modifier.fillMaxSize().edgeBlur(top = 28.dp, bottom = 130.dp, topStrength = { state.topEdgeStrength(band) }),
        state = state,
        contentPadding = PaddingValues(bottom = bottom),
    ) {
        groups.entries.forEachIndexed { index, (name, list) ->
            item(key = "group:$name") {
                val first = list.first()
                GroupHeading(labels[name] ?: name, first = index == 0, onClick = { onRepo(first.owner, first.repo) }, modifier = Modifier.animateItem())
            }
            items(list, key = { it.key }) { entry ->
                val inset = with(LocalDensity.current) { 16.dp.toPx() }
                val stoppable = runningTaskId(tasks, pullKey(entry.owner, entry.repo, entry.number), "tour") != null
                SwipeRow(
                    label = entry.prTitle,
                    onOpen = { onOpen(entry) },
                    onArchive = { onDismiss(entry) },
                    archived = false,
                    modifier = Modifier.animateItem(),
                    endLabel = if (stoppable) "Stop" else "Remove",
                    endIcon = Lu.close,
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .drawBehind { drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(30.dp)) {
                            if (entry.writingAt(now)) Orb(size = 30.dp) else ProgressRing(entry.reached, entry.stops, size = 30.dp)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.prTitle, style = PullsType.row, color = c.text)
                            Text(tourSubtitle(entry, now), style = PullsType.sm, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

/** `3 of 7 · Three lines that fix it`, `Writing · about a minute`, `Done · 4 stops`. */
internal fun tourSubtitle(entry: TourEntry, now: Long): String = when {
    entry.writingAt(now) -> "Writing · about a minute"
    entry.doneAt(now) -> "Done · ${entry.stops} ${if (entry.stops == 1) "stop" else "stops"}"
    entry.reached > 0 -> "${entry.reached} of ${entry.stops} · ${entry.stopTitle}".trimEnd(' ', '·')
    else -> "${entry.stops} ${if (entry.stops == 1) "stop" else "stops"} · not started"
}

/** The profile sheet: check out a branch, show closed pull requests, refresh. Links and numbers go in the search pill. */
@Composable
private fun ProfileSheet(
    nav: Nav,
    viewer: String?,
    showClosed: Boolean,
    onShowClosed: (Boolean) -> Unit,
    updating: Boolean,
    fetchedAt: Long?,
    error: String?,
    now: Long,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val host = remember { ServerStore.load(context).url.removePrefix("https://").removePrefix("http://").trimEnd('/') }
    PullsSheet(onClose, viewer ?: "Pulls") {
        Text(host, Modifier.fillMaxWidth(), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
        SheetCard {
            SheetRow("Check out a branch", { onClose(); nav.checkout() }, first = true, icon = { LuIcon(Lu.branch, tint = c.text, size = 20.dp) }) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Commands", { onClose(); Commands.open() }, icon = { LuIcon(Lu.search, tint = c.text, size = 20.dp) }) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Closed pull requests", { onShowClosed(!showClosed) }, icon = { LuIcon(Lu.arch, tint = c.text) }) {
                PullsSwitch(showClosed, onShowClosed, "Show closed pull requests")
            }
            SheetRow("Refresh", if (updating) null else onRefresh, icon = { LuIcon(Lu.redo, tint = c.text, size = 20.dp) }) {
                when {
                    updating -> Spinner(16.dp)
                    error != null -> Text("Couldn't update", style = PullsType.sm, color = c.error)
                    fetchedAt != null -> Text("Updated ${timeAgo(fetchedAt, now)}", style = PullsType.sm, color = c.muted, maxLines = 1)
                }
            }
        }
    }
}

/** A small turning arrow beside work the phone is doing quietly (refreshing, loading more). */
@Composable
fun Spinner(size: Dp = 16.dp, tint: Color = LocalPullsColors.current.fade) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    LuIcon(Lu.redo, Modifier.rotate(angle), tint = tint, size = size)
}

/** `rounded bg-hover px-1.5 py-0.5 text-xs`. */
@Composable
internal fun Chip(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(6.dp)).background(LocalPullsColors.current.hover).padding(horizontal = 6.dp, vertical = 2.dp),
        style = PullsType.xs,
        color = color,
        maxLines = 1,
    )
}

/** The app-update notice: download, check the digest and hand it to the installer, all in-app. */
@Composable
private fun UpdateBanner(notice: String, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .floatShadow(shape, PullsShadow.Note)
            .clip(shape)
            .background(c.raised)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LuIcon(Lu.arrow, Modifier.rotate(-90f), tint = c.accentTeal, size = 22.dp)
        Text(progress ?: notice, Modifier.weight(1f), style = PullsType.sm, color = c.text)
        val result = com.octaviantocan.pulls.update.UpdateBus.last
        LaunchedEffect(result) {
            when (result) {
                is com.octaviantocan.pulls.update.InstallResult.Failed -> { progress = result.message; busy = false }
                com.octaviantocan.pulls.update.InstallResult.Confirming -> progress = "Confirm the update in the system dialog."
                else -> {}
            }
        }
        PillButton("Update", enabled = !busy, leading = { if (busy) Orb(size = 18.dp) }, onClick = {
            // Android asks once per app for "Install unknown apps"; send the user there first.
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                progress = "Allow pulls.review to install apps, then tap Update again."
                runCatching {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}")))
                }
                return@PillButton
            }
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val config = ServerStore.load(context)
                        val updater = AppUpdater(context)
                        val manifest = updater.check(config.url, config.headers(), installedVersion(context)) ?: error("Already up to date.")
                        val apk = updater.download(manifest, config.headers()) { read, total ->
                            if (total > 0) progress = "Downloading ${manifest.versionName}… ${read * 100 / total}%"
                        }
                        updater.install(apk)
                    }
                }
                outcome.onFailure { progress = it.message ?: it.toString(); busy = false }.onSuccess { progress = "Installing…" }
            }
        })
    }
}

@Suppress("DEPRECATION")
private fun installedVersion(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
}
