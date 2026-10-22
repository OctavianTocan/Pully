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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.withFrameNanos
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.data.Analytics
import com.octaviantocan.pulls.data.NativeEntry
import com.octaviantocan.pulls.data.JvmRpcTransport
import com.octaviantocan.pulls.ui.explore.GuestBrowseSession
import com.octaviantocan.pulls.ui.explore.GuestPages
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.book.UiBook
import com.octaviantocan.pulls.ui.design.BlurSwitch
import com.octaviantocan.pulls.ui.design.FootBarLayer
import com.octaviantocan.pulls.ui.design.FootBars
import com.octaviantocan.pulls.ui.design.LocalFootBars
import com.octaviantocan.pulls.ui.design.PageBack
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.ToastHost
import com.octaviantocan.pulls.ui.design.Toaster
import com.octaviantocan.pulls.ui.design.themeWipe
import com.octaviantocan.pulls.ui.home.HomeScreen
import com.octaviantocan.pulls.ui.home.rememberAndroidHomeScreenServices
import com.octaviantocan.pulls.ui.home.HomeUi
import com.octaviantocan.pulls.ui.home.RepoScreen
import com.octaviantocan.pulls.ui.review.ReviewScreen
import com.octaviantocan.pulls.ui.settings.ColorThemeSheet
import com.octaviantocan.pulls.ui.settings.AccountCheck
import com.octaviantocan.pulls.ui.settings.SettingsScreen
import com.octaviantocan.pulls.ui.settings.SignInGate
import com.octaviantocan.pulls.ui.settings.ConnectionSetupGate
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

@Composable
fun PullsApp(initialTarget: String?, initialAIResult: String? = null) {
    val context = LocalContext.current
    val entry = remember { NativeEntry.get(context) }
    val navigation = remember {
        AppNavigation(initialTarget?.let { runCatching { linkScreen(it, initialAIResult) }.getOrNull() },
            rootScreen = if (entry.mode == NativeEntryMode.Explore) Screen.Explore else Screen.Home)
    }
    fun select(mode: NativeEntryMode) {
        if (entry.mode == mode) return
        val pendingLink = navigation.stack.lastOrNull()?.takeIf { it is Screen.Review || it is Screen.Repo }
        Commands.close()
        NativeEntry.select(context, mode)
        navigation.reset(if (mode == NativeEntryMode.Explore) Screen.Explore else Screen.Home)
        if (mode == NativeEntryMode.ExistingServer && pendingLink != null) navigation.stack.add(pendingLink)
    }
    key(entry.generation) {
        if (entry.mode == NativeEntryMode.Explore) {
            GuestPullsApp(navigation, entry.guestSource()!!, initialTarget, onExistingAccount = { select(NativeEntryMode.ExistingServer) })
        } else {
            PrivatePullsApp(navigation, initialTarget, onExplore = { select(NativeEntryMode.Explore) })
        }
    }
}

@Composable
private fun GuestPullsApp(navigation: AppNavigation, source: NativeEntryState.GuestSource, initialTarget: String?, onExistingAccount: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { GuestBrowseSession(JvmRpcTransport(), scope, source, navigation) }
    val personal = remember {
        com.octaviantocan.pulls.data.personal.PersonalAccounts.runtime(context, scope, { source.current }) {
            Commands.close()
            NativeEntry.get(context).renewExplore()
            navigation.reset(Screen.Explore)
        }
    }
    DisposableEffect(session, personal) { onDispose { personal.close(); session.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { personal.foreground(true); session.foreground(); personal.restore() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { personal.foreground(false) }
    val personalView by personal.view.collectAsState()
    var accountSheet by remember { mutableStateOf(false) }
    val toaster = remember { Toaster() }
    val motion = remember { PageMotion() }
    var leaving by remember { mutableStateOf(false) }
    var themePicker by remember { mutableStateOf(false) }
    PageBack(motion, enabled = navigation.stack.size > 1) { navigation.nav.back() }
    androidx.activity.compose.BackHandler(enabled = navigation.stack.size == 1) { leaving = true } // motion-ok: the root opens the existing close confirmation
    LaunchedEffect(Unit) { if (initialTarget != null && runCatching { linkScreen(initialTarget) }.getOrNull() == null) toaster.show(CANT_OPEN, cue = Cue.Error) }
    val arrival = IncomingLinks.pending
    LaunchedEffect(arrival) {
        if (arrival == null || !session.current) return@LaunchedEffect
        val screen = runCatching { linkScreen(arrival.text, arrival.aiResult) }.getOrNull()
        if (screen == null) {
            IncomingLinks.consume(arrival)
            toaster.show(CANT_OPEN, cue = Cue.Error)
        } else {
            session.openIncoming(
                screen,
                isPending = { IncomingLinks.pending === arrival },
                closeSheets = { com.octaviantocan.pulls.ui.design.Sheets.closeAll() },
                consume = { IncomingLinks.consume(arrival) },
            )
        }
    }
    val settings = com.octaviantocan.pulls.data.AppSettings.get(context)
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    val dark = com.octaviantocan.pulls.ui.settings.wantsDark(settings)
    val colorTheme = com.octaviantocan.pulls.ui.design.ColorThemes.of(settings.colorTheme)
    val moving = com.octaviantocan.pulls.ui.design.rememberMotionEnabled()
    UseCommands {
        val group = messages.t("palette.group.general")
        listOf(
            Command("home", messages.t("explore.browse"), group) { navigation.nav.home() },
            Command("account", messages.t("explore.existingAccount"), group, run = onExistingAccount),
            Command("theme", messages.t("keys.theme"), group) {
                scope.launch { com.octaviantocan.pulls.ui.design.ThemeWipe.run(fromRight = !dark, motion = moving, changes = colorTheme.colors(dark) != colorTheme.colors(!dark)) { settings.updateTheme(if (dark) "light" else "dark") } }
            },
            Command("colorTheme", messages.t("keys.colorTheme"), group, keywords = "theme colour color palette appearance") { themePicker = true },
        )
    }
    CompositionLocalProvider(LocalToaster provides toaster) {
        val openGitHub = rememberOpenOnGithub()
        Box(Modifier.fillMaxSize().themeWipe().background(LocalPullsColors.current.base)) {
            GuestPages(navigation, motion, session, onExistingAccount, openGitHub,
                onGitHubSignIn = if (personal.configured) ({ accountSheet = true }) else null,
                onCommands = { Commands.open() }, githubAccount = personalView.account?.login)
            if (accountSheet) com.octaviantocan.pulls.ui.personal.PersonalAccountSheet(personal,
                onOpenBrowser = { url ->
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }, onClose = { accountSheet = false })
            if (themePicker) ColorThemeSheet(onClose = { themePicker = false })
            CommandPalette()
            if (leaving) LeaveSheet(onStay = { leaving = false }, onLeave = { context.findActivity()?.finish() })
            ToastHost(toaster)
        }
    }
}

@Composable
private fun PrivatePullsApp(navigation: AppNavigation, initialTarget: String?, onExplore: () -> Unit) {
    val context = LocalContext.current
    val messages = com.octaviantocan.pulls.ui.design.LocalMessages.current
    val stack = navigation.stack
    // The server is asked for a newer build on start and whenever the app comes back to the front, and an update
    // waiting on "Install unknown apps" carries on once that is allowed.
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Updates.restore(context)
        Updates.resume(context)
        scope.launch { Updates.check(context) }
        com.octaviantocan.pulls.notifications.DeviceNotifications.sync(context)
    }
    // Whether the server has an account and this phone's session still works, on start and on every return.
    remember { com.octaviantocan.pulls.data.Account.load(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { com.octaviantocan.pulls.data.AiWork.foreground(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { com.octaviantocan.pulls.data.AiWork.background() }
    val appLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(com.octaviantocan.pulls.data.Account.email, com.octaviantocan.pulls.data.Account.mustSignIn) {
        if (com.octaviantocan.pulls.data.Account.mustSignIn) {
            com.octaviantocan.pulls.data.AiWork.clear(context)
            com.octaviantocan.pulls.notifications.DeviceNotifications.reset(context)
        }
        else if (appLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) com.octaviantocan.pulls.data.AiWork.foreground(context)
    }
    AccountCheck()
    // After an update, what changed opens once, over whatever page Pully opened on.
    var whatsNew by remember { mutableStateOf(WhatsNew.pending(context)) }
    LaunchedEffect(Unit) { if (whatsNew.isNotEmpty()) Analytics.track("update.event", "step" to "whatsnew", "version" to AppVersion.name(context)) }
    // Commands' Change theme opens the theme list as a sheet of its own over whatever page is open (K18).
    var themePicker by remember { mutableStateOf(false) }
    // Send feedback, from Commands on any page (nav-feedback-from-anywhere).
    var feedback by remember { mutableStateOf(false) }
    AskForUpdateNotifications(waiting = whatsNew.isNotEmpty() || Updates.available != null)
    val nav = navigation.nav
    // Back slides the page backward, and a back swipe moves the page with the finger before it goes (rule 105).
    val pageMotion = remember { PageMotion() }
    PageBack(pageMotion, enabled = stack.size > 1) { nav.back() }
    // Back on the Inbox asks before Pully closes, rather than leaving at once (O5).
    var leaving by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = stack.size == 1) { leaving = true } // motion-ok: on the Inbox there is no page behind to move; Back opens the "Close Pully?" sheet
    val settings = com.octaviantocan.pulls.data.AppSettings.get(context)
    // Every code view reads `PullsType.mono`, which follows this.
    if (com.octaviantocan.pulls.ui.design.CodeFonts.selected != settings.codeFont) com.octaviantocan.pulls.ui.design.CodeFonts.selected = settings.codeFont
    // The look asked for, not the palette's: a theme with one look stays dark under Light, and the toggle still flips the choice.
    val dark = com.octaviantocan.pulls.ui.settings.wantsDark(settings)
    val colorTheme = com.octaviantocan.pulls.ui.design.ColorThemes.of(settings.colorTheme)
    val moving = com.octaviantocan.pulls.ui.design.rememberMotionEnabled()
    // The inbox's tab, chip and search live above the screens, so they are still there after a review.
    // It opens on the Inbox chip last picked (P1).
    val homeUi = remember { HomeUi(settings.inboxFilter) }
    val toaster = remember { Toaster() }
    // A link Pully was opened with that it cannot open says so, rather than leaving the reader on Home wondering.
    LaunchedEffect(Unit) { if (initialTarget != null && linkScreen(initialTarget) == null) toaster.show(CANT_OPEN, cue = Cue.Error) }
    // A link handed over while Pully is open opens over the page on screen, unless that page is already it: then it
    // reads its pull request again.
    val arrival = IncomingLinks.pending
    LaunchedEffect(arrival) {
        if (arrival == null) return@LaunchedEffect
        IncomingLinks.consume(arrival)
        val screen = linkScreen(arrival.text, arrival.aiResult)
        // Any sheet open (Settings, Profile, a pull request's ⋯) slides down first, so the new page never opens under
        // it and nothing is left open behind it to find on the way back (O1, O4).
        if (screen != null) com.octaviantocan.pulls.ui.design.Sheets.closeAll()
        if (screen != null && stack.last() == Screen.Settings) stack.removeAt(stack.lastIndex)
        val top = stack.last()
        when {
            screen == null -> toaster.show(CANT_OPEN, cue = Cue.Error)
            screen == top || screen is Screen.Review && top is Screen.Review && screen.target.serverTarget == top.target.serverTarget ->
                (top as? Screen.Review)?.let { IncomingLinks.reopen(it.target, (screen as? Screen.Review)?.place?.place, (screen as? Screen.Review)?.aiResult) }
            else -> stack.add(screen)
        }
    }
    // `KeyboardLayer.vue`'s always-on commands, and the app's own update.
    UseCommands {
        val commandEpoch = com.octaviantocan.pulls.data.RepoIdentity.generation
        appCommands(
            { messages.t(it) }, nav,
            current = { commandEpoch == com.octaviantocan.pulls.data.RepoIdentity.generation },
            toggleTheme = {
                scope.launch { com.octaviantocan.pulls.ui.design.ThemeWipe.run(fromRight = !dark, motion = moving, changes = colorTheme.colors(dark) != colorTheme.colors(!dark)) { settings.updateTheme(if (dark) "light" else "dark") } }
            },
            openThemes = { themePicker = true }, feedback = { feedback = true },
            update = { scope.launch { updateFromCommand(context, toaster) } },
            whatsNew = { whatsNew = WhatsNew.all(context) },
            explore = onExplore,
        )
    }
    // One hook for screen time: the page on top by a fixed name (`screen.view`, `screen.time`). Settings names its own pages.
    val top = stack.last()
    LaunchedEffect(top.analyticsName) { Analytics.showScreen(top.analyticsName) }
    // The pull request or repository feedback is written about: the page under Settings when Settings is open.
    val page = stack.lastOrNull { it != Screen.Settings }
    LaunchedEffect(page) {
        com.octaviantocan.pulls.ui.settings.FeedbackFrom.place = when (page) {
            is Screen.Review -> page.target.serverTarget
            is Screen.Repo -> "${page.owner}/${page.repo}"
            else -> null
        }
    }
    // Profile's GitHub numbers and the Stats page are asked for once Pully has settled, so they open on today's
    // numbers instead of waiting (O6, O10).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_500)
        launch { com.octaviantocan.pulls.ui.home.StatsMemory.warm(context) }
        launch { com.octaviantocan.pulls.ui.settings.StatsKeep.warm(context) }
    }
    // The first frame of a launch on Home is how long the app took to open (`app.open`).
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (stack.size == 1 && stack.first() == Screen.Home) Analytics.firstFrame()
    }
    // The one bar at the foot, over every page: pages say what it holds and it stays while they slide (Q2).
    val footBars = remember { FootBars() }
    // A link tapped in a description, a comment or an answer opens in Pully when Pully would take it from another app, and
    // outside otherwise; "on GitHub" actions go outside on their own (`rememberOpenOnGithub`).
    val uriHandler = remember(context, toaster) { PullyUriHandler(context, toaster) }
    val nativeHomeActions = com.octaviantocan.pulls.ui.home.rememberAndroidNativeHomeActions()
    CompositionLocalProvider(
        com.octaviantocan.pulls.ui.home.LocalNativeHomeActions provides nativeHomeActions,
        LocalToaster provides toaster, LocalFootBars provides footBars,
        androidx.compose.ui.platform.LocalUriHandler provides uriHandler,
        com.octaviantocan.pulls.ui.ai.LocalAiCapabilities provides com.octaviantocan.pulls.ui.ai.AiCapabilities.Full,
        com.octaviantocan.pulls.ui.review.LocalReviewCapabilities provides com.octaviantocan.pulls.ui.review.ReviewCapabilities.Full,
    ) {
        val homeServer = remember { com.octaviantocan.pulls.data.Repo(context) }
        val homeServices = rememberAndroidHomeScreenServices(homeServer)
        Box(Modifier.fillMaxSize().themeWipe().background(LocalPullsColors.current.base)) {
            // Settings is a modal over the page it was opened from, as on the web. A page that opens slides in and
            // the one under it slides away (rule 73), and back slides the other way, ranked by how deep the page is.
            AppPages(navigation, pageMotion, Modifier.fillMaxSize()) { current ->
                when (current) {
                    Screen.Home -> HomeScreen(nav, homeUi, homeServices)
                    is Screen.Repo -> RepoScreen(current.owner, current.repo, nav, current.tours, homeServices)
                    is Screen.Review -> ReviewScreen(current.target, nav, startOnTour = current.tour, openedFrom = current.from, place = current.place, initialAIResult = current.aiResult)
                    Screen.UiBook -> UiBook(nav.back)
                    Screen.Settings -> {}
                    Screen.Explore, is Screen.PublicRepo -> {}
                }
            }
            FootBarLayer(footBars, Modifier.align(Alignment.BottomCenter))
            if (stack.last() == Screen.Settings) SettingsScreen(nav)
            if (themePicker) ColorThemeSheet(onClose = { themePicker = false })
            if (feedback) com.octaviantocan.pulls.ui.settings.FeedbackSheet(onClose = { feedback = false })
            if (whatsNew.isNotEmpty()) WhatsNewSheet(whatsNew, onClose = { WhatsNew.markSeen(context); whatsNew = emptyList() })
            CommandPalette()
            if (leaving) LeaveSheet(onStay = { leaving = false }, onLeave = { context.findActivity()?.finish() })
            ToastHost(toaster)
            // A newer build is not optional: while the server offers one, its screen covers the app. Back leaves Pully.
            UpdateGate(onLeave = { context.findActivity()?.moveTaskToBack(true) })
            // On a server with an account, a phone not signed in to it signs in first; once signed in it stays (plan 13).
            if (Updates.available == null) {
                ConnectionSetupGate(onLeave = { context.findActivity()?.moveTaskToBack(true) }, onExplore = onExplore)
                SignInGate(onLeave = { context.findActivity()?.moveTaskToBack(true) }, onExplore = onExplore)
            }
        }
    }
}

/**
 * "Close Pully?" when Back is pressed on the Inbox: one button closes the app, the other keeps it, and Back or the ✕
 * keeps it too ("I would maybe add a little menu that says, are you sure you want to close the app?": recording
 * 16 Oct 13:06, 02:47 to 02:55; O5).
 */
@Composable
private fun LeaveSheet(onStay: () -> Unit, onLeave: () -> Unit) {
    com.octaviantocan.pulls.ui.design.PullsSheet(onStay, "Close Pully?", expandable = false) {
        androidx.compose.foundation.layout.Spacer(Modifier.height(com.octaviantocan.pulls.ui.design.SheetTopGap))
        com.octaviantocan.pulls.ui.design.CtaButton("Close Pully", onLeave, Modifier.fillMaxWidth())
        com.octaviantocan.pulls.ui.design.PillButton("Stay", onStay, Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

private const val CANT_OPEN = "Pully can't open that link"

/**
 * The page a link from another app opens: a pull request, commit or compare opens its review, a repository its page.
 * `null` for anything else, an issue or a file included.
 */
private fun linkScreen(text: String, aiResult: String? = null): Screen? = sharedLinkScreen(text, aiResult)

/** The screen's name in usage stats: a fixed word, never a title or a repository. */
private val Screen.analyticsName: String
    get() = when (this) {
        Screen.Home -> "home"
        Screen.Explore -> "explore"
        is Screen.PublicRepo -> "publicrepo"
        is Screen.Repo -> "repo"
        is Screen.Review -> "review"
        Screen.Settings -> "settings"
        Screen.UiBook -> "uibook"
    }

/**
 * The Update command: asks the server now, however recently it was asked. A newer build starts downloading at once, on
 * the update screen; otherwise a toast says this is the newest, or that the server could not be reached.
 */
private suspend fun updateFromCommand(context: Context, toaster: Toaster) {
    if (Updates.available != null) return Updates.start(context)
    when (Updates.check(context, force = true)) {
        UpdateCheck.Found -> Updates.start(context)
        UpdateCheck.UpToDate -> toaster.show("Pully ${AppVersion.name(context)} is the newest version")
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
