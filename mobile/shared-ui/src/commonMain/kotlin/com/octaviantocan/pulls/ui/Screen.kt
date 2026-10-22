package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.ExploreRepository

/** The web routes: `/` home, `/:owner/:repo` a repo's PRs, a diff page, and the settings modal as a screen. */
sealed interface Screen {
    data object Home : Screen
    data object Explore : Screen
    data class PublicRepo(val repository: ExploreRepository) : Screen
    data class Repo(val owner: String, val repo: String, val tours: Boolean = false) : Screen
    /**
     * [tour] opens the pull request on its tour instead of its overview; [from] is where it was opened, for `pr.open`;
     * [place] is the thing on it a link pointed at, which the page goes to once it is there.
     */
    data class Review(val target: OpenTarget, val tour: Boolean = false, val from: String = "other", val place: PlaceRequest? = null, val aiResult: String? = null) : Screen
    data object Settings : Screen
    data object UiBook : Screen
}
