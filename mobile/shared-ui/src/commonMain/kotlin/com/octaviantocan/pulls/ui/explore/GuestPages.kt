package com.octaviantocan.pulls.ui.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.AppPages
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.headerTopPadding
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.home.PullToCommands
import com.octaviantocan.pulls.ui.personal.PersonalBrowseMount
import com.octaviantocan.pulls.ui.personal.PersonalBrowsePages
import com.octaviantocan.pulls.ui.review.PublicReviewFrame
import com.octaviantocan.pulls.ui.review.publicPullTarget

@Composable
fun GuestPages(
    navigation: AppNavigation,
    motion: PageMotion,
    session: GuestBrowseSession,
    onExistingAccount: () -> Unit,
    onOpenGitHub: (String) -> Unit,
    onGitHubSignIn: (() -> Unit)? = null,
    onCommands: () -> Unit = { Commands.open() },
    githubAccount: String? = null,
    personal: PersonalBrowseMount? = null,
    publicReview: PublicReviewFrame? = null,
    links: (@Composable () -> Unit)? = null,
    welcomeSeen: Boolean = true,
    onWelcomeSeen: () -> Unit = {},
) {
    SideEffect { session.keepRoutes(); personal?.keepRoutes() }
    // The sheet with the ways in opens by itself the first time Explore is shown, then from the account button.
    var welcome by remember { mutableStateOf(!welcomeSeen) }
    if (welcome) GuestWelcomeSheet(
        onClose = { welcome = false; onWelcomeSeen() },
        onGitHubSignIn = onGitHubSignIn?.let { action -> { welcome = false; onWelcomeSeen(); session.runFrom(Screen.Explore, action) } },
        onExistingAccount = { welcome = false; onWelcomeSeen(); onExistingAccount() },
        githubAccount = githubAccount, links = links,
    )
    PullToCommands({ session.runFrom(navigation.stack.last(), onCommands) }, Modifier.fillMaxSize()) {
        AppPages(navigation, motion, Modifier.fillMaxSize()) { screen ->
            when (screen) {
                Screen.Explore -> ExploreScreen(
                    session.repositories, session::openRepository,
                    onAccount = { session.runFrom(screen) { welcome = true } },
                    githubAccount = githubAccount,
                )
                is Screen.PublicRepo -> {
                    val state = remember(session, screen) { session.pulls(screen) }
                    DisposableEffect(session, screen) {
                        session.retain(screen)
                        onDispose { session.release(screen) }
                    }
                    ExplorePullsScreen(state, { session.runFrom(screen, navigation.nav.back) }, { pull -> session.openPull(screen, pull) })
                }
                is Screen.PersonalPulls, is Screen.PersonalRepositories, is Screen.PersonalRepo -> {
                    val browse = personal?.session
                    if (browse != null && browse.admits(screen)) PersonalBrowsePages(
                        browse, screen,
                        onBack = navigation.nav.back,
                        onOpenGitHub = onOpenGitHub,
                        onPrivateConsent = { personal.openRepositoryConsent(browse) },
                    ) else Column(
                        Modifier.fillMaxSize().padding(top = headerTopPadding()).padding(horizontal = PullsSpace.x4),
                        verticalArrangement = Arrangement.spacedBy(PullsSpace.x3),
                    ) {
                        Text(t("personal.title"), style = PullsType.xxl, color = LocalPullsColors.current.text)
                        Text(t(if (personal?.account?.configured == true) "personal.intro" else "explore.signInUnavailable"),
                            style = PullsType.read, color = LocalPullsColors.current.muted)
                        if (onGitHubSignIn != null) CtaButton(t("personal.connectGitHub"), {
                            session.runFrom(screen, onGitHubSignIn)
                        }, Modifier.fillMaxWidth())
                        PillButton(t("personalBrowse.back"), { session.runFrom(screen, navigation.nav.back) })
                    }
                }
                is Screen.Review if publicReview != null && publicPullTarget(screen) != null -> publicReview.Page(screen, navigation.nav)
                else -> {
                    val colors = LocalPullsColors.current
                    val publicUrl = guestGitHubUrl(screen)
                    Column(
                        Modifier.fillMaxSize().padding(top = headerTopPadding()).padding(horizontal = PullsSpace.x4),
                        verticalArrangement = Arrangement.spacedBy(PullsSpace.x3),
                    ) {
                        Text(t(if (publicUrl == null) "explore.accountLink" else "explore.publicLink"), style = PullsType.xxl, color = colors.text)
                        Text(t(if (publicUrl == null) "explore.accountLinkBody" else "explore.publicLinkBody"), style = PullsType.read, color = colors.muted)
                        if (publicUrl != null) CtaButton(t("explore.openLink"), { session.runFrom(screen) { onOpenGitHub(publicUrl) } }, Modifier.fillMaxWidth())
                        CtaButton(t("explore.existingAccount"), { session.runFrom(screen, onExistingAccount) }, Modifier.fillMaxWidth())
                        PillButton(t("explore.browse"), { session.runFrom(screen) { navigation.reset(Screen.Explore) } })
                    }
                }
            }
        }
    }
}

internal fun guestGitHubUrl(screen: Screen): String? {
    fun segment(value: String) = value.isNotEmpty() && value !in setOf(".", "..") && value.length <= 100 && value.all { it.isLetterOrDigit() && it.code < 128 || it in "-_." }
    return when (screen) {
        is Screen.Repo -> if (segment(screen.owner) && segment(screen.repo)) "https://github.com/${screen.owner}/${screen.repo}" else null
        is Screen.Review -> (screen.target as? OpenTarget.Pull)?.takeIf { it.host == "github.com" && segment(it.owner) && segment(it.repo) && it.number > 0 }?.let { "https://github.com/${it.owner}/${it.repo}/pull/${it.number}" }
        else -> null
    }
}
