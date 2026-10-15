package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.OpenTarget

/** Navigation actions every screen gets (the web router's routes, as a back stack). */
class Nav(
    val openTarget: (OpenTarget) -> Unit,
    /** Opens a pull request straight on its tour (the Tours tab). */
    val openTour: (OpenTarget) -> Unit,
    val openRepo: (owner: String, repo: String) -> Unit,
    /** Opens a repository's page on its tours (a heading on the Tours tab, H26). */
    val openRepoTours: (owner: String, repo: String) -> Unit = openRepo,
    val home: () -> Unit,
    val settings: () -> Unit,
    /** Opens the UI Book, the design system as screens (Settings, at the bottom). */
    val uiBook: () -> Unit = {},
    val back: () -> Unit,
)
