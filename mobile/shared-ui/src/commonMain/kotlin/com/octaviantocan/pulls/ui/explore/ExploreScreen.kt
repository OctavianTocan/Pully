package com.octaviantocan.pulls.ui.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.octaviantocan.pulls.data.ExploreRepository
import com.octaviantocan.pulls.data.PublicBrowseProblem
import com.octaviantocan.pulls.ui.design.BarLift
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FieldHeight
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PillInput
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.edgeFade
import com.octaviantocan.pulls.ui.design.floatingBar
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t

@Composable
fun ExploreScreen(
    state: ExploreRepositoriesState,
    onOpenRepository: (ExploreRepository) -> Unit,
    onGitHubSignIn: (() -> Unit)?,
    onExistingAccount: (() -> Unit)?,
) {
    val colors = LocalPullsColors.current
    LaunchedEffect(state) { state.start() }
    val bottom = FieldHeight + BarLift + PullsSpace.x8 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(colors.base)) {
        LazyColumn(
            Modifier.fillMaxSize().edgeFade(bottom = bottom),
            contentPadding = PaddingValues(top = headerTopPadding(), bottom = bottom),
        ) {
            item(key = "welcome") {
                Column(Modifier.padding(horizontal = PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                    Text(t("explore.title"), Modifier.semantics { heading() }, style = PullsType.xxl, color = colors.text)
                    Text(t("explore.intro"), style = PullsType.read, color = colors.muted)
                    CtaButton(t("explore.signIn"), { state.runIfCurrent { onGitHubSignIn?.invoke() } }, Modifier.fillMaxWidth(), enabled = onGitHubSignIn != null)
                    if (onGitHubSignIn == null) Text(t("explore.signInUnavailable"), style = PullsType.sm, color = colors.muted)
                    if (onExistingAccount != null) PillButton(t("explore.existingAccount"), { state.runIfCurrent(onExistingAccount) })
                    Text(
                        if (state.submittedQuery.isEmpty()) t("explore.popular") else t("explore.results", "query" to state.submittedQuery),
                        Modifier.padding(top = PullsSpace.x3).semantics { heading() }, style = PullsType.xl, color = colors.text,
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
                    BrowseStatus(state.loading, state.problem, state.hasLoaded && state.repositories.isEmpty(), "explore.emptyRepositories", state::retry)
                    if (state.incomplete && state.problem == null) Text(t("explore.incomplete"), style = PullsType.sm, color = colors.muted)
                    if (state.nextPage != null && state.problem == null) PillButton(t("explore.loadMore"), state::loadMore, enabled = !state.loading)
                }
            }
        }
        PillInput(
            state.query, state::editQuery, t("explore.searchPlaceholder"), Modifier.align(Alignment.BottomCenter).floatingBar(),
            imeAction = ImeAction.Go, onSend = state::search,
            trailing = { FloatIcon(Lu.search, t("explore.search"), state::search, flat = true) },
        )
    }
}

@Composable
fun ExplorePullsScreen(state: ExplorePullsState, onBack: () -> Unit, onOpenGitHub: (String) -> Unit) {
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
                Text(t("explore.opensOnGitHub"), style = PullsType.sm, color = colors.muted)
            }
        }
        itemsIndexed(state.pulls, key = { _, pull -> pull.number }) { index, pull ->
            SheetRow(
                pull.title, { state.open(pull, onOpenGitHub) }, first = index == 0, iconless = true,
                detail = listOfNotNull("#${pull.number}", pull.author, t(if (pull.draft) "explore.draft" else "explore.open")).joinToString(" · "),
                value = { Text(t("explore.github"), style = PullsType.sm, color = colors.muted) },
            )
        }
        item(key = "status") {
            Column(Modifier.padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
                BrowseStatus(state.loading, state.problem, state.hasLoaded && state.pulls.isEmpty(), "explore.emptyPulls", state::retry)
                if (state.nextPage != null && state.problem == null) PillButton(t("explore.loadMore"), state::loadMore, enabled = !state.loading)
                if (state.hasLoaded && state.problem == null) PillButton(t("common.refresh"), state::refresh, enabled = !state.loading)
            }
        }
    }
}

@Composable
private fun BrowseStatus(loading: Boolean, problem: PublicBrowseProblem?, empty: Boolean, emptyKey: String, onRetry: () -> Unit) {
    val colors = LocalPullsColors.current
    when {
        loading -> Text(t("common.loading"), style = PullsType.read, color = colors.muted)
        problem != null -> {
            Text(t("explore.problem.${problem.name}"), style = PullsType.read, color = colors.muted)
            PillButton(t("common.retry"), onRetry)
        }
        empty -> Text(t(emptyKey), style = PullsType.read, color = colors.muted)
    }
}
