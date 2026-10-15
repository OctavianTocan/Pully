package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.rememberSidePages
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.data.PullSizes
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.R
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
 * The first page of each repository's list as last seen, so opening it again shows rows at once and refreshes
 * underneath. It is kept on the phone as well, so a repository opened before the app last closed opens at once too (H14).
 */
private object FirstPages {
    class Page(val items: List<RepoPull>, val totalCount: Int?, val next: String?)

    private val pages = HashMap<String, Page>()

    fun get(owner: String, name: String): Page? = pages["$owner/$name".lowercase()]

    fun put(owner: String, name: String, page: Page) { pages["$owner/$name".lowercase()] = page }

    private fun file(dir: File, owner: String, name: String) = File(File(dir, "repo-pulls"), "${"$owner/$name".lowercase().replace('/', '~')}.json")

    /** The page the phone kept from an earlier run, or null. */
    suspend fun read(dir: File, owner: String, name: String): Page? = withContext(Dispatchers.IO) {
        runCatching { Json.parseToJsonElement(file(dir, owner, name).readText()).jsonObject.asRepoPullsPage() }.getOrNull()
            ?.let { Page(it.items, it.totalCount, it.next) }
    }

    /** Keeps the server's answer for the next run; a failed write only costs that. */
    suspend fun write(dir: File, owner: String, name: String, raw: JsonObject) = withContext(Dispatchers.IO) {
        runCatching { file(dir, owner, name).apply { parentFile?.mkdirs() }.writeText(raw.toString()) }
    }
}

/** A first page older than this, as the server answered it, is asked for again from GitHub straight after. */
private const val FIRST_PAGE_FRESH_MS = 60_000L

/** `PullRequestListStore`: the first page, then pages appended through the `next` cursor. */
private class RepoPullsStore(private val repo: Repo, val owner: String, val name: String, private val dir: File) {
    private val seen = FirstPages.get(owner, name)
    var items by mutableStateOf(seen?.items.orEmpty())
    var totalCount by mutableStateOf(seen?.totalCount)
    var next by mutableStateOf(seen?.next)
    var isLoading by mutableStateOf(false)
    var isLoadingMore by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var generation = 0
    val hasMore get() = next != null

    /** Whether the last first page the server sent was an old copy, so a fresh one should follow. */
    private var answeredOld = false

    private suspend fun fetchFirstPage(fresh: Boolean = false) {
        val current = ++generation
        error = null
        try {
            val raw = repo.repoPulls(owner, name, fresh = fresh)
            val page = raw.asRepoPullsPage()
            if (current != generation) return
            items = page.items; totalCount = page.totalCount; next = page.next
            FirstPages.put(owner, name, FirstPages.Page(page.items, page.totalCount, page.next))
            FirstPages.write(dir, owner, name, raw)
            val cachedAt = (raw["cachedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            answeredOld = cachedAt != null && System.currentTimeMillis() - cachedAt > FIRST_PAGE_FRESH_MS
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

    /**
     * The first page: the rows last seen (in this run, else kept on the phone) are on screen at once while it comes, and
     * only a list with none shows the skeleton. The server answers from its own copy; an old one is asked for again.
     */
    suspend fun load() {
        if (items.isEmpty()) FirstPages.read(dir, owner, name)?.let { kept -> if (items.isEmpty()) { items = kept.items; totalCount = kept.totalCount; next = kept.next } }
        if (items.isEmpty()) isLoading = true
        try { fetchFirstPage() } finally { isLoading = false }
        followOldAnswer()
    }

    /** The server answered from a copy older than a minute: ask again for GitHub's own, quietly. */
    private suspend fun followOldAnswer() {
        if (answeredOld && error == null) {
            fetchFirstPage(fresh = true)
            refreshes++
        }
    }

    /** How many quiet refreshes have landed: a search keys on it to fetch the later pages again after one. */
    var refreshes by mutableIntStateOf(0)
        private set

    /** The first page again, quietly, over the rows on screen. */
    suspend fun refresh() {
        fetchFirstPage()
        refreshes++
        followOldAnswer()
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
        // Waits out a page already on its way rather than spinning on the main thread until it lands.
        while (next != null && error == null) if (isLoadingMore) delay(50) else loadMore()
    }
}

/**
 * A repository's open pull requests, in the Inbox's own look: a back circle, the name, sort chips, hairline rows and a
 * search pill. Its tours sit beside them, as Tours does beside the Inbox; a heading on the Tours tab opens the page on
 * them ([startOnTours], H26).
 */
@Composable
fun RepoScreen(owner: String, repo: String, nav: Nav, startOnTours: Boolean = false) {
    val context = LocalContext.current
    val c = LocalPullsColors.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val store = remember(owner, repo) { RepoPullsStore(Repo(context), owner, repo, context.cacheDir) }
    var query by rememberSaveable { mutableStateOf("") }
    // Matches the order pages arrive in, so an unfinished load never looks mis-sorted.
    var sort by rememberSaveable { mutableStateOf("recently-updated") }
    var tab by rememberSaveable { mutableIntStateOf(if (startOnTours) 1 else 0) }
    val tours = remember { TourIndex(deviceStore(context, "pulls-inbox")) }
    val toaster = LocalToaster.current
    // The server's tasks, so removing a tour here records them as dismissed and stops one still being written.
    val server = remember { Repo(context) }
    val workTasks = rememberWork(server)
    LaunchedEffect(store) { store.load() }
    // Refreshing is the page's own job and quiet: on open, and again when the app comes back to the front.
    var away by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { away = true }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (away) {
            away = false
            scope.launch { store.refresh() }
        }
    }
    // Independent of the network, like the web: viewed PRs decorate rows whenever they arrive.
    LaunchedEffect(store) { store.loadViewed() }
    // A client-side search is only trustworthy over the full set: typing drains the remaining pages, and so does a
    // quiet refresh, which leaves only the first page.
    LaunchedEffect(query, store.refreshes) { if (query.isNotBlank()) store.loadAll() }

    LaunchedEffect(store.items) { store.items.forEach { PullSizes.put(owner, repo, it.number, it.additions, it.deletions, it.changedFiles) } }
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
                FloatButton({ uri.openUri("https://github.com/$owner/$repo/pulls") }, "Open on GitHub") { DrawableIcon(R.drawable.ic_carbon_logo_github, size = 22.dp) }
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
            PullToCommands({ Commands.open() }, Modifier.weight(1f).fillMaxWidth()) {
            SidePages(sides, listOf(0, 1), Modifier.fillMaxSize()) { page ->
            if (page == 1) {
                val mine = tours.entries.filter {
                    it.owner.equals(owner, ignoreCase = true) && it.repo.equals(repo, ignoreCase = true) &&
                        (query.isBlank() || "${it.prTitle} #${it.number}".lowercase().contains(query.trim().lowercase()))
                }
                RepoTours(mine, now, onOpen = { entry -> nav.openTour(OpenTarget.Pull(entry.owner, entry.repo, entry.number, "github.com")) }) { entry ->
                    dismissTour(entry, workTasks, tours, server, scope, toaster)
                }
                return@SidePages
            }
            Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                RepoPullRow(pr, now, Modifier.animateItem()) { nav.openTarget(OpenTarget.Pull(owner, repo, pr.number, "github.com")) }
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
            query, { query = it }, if (tab == 1) "Search tours" else t("pulls.search"), null, {},
            Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
    }
}

/** One pull request, as an Inbox row: its number, the whole title, labels, then author, checks, size and age on one line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoPullRow(pr: RepoPull, now: Long, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    val inset = with(LocalDensity.current) { 16.dp.toPx() }
    val title = buildAnnotatedString {
        // The draft mark is muted at the title's own size, not a smaller run (rule 14).
        if (pr.state == "draft") withStyle(SpanStyle(color = c.muted)) { append("[${t("local.home.draft")}] ") }
        append(pr.title)
    }
    Row(
        modifier.fillMaxWidth()
            .cueClickable(role = Role.Button, onClickLabel = pr.title, onClick = onClick)
            .drawBehind { drawRect(c.borderMute, Offset(inset, 0f), Size(size.width - 2 * inset, 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("#${pr.number}", Modifier.width(52.dp), style = PullsType.num.copy(fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = PullsType.row, color = c.text)
            if (pr.labels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pr.labels.forEach { LabelChip(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pr.author?.login ?: "ghost", Modifier.weight(1f), style = PullsType.sm, color = c.muted)
                Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
                    when (pr.checks) {
                        "failure" -> LuIcon(Lu.xc, tint = c.error, size = 18.dp, contentDescription = t("pulls.checks.failure"))
                        "success" -> LuIcon(Lu.okc, tint = c.success, size = 18.dp, contentDescription = t("pulls.checks.success"))
                        "pending" -> Box(Modifier.size(8.dp).clip(CircleShape).background(StatusColors.amber500))
                    }
                }
                val adds = pr.additions
                if (adds != null) {
                    val mono = PullsType.sm.copy(fontFamily = PullsType.mono, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("+${compactCount(adds)}", Modifier.width(56.dp), style = mono, color = c.success, maxLines = 1, softWrap = false)
                        Text("−${compactCount(pr.deletions ?: 0)}", Modifier.width(40.dp), style = mono, color = c.error, maxLines = 1, softWrap = false)
                    }
                }
                Text(compactAge(parseInstant(pr.updatedAt.ifBlank { pr.createdAt }), now), Modifier.width(34.dp), style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted, textAlign = TextAlign.End, maxLines = 1)
            }
        }
    }
}

/** A GitHub label chip, colored from its own hex like the web's `labelStyle`. */
@Composable
private fun LabelChip(label: GhLabel) {
    val c = LocalPullsColors.current
    val colors = remember(label.color, c.dark) { LabelStyle.of(label.color, c.dark) }
    val fg = colors?.let { Color(it.text) } ?: c.muted // raw-value-ok: the label's own colour from GitHub
    val bg = colors?.let { Color(it.background) } ?: c.paper2 // raw-value-ok: the label's own colour from GitHub
    Text(
        label.name,
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
        style = PullsType.sm.copy(fontWeight = FontWeight.Medium),
        color = fg,
        maxLines = 1,
    )
}

/** `ViewedPullRequest`: the cached diff's size and grouping for a PR opened before. */
data class Viewed(val additions: Int, val deletions: Int, val groups: Int, val hasAiResult: Boolean)

/** A repository's tours, as rows of the Tours tab without the headings: tap or swipe right to read, swipe left to remove. */
@Composable
private fun RepoTours(entries: List<TourEntry>, now: Long, onOpen: (TourEntry) -> Unit, onRemove: (TourEntry) -> Unit) {
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
                label = entry.prTitle, onOpen = { onOpen(entry) }, onArchive = { onRemove(entry) }, archived = false,
                modifier = Modifier.animateItem(), endLabel = "Remove", endIcon = Lu.close,
            ) { TourRowContent(entry, null, now) }
        }
    }
}
