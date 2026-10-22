package com.octaviantocan.pulls.ui.ai

/** The pages of the Analyze sheet. Each but the first slides in inside the same sheet and goes back to its [parent] (rule 78). */
enum class AnalyzePage(val parent: AnalyzePage?) {
    Main(null),
    Tour(null),
    Custom(Main),
    Model(Main),
    Effort(Main),
    TourEffort(Tour),
}

/** The Tour's actions, stale-tour entry and update setup, with model pages inside the same sheet (rule 78). */
sealed interface TourSheetPage {
    data object Main : TourSheetPage
    data class Stale(val identity: StaleTourIdentity) : TourSheetPage
    data object Update : TourSheetPage
    data class Ai(val page: AnalyzePage) : TourSheetPage
}

