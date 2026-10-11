package com.octaviantocan.pulls.ui.home

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.data.Triage
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.EmptyState
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.Loading
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PrStatusIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
import com.octaviantocan.pulls.ui.design.ToggleGroup
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import com.octaviantocan.pulls.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIDEBAR = 208.dp // md:grid-cols-[13rem_minmax(0,1fr)]

/** Keeps the last list per state on this device, like the web's `localStorage` copy. */
internal fun deviceStore(context: Context, name: String = "pulls-home"): KeyValueStore {
    val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    return object : KeyValueStore {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }
}

/** Port of `pages-local/index.vue`: your pull requests, triaged and grouped by repository. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(nav: Nav, updateNotice: String?) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val bp = rememberBreakpoints()
    val repo = remember { Repo(context) }
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var state by rememberSaveable { mutableStateOf("open") }
    var queueChoice by rememberSaveable { mutableStateOf("all") }
    var role by rememberSaveable { mutableStateOf("all") }
    val queue = if (state == "open") queueChoice else "all"
    val parsed = remember(url) { HomeLinks.parse(url) }
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }

    val sync = remember {
        MyPullsSync(
            cached = { selected -> repo.myPullsCached(selected)?.asSnapshot() },
            fresh = { selected -> repo.myPulls(selected) },
            storage = deviceStore(context),
        )
    }
    LaunchedEffect(state) { sync.select(state) }
    val reload: () -> Unit = { scope.launch { sync.reload() } }

    val now = rememberNow(60_000)
    val filtered = remember(sync.pulls, queue, role, search, now) {
        Triage.filterPulls(sync.pulls.orEmpty(), queue, role, search.trim().lowercase(), now)
    }
    val groups = remember(filtered) { Triage.groupByRepo(filtered.pulls) }

    fun open() {
        when (val link = parsed) {
            is HomeLink.Target -> nav.openTarget(link.target)
            is HomeLink.Repo -> nav.openRepo(link.owner, link.repo)
            null -> {}
        }
    }

    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 10 } }
    val hPad = if (bp.md) 24.dp else 16.dp
    val wide = bp.md && sync.pulls != null

    Column(Modifier.fillMaxSize().background(c.base)) {
        AppHeader(nav, scrolled)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // `mxa max-w-5xl`: the column is centred and at most 1024dp wide.
            val sideMargin = ((maxWidth - 1024.dp) / 2).coerceAtLeast(0.dp)
            val contentStart = if (wide) SIDEBAR + 24.dp else 0.dp
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = sideMargin + hPad, end = sideMargin + hPad, top = if (bp.md) 40.dp else 32.dp),
            ) {
                if (updateNotice != null) item(key = "update") { UpdateBanner(updateNotice); Spacer(Modifier.height(24.dp)) }

                item(key = "title") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t("local.home.title"), style = PullsType.xxl.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Text(t("local.home.tagline"), style = PullsType.sm, color = c.fade)
                    }
                    Spacer(Modifier.height(24.dp))
                }

                item(key = "url") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextInput(
                            url, { url = it }, t("local.home.urlPlaceholder"),
                            Modifier.weight(1f),
                            icon = "link-duotone",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { open() }),
                        )
                        ActionButton({ open() }, variant = ButtonVariant.Primary, icon = "arrow-right-bold", enabled = parsed != null) { Text(t("local.home.open")) }
                    }
                    Spacer(Modifier.height(24.dp))
                }

                item(key = "filters") {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        // `min-w-0 flex-1 basis-60`: a full line on phones, shares the line when there is room.
                        TextInput(search, { search = it }, t("local.home.search"), Modifier.weight(1f).widthIn(min = 240.dp), icon = "magnifying-glass-duotone")
                        ToggleGroup(
                            listOf("open" to t("local.home.state.open"), "closed" to t("local.home.state.closed")),
                            state,
                            { value -> if (value != state) { state = value; queueChoice = "all" } },
                        )
                        Spacer(Modifier.weight(1f))
                        HomeSyncStatus(sync.updating, sync.fetchedAt, if (sync.pulls != null) sync.error else null, reload)
                    }
                    Spacer(Modifier.height(24.dp))
                }

                val pulls = sync.pulls
                when {
                    pulls == null && sync.error == null -> item(key = "loading") { Loading(t("local.home.loading")) }
                    pulls == null -> item(key = "failed") {
                        EmptyState("warning-duotone", t("local.home.failed"), Modifier.fillMaxWidth(), hint = sync.error) {
                            ActionButton(reload, variant = ButtonVariant.Primary, enabled = !sync.updating) { Text(t("common.retry")) }
                        }
                    }
                    else -> {
                        item(key = "content-start") { Spacer(Modifier.height(0.dp)) }
                        if (!wide) item(key = "queues") {
                            HomeQueueNav(queue, { queueChoice = it }, role, { role = it }, filtered.queueCounts, filtered.roleCounts, state == "open", vertical = false)
                            Spacer(Modifier.height(16.dp))
                        }
                        homeGroups(groups, collapsed, contentStart, queue, nav, now, bp)
                    }
                }

                item(key = "footer") { Spacer(Modifier.height(24.dp)); AppFooter(); Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            }

            if (wide) {
                // `md:sticky md:top-4`: the sidebar follows the list's first row until it reaches the top.
                val density = LocalDensity.current
                val topPx by remember {
                    derivedStateOf {
                        val start = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "content-start" }
                        val floor = with(density) { 16.dp.roundToPx() }
                        when {
                            start != null -> maxOf(start.offset, floor)
                            listState.layoutInfo.visibleItemsInfo.any { it.key == "title" || it.key == "url" || it.key == "filters" || it.key == "update" } -> Int.MAX_VALUE
                            else -> floor
                        }
                    }
                }
                if (topPx != Int.MAX_VALUE) {
                    Box(Modifier.offset { IntOffset(with(density) { (sideMargin + hPad).roundToPx() }, topPx) }.width(SIDEBAR)) {
                        HomeQueueNav(queue, { queueChoice = it }, role, { role = it }, filtered.queueCounts, filtered.roleCounts, state == "open", vertical = true)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.homeGroups(
    groups: List<Triage.RepoGroup>,
    collapsed: MutableMap<String, Boolean>,
    start: Dp,
    queue: String,
    nav: Nav,
    now: Long,
    bp: Breakpoints,
) {
    if (groups.isEmpty()) {
        item(key = "empty") {
            EmptyState("git-pull-request-duotone", if (queue == "all") t("local.home.empty") else t("triage.empty"), Modifier.fillMaxWidth().padding(start = start))
        }
        return
    }
    groups.forEachIndexed { index, group ->
        val open = collapsed[group.name] != true
        item(key = "group:${group.name}") {
            Column(Modifier.padding(start = start, top = if (index == 0) 0.dp else 16.dp)) {
                RepoGroupSummary(group, open, { collapsed[group.name] = open }, { nav.openRepo(group.owner, group.repo) })
            }
        }
        if (open) {
            item(key = "rows:${group.name}") {
                Column(
                    Modifier
                        .padding(start = start, top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, LocalPullsColors.current.border, RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp),
                ) {
                    group.pulls.forEach { pull ->
                        HomePullRow(pull, now, bp) { nav.openTarget(OpenTarget.Pull(pull.owner, pull.repo, pull.number, "github.com")) }
                    }
                }
            }
        }
    }
}

/** The `<summary>` of a repo group: caret, branch icon, `owner/repo`, count, and the repo link. */
@Composable
private fun RepoGroupSummary(group: Triage.RepoGroup, open: Boolean, toggle: () -> Unit, openRepo: () -> Unit) {
    val c = LocalPullsColors.current
    val mono = PullsType.sm.copy(fontFamily = PullsType.mono)
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).clickable(role = Role.Button, onClick = toggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon("caret-right-bold", Modifier.rotate(if (open) 90f else 0f), tint = c.fade, size = 13.dp)
        PhIcon("git-branch-duotone", tint = c.fade, size = 16.dp)
        Text(group.name, Modifier.weight(1f, fill = false), style = mono, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${group.pulls.size}", style = mono, color = c.fade)
        Spacer(Modifier.weight(1f))
        Box(Modifier.defaultMinSize(minHeight = TOUCH).clickable(role = Role.Button, onClick = openRepo).padding(start = 8.dp), contentAlignment = Alignment.Center) {
            Text(t("local.home.allInRepo"), style = PullsType.xs, color = c.fade, maxLines = 1)
        }
    }
}

/** `HomeQueueNav`: chips that scroll sideways on phones, a labelled vertical list from `md`. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeQueueNav(
    queue: String,
    onQueue: (String) -> Unit,
    role: String,
    onRole: (String) -> Unit,
    queueCounts: Map<String, Int>,
    roleCounts: Map<String, Int>,
    showQueues: Boolean,
    vertical: Boolean,
) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val queueIcons = mapOf(
        "needs-review" to "eye-duotone",
        "ci-failing" to "x-circle-duotone",
        "changes-requested" to "note-pencil-duotone",
        "ready-to-merge" to "git-merge-duotone",
        "stale" to "hourglass-medium-duotone",
        "all" to "stack-duotone",
    )

    @Composable
    fun item(label: String, count: Int, active: Boolean, icon: String?, iconTint: Color, hint: String?, onClick: () -> Unit) {
        val shape = if (vertical) RoundedCornerShape(6.dp) else RoundedCornerShape(50)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Row(
            Modifier
                .then(if (vertical) Modifier.fillMaxWidth() else Modifier)
                .defaultMinSize(minHeight = TOUCH)
                .clip(shape)
                .then(if (vertical) Modifier else Modifier.border(1.dp, c.border, shape))
                .background(if (active || pressed) c.hover else Color.Transparent)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Tab,
                    onLongClick = hint?.let { { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } },
                    onClick = onClick,
                )
                .padding(horizontal = if (vertical) 8.dp else 12.dp, vertical = if (vertical) 6.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fg = if (active) c.text else c.fade
            if (vertical && icon != null) PhIcon(icon, tint = if (iconTint != Color.Unspecified) iconTint else fg, size = 16.dp)
            Text(
                label,
                if (vertical) Modifier.weight(1f) else Modifier,
                style = PullsType.sm.copy(fontWeight = if (active) FontWeight.Medium else FontWeight.Normal),
                color = fg,
                maxLines = 1,
                softWrap = false,
            )
            Text("$count", style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
        }
    }

    @Composable
    fun section(heading: String, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (vertical) Text(heading, Modifier.padding(horizontal = 8.dp), style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = c.fade)
            if (vertical) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(if (vertical) 20.dp else 12.dp)) {
        if (showQueues) section(t("local.home.queues")) {
            Triage.queues.forEach { q ->
                val count = queueCounts[q] ?: 0
                item(
                    t("triage.queue.$q"), count, queue == q, queueIcons[q],
                    if (q == "ci-failing" && count > 0) StatusColors.red(c.dark) else Color.Unspecified,
                    t("triage.hint.$q", "n" to Triage.STALE_AFTER_DAYS),
                ) { onQueue(q) }
            }
        }
        section(t("local.home.roles")) {
            Triage.roles.forEach { r ->
                item(if (r == "all") t("local.home.all") else t("local.home.role.$r"), roleCounts[r] ?: 0, role == r, null, Color.Unspecified, null) { onRole(r) }
            }
        }
    }
}

/** `HomeSyncStatus`: Updating… / Updated {time} / Couldn't update, and the refresh button. */
@Composable
internal fun HomeSyncStatus(updating: Boolean, fetchedAt: Long?, error: String?, onRefresh: () -> Unit) {
    val c = LocalPullsColors.current
    val now = rememberNow(30_000)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            updating -> {
                Spinner(13.dp)
                Text(t("local.home.updating"), style = PullsType.xs, color = c.fade)
            }
            error != null -> {
                PhIcon("warning-duotone", tint = StatusColors.amber500, size = 14.dp)
                Text(t("local.home.updateFailed", "message" to error), Modifier.widthIn(max = 240.dp), style = PullsType.xs, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            fetchedAt != null -> Text(t("local.home.updatedAgo", "time" to timeAgo(fetchedAt, now)), style = PullsType.xs, color = c.fade)
        }
        IconButton("arrows-clockwise-duotone", t("local.home.refresh"), onRefresh, compact = true, enabled = !updating)
    }
}

/** `i-ph:circle-notch animate-spin`. */
@Composable
fun Spinner(size: Dp = 16.dp, tint: Color = LocalPullsColors.current.fade) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    PhIcon("circle-notch", Modifier.rotate(angle), tint = tint, size = size)
}

/**
 * `HomePullRow`. Below `sm` the title drops to its own line (two lines at most) and the
 * time moves to the end of the first; from `sm` it is one line with the author, from
 * `md` the diff size, from `lg` the first label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomePullRow(pull: PullRow, now: Long, bp: Breakpoints, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val checks = pull.checks.takeIf { it.isNotEmpty() && it != "none" }
    val requested = pull.requestedFromMe ?: (pull.role == "review-requested")
    val title = buildAnnotatedString {
        if (pull.isDraft) withStyle(SpanStyle(fontSize = PullsType.xs.fontSize, color = c.fade)) { append("[${t("local.home.draft")}] ") }
        append(pull.title)
    }
    val time = timeAgo(pull.updatedAtMs, now)

    @Composable
    fun Badges() {
        if (checks != null) ChecksIcon(checks, 13.dp)
        ReviewIcon(pull.reviewDecision, 16.dp)
        if (requested) Chip(t("triage.requested"), c.accentTeal)
        if (pull.mergeable == "conflicting") Chip(t("triage.conflict"), StatusColors.red(c.dark))
    }

    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).pressTint(onClick, label = pull.title)) {
        if (!bp.sm) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrStatusIcon(Triage.status(pull), size = 16.dp)
                    Text("#${pull.number}", Modifier.widthIn(min = 40.dp), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
                    Badges()
                    Spacer(Modifier.weight(1f))
                    Text(time, style = PullsType.xs, color = c.fade, maxLines = 1, textAlign = TextAlign.End)
                }
                Text(title, style = PullsType.sm, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).defaultMinSize(minHeight = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrStatusIcon(Triage.status(pull), size = 16.dp)
                Text("#${pull.number}", Modifier.widthIn(min = 40.dp), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade)
                Text(title, Modifier.weight(1f), style = PullsType.sm, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Badges()
                if (bp.lg && pull.labels.isNotEmpty()) {
                    val text = pull.labels.first() + if (pull.labels.size > 1) " +${pull.labels.size - 1}" else ""
                    Text(text, Modifier.widthIn(max = 96.dp).clip(RoundedCornerShape(4.dp)).background(c.hover).padding(horizontal = 6.dp, vertical = 2.dp), style = PullsType.xs, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (bp.md && pull.additions != null) Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) { DiffStats(pull.additions, pull.deletions) }
                Text(pull.author, Modifier.width(96.dp), style = PullsType.xs, color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(time, Modifier.widthIn(min = 80.dp), style = PullsType.xs, color = c.fade, maxLines = 1, textAlign = TextAlign.End)
            }
        }
    }
}

/** `rounded bg-hover px-1.5 py-0.5 text-xs`. */
@Composable
internal fun Chip(text: String, color: Color) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(4.dp)).background(LocalPullsColors.current.hover).padding(horizontal = 6.dp, vertical = 2.dp),
        style = PullsType.xs,
        color = color,
        maxLines = 1,
    )
}

/** The CI rollup glyph next to a pull request (`CHECKS_ICON`). */
@Composable
internal fun ChecksIcon(checks: String, size: Dp) {
    val dark = LocalPullsColors.current.dark
    val label = t("pulls.checks.$checks")
    when (checks) {
        "success" -> PhIcon("check-bold", tint = StatusColors.green(dark), size = size, contentDescription = label)
        "failure" -> PhIcon("x-bold", tint = StatusColors.red(dark), size = size, contentDescription = label)
        "pending" -> PhIcon("circle-fill", tint = StatusColors.amber500, size = size, contentDescription = label)
    }
}

/** The review decision glyph (`REVIEW`); accepts the home (`changes-requested`) and repo list (`changes_requested`) spellings. */
@Composable
internal fun ReviewIcon(decision: String?, size: Dp) {
    val c = LocalPullsColors.current
    when (decision?.replace('-', '_')) {
        "approved" -> PhIcon("check-circle-duotone", tint = StatusColors.green(c.dark), size = size, contentDescription = t("pulls.review.approved"))
        "changes_requested" -> PhIcon("x-circle-duotone", tint = StatusColors.red(c.dark), size = size, contentDescription = t("pulls.review.changes_requested"))
        "review_required" -> PhIcon("eye-duotone", tint = c.fade, size = size, contentDescription = t("pulls.review.review_required"))
    }
}

/** The app-update notice: download, check the digest and hand it to the installer, all in-app. */
@Composable
private fun UpdateBanner(notice: String) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, c.accentTeal.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .background(c.accentTeal.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhIcon("arrow-circle-up-duotone", tint = c.accentTeal, size = 20.dp)
        Text(progress ?: notice, Modifier.weight(1f), style = PullsType.sm, color = c.text)
        ActionButton({
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
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
                busy = false
                result.onFailure { progress = it.message ?: it.toString() }.onSuccess { progress = "Installing…" }
            }
        }, variant = ButtonVariant.Primary, small = true, loading = busy, enabled = !busy) { Text("Update") }
    }
}

@Suppress("DEPRECATION")
private fun installedVersion(context: Context): Int {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
}
