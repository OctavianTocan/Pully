package com.octaviantocan.pulls.ui.personal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

/** Both app roots render these same source- and capability-bound account actions. */
@Composable
fun PersonalBrowseAccountSheet(mount: PersonalBrowseMount, onOpenBrowser: (String) -> Unit) {
    val presentation = mount.presentation ?: return
    val browse = mount.session?.takeIf { it.current }
    key(presentation) {
        PersonalAccountSheet(
            mount.account,
            onOpenBrowser = onOpenBrowser,
            onClose = { mount.dismiss(presentation) },
            onYourPulls = browse?.let { opening -> { if (opening.privateEnabled) mount.openHome(opening) else mount.openPulls(opening) } },
            pullsLabel = if (browse?.privateEnabled == true) "personalHome.home" else "personalBrowse.pulls",
            onBrowsePublic = browse?.let { opening -> { mount.openExplore(opening) } },
            onRepositories = browse?.let { opening -> { mount.openRepositories(opening) } },
            repositoryCapabilities = browse?.capabilities,
            repositoryConsent = presentation.repositoryConsent,
        )
    }
}
