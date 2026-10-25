package com.octaviantocan.pulls.ui.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.PersonalAccountPull
import com.octaviantocan.pulls.data.PersonalPullRelation
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SidePages
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.rememberSidePages
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.InboxGrouping
import com.octaviantocan.pulls.ui.home.InboxHeader
import com.octaviantocan.pulls.ui.home.InboxList
import com.octaviantocan.pulls.ui.home.InboxSort
import com.octaviantocan.pulls.ui.home.PullActionsSheet
import com.octaviantocan.pulls.ui.home.SearchDim
import com.octaviantocan.pulls.ui.home.SearchPill
import com.octaviantocan.pulls.ui.home.inboxSections
import com.octaviantocan.pulls.ui.home.rememberNow

/** The existing Inbox presentation, backed only by this verified personal reader's selections. */
@Composable
internal fun PersonalHomeScreen(
    session: PersonalBrowseSession,
    screen: Screen.PersonalPulls,
    onAccount: () -> Unit,
    onRepositories: () -> Unit,
    onWorkspace: () -> Unit,
    onCommands: () -> Unit,
    onOpenGitHub: (String) -> Unit,
) {
    val colors = LocalPullsColors.current
    val tab = session.homeTab(screen)
    val relation = session.relation(screen)
    val state = remember(session, screen, relation) { session.pulls(screen) }
    val query = session.homeQuery(screen)
    var focused by remember(session, screen) { mutableStateOf(false) }
    val folded = session.homeFolded(screen)
    var held by remember(session, screen) { mutableStateOf<Pair<PersonalAccountPull, PullRow>?>(null) }
    val originals = remember(state.rows) { state.rows.associateBy { "${it.repository.fullName}#${it.number}" } }
    val needle = query.trim()
    val rows = remember(state.rows, needle, relation) {
        state.rows.filter { needle.isEmpty() || "${it.repository.fullName} #${it.number} ${it.title} ${it.author.orEmpty()}".contains(needle, ignoreCase = true) }
            .map { it.homeRow(relation) }
    }
    val now = rememberNow()
    val sections = inboxSections(InboxSort.Updated.apply(rows), InboxGrouping.Repo).map { section ->
        val repository = originals.values.firstOrNull { "${it.repository.owner}/${it.repository.name}" == section.repo?.name }?.repository
        section.copy(label = listOfNotNull(section.label, repository?.let { t(if (it.private) "personalBrowse.private" else "personalBrowse.public") }).joinToString(" · "))
    }
    val sides = rememberSidePages(tab, 2, { session.selectHomeTab(screen, it) })
    LaunchedEffect(state, tab) { if (tab == 0) state.start() }

    fun owned(row: PullRow, action: (PersonalAccountPull) -> Unit) {
        originals["${row.owner}/${row.repo}#${row.number}"]?.let { original ->
            state.open(original) { session.runFrom(screen) { action(it) } }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.base)) {
        Column(Modifier.fillMaxSize()) {
            InboxHeader(tab, { session.selectHomeTab(screen, it) }, session.login,
                onSettings = onCommands, onProfile = { session.runFrom(screen, onAccount) },
                position = { sides.at }, onRepositories = { session.runFrom(screen, onRepositories) })
            SidePages(sides, listOf(0, 1), Modifier.weight(1f).fillMaxWidth()) { page ->
                if (page == 0) Column(Modifier.fillMaxSize()) {
                    UnderlineTabs(PersonalPullRelation.entries.map { t("personalBrowse.relation.${it.name}") },
                        PersonalPullRelation.entries.indexOf(relation), { session.selectRelation(screen, PersonalPullRelation.entries[it]) },
                        Modifier.padding(horizontal = PullsSpace.x4))
                    InboxList(
                        sections, emptyMap(), now, emptySet(), emptyMap(),
                        loading = !state.hasLoaded && state.problem == null,
                        failed = state.problem?.takeIf { state.rows.isEmpty() }?.let { t("personalBrowse.problem.${it.name}") },
                        filter = when (relation) { PersonalPullRelation.ReviewRequested -> "waiting"; PersonalPullRelation.Authored -> "authored"; PersonalPullRelation.Involved -> "all" },
                        searching = needle.isNotEmpty(), sort = InboxSort.Updated, groupBy = InboxGrouping.Repo,
                        folded = folded, onFold = { section -> session.toggleHomeFold(screen, section.key) },
                        onRetry = state::retry, onCommands = onCommands,
                        onOpen = { row -> owned(row) { onOpenGitHub(it.url) } }, onArchive = {},
                        onRepo = { group -> originals.values.firstOrNull { it.repository.owner == group.owner && it.repository.name == group.repo }?.let(session::openRepositoryFromPull) },
                        onHold = { row -> owned(row) { held = it to row } },
                        footer = { if (state.rows.isNotEmpty()) PersonalStatus(state, "personalBrowse.emptyPulls") },
                    )
                } else Column(Modifier.fillMaxSize().padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                    Text(t("personalHome.workspaceTours"), style = PullsType.read, color = colors.muted)
                    CtaButton(t("personalHome.openWorkspace"), { session.runFrom(screen, onWorkspace) }, Modifier.fillMaxWidth())
                }
            }
        }
        if (tab == 0) {
            SearchDim(focused, query.isEmpty())
            SearchPill("personal-home", query, { session.setHomeQuery(screen, it) }, t("personalHome.search"),
                openLabel = null, onOpen = {}, modifier = Modifier.align(Alignment.BottomCenter), onFocus = { focused = it })
        }
        held?.takeIf { pair -> state.ownsRow(pair.first) }?.let { opening ->
            val (original, row) = opening
            PullActionsSheet(row, archived = false, onTour = null,
                onRepo = { state.open(original, session::openRepositoryFromPull) }, onArchive = null, onClose = { if (held === opening) held = null },
                current = { held === opening && state.ownsRow(original) })
        }
    }
}

internal fun PersonalAccountPull.homeRow(relation: PersonalPullRelation) = PullRow(
    owner = repository.owner, repo = repository.name, number = number.toInt(), title = title,
    author = author.orEmpty(), updatedAt = updatedAt, isDraft = draft,
    role = when (relation) { PersonalPullRelation.ReviewRequested -> "review-requested"; PersonalPullRelation.Authored -> "authored"; PersonalPullRelation.Involved -> "involved" },
    updatedAtMs = runCatching { kotlin.time.Instant.parse(updatedAt).toEpochMilliseconds() }.getOrNull(), url = url,
)
