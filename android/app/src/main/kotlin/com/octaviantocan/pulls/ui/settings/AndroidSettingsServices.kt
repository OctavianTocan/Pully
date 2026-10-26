package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.RepoIdentity
import com.octaviantocan.pulls.ui.OutsideLinks
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Haptics
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.ui.book.UiBookRow
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.update.ChangelogEntries
import com.octaviantocan.pulls.update.WhatsNew

/** Host boundary for the provider browser; tests keep the real Settings page with a local browser fixture. */
internal val LocalProviderSignInBrowser = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun rememberAndroidSettingsServices(): SettingsServices {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = AppSettings.get(context)
    val providerBrowser = LocalProviderSignInBrowser.current
    val paper = LocalPullsColors.current.paper
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    var foreground by androidx.compose.runtime.remember(lifecycle) {
        androidx.compose.runtime.mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val models = rememberAndroidModelSettingsServices()
    val account = rememberAndroidAccountPagesServices()
    val stats = rememberAndroidStatsServices()
    val feedbackPage = rememberAndroidFeedbackServices()
    val server = rememberAndroidServerPageServices()
    return remember(context, settings, models, account, stats, feedbackPage, server, providerBrowser, paper) {
        val epoch = RepoIdentity.generation
        SettingsServices(settings, models, account, stats, feedbackPage, { server }, object : SettingsFeedback {
            override fun vibrationOn() = Haptics.cue(Cue.Toggle)
            override fun soundsChanged(enabled: Boolean) {
                Sounds.refresh()
                if (enabled) Sounds.play(Cue.Toggle, Emphasis.Normal, Direction.Forward)
            }
            override fun soundSelected(direction: Direction?) = Sounds.play(Cue.Select, Emphasis.Normal, direction)
        }, SettingsPlatformContent(
            pages = setOf(SettingsPage.PastVersions, SettingsPage.WhatsNew, SettingsPage.Links, SettingsPage.BrowserHelper, SettingsPage.Notifications),
            beforeStats = { open ->
                GithubLinksGroup(open = { open(SettingsPage.Links) })
            },
            afterModel = { open ->
                UpdatesGroup(settings, open)
                Group("Notifications") {
                    SheetCard {
                        SheetRow("Checks and AI results", { open(SettingsPage.Notifications) }, first = true, iconless = true) {
                            LuIcon(Lu.chev, tint = LocalPullsColors.current.faint, size = 18.dp)
                        }
                    }
                }
            },
            advancedRows = { open ->
                SheetRow("Past versions", { open(SettingsPage.PastVersions) }, iconless = true) {
                    LuIcon(Lu.chev, tint = LocalPullsColors.current.faint, size = 18.dp)
                }
            },
            advancedFooter = { nav -> UiBookRow(nav.uiBook) },
            page = { page, onPage ->
                when (page) {
                    SettingsPage.PastVersions -> PastVersionsPage()
                    SettingsPage.WhatsNew -> ChangelogEntries(remember { WhatsNew.all(context) })
                    SettingsPage.Links -> GithubLinksPage()
                    SettingsPage.BrowserHelper -> BrowserHelperPage()
                    SettingsPage.Notifications -> NotificationSettings()
                    else -> error("No Android platform settings page: $page")
                }
            },
            experimentalRows = { open -> BrowserHelperExperimentRow { open(SettingsPage.BrowserHelper) } },
        ), { epoch == RepoIdentity.generation }, Analytics::showScreen,
            // The providers' sign-in pages open in a browser sheet over Pully, else in the browser, never in their own
            // apps, which do not show the code.
            SourceAiSignInServices(Repo(context.applicationContext), { epoch == RepoIdentity.generation },
                { url ->
                    if (providerBrowser != null) providerBrowser(url)
                    else if (!ProviderSignInTab.open(context, url, paper)) OutsideLinks.open(context, url, browserOnly = true)
                }, { com.octaviantocan.pulls.ui.book.copyToClipboard(context, it) }, { foreground }, { clear ->
                    val stop = RepoIdentity.observeInvalidation { if (epoch != RepoIdentity.generation) clear() }
                    if (epoch != RepoIdentity.generation) clear()
                    stop
                }, readCopied = { readClipboard(context) }))
    }
}

/** The clipboard's text, read only while Pully is in front (Android allows nothing else), for Claude's sign-in code. */
private fun readClipboard(context: android.content.Context): String? = runCatching {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}.getOrNull()
