package com.octaviantocan.pulls.ui

import com.octaviantocan.pulls.core.OpenTarget

/** Navigation actions every screen gets (the web router's routes, as a back stack). */
class Nav(
    val openTarget: (OpenTarget) -> Unit,
    val openRepo: (owner: String, repo: String) -> Unit,
    val home: () -> Unit,
    val settings: () -> Unit,
    val checkout: () -> Unit,
    val back: () -> Unit,
)
