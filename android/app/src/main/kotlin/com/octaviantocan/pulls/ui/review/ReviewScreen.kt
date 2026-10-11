package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.ai.ChatWidget
import com.octaviantocan.pulls.ui.ai.CritiquePanel
import com.octaviantocan.pulls.ui.ai.SharedAnalysisBanner
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.EmptyState
import com.octaviantocan.pulls.ui.design.Loading
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.t
import kotlinx.coroutines.delay

/** Key of the overview panel's item, so the header chips can scroll to it. */
private const val OVERVIEW_ITEM = "top:overview"

/**
 * Port of `DiffsPage.vue` + `diff.vue`: one scrolling page. The header (`DiffsHeader`), the
 * commit range banner and `PrOverviewPanel`, the stale and shared-analysis banners, the
 * review summaries (only without an overview, like the web), `CritiquePanel` and the overall
 * AI summary come first; [diffBodyItems] comes LAST (it ends with the end-of-diff footer).
 * The header scrolls away on phones, as the web header does below `md`.
 */
@Composable
fun ReviewScreen(target: OpenTarget, nav: Nav) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(target) { ReviewSession(target, Repo(context), scope, AppSettings.get(context)).also { it.load() } }
    DisposableEffect(session) { onDispose { session.detach() } }
    val listState = rememberLazyListState()
    val ui = rememberDiffUi(session)
    val top = remember(session) { ReviewTopUi(context) }
    val data = remember(session.overview) { parsePrOverviewData(session.overview) }
    val c = LocalPullsColors.current

    OverviewEffects(session, top, data, listState)
    PrCommands(session, ui, listState, onAnalyze = { com.octaviantocan.pulls.ui.ai.runAnalyzeFromCommand(session) })

    Box(Modifier.fillMaxSize()) {
        val opened = session.opened
        when {
            opened == null && session.loading -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                CommitRangeBanner(session, data?.overview)
                Loading(t("pr.loading"))
            }
            opened == null && session.loadError != null -> Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 48.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CommitRangeBanner(session, data?.overview)
                EmptyState("warning-duotone", t("pr.loadFailed"), hint = session.loadError) {
                    ActionButton({ session.refresh() }, variant = ButtonVariant.Primary) { Text(t("common.retry")) }
                }
            }
            opened != null -> {
                val visibleGroups by remember(listState) {
                    derivedStateOf {
                        // A group is visible while any of its items (header, tree, files) is on screen, like the web's rect test.
                        val owners = ui.groupOfKey
                        listState.layoutInfo.visibleItemsInfo.mapNotNull { info -> (info.key as? String)?.let { owners[it] } }.toSet()
                    }
                }
                // Inset the list itself so pinned file headers stop below the status bar; the header's own inset is consumed here.
                LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), state = listState) {
                    item(key = "top:header") {
                        ReviewHeader(session, top, data, nav, visibleGroups.ifEmpty { setOfNotNull(session.groups.firstOrNull()?.key) })
                    }
                    if (session.pull != null) {
                        item(key = "top:range") { CommitRangeBanner(session, data?.overview, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) }
                        item(key = OVERVIEW_ITEM) { OverviewPanel(session, top, data, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) }
                    }
                    if (session.loading) item(key = "top:loading") { Loading(t("common.loading"), Modifier.padding(horizontal = 12.dp)) }
                    if (session.loadError != null) item(key = "top:refreshError") {
                        Text(session.loadError.orEmpty(), Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp), style = PullsType.sm, color = WebColors.red(c.dark))
                    }
                    if (isStale(session, data?.overview) && session.selection == null && !session.settings.autoRefresh) item(key = "top:stale") {
                        StaleBanner(session, { top.polls = 0; session.refresh() }, Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp))
                    }
                    item(key = "top:shared") { SharedAnalysisBanner(session, Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp)) }
                    val summaries = session.reviewData?.summaries.orEmpty()
                    if (summaries.isNotEmpty() && data == null) item(key = "top:summaries") {
                        ReviewSummaries(summaries, Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp))
                    }
                    item(key = "top:critique") { CritiquePanel(session, Modifier.padding(top = 16.dp)) }
                    val overall = session.saved?.analysis?.overallSummary?.takeIf { session.analyzeMode == "ai" && session.hasAiResult }
                    if (!overall.isNullOrBlank()) item(key = "top:summary") {
                        Column(Modifier.fillMaxWidth().padding(top = 16.dp).bottomBorder(c.border).padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                            Markdown(overall, style = PullsType.sm.copy(color = c.fade))
                        }
                    }
                    diffBodyItems(session, ui)
                }
            }
        }
        DiffFocusEffect(session, ui, listState)
        ChatWidget(session)
    }

    // Sheets live outside the list so scrolling their opener away does not close them.
    if (session.opened != null) {
        ReviewProgressSheet(session, top)
        ReviewSubmitSheet(session, top)
        if (data != null && data.mergeability.allowed) MergeSheet(session, top, data)
    }
}

/**
 * The overview's side effects: scroll to the panel when a chip asks (`focusRequest`),
 * re-fetch while GitHub is still computing mergeability or checks are pending
 * (`revalidateDelay`, at most 20 polls), and pull new commits in at once when the
 * auto-refresh setting is on and the PR head moved (the web's `checkStaleness`).
 */
@Composable
private fun OverviewEffects(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData?, listState: LazyListState) {
    LaunchedEffect(top.focusRequest) {
        if (top.focusRequest == 0) return@LaunchedEffect
        val index = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == OVERVIEW_ITEM }?.index ?: 2
        listState.animateScrollToItem(index)
    }
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
