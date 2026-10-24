package com.octaviantocan.pulls.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.octaviantocan.pulls.data.ExplorePull
import com.octaviantocan.pulls.data.ExploreRepository
import com.octaviantocan.pulls.data.PublicBrowseProblem
import com.octaviantocan.pulls.ui.design.BarLift
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FieldHeight
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PillInput
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.floatingBar
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.stayOnPull
import com.octaviantocan.pulls.sound.cueClickable

/**
 * Explore is the repositories: a title with the account button where Home has its profile, the list, and the search
 * pill. The ways in (Connect GitHub, an existing Pully account, GitHub links) live on [GuestWelcomeSheet], which the
 * account button opens ("this layout here is garbage ... we would open a bottom sheet with these things and then you
 * can have these repositories as the page", recording 23 Oct 12:12, 00:21 to 00:33).
 */
@Composable
fun ExploreScreen(
    state: ExploreRepositoriesState,
    onOpenRepository: (ExploreRepository) -> Unit,
    onAccount: () -> Unit,
    githubAccount: String? = null,
) {
    val colors = LocalPullsColors.current
    LaunchedEffect(state) { state.start() }
    val bottom = FieldHeight + BarLift + PullsSpace.x8 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(colors.base)) {
        LazyColumn(
            Modifier.fillMaxSize().edgeFade(bottom = bottom),
            contentPadding = PaddingValues(top = headerTopPadding(), bottom = bottom),
        ) {
            item(key = "title") {
                Column(Modifier.padding(horizontal = PullsSpace.x4)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("explore.title"), Modifier.weight(1f).semantics { heading() }, style = PullsType.xxl, color = colors.text)
                        val label = t("explore.account")
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).cueClickable(role = Role.Button, onClickLabel = label) { state.runIfCurrent(onAccount) }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (githubAccount != null) GithubAvatar(githubAccount, size = 40.dp) else ProfileDot(size = 40.dp)
                        }
                    }
                    Text(
                        if (state.submittedQuery.isEmpty()) t("explore.popular") else t("explore.results", "query" to state.submittedQuery),
                        Modifier.padding(top = PullsSpace.x3, bottom = PullsSpace.x2).semantics { heading() }, style = PullsType.read, color = colors.muted,
                    )
                }
            }
            itemsIndexed(state.repositories, key = { _, repository -> repository.id }) { index, repository ->
                SheetRow(
                    repository.fullName, { state.open(repository, onOpenRepository) }, first = index == 0, iconless = true,
                    detail = listOfNotNull(repository.description?.takeIf { it.isNotBlank() }, listOfNotNull(repository.language, t("explore.stars", "count" to repository.stars)).joinToString(" · ")).joinToString("\n"),
                )
            }
            item(key = "status") {
                Column(Modifier.padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                    BrowseStatus(state.loading && !state.refreshing, state.problem, state.hasLoaded && state.repositories.isEmpty(), "explore.emptyRepositories", state::retry)
                    if (state.incomplete && state.problem == null) Text(t("explore.incomplete"), style = PullsType.sm, color = colors.muted)
                    if (state.nextPage != null && state.problem == null) PillButton(t("explore.loadMore"), state::loadMore, enabled = !state.loading)
                }
            }
        }
        PillInput(
            state.query, state::editQuery, t("explore.searchPlaceholder"), Modifier.align(Alignment.BottomCenter).floatingBar().stayOnPull(),
            imeAction = ImeAction.Go, onSend = state::search,
            trailing = { FloatIcon(Lu.search, t("explore.search"), state::search, flat = true) },
        )
    }
}

/**
 * The ways into Pully from Explore, on one sheet: Connect GitHub, an existing Pully account, and on Android the
 * GitHub links prompt [links], since a github.com link otherwise opens the GitHub app ("you actually need to prompt
 * people to allow Pully to set itself as your thing to open things", recording 23 Oct 12:12, 00:59 to 01:07).
 */
@Composable
fun GuestWelcomeSheet(
    onClose: () -> Unit,
    onGitHubSignIn: (() -> Unit)?,
    onExistingAccount: (() -> Unit)?,
    githubAccount: String? = null,
    links: (@Composable () -> Unit)? = null,
) {
    val colors = LocalPullsColors.current
    PullsSheet(onClose, t("connection.welcome"), expandable = false) {
        Column(Modifier.padding(horizontal = PullsSpace.x4).padding(top = PullsSpace.x2, bottom = PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
            Text(t("explore.intro"), style = PullsType.read, color = colors.muted)
            CtaButton(
                if (githubAccount == null) t("personal.connectGitHub") else t("personal.account", "login" to githubAccount),
                { onGitHubSignIn?.invoke() }, Modifier.fillMaxWidth(), enabled = onGitHubSignIn != null,
            )
            if (onGitHubSignIn == null) Text(t("explore.signInUnavailable"), style = PullsType.sm, color = colors.muted)
            if (onExistingAccount != null) CtaButton(t("explore.existingAccount"), onExistingAccount, Modifier.fillMaxWidth(), container = colors.paper2, contentColor = colors.text)
        }
        links?.invoke()
        PillButton(t("explore.browse"), onClose, Modifier.padding(horizontal = PullsSpace.x4).padding(top = PullsSpace.x3, bottom = PullsSpace.x4))
    }
}

@Composable
fun ExplorePullsScreen(state: ExplorePullsState, onBack: () -> Unit, onOpenPull: (ExplorePull) -> Unit) {
    val colors = LocalPullsColors.current
    LaunchedEffect(state) { state.start() }
    LazyColumn(
        Modifier.fillMaxSize().background(colors.base),
        contentPadding = PaddingValues(top = headerTopPadding(), bottom = PullsSpace.x8 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        item(key = "repository") {
            Column(Modifier.padding(horizontal = PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                FloatIcon(Lu.back, t("explore.back"), { state.runIfCurrent(onBack) })
                Text(state.repository.fullName, Modifier.semantics { heading() }, style = PullsType.xxl, color = colors.text)
                Text(t("explore.publicPulls"), style = PullsType.read, color = colors.muted)
            }
        }
        itemsIndexed(state.pulls, key = { _, pull -> pull.number }) { index, pull ->
            SheetRow(
                pull.title, { state.open(pull, onOpenPull) }, first = index == 0, iconless = true,
                labelMaxLines = 2,
                detail = listOfNotNull("#${pull.number}", pull.author, t(if (pull.draft) "explore.draft" else "explore.open")).joinToString(" · "),
            )
        }
        item(key = "status") {
            Column(Modifier.padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                BrowseStatus(state.loading && !state.refreshing, state.problem, state.hasLoaded && state.pulls.isEmpty(), "explore.emptyPulls", state::retry)
                if (state.nextPage != null && state.problem == null) PillButton(t("explore.loadMore"), state::loadMore, enabled = !state.loading)
            }
        }
    }
}

@Composable
private fun BrowseStatus(loading: Boolean, problem: PublicBrowseProblem?, empty: Boolean, emptyKey: String, onRetry: () -> Unit) {
    val colors = LocalPullsColors.current
    when {
        loading -> RowsSkeleton(rows = 3)
        problem != null -> {
            Text(t("explore.problem.${problem.name}"), style = PullsType.read, color = colors.muted)
            PillButton(t("common.retry"), onRetry)
        }
        empty -> Text(t(emptyKey), style = PullsType.read, color = colors.muted)
    }
}
