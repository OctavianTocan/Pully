package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.OpenTarget

/** Navigation actions every screen gets (the web router's routes, as a back stack). */
class Nav(
    val openTarget: (OpenTarget) -> Unit,
    /** Opens a pull request straight on its tour (the Tours tab). */
    val openTour: (OpenTarget) -> Unit,
    /**
     * Opens a pull request, on its tour when the last argument is true, and says where it was opened from for usage
     * stats (`pr.open`): `inbox`, `search`, `link`, `repo`, `stack` or `other`.
     */
    val openFrom: (target: OpenTarget, from: String, tour: Boolean) -> Unit = { target, _, tour -> if (tour) openTour(target) else openTarget(target) },
    val openRepo: (owner: String, repo: String) -> Unit,
    /** Opens a repository's page on its tours (a heading on the Tours tab, H26). */
    val openRepoTours: (owner: String, repo: String) -> Unit = openRepo,
    val home: () -> Unit,
    val settings: () -> Unit,
    /** Opens the UI Book, the design system as screens (Settings, at the bottom). */
    val uiBook: () -> Unit = {},
    val back: () -> Unit,
)
