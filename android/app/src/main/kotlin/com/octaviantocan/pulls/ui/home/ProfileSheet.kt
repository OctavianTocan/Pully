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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.PullRow
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.Commands
import com.octaviantocan.pulls.ui.design.GithubAvatar
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PillInput
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
 * The profile sheet: who you are and where to find you (the GitHub profile, the website the server lives
 * at), which repositories the Inbox shows, closed pull requests, and Commands. The Inbox refreshes itself,
 * so there is no Refresh here, and the rest of the app has no use for checking out a branch.
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

internal fun ProfilePage.title(): String = if (this == ProfilePage.Main) "Profile" else "Repositories"

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
        ProfilePage.Main -> ProfileMain(viewer, showClosed, onShowClosed, choices = repoChoices(pulls, settings.hiddenRepos), onRepos = { onPage(ProfilePage.Repos) }, onClose = onClose)
        ProfilePage.Repos -> ReposPage(pulls, settings)
    }
}

@Composable
private fun ColumnScope.ProfileMain(viewer: String?, showClosed: Boolean, onShowClosed: (Boolean) -> Unit, choices: List<RepoChoice>, onRepos: () -> Unit, onClose: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val server = remember { ServerStore.load(context).url.trim().trimEnd('/') }
    val host = server.removePrefix("https://").removePrefix("http://")
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (viewer != null) GithubAvatar(viewer, size = 56.dp) else ProfileDot(size = 56.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(viewer ?: "Pulls", style = PullsType.xl.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1)
            if (host.isNotBlank()) {
                // The website the server lives at: it opens, and the mark says it leaves the app.
                Row(
                    Modifier.clip(RoundedCornerShape(PullsRadius.md)).cueClickable(role = Role.Button, onClickLabel = "Open $host") { uri.openUri(server) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(host, style = PullsType.sm, color = c.activeText, maxLines = 1)
                    LuIcon(Lu.ext, tint = c.activeText, size = 14.dp)
                }
            }
        }
    }
    SheetCard {
        if (viewer != null) {
            SheetRow("GitHub profile", { uri.openUri("https://github.com/$viewer") }, first = true, iconless = true) { LuIcon(Lu.ext, tint = c.faint, size = 18.dp) }
        }
        SheetRow("Repositories", onRepos, first = viewer == null, iconless = true) {
            val on = choices.count { it.shown }
            Text(if (choices.isEmpty()) "None yet" else "$on of ${choices.size} shown", style = PullsType.sm, color = c.muted)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
        SheetRow("Closed pull requests", { onShowClosed(!showClosed) }, iconless = true) {
            PullsSwitch(showClosed, onShowClosed, "Show closed pull requests")
        }
        SheetRow("Commands", { onClose(); Commands.open() }, iconless = true) { LuIcon(Lu.chev, tint = c.faint, size = 18.dp) }
    }
}

/** Every repository your pull requests are in, a switch each: one switched off is left out of the Inbox and of the numbers on its chips. */
@Composable
private fun ColumnScope.ReposPage(pulls: List<PullRow>, settings: AppSettings) {
    val c = LocalPullsColors.current
    var query by remember { mutableStateOf("") }
    val choices = repoChoices(pulls, settings.hiddenRepos, query)
    PillInput(query, { query = it }, "Find a repository", Modifier.padding(top = 14.dp), imeAction = ImeAction.Search)
    Text(
        "A repository that is switched off is left out of the Inbox and of the numbers on its chips.",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
    )
    if (choices.isEmpty()) {
        Text(
            if (query.isBlank()) "No repositories yet. They appear once you have a pull request." else "No repository matches “${query.trim()}”.",
            Modifier.fillMaxWidth().padding(vertical = 32.dp), style = PullsType.sm, color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    } else {
        SheetCard {
            choices.forEachIndexed { index, choice ->
                SheetRow(choice.key, { settings.setRepoShown(choice.key, !choice.shown) }, first = index == 0, iconless = true) {
                    if (choice.count > 0) Text("${choice.count}", style = PullsType.sm, color = c.muted)
                    PullsSwitch(choice.shown, { settings.setRepoShown(choice.key, it) }, "Show ${choice.key}")
                }
            }
        }
    }
}
