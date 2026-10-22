package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.ui.ai.AnalyzePage

/**
 * The pages of the pull request's sheet. Everything one of its rows opens is a page that slides in inside the same sheet,
 * with ← back to the page it came from, and the phone's Back does the same (rule 78, J27, J33): the AI review's lenses,
 * and the page of decisions with the confirmation each one asks for. The model, Analyze options, Checks and Review
 * progress are not top-level rows: each AI launch shows its model within its own setup.
 */
sealed interface PrPage {
    /** Aa on the Diff: the code's look and the file's own actions. */
    data object Tools : PrPage
    data object Font : PrPage

    /** ⋯: the pull request's actions. */
    data object Actions : PrPage

    /** Approve, request changes, merge, draft and close: the ones that cannot be taken back lightly (J29). */
    data object Decide : PrPage

    /** The check before one of [Decide]'s changes: what it does, and the one button that does it. */
    data class Confirm(val change: String) : PrPage
    data object Merge : PrPage
    data class Submit(val verdict: String) : PrPage
    data class Discard(val verdict: String) : PrPage
    data object Review : PrPage
    data object ReviewModel : PrPage
    data object ReviewEffort : PrPage
    data class Analyze(val page: AnalyzePage = AnalyzePage.Main, val origin: AnalyzePage = AnalyzePage.Main) : PrPage
}
