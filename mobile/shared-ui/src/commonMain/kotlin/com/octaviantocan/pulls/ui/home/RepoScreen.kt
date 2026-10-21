package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.core.pullKey
import com.octaviantocan.pulls.core.runningTaskId
import com.octaviantocan.pulls.ui.design.sideScrollFade
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.rememberSidePages
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import com.octaviantocan.pulls.sound.cueClickable
import kotlinx.serialization.json.put
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.FloatButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.topEdgeStrength
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A repository's open pull requests, in the Inbox's own look: a back circle, the name, sort chips, hairline rows and a
 * search pill. Its tours sit beside them, as Tours does beside the Inbox; a heading on the Tours tab opens the page on
 * them ([startOnTours], H26).
 */
@Composable
fun RepoScreen(owner: String, repo: String, nav: Nav, startOnTours: Boolean, services: HomeScreenServices) {
    CompositionLocalProvider(LocalHomeScreenServices provides services, LocalNativeHomeActions provides services.native) {
        val guarded = remember(nav, services) { services.navigation(nav) }
        SourceOwnedScreen(services.data) { RepoScreenBody(owner, repo, guarded, startOnTours) }
    }
}

@Composable
private fun RepoScreenBody(owner: String, repo: String, nav: Nav, startOnTours: Boolean) {
    val services = LocalHomeScreenServices.current
    val c = LocalPullsColors.current
    val github = services.native::openOnGithub
    val scope = rememberCoroutineScope()
    val store = remember(owner, repo, services.data) { services.data.repository(owner, repo) }
    DisposableEffect(store) { onDispose { services.data.release(store) } }
    var query by rememberSaveable { mutableStateOf("") }
    // Matches the order pages arrive in, so an unfinished load never looks mis-sorted.
    var sort by rememberSaveable { mutableStateOf("recently-updated") }
    var tab by rememberSaveable { mutableIntStateOf(if (startOnTours) 1 else 0) }
    val tours = services.home.tours
    val toaster = LocalToaster.current
    // The server's tasks, so removing a tour here records them as dismissed and stops one still being written.
    val server = services.repo
    val workTasks = if (services.capabilities.taskObservation) rememberWork(server) else emptyList()
    LaunchedEffect(store) { store.load() }
    // Refreshing is the page's own job and quiet: on open, and again when the app comes back to the front.
    var away by remember { mutableStateOf(false) }
    HomeLifecycle(onStop = { away = true }, onStart = {
        if (away) {
            away = false
            scope.launch { store.refresh() }
        }
    })
    // Independent of the network, like the web: viewed PRs decorate rows whenever they arrive.
    LaunchedEffect(store) { if (services.capabilities.reviewRoutes) store.loadViewed() }
    // A client-side search is only trustworthy over the full set: typing drains the remaining pages, and so does a
    // quiet refresh, which leaves only the first page.
    LaunchedEffect(query, store.refreshes) { if (query.isNotBlank()) store.loadAll() }

    LaunchedEffect(store.items) { store.items.forEach { if (services.data.current) services.effects.size(owner, repo, it.number, it.additions, it.deletions, it.changedFiles) } }
    val rows = remember(store.items, query, sort) { PullListQuery.sort(PullListQuery.filter(store.items, query), sort) }
    val isSearching = query.isNotBlank() && store.hasMore
    val listState = rememberLazyListState()
    val band = with(LocalDensity.current) { PullsMotion.EdgeTop.toPx() }
    val now = rememberNow()

    // Fetch the next page once the tail comes within 10 rows, which also covers a short first page.
    LaunchedEffect(listState, store) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && last >= total - 10 && store.hasMore && store.error == null) store.loadMore() }
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = headerTopPadding()), verticalAlignment = Alignment.CenterVertically) {
                FloatIcon(Lu.back, "Back", nav.back, iconSize = 22.dp)
                Spacer(Modifier.weight(1f))
                // The GitHub mark says where it goes; no arrow-out icon is ever used for that (rule 100).
                FloatButton({ github("https://github.com/$owner/$repo/pulls") }, "Open on GitHub") { GithubIcon(size = 22.dp) }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GithubAvatar(owner, size = 18.dp)
                    Text(owner, style = PullsType.sm, color = c.muted)
                    store.totalCount?.let { Text("· ${t("pulls.open", "n" to it)}", style = PullsType.sm, color = c.muted) }
                }
                Text(repo, style = PullsType.xxl, color = c.text)
            }
            val sides = rememberSidePages(tab, 2, { tab = it })
            CapsuleTabs(listOf("Pull requests", "Tours"), tab, { tab = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), position = { sides.at })
            // Pulling down past the top opens Commands here as on every page (J30).
            PullToCommands({ services.commands() }, Modifier.weight(1f).fillMaxWidth()) {
            SidePages(sides, listOf(0, 1), Modifier.fillMaxSize()) { page ->
            if (page == 1) {
                val mine = tours.entries.filter {
                    it.owner.equals(owner, ignoreCase = true) && it.repo.equals(repo, ignoreCase = true) &&
                        (query.isBlank() || "${it.prTitle} #${it.number}".lowercase().contains(query.trim().lowercase()))
                }
                RepoTours(mine, now, stoppable = { entry -> runningTaskId(workTasks, pullKey(entry.owner, entry.repo, entry.number), "tour") != null }, onOpen = { entry -> nav.openFrom(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com"), "repo", true) }) { entry ->
                    dismissTour(entry, workTasks, tours, server, scope, toaster) { services.data.current }
                }
                return@SidePages
            }
            Column(Modifier.fillMaxSize()) {
            val sortScroll = rememberScrollState()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).sideScrollFade(sortScroll).horizontalScroll(sortScroll).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PullListQuery.sorts.forEach { option -> SoftChip(t("pulls.sort.$option"), option == sort, { sort = option }) }
            }
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().edgeFade(top = PullsMotion.EdgeTop, bottom = PullsMotion.EdgeBottom, topStrength = { listState.topEdgeStrength(band) }),
                state = listState,
                contentPadding = PaddingValues(bottom = bottom),
            ) {
                when {
                    // No loading screen: the shape of the rows, replaced in place when they arrive.
                    store.isLoading -> item(key = "loading") { RowsSkeleton() }
                    store.error != null && store.items.isEmpty() -> item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(t("pulls.loadFailed"), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                            Text(store.error.orEmpty(), style = PullsType.sm, color = c.muted)
                            Text("GitHub access comes from the server's sign-in. Check it there, then retry.", style = PullsType.sm, color = c.muted)
                            PillButton(t("common.retry"), { scope.launch { store.load() } })
                        }
                    }
                    else -> {
                        if (store.items.isNotEmpty()) {
                            items(rows, key = { it.number }) { pr ->
                                RepoPullRow(pr, now, Modifier.animateItem()) { nav.openFrom(OpenTarget.Pull(owner, repo, pr.number, "github.com"), "repo", false) }
                            }
                            if (rows.isEmpty() && !isSearching) item {
                                Text(t("pulls.noMatches"), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
                            }
                        } else item {
                            Text(t("pulls.empty"), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 48.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center)
                        }
                        if (isSearching || store.isLoadingMore) item { RowsSkeleton(rows = 2) }
                        if (store.error != null && store.items.isNotEmpty()) item {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(store.error.orEmpty(), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                                PillButton(t("common.retry"), { scope.launch { if (store.hasMore) store.loadMore() else store.refresh() } })
                            }
                        }
                    }
                }
            }
            }
            }
            }
        }
        // `.search`: the list fades into the page under a floating search pill (its own `edgeFade`), as in the Inbox, and
        // nothing else sits behind the pill: it floats on its own shadow (rule 74).
        SearchPill(
            "repo-search", query, { query = it }, if (tab == 1) "Search tours" else t("pulls.search"), null, {},
            Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * One pull request, as an Inbox row (N4): its state's tile, the title, then the number, author, checks, size and age on
 * one line in fixed columns. Its labels are not shown, so the row is no taller than any other (Tavi's pick, 17 Oct; Q1).
 */
@Composable
private fun RepoPullRow(pr: RepoPull, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    PullRowFrame(
        AnnotatedString(pr.title),
        tile = { StateTile(pullStateOf(pr.state)) },
        modifier = modifier.cueClickable(role = Role.Button, onClickLabel = pr.title, onClick = onClick),
    ) {
        RowNumber(pr.number)
        Text(pr.author?.login ?: "ghost", Modifier.weight(1f), style = PullsType.sm, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        RowFacts({ ChecksMark(pr.checks) }, pr.additions, pr.deletions, compactAge(parseInstant(pr.updatedAt.ifBlank { pr.createdAt }), now))
    }
}

/** A repository's tours, as rows of the Tours tab without the headings: tap or swipe right to read, swipe left to remove. */
@Composable
private fun RepoTours(entries: List<TourEntry>, now: Long, stoppable: (TourEntry) -> Boolean, onOpen: (TourEntry) -> Unit, onRemove: (TourEntry) -> Unit) {
    var held by remember { mutableStateOf<TourEntry?>(null) }
    // A tour still being written is stopped, not removed, as on Home's Tours.
    held?.let { entry -> TourActionsSheet(entry, stoppable = stoppable(entry), onOpen = { onOpen(entry) }, onDismiss = { onRemove(entry) }, onClose = { held = null }) }
    val c = LocalPullsColors.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 100.dp
    if (entries.isEmpty()) {
        Text(
            "No tours of this repository yet. Open one of its pull requests and tap Tour.",
            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), style = PullsType.sm, color = c.muted, textAlign = TextAlign.Center,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(top = 10.dp).edgeFade(bottom = PullsMotion.EdgeBottom), contentPadding = PaddingValues(bottom = bottom)) {
        items(entries, key = { it.key }) { entry ->
            SwipeRow(
                // As on Home's Tours: a swipe left opens, a swipe right goes back to Pull requests, and Remove is on hold (M4).
                label = entry.prTitle, onOpen = { onOpen(entry) }, onArchive = null, archived = false,
                modifier = Modifier.animateItem(), opensLeft = true, onLongClick = { held = entry },
            ) { TourRowContent(entry, null, now) }
        }
    }
}
