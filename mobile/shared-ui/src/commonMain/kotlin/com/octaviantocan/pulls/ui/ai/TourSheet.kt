package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.home.GithubIcon
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices

/**
 * What ⋯ opens on the Tour: the tour's own things, not the pull request's ("on the Tour, ⋯ should hold tour things",
 * recording of 16 Oct 12:10, 10:49 to 11:24, N23). The model the tour is written with and its effort as a row of its own
 * under it (rule 142), the Analyze options, then Regenerate tour, Ask about this tour and Switch to the diff, and last one
 * row on to the pull request's own actions. The model, its effort and the Analyze options are pages of this sheet, with
 * ← back. It is drawn from what the phone already holds, so it opens in the frame of the tap: the pull request's
 * sheet took a moment to build ("took far too long to open", 10:37 to 10:43, N22).
 * A stale tour opens on [TourSheetPage.Stale], showing its original commit and the choice to keep reading; Update moves
 * on to the model choice before it starts any work (Tavi's direct stale-tour feedback, 20 Oct 2026).
 */
@Composable
fun TourSheet(session: ReviewSession, models: ModelSettingsServices, native: NativeHomeActions, onClose: () -> Unit, onAsk: (() -> Unit)?, onDiff: (() -> Unit)?, onPrActions: () -> Unit, start: TourSheetPage = TourSheetPage.Main) {
    val c = LocalPullsColors.current
    val actions = rememberAiSetupActions(session, models)
    var page by remember(session, models, start) { mutableStateOf(start) }
    // The model and effort pages return to their opener: tour actions, Analyze options or update setup.
    var modelFrom by remember(session, models) { mutableStateOf<TourSheetPage>(TourSheetPage.Main) }
    var effortFrom by remember(session, models) { mutableStateOf<TourSheetPage>(TourSheetPage.Main) }
    val custom = rememberCustomAnalysis(session)
    fun go(next: TourSheetPage) {
        if (next == TourSheetPage.Ai(AnalyzePage.Model)) modelFrom = page
        if (next == TourSheetPage.Ai(AnalyzePage.TourEffort) || next == TourSheetPage.Ai(AnalyzePage.Effort)) effortFrom = page
        page = next
    }
    // The shared tour-effort page names its parent Tour; here that is whichever tour page opened it.
    fun goAi(next: AnalyzePage) = go(if (next == AnalyzePage.Tour) effortFrom else TourSheetPage.Ai(next))
    val parent: (TourSheetPage) -> TourSheetPage? = { p ->
        when (p) {
            TourSheetPage.Main -> null
            is TourSheetPage.Stale -> null
            TourSheetPage.Update -> if (start == TourSheetPage.Update) null else start
            is TourSheetPage.Ai -> when (p.page) {
                AnalyzePage.Model -> modelFrom
                AnalyzePage.Effort, AnalyzePage.TourEffort -> effortFrom
                AnalyzePage.Main, AnalyzePage.Tour -> TourSheetPage.Main
                else -> p.page.parent?.let { TourSheetPage.Ai(it) } ?: TourSheetPage.Main
            }
        }
    }
    val running = session.taskFor("tour")?.running == true
    PullsPagedSheet(
        onClose, page, parent = parent, onPage = ::go,
        title = { p -> when (p) {
            is TourSheetPage.Ai -> analyzePageTitle(p.page)
            is TourSheetPage.Stale -> "Update this tour?"
            TourSheetPage.Update -> if (running) "Writing the tour" else "Update tour"
            TourSheetPage.Main -> "Tour"
        } },
    ) { current ->
        when (current) {
            is TourSheetPage.Stale -> {
                Text(
                    "This tour was generated for an older commit. Do you want to update it?",
                    Modifier.padding(horizontal = 4.dp, vertical = 12.dp), style = PullsType.read, color = c.text,
                )
                val repo = reviewRepo(session)
                SheetCard {
                    SheetRow(
                        if (repo != null) "Original commit on GitHub" else "Original commit",
                        if (repo != null) ({ actions.read { native.openOnGithub("https://github.com/$repo/commit/${current.identity.originalHead}") } }) else null,
                        first = true, iconless = repo == null,
                        icon = { GithubIcon(tint = c.text, size = 22.dp) }, detail = current.identity.originalHead,
                    )
                }
                SheetCard {
                    SheetRow("Continue reading", { actions.read(onClose) }, first = true, iconless = true)
                    if (actions.canTour) SheetRow("Update tour", { if (actions.canTour) go(TourSheetPage.Update) }, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
                }
            }
            TourSheetPage.Update -> {
                if (running) Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
                    TourRunning(session, Modifier)
                } else {
                    ModelCard(session, models, onModel = { goAi(AnalyzePage.Model) }, onEffort = { goAi(AnalyzePage.TourEffort) })
                    val failed = session.taskFor("tour")?.takeIf { it.status == "failed" }?.error
                    if (failed != null) Column(Modifier.padding(top = 12.dp)) { ErrorBox("The tour could not be written", failed) }
                    if (actions.canTour) CtaButton("Update tour", { actions.update() }, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = session.key != null)
                }
            }
            TourSheetPage.Main -> {
                // The model and its effort as the "No tour yet" sheet shows them, a row each (rule 142).
                ModelCard(session, models, onModel = { goAi(AnalyzePage.Model) }, onEffort = { goAi(AnalyzePage.TourEffort) })
                if (actions.canAnalyze(com.octaviantocan.pulls.core.AnalyzeChoice.of(com.octaviantocan.pulls.core.AnalyzePreset.Quick))) SheetCard {
                    SheetRow("Analyze options", { if (actions.canAnalyze(com.octaviantocan.pulls.core.AnalyzeChoice.of(com.octaviantocan.pulls.core.AnalyzePreset.Quick))) goAi(AnalyzePage.Main) }, first = true, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
                }
                val showAsk = onAsk != null && actions.canAsk
                if (actions.canTour || showAsk || onDiff != null) {
                    SheetHeading("This tour")
                    SheetCard(gap = 8.dp) {
                        if (actions.canTour) SheetRow(
                            "Regenerate tour", { if (actions.canTour) go(TourSheetPage.Update) }, first = true, iconless = true,
                            enabled = !running && session.key != null, detail = if (running) "Writing a new tour…" else null,
                        )
                        if (showAsk) SheetRow("Ask about this tour", { actions.ask { onClose(); onAsk?.invoke() } }, first = !actions.canTour, iconless = true)
                        if (onDiff != null) SheetRow("Switch to the diff", { actions.read { onClose(); onDiff() } }, first = !actions.canTour && !showAsk, iconless = true)
                    }
                }
                SheetHeading("Pull request")
                SheetCard(Modifier.padding(bottom = 16.dp), gap = 8.dp) {
                    SheetRow("Pull request actions", { actions.read(onPrActions) }, first = true, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
                }
            }
            is TourSheetPage.Ai -> AnalyzePageContent(
                session, current.page, custom, models, onPage = ::goAi, onClose = onClose,
                onModelDone = { go(parent(current) ?: TourSheetPage.Main) },
                onEffortDone = { go(effortFrom) },
            )
        }
    }
}
