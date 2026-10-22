package com.octaviantocan.pulls.ui.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.octaviantocan.pulls.data.PersonalPullRelation
import com.octaviantocan.pulls.data.PersonalReadProblem
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.RowsSkeleton
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.UnderlineTabs
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t

/** A renderer for the host's existing AppPages, with no separate route stack or private workspace. */
@Composable
fun PersonalBrowsePages(session: PersonalBrowseSession, screen: Screen, onBack: () -> Unit,
    onOpenGitHub: (String) -> Unit, onPrivateConsent: (() -> Unit)?) {
    SideEffect { session.keepRoutes() }
    if (!session.admits(screen)) return
    DisposableEffect(session, screen) {
        session.retain(screen)
        onDispose { session.release(screen) }
    }
    when (screen) {
        is Screen.PersonalRepositories -> Repositories(session, screen, onBack, onPrivateConsent)
        is Screen.PersonalPulls, is Screen.PersonalRepo -> Pulls(session, screen, onBack, onOpenGitHub, onPrivateConsent)
        else -> Unit
    }
}

@Composable
private fun Repositories(session: PersonalBrowseSession, screen: Screen.PersonalRepositories, onBack: () -> Unit,
    onPrivateConsent: (() -> Unit)?) {
    val colors = LocalPullsColors.current
    val state = remember(session, screen) { session.repositories(screen) }
    LaunchedEffect(state) { state.start() }
    LazyColumn(Modifier.fillMaxSize().background(colors.base), contentPadding = pagePadding()) {
        item(key = "heading") {
            PersonalHeading(session, screen, t("personalBrowse.repositories"), onBack, onPrivateConsent)
        }
        itemsIndexed(state.rows, key = { _, repository -> repository.id }) { index, repository ->
            SheetRow(repository.fullName, { session.openRepository(repository) }, first = index == 0, iconless = true,
                detail = listOfNotNull(t(if (repository.private) "personalBrowse.private" else "personalBrowse.public"),
                    repository.metadata.description?.takeIf { it.isNotBlank() }).joinToString(" · "))
        }
        item(key = "status") { PersonalStatus(state, "personalBrowse.emptyRepositories") }
    }
}

@Composable
private fun Pulls(session: PersonalBrowseSession, screen: Screen, onBack: () -> Unit, onOpenGitHub: (String) -> Unit,
    onPrivateConsent: (() -> Unit)?) {
    val colors = LocalPullsColors.current
    val relation = if (screen is Screen.PersonalPulls) session.relation(screen) else null
    val state = remember(session, screen, relation) { session.pulls(screen) }
    LaunchedEffect(state) { state.start() }
    LazyColumn(Modifier.fillMaxSize().background(colors.base), contentPadding = pagePadding()) {
        item(key = "heading") {
            PersonalHeading(session, screen, if (screen is Screen.PersonalRepo) screen.repository.fullName else t("personalBrowse.pulls"), onBack, onPrivateConsent)
            if (screen is Screen.PersonalPulls) {
                Column(Modifier.padding(horizontal = PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                    UnderlineTabs(PersonalPullRelation.entries.map { t("personalBrowse.relation.${it.name}") },
                        PersonalPullRelation.entries.indexOf(relation), { index -> session.selectRelation(screen, PersonalPullRelation.entries[index]) })
                }
            }
        }
        itemsIndexed(state.rows, key = { _, pull -> "${pull.repository.id}:${pull.number}" }) { index, pull ->
            SheetRow(pull.title, { state.open(pull) { session.runFrom(screen) { onOpenGitHub(it.url) } } },
                first = index == 0, iconless = true, labelMaxLines = 2,
                detail = listOfNotNull(pull.repository.fullName, "#${pull.number}", pull.author,
                    t(if (pull.repository.private) "personalBrowse.private" else "personalBrowse.public"),
                    t(if (pull.draft) "explore.draft" else "explore.open")).joinToString(" · "),
                value = { Text(t("explore.github"), style = PullsType.sm, color = colors.muted) })
        }
        item(key = "status") { PersonalStatus(state, "personalBrowse.emptyPulls") }
    }
}

@Composable
private fun PersonalHeading(session: PersonalBrowseSession, screen: Screen, title: String, onBack: () -> Unit,
    onPrivateConsent: (() -> Unit)?) {
    val colors = LocalPullsColors.current
    Column(Modifier.padding(horizontal = PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
        FloatIcon(Lu.back, t("personalBrowse.back"), { session.runFrom(screen, onBack) })
        Text(title, Modifier.semantics { heading() }, style = PullsType.xxl, color = colors.text)
        Text(t(if (session.privateEnabled) "personalBrowse.accountScope" else "personalBrowse.publicScope"), style = PullsType.read, color = colors.muted)
        Text(t("explore.opensOnGitHub"), style = PullsType.sm, color = colors.muted)
        if (!session.privateEnabled && session.capabilities?.consentAvailable == true && onPrivateConsent != null) {
            CtaButton(t("personalBrowse.enablePrivate"), { session.requestPrivateConsent(screen, onPrivateConsent) }, Modifier.fillMaxWidth())
        }
        session.capabilityProblem?.let {
            Text(t("personalBrowse.problem.${it.name}"), style = PullsType.sm, color = colors.muted)
        }
    }
}

@Composable
private fun <T> PersonalStatus(state: PersonalListState<T>, emptyKey: String) {
    val colors = LocalPullsColors.current
    Column(Modifier.padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
        when {
            state.loading && !state.refreshing -> RowsSkeleton(rows = 3)
            state.problem != null -> {
                Text(t("personalBrowse.problem.${state.problem!!.name}"), style = PullsType.read, color = colors.muted)
                if (state.problem != PersonalReadProblem.Unauthorized && state.problem != PersonalReadProblem.Reconnect)
                    PillButton(t("common.retry"), state::retry)
            }
            state.hasLoaded && state.rows.isEmpty() -> Text(t(emptyKey), style = PullsType.read, color = colors.muted)
        }
        if (state.incomplete && state.problem == null) Text(t("personalBrowse.incomplete"), style = PullsType.sm, color = colors.muted)
        if (state.nextPage != null && state.problem == null) PillButton(t("explore.loadMore"), state::loadMore, enabled = !state.loading)
    }
}

@Composable
private fun pagePadding() = PaddingValues(top = headerTopPadding(), bottom = PullsSpace.x8 + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
