package com.octaviantocan.pulls.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.SheetSearch
import com.octaviantocan.pulls.ui.design.ProfileDot
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow

/** The pages of the profile sheet; Repositories slides in inside the same sheet (rule 78). */
internal enum class ProfilePage(val parent: ProfilePage?) {
    Main(null),
    Repos(Main),
}

/**
 * The profile sheet: who you are (a tap on your photo or name opens your GitHub profile), which repositories the
 * Inbox and Tours show, and closed pull requests. Commands is not here: pulling the Inbox down opens it (H22), and
 * neither is the server's address, which is Settings' business (H24). The Inbox refreshes itself, so there is no
 * Refresh here.
 */
@Composable
internal fun ProfileSheet(
    viewer: String?,
    showClosed: Boolean,
    onShowClosed: (Boolean) -> Unit,
    pulls: List<PullRow>,
    onClose: () -> Unit,
) {
    var page by remember { mutableStateOf(ProfilePage.Main) }
    PullsPagedSheet(onClose, page, parent = { it.parent }, onPage = { page = it }, title = { it.title() }) { current ->
        ProfilePageContent(current, viewer, showClosed, onShowClosed, pulls, onPage = { page = it }, onClose = onClose)
    }
}

internal fun ProfilePage.title(): String = when (this) {
    ProfilePage.Main -> "Profile"
    ProfilePage.Repos -> "Repositories"
}

/** What one page of the profile sheet holds. */
@Composable
internal fun ColumnScope.ProfilePageContent(
    page: ProfilePage,
    viewer: String?,
    showClosed: Boolean,
    onShowClosed: (Boolean) -> Unit,
    pulls: List<PullRow>,
    onPage: (ProfilePage) -> Unit,
    onClose: () -> Unit,
) {
    val settings = AppSettings.get(LocalContext.current)
    when (page) {
        ProfilePage.Main -> ProfileMain(viewer, showClosed, onShowClosed, choices = repoChoices(pulls, settings.hiddenRepos), onRepos = { onPage(ProfilePage.Repos) })
        ProfilePage.Repos -> ReposPage(pulls, settings)
    }
}

@Composable
private fun ColumnScope.ProfileMain(viewer: String?, showClosed: Boolean, onShowClosed: (Boolean) -> Unit, choices: List<RepoChoice>, onRepos: () -> Unit) {
    val c = LocalPullsColors.current
    val github = com.octaviantocan.pulls.ui.rememberOpenOnGithub()
    // The Inbox names you only from pull requests you opened; the server's GitHub sign-in names you otherwise.
    var statsLogin by remember { mutableStateOf<String?>(null) }
    val you = viewer ?: statsLogin
    // You, and a tap on you opens your GitHub profile (H23): no row of its own, no server address under the name (H24).
    Row(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(PullsRadius.lg))
            .then(if (you != null) Modifier.cueClickable(role = Role.Button, onClickLabel = "Open your GitHub profile") { github("https://github.com/$you") } else Modifier)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (you != null) GithubAvatar(you, size = 64.dp) else ProfileDot(size = 64.dp)
        Text(you ?: "Pully", style = PullsType.xxl.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
    }
    // Your year on GitHub, between you and the settings: the sheet is a profile, not only switches (M5).
    ProfileStats(onLogin = { statsLogin = it })
    // The card sits under its heading, below the numbers.
    com.octaviantocan.pulls.ui.design.SheetHeading("Inbox")
    SheetCard(gap = 6.dp) {
        SheetRow("Repositories", onRepos, first = true, iconless = true) {
            val on = choices.count { it.shown }
            Text(if (choices.isEmpty()) "None yet" else "$on of ${choices.size} shown", style = PullsType.sm, color = c.muted)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
        SheetRow("Closed pull requests", { onShowClosed(!showClosed) }, iconless = true) {
            PullsSwitch(showClosed, onShowClosed, "Show closed pull requests")
        }
    }
}

/** Every repository your pull requests are in, a switch each: one switched off is left out of the Inbox, of Tours and of the numbers on the chips. */
@Composable
private fun ColumnScope.ReposPage(pulls: List<PullRow>, settings: AppSettings) {
    val c = LocalPullsColors.current
    var query by remember { mutableStateOf("") }
    val allChoices = repoChoices(pulls, settings.hiddenRepos)
    val choices = repoChoices(pulls, settings.hiddenRepos, query)
    SheetSearch(query, { query = it }, "Find a repository", Modifier.padding(top = com.octaviantocan.pulls.ui.design.SheetTopGap))
    if (allChoices.isNotEmpty()) {
        val enabled = allChoices.count { it.shown }
        val allShown = enabled == allChoices.size
        val summary = "$enabled of ${allChoices.size} enabled"
        fun setAll(shown: Boolean) = settings.setReposShown(allChoices.map { it.key }, shown)
        // This always addresses every repository, even when the search shows only some of them.
        SheetCard {
            SheetRow("All repositories", { setAll(!allShown) }, first = true, iconless = true, detail = summary) {
                PullsSwitch(allShown, ::setAll, "Enable all repositories", Modifier.semantics { stateDescription = summary })
            }
        }
    }
    if (choices.isEmpty()) {
        Text(
            if (query.isBlank()) "No repositories yet. They appear once you have a pull request." else "No repository matches “${query.trim()}”.",
            Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    } else {
        SheetCard {
            choices.forEachIndexed { index, choice ->
                // The repository's name, with its owner under it, so a long owner never pushes the name into a wrap.
                SheetRow(choice.repo, { settings.setRepoShown(choice.key, !choice.shown) }, first = index == 0, iconless = true, detail = choice.owner) {
                    if (choice.count > 0) Text("${choice.count}", style = PullsType.sm.copy(fontFeatureSettings = "tnum"), color = c.muted)
                    PullsSwitch(choice.shown, { settings.setRepoShown(choice.key, it) }, "Show ${choice.key}")
                }
            }
        }
    }
}
