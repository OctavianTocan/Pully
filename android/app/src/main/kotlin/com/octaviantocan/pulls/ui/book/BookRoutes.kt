package com.octaviantocan.pulls.ui.book

import androidx.compose.runtime.Composable

/**
 * The pages that are not a component: each id in `book.json` without a `component` has an arm
 * here, and every arm is in `book.json`. `pnpm run android:design` fails on a page with no route
 * and on a route with no page. A component page is drawn by `ComponentPage`.
 */
@Composable
internal fun BookRoute(id: String, book: BookData) {
    when (id) {
        "foundations/colors" -> ColorsPage(book)
        "foundations/themes" -> ThemesPage()
        "foundations/typography" -> TypographyPage(book)
        "foundations/shapes" -> ShapesPage(book)
        "foundations/spacing" -> SpacingPage(book)
        "foundations/elevation" -> ElevationPage(book)
        "foundations/motion" -> MotionPage(book)
        "foundations/icons" -> IconsPage()
        "checks/specs" -> SpecsPage(book)
        "checks/rules" -> RulesPage(book)
        "contracts/all" -> ContractsPage()
    }
}
