package com.octaviantocan.pulls.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.serialization.json.put
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.DiffStats
import com.octaviantocan.pulls.ui.design.EmptyState
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.Loading
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PrStatusIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** `PullRequestListStore`: the first page, then pages appended through the `next` cursor. */
private class RepoPullsStore(private val repo: Repo, val owner: String, val name: String) {
    var items by mutableStateOf<List<RepoPull>>(emptyList())
    var totalCount by mutableStateOf<Int?>(null)
    var next by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(false)
    var isRefreshing by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var generation = 0
    val hasMore get() = next != null

    private suspend fun fetchFirstPage() {
        val current = ++generation
        error = null
        try {
            val page = repo.repoPulls(owner, name).asRepoPullsPage()
            if (current != generation) return
            items = page.items; totalCount = page.totalCount; next = page.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current == generation) error = e.message ?: e.toString()
        }
    }

    /** `viewed`: what the server's cache holds for PRs of this repo already opened (`loadViewed`). */
    var viewed by mutableStateOf<Map<Int, Viewed>>(emptyMap())
        private set

    suspend fun loadViewed() {
        runCatching { repo.call("repo-viewed") { put("owner", owner); put("repo", name) } }.onSuccess { result ->
            viewed = (result as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun int(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0
                int("number") to Viewed(int("additions"), int("deletions"), int("groups"), (o["hasAiResult"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true")
            }.toMap()
        }
    }

    suspend fun load() {
        val cached = items.isNotEmpty()
        if (cached) isRefreshing = true else isLoading = true
        try { fetchFirstPage() } finally { isLoading = false; isRefreshing = false }
    }

    suspend fun refresh() {
        isRefreshing = true
        try { fetchFirstPage() } finally { isRefreshing = false }
    }

    suspend fun loadMore() {
        val cursor = next ?: return
        if (isLoadingMore) return
        val current = generation
        isLoadingMore = true
        error = null
        try {
            val page = repo.repoPulls(owner, name, cursor).asRepoPullsPage()
            if (current != generation) return
            items = items + page.items; totalCount = page.totalCount; next = page.next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            isLoadingMore = false
        }
    }

    suspend fun loadAll() {
        while (next != null && error == null) loadMore()
    }
}

/** Port of `PullsPage` (`PullsHeader`, `PullRequestList`, `PullRequestRow`): a repository's open pull requests. */
@Composable
fun RepoScreen(owner: String, repo: String, nav: Nav) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val scope = rememberCoroutineScope()
    val store = remember(owner, repo) { RepoPullsStore(Repo(context), owner, repo) }
    var query by rememberSaveable { mutableStateOf("") }
    // Matches the order pages arrive in, so an unfinished load never looks mis-sorted.
    var sort by rememberSaveable { mutableStateOf("recently-updated") }
    LaunchedEffect(store) { store.load() }
    // Independent of the network, like the web: viewed PRs decorate rows whenever they arrive.
    LaunchedEffect(store) { store.loadViewed() }
    // A client-side search is only trustworthy over the full set: typing drains the remaining pages.
    LaunchedEffect(query) { if (query.isNotBlank()) store.loadAll() }

    val rows = remember(store.items, query, sort) { PullListQuery.sort(PullListQuery.filter(store.items, query), sort) }
    val isSearching = query.isNotBlank() && store.hasMore
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 20 } }

    // Fetch the next page once the tail comes within 10 rows, which also covers a short first page.
    LaunchedEffect(listState, store) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && last >= total - 10 && store.hasMore && store.error == null) store.loadMore() }
    }

    Column(Modifier.fillMaxSize().background(c.base)) {
        PullsHeader(store, query, { query = it }, sort, { sort = it }, scrolled, nav) { scope.launch { store.refresh() } }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val side = ((maxWidth - 1152.dp) / 2).coerceAtLeast(0.dp) // mxa max-w-6xl
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = side)) {
                when {
                    store.isLoading -> item { Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), contentAlignment = Alignment.Center) { Loading(t("pulls.loading")) } }
                    store.error != null && store.items.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                            EmptyState("warning-duotone", t("pulls.loadFailed"), hint = store.error) {
                                ActionButton({ scope.launch { store.load() } }, variant = ButtonVariant.Primary) { Text(t("common.retry")) }
                            }
                            // `GithubTokenRecovery`: the server owns the GitHub sign-in here.
                            Text("GitHub access comes from the server's sign-in (gh). Check it there, then retry.", Modifier.widthIn(max = 800.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(16.dp), style = PullsType.sm, color = c.fade)
                        }
                    }
                    else -> {
                        if (store.items.isNotEmpty()) {
                            itemsIndexed(rows, key = { _, pr -> pr.number }) { index, pr ->
                                val first = index == 0
                                val last = index == rows.lastIndex
                                // The rows share one `border border-base rounded-lg` box.
                                val shape = RoundedCornerShape(topStart = if (first) 8.dp else 0.dp, topEnd = if (first) 8.dp else 0.dp, bottomStart = if (last) 8.dp else 0.dp, bottomEnd = if (last) 8.dp else 0.dp)
                                Box(Modifier.fillMaxWidth().clip(shape).sideBorders(c.border, first, last)) {
                                    PullRequestRow(pr, last, store.viewed[pr.number]) { nav.openTarget(OpenTarget.Pull(owner, repo, pr.number, "github.com")) }
                                }
                            }
                            if (rows.isEmpty() && !isSearching) item {
                                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 48.dp), contentAlignment = Alignment.Center) {
                                    EmptyState("magnifying-glass-duotone", t("pulls.noMatches"))
                                }
                            }
                        } else item {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), contentAlignment = Alignment.Center) { EmptyState("git-pull-request-duotone", t("pulls.empty")) }
                        }
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when {
                                    isSearching -> Loading(t("pulls.searchingAll", "n" to (store.totalCount ?: 0)))
                                    store.isLoadingMore -> Loading(t("pulls.loadingMore"))
                                    store.error != null -> {
                                        Text(store.error!!, style = PullsType.sm, color = c.error)
                                        ActionButton({ scope.launch { if (store.hasMore) store.loadMore() else store.refresh() } }, small = true) { Text(t("common.retry")) }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            }
        }
    }
}

/** Left/right (and top on the first row) parts of the shared list border; rows draw their own bottom border. */
private fun Modifier.sideBorders(color: Color, first: Boolean, last: Boolean): Modifier = this.then(
    Modifier.drawBehindBorder(color, first, last),
)

private fun Modifier.drawBehindBorder(color: Color, first: Boolean, last: Boolean): Modifier = drawWithContent {
    drawContent()
    val w = 1.dp.toPx()
    val r = 8.dp.toPx()
    val top = if (first) r else 0f
    val bottom = if (last) size.height - r else size.height
    drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, top), androidx.compose.ui.geometry.Offset(w / 2, bottom), w)
    drawLine(color, androidx.compose.ui.geometry.Offset(size.width - w / 2, top), androidx.compose.ui.geometry.Offset(size.width - w / 2, bottom), w)
    if (first) {
        drawLine(color, androidx.compose.ui.geometry.Offset(r, w / 2), androidx.compose.ui.geometry.Offset(size.width - r, w / 2), w)
        drawArc(color, 180f, 90f, false, androidx.compose.ui.geometry.Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(2 * r - w, 2 * r - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(color, 270f, 90f, false, androidx.compose.ui.geometry.Offset(size.width - 2 * r + w / 2, w / 2), androidx.compose.ui.geometry.Size(2 * r - w, 2 * r - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    }
    if (last) {
        drawLine(color, androidx.compose.ui.geometry.Offset(r, size.height - w / 2), androidx.compose.ui.geometry.Offset(size.width - r, size.height - w / 2), w)
        drawArc(color, 90f, 90f, false, androidx.compose.ui.geometry.Offset(w / 2, size.height - 2 * r + w / 2), androidx.compose.ui.geometry.Size(2 * r - w, 2 * r - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawArc(color, 0f, 90f, false, androidx.compose.ui.geometry.Offset(size.width - 2 * r + w / 2, size.height - 2 * r + w / 2), androidx.compose.ui.geometry.Size(2 * r - w, 2 * r - w), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
    }
}

/** `PullsHeader`: home, `owner / repo`, `{n} open`, refresh, NavControls; then the filter and sort. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullsHeader(
    store: RepoPullsStore,
    query: String,
    onQuery: (String) -> Unit,
    sort: String,
    onSort: (String) -> Unit,
    scrolled: Boolean,
    nav: Nav,
    onRefresh: () -> Unit,
) {
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val spin = rememberInfiniteTransition(label = "refresh")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (scrolled) Modifier.shadow(6.dp) else Modifier)
            .background(c.base)
            .bottomBorder(if (scrolled) c.border else Color.Transparent)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            IconButton("house-line-duotone", t("local.home.title"), nav.home, tint = c.accentTeal)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.weight(1f, fill = false).defaultMinSize(minHeight = TOUCH).clickable { uri.openUri("https://github.com/${store.owner}") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GithubAvatar(store.owner, size = 20.dp)
                    Text(store.owner, style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold), color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("/", style = PullsType.lg, color = c.mute)
                Text(
                    store.name,
                    Modifier.weight(1f, fill = false).clickable { uri.openUri("https://github.com/${store.owner}/${store.name}/pulls") },
                    style = PullsType.lg.copy(fontWeight = FontWeight.SemiBold),
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                store.totalCount?.let { Text(t("pulls.open", "n" to it), Modifier.padding(start = 4.dp), style = PullsType.sm, color = c.fade, maxLines = 1) }
                Box(Modifier.rotate(if (store.isRefreshing) angle else 0f)) { IconButton("arrows-clockwise-duotone", t("common.refresh"), onRefresh) }
            }
            NavControls(nav)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextInput(query, onQuery, t("pulls.search"), Modifier.weight(1f).widthIn(min = 240.dp), icon = "magnifying-glass")
            SelectField(sort, PullListQuery.sorts.map { it to t("pulls.sort.$it") }, onSort, t("pulls.sort.label"))
        }
    }
}

/** `PullRequestRow`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullRequestRow(pr: RepoPull, last: Boolean, viewed: Viewed?, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val now = rememberNow()
    val opened = timeAgo(parseInstant(pr.createdAt), now)
    Row(
        Modifier
            .fillMaxWidth()
            .pressTint(onClick, label = pr.title)
            .then(if (last) Modifier else Modifier.bottomBorder(c.border))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrStatusIcon(pr.state, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(pr.title, style = PullsType.base.copy(fontWeight = FontWeight.Medium, lineHeight = PullsType.base.fontSize * 1.375f), color = c.text)
                pr.checks?.let { ChecksIcon(it, 13.dp) }
                pr.labels.forEach { LabelChip(it) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                val meta = PullsType.xs
                Text("#${pr.number}", style = meta, color = c.fade)
                OpenedBy(opened, pr.author)
                pr.milestone?.let {
                    Text("·", style = meta, color = c.fade)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PhIcon("flag-duotone", tint = c.fade, size = 13.dp, contentDescription = t("pulls.milestone"))
                        Text(it, style = meta, color = c.fade)
                    }
                }
                pr.reviewDecision?.let {
                    Text("·", style = meta, color = c.fade)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ReviewIcon(it, 13.dp)
                        Text(t("pulls.review.$it"), style = meta, color = c.fade)
                    }
                }
            }
        }
        Column(Modifier.padding(top = 2.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The locally viewed diff is the one the reader will reopen, so its size wins.
            if (viewed != null) DiffStats(viewed.additions, viewed.deletions) else if (pr.additions != null) DiffStats(pr.additions, pr.deletions)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viewed != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (viewed.hasAiResult) PhIcon("sparkle-duotone", tint = c.accentTeal, size = 13.dp, contentDescription = t("pulls.aiAvailable"))
                    else PhIcon("stack-duotone", tint = c.fade, size = 13.dp)
                    Text(tc("pulls.groups", viewed.groups), style = PullsType.xs, color = c.fade)
                }
                pr.linkedIssues?.takeIf { it > 0 }?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DrawableIcon(R.drawable.ic_octicon_issue_closed_16, tint = c.fade, size = 13.dp, contentDescription = tc("pulls.linkedIssues", it))
                        Text("$it", style = PullsType.xs, color = c.fade)
                    }
                }
                if (pr.assignees.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                    pr.assignees.forEach { a ->
                        GithubAvatar(a.login, Modifier.border(2.dp, c.base, CircleShape), avatarUrl = a.avatarUrl, size = 20.dp)
                    }
                }
                if (pr.comments > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhIcon("chat-circle-duotone", tint = c.fade, size = 13.dp, contentDescription = tc("pulls.comments", pr.comments))
                    Text("${pr.comments}", style = PullsType.xs, color = c.fade)
                }
            }
        }
    }
}

/** `pulls.openedBy` with the avatar and login in the `{author}` slot. */
@Composable
private fun OpenedBy(time: String, author: GhUser?) {
    val c = LocalPullsColors.current
    val template = t("pulls.openedBy", "time" to time)
    val (before, after) = template.split("{author}").let { it.first() to it.getOrElse(1) { "" } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (before.isNotEmpty()) Text(before, style = PullsType.xs, color = c.fade)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (author != null) GithubAvatar(author.login, avatarUrl = author.avatarUrl, size = 14.dp)
            Text(author?.login ?: "ghost", style = PullsType.xs.copy(fontWeight = FontWeight.Medium), color = c.fade)
        }
        if (after.isNotEmpty()) Text(after, style = PullsType.xs, color = c.fade)
    }
}

/** A GitHub label chip, colored from its own hex like the web's `labelStyle`. */
@Composable
private fun LabelChip(label: GhLabel) {
    val c = LocalPullsColors.current
    val colors = remember(label.color, c.dark) { LabelStyle.of(label.color, c.dark) }
    val fg = colors?.let { Color(it.text) } ?: c.fade
    val bg = colors?.let { Color(it.background) } ?: c.hover
    val border = colors?.let { Color(it.border) } ?: c.border
    Text(
        label.name,
        Modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, border, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = PullsType.xs.copy(fontWeight = FontWeight.Medium, lineHeight = PullsType.xs.fontSize),
        color = fg,
        maxLines = 1,
    )
}

/** `ViewedPullRequest`: the cached diff's size and grouping for a PR opened before. */
data class Viewed(val additions: Int, val deletions: Int, val groups: Int, val hasAiResult: Boolean)
