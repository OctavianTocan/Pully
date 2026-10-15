package com.octaviantocan.pulls.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.ui.book.UiBook
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.PageBack
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.ToastHost
import com.octaviantocan.pulls.ui.design.Toaster
import com.octaviantocan.pulls.ui.design.themeWipe
import com.octaviantocan.pulls.ui.home.HomeScreen
import com.octaviantocan.pulls.ui.home.HomeUi
import com.octaviantocan.pulls.ui.home.RepoScreen
import com.octaviantocan.pulls.ui.review.ReviewScreen
import com.octaviantocan.pulls.ui.settings.SettingsScreen
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.ui.update.UpdateGate
import com.octaviantocan.pulls.ui.update.WhatsNewSheet
import com.octaviantocan.pulls.update.AppVersion
import com.octaviantocan.pulls.update.UpdateCheck
import com.octaviantocan.pulls.update.UpdateChecks
import com.octaviantocan.pulls.update.UpdatePrefs
import com.octaviantocan.pulls.update.Updates
import com.octaviantocan.pulls.update.WhatsNew
import kotlinx.coroutines.launch

/** The web routes: `/` home, `/:owner/:repo` a repo's PRs, a diff page, and the settings modal as a screen. */
internal sealed interface Screen {
    data object Home : Screen
    data class Repo(val owner: String, val repo: String, val tours: Boolean = false) : Screen
    /** [tour] opens the pull request on its tour instead of its overview. */
    data class Review(val target: OpenTarget, val tour: Boolean = false) : Screen
    data object Settings : Screen
    data object UiBook : Screen
}

/** A screen and how deep in the stack it sits: what [BlurSwitch] ranks, so opening slides one way and back the other. */
private data class Layer(val screen: Screen, val depth: Int)

@Composable
fun PullsApp(initialTarget: String?) {
    val context = LocalContext.current
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    val stack = remember {
        mutableStateListOf<Screen>(Screen.Home).apply {
            // Same parsing as the home URL field: PRs, commits, compares, and repository links.
            when (val link = initialTarget?.let(com.octaviantocan.pulls.ui.home.HomeLinks::parse)) {
                is com.octaviantocan.pulls.ui.home.HomeLink.Target -> add(Screen.Review(link.target))
                is com.octaviantocan.pulls.ui.home.HomeLink.Repo -> add(Screen.Repo(link.owner, link.repo))
                null -> {}
            }
        }
    }
    // The server is asked for a newer build on start and whenever the app comes back to the front, and an update
    // waiting on "Install unknown apps" carries on once that is allowed.
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Updates.restore(context)
        Updates.resume(context)
        scope.launch { Updates.check(context) }
    }
    // After an update, what changed opens once, over whatever page Pulls opened on.
    var whatsNew by remember { mutableStateOf(WhatsNew.pending(context)) }
    AskForUpdateNotifications(waiting = whatsNew.isNotEmpty() || Updates.available != null)
    val nav = remember {
        Nav(
            openTarget = { stack.add(Screen.Review(it)) },
            openTour = { stack.add(Screen.Review(it, tour = true)) },
            openRepo = { owner, repo -> stack.add(Screen.Repo(owner, repo)) },
            openRepoTours = { owner, repo -> stack.add(Screen.Repo(owner, repo, tours = true)) },
            home = { stack.clear(); stack.add(Screen.Home) },
            settings = { if (stack.last() != Screen.Settings) stack.add(Screen.Settings) },
            uiBook = { if (stack.last() != Screen.UiBook) stack.add(Screen.UiBook) },
            back = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
        )
    }
    // Back slides the page backward, and a back swipe moves the page with the finger before it goes (rule 105).
    val pageMotion = remember { PageMotion() }
    PageBack(pageMotion, enabled = stack.size > 1) { nav.back() }
    val settings = com.octaviantocan.pulls.data.AppSettings.get(context)
    // Every code view reads `PullsType.mono`, which follows this.
    if (com.octaviantocan.pulls.ui.design.CodeFonts.selected != settings.codeFont) com.octaviantocan.pulls.ui.design.CodeFonts.selected = settings.codeFont
    val dark = LocalPullsColors.current.dark
    val moving = com.octaviantocan.pulls.ui.design.rememberMotionEnabled()
    // The inbox's tab, chip and search live above the screens, so they are still there after a review.
    val homeUi = remember { HomeUi() }
    val toaster = remember { Toaster() }
    // `KeyboardLayer.vue`'s always-on commands, and the app's own update.
    UseCommands {
        val group = messages.t("palette.group.general")
        listOf(
            Command("settings", messages.t("keys.settings"), group) { nav.settings() },
            Command("home", messages.t("keys.home"), group) { nav.home() },
            Command("theme", messages.t("keys.theme"), group) {
                // The toggle always goes to the other look than the one on screen, so it always wipes.
                scope.launch { com.octaviantocan.pulls.ui.design.ThemeWipe.run(fromRight = !dark, motion = moving, changes = true) { settings.updateTheme(if (dark) "light" else "dark") } }
            },
            Command("update", "Update Pulls", group, keywords = "upgrade new version install check latest") {
                scope.launch { updateFromCommand(context, toaster) }
            },
            Command("whatsNew", "What's new", group, keywords = "changelog changes version release notes") { whatsNew = WhatsNew.all(context) },
        )
    }
    CompositionLocalProvider(LocalToaster provides toaster) {
        Box(Modifier.fillMaxSize().themeWipe().background(LocalPullsColors.current.base)) {
            // Settings is a modal over the page it was opened from, as on the web. A page that opens slides in and
            // the one under it slides away (rule 73), and back slides the other way, ranked by how deep the page is.
            val here = stack.indexOfLast { it != Screen.Settings }.coerceAtLeast(0)
            BlurSwitch(Layer(stack.getOrNull(here) ?: Screen.Home, here), Modifier.fillMaxSize(), order = { it.depth }, motion = pageMotion) { layer ->
                when (val current = layer.screen) {
                    Screen.Home -> HomeScreen(nav, homeUi)
                    is Screen.Repo -> RepoScreen(current.owner, current.repo, nav, startOnTours = current.tours)
                    is Screen.Review -> ReviewScreen(current.target, nav, startOnTour = current.tour)
                    Screen.UiBook -> UiBook(nav.back)
                    Screen.Settings -> {}
                }
            }
            if (stack.last() == Screen.Settings) SettingsScreen(nav)
            if (whatsNew.isNotEmpty()) WhatsNewSheet(whatsNew, onClose = { WhatsNew.markSeen(context); whatsNew = emptyList() })
            CommandPalette()
            ToastHost(toaster)
            // A newer build is not optional: while the server offers one, its screen covers the app. Back leaves Pulls.
            UpdateGate(onLeave = { context.findActivity()?.moveTaskToBack(true) })
        }
    }
}

/**
 * The Update command: asks the server now, however recently it was asked. A newer build starts downloading at once, on
 * the update screen; otherwise a toast says this is the newest, or that the server could not be reached.
 */
private suspend fun updateFromCommand(context: Context, toaster: Toaster) {
    if (Updates.available != null) return Updates.start(context)
    when (Updates.check(context, force = true)) {
        UpdateCheck.Found -> Updates.start(context)
        UpdateCheck.UpToDate -> toaster.show("Pulls ${AppVersion.name(context)} is the newest version")
        UpdateCheck.Failed -> toaster.show("Couldn't reach the server to check for updates", cue = Cue.Error)
        UpdateCheck.Skipped -> {}
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Asks once for leave to post notifications (Android 13 and up), so the background check can say when a new version
 * is out. It waits for the What's new sheet to close, so the system dialog never covers it. Turning the switch on in
 * Settings asks again.
 */
@Composable
private fun AskForUpdateNotifications(waiting: Boolean) {
    val context = LocalContext.current
    val settings = com.octaviantocan.pulls.data.AppSettings.get(context)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { UpdateChecks.sync(context) }
    LaunchedEffect(waiting) {
        if (waiting || Build.VERSION.SDK_INT < 33 || !settings.updateNotifications || UpdatePrefs.askedForNotifications(context)) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return@LaunchedEffect
        UpdatePrefs.markAskedForNotifications(context)
        ask.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
