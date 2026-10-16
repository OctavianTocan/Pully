package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.review.ReviewSession

/** The pages of the Tour's sheet: its own first page, and the Analyze pages it opens inside it (rule 78). */
internal sealed interface TourSheetPage {
    data object Main : TourSheetPage
    data class Ai(val page: AnalyzePage) : TourSheetPage
}

/**
 * What ⋯ opens on the Tour: the tour's own things, not the pull request's ("on the Tour, ⋯ should hold tour things",
 * recording of 16 Oct 12:10, 10:49 to 11:24, N23). The model the tour is written with and its effort as a row of its own
 * under it (rule 142), the Analyze options, then Regenerate tour, Ask about this tour and Switch to the diff, and last one
 * row on to the pull request's own actions. The model, its effort and the Analyze options are pages of this sheet, with
 * ← back. It is drawn from what the phone already holds, so it opens in the frame of the tap: the pull request's
 * sheet took a moment to build ("took far too long to open", 10:37 to 10:43, N22).
 */
@Composable
internal fun TourSheet(session: ReviewSession, onClose: () -> Unit, onAsk: () -> Unit, onDiff: () -> Unit, onPrActions: () -> Unit) {
    val c = LocalPullsColors.current
    var page by remember { mutableStateOf<TourSheetPage>(TourSheetPage.Main) }
    // The model page goes back to the page it was opened from: this sheet's first page or the Analyze options.
    var modelFrom by remember { mutableStateOf<TourSheetPage>(TourSheetPage.Main) }
    val custom = rememberCustomAnalysis(session)
    fun go(next: TourSheetPage) {
        if (next == TourSheetPage.Ai(AnalyzePage.Model)) modelFrom = page
        page = next
    }
    // An Analyze page that goes back to the tour's own page (the tour's effort) comes back to this sheet's first page.
    fun goAi(next: AnalyzePage) = go(if (next == AnalyzePage.Tour) TourSheetPage.Main else TourSheetPage.Ai(next))
    val parent: (TourSheetPage) -> TourSheetPage? = { p ->
        when (p) {
            TourSheetPage.Main -> null
            is TourSheetPage.Ai -> when (p.page) {
                AnalyzePage.Model -> modelFrom
                AnalyzePage.Main, AnalyzePage.Tour, AnalyzePage.TourEffort -> TourSheetPage.Main
                else -> p.page.parent?.let { TourSheetPage.Ai(it) } ?: TourSheetPage.Main
            }
        }
    }
    val running = session.taskFor("tour")?.running == true
    PullsPagedSheet(
        onClose, page, parent = parent, onPage = ::go,
        title = { p -> if (p is TourSheetPage.Ai) analyzePageTitle(p.page) else "Tour" },
    ) { current ->
        when (current) {
            TourSheetPage.Main -> {
                // The model and its effort as the "No tour yet" sheet shows them, a row each (rule 142).
                ModelCard(session, onModel = { goAi(AnalyzePage.Model) }, onEffort = { goAi(AnalyzePage.TourEffort) })
                SheetCard {
                    SheetRow("Analyze options", { goAi(AnalyzePage.Main) }, first = true, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
                }
                SheetHeading("This tour")
                SheetCard(gap = 8.dp) {
                    SheetRow(
                        "Regenerate tour", { onClose(); startTour(session) }, first = true, iconless = true,
                        enabled = !running && session.key != null, detail = if (running) "Writing a new tour…" else null,
                    )
                    SheetRow("Ask about this tour", { onClose(); onAsk() }, iconless = true)
                    SheetRow("Switch to the diff", { onClose(); onDiff() }, iconless = true)
                }
                SheetHeading("Pull request")
                SheetCard(Modifier.padding(bottom = 16.dp), gap = 8.dp) {
                    SheetRow("Pull request actions", onPrActions, first = true, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
                }
            }
            is TourSheetPage.Ai -> AnalyzePageContent(
                session, current.page, custom, onPage = ::goAi, onClose = onClose,
                onModelDone = { go(parent(current) ?: TourSheetPage.Main) },
            )
        }
    }
}
