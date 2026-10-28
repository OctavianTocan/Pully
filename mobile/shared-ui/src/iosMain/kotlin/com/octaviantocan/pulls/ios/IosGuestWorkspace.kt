package com.octaviantocan.pulls.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.*
import com.octaviantocan.pulls.ui.design.*
import com.octaviantocan.pulls.ui.explore.GuestBrowseSession
import com.octaviantocan.pulls.ui.explore.GuestPages
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.home.HomeBackEffect
import com.octaviantocan.pulls.ui.home.HomeLifecycleObserver
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.home.SourceHomeActions
import com.octaviantocan.pulls.ui.personal.PersonalBrowseMount
import com.octaviantocan.pulls.ui.personal.PersonalBrowseAccountSheet
import com.octaviantocan.pulls.ui.personal.GitHubAccountEntry
import com.octaviantocan.pulls.ui.personal.GitHubAccountEntrySheet
import com.octaviantocan.pulls.ui.personal.GitHubAccountRequest
import com.octaviantocan.pulls.ui.settings.ColorThemeSheet
import com.octaviantocan.pulls.ui.settings.wantsDark
import com.octaviantocan.pulls.settings.UserDefaultsSettings
import com.octaviantocan.pulls.ui.review.PublicReviewFrame
import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalWorkspaceOwner
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSUserDefaults
import kotlin.time.Clock

/** Public identity remains separate from the tokenless repository session and every private service. */
internal class IosGuestWorkspace(
    native: NativeHomeActions,
    private val settings: AppSettings,
    transport: RpcTransport,
    scope: CoroutineScope,
    source: NativeEntryState.GuestSource,
    private val navigation: AppNavigation,
    private val incoming: IncomingLinkInbox,
    private val isCurrent: () -> Boolean,
    private val personalSessions: IosPersonalSessionStore? = null,
    private val accountRequest: GitHubAccountRequest? = null,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : NativeWorkspace {
    private var closed = false
    private val personalForeground = native as? NativePersonalForegroundActions
    private val session = GuestBrowseSession(transport, scope, source, navigation)
    val current get() = !closed && isCurrent() && session.current
    private val native = SourceHomeActions(native) { current }
    /** A public pull request opens on Pully's own review page, read-only, as on Android. */
    private val publicReview = PublicReviewFrame(
        transport, scope, settings, this.native, SharedReviewFrameBack, current = { current },
        nowMillis = { Clock.System.now().toEpochMilliseconds() },
    )
    /** Whether the Explore welcome sheet has been seen, on this phone; it opens once, as on Android. */
    private val explorePrefs = UserDefaultsSettings(defaults, "pully-explore")
    private val personal = personalSessions?.takeIf { it.configured && personalForeground != null }?.open(scope) { current }
    internal val personalBrowse = personal?.let { PersonalBrowseMount(it, scope, source, navigation) }
    private var foreground by mutableStateOf(false)
    private var personalAvailable by mutableStateOf(false)
    /** The whole app mounted for a verified GitHub workspace, as on Android; null on Explore. */
    private var personalApp: IosPersonalWorkspace? = null
    /** Whether the person went back to Explore from a mounted GitHub workspace. */
    private var exploring by mutableStateOf(false)
    /**
     * A saved GitHub login opens on the signed-in side's still Inbox while it is checked, as on Android. Asked once,
     * after the first active storage probe, since secure storage is read only on an actual active event.
     */
    private var awaitingSaved by mutableStateOf(false)
    private var savedLoginAsked = false
    private var personalActivityEpoch = 0L
    private val githubAccountEntry = GitHubAccountEntry(
        current = { current }, foreground = { this.native.isSharedBackActive() },
        configured = { personalSessions?.configured == true },
        available = { foreground && personalAvailable && personal != null },
        mount = { personalBrowse },
    )
    private val commands = CommandPaletteOwner { current }
    private val links = NativeGuestLinkHandoff(incoming, navigation) { current }
    private val personalActivity = try {
        personalForeground?.observePersonalForeground(object : NativePersonalForegroundObserver {
            override fun changed(active: Boolean) {
                val opening = ++personalActivityEpoch
                if (!current) return
                personalApp?.foreground(false)
                personalBrowse?.foreground(active, available = false)
                if (!current || personalActivityEpoch != opening) return
                foreground = active
                personalAvailable = false
                if (!active || !current || personalActivityEpoch != opening || !foreground) return
                val available = personal != null && try { requireNotNull(personalSessions).verifyStorage(); true } catch (_: Exception) { false }
                if (!current || personalActivityEpoch != opening || !foreground) return
                personalAvailable = available
                personalBrowse?.availability(available)
                if (!available) awaitingSaved = false
                if (available && !savedLoginAsked) {
                    savedLoginAsked = true
                    awaitingSaved = personal?.savedPrivateLogin() == true
                    if (!current || personalActivityEpoch != opening || !foreground) return
                }
                if (available) personal?.restore()
                if (current && personalActivityEpoch == opening && foreground) personalApp?.foreground(available)
                if (current && personalActivityEpoch == opening && foreground) accountRequest?.deliver(githubAccountEntry)
            }
        })
    } catch (failure: Throwable) {
        personalBrowse?.close()
        personal?.close()
        commands.close()
        publicReview.close()
        session.close()
        throw failure
    }
    private val lifecycle = try {
        native.observeLifecycle(object : HomeLifecycleObserver {
            override fun started() {}
            override fun resumed() { if (current) session.foreground() }
            override fun stopped() {}
        })
    } catch (failure: Throwable) {
        personalActivity?.cancel()
        personalBrowse?.close()
        personal?.close()
        commands.close()
        publicReview.close()
        session.close()
        throw failure
    }

    @Composable
    fun Content(onExistingAccount: () -> Unit) {
        val personalView = personal?.view?.collectAsState()?.value
        val workspace = personalView?.account?.let { personal.workspaceOwner() }
        var sawChecking by remember { mutableStateOf(false) }
        LaunchedEffect(personalView) {
            if (personalView == null) return@LaunchedEffect
            if (personalView.checking) sawChecking = true
            if (personalView.account != null || personalView.problem != null || (sawChecking && !personalView.checking)) awaitingSaved = false
        }
        LaunchedEffect(awaitingSaved) { if (awaitingSaved) { delay(SAVED_LOGIN_WAIT_MS); awaitingSaved = false } }
        if (workspace != null && !exploring) {
            key(workspace) { PersonalApp(workspace, personal) }
            return
        }
        val colorTheme = ColorThemes.of(settings.colorTheme)
        val dark = wantsDark(settings)
        val messages = rememberMessages(settings.locale)
        val colors = colorTheme.colors(dark)
        if (AppFonts.selected != settings.appFont) AppFonts.selected = settings.appFont
        if (CodeFonts.selected != settings.codeFont) CodeFonts.selected = settings.codeFont
        PullsMaterialTheme(colors, messages) {
            val scope = rememberCoroutineScope()
            val motion = remember { PageMotion() }
            val backs = remember { SharedBackDispatcher() }
            val toaster = remember { Toaster() }
            var themes by remember { mutableStateOf(false) }
            if (awaitingSaved && workspace == null && !exploring) {
                Box(Modifier.fillMaxSize().background(colors.base)) { RowsSkeleton(Modifier.statusBarsPadding().padding(top = 128.dp)) }
                return@PullsMaterialTheme
            }
            LaunchedEffect(accountRequest?.hasPending, foreground, personalAvailable) {
                if (current) accountRequest?.deliver(githubAccountEntry)
            }
            val moving = rememberMotionEnabled()
            CompositionLocalProvider(
                LocalColorTheme provides colorTheme,
                LocalToaster provides toaster,
                LocalSharedBackDispatcher provides backs,
                com.octaviantocan.pulls.ui.home.LocalNativeHomeActions provides native,
            ) {
                HomeBackEffect(native, motion, backs, current && navigation.stack.size > 1) {
                    if (current) navigation.nav.back()
                }
                UseCommands {
                    if (!current) emptyList() else {
                        val group = messages.t("palette.group.general")
                        listOf(
                            Command("home", messages.t("explore.browse"), group) { if (current) navigation.nav.home() },
                            Command("account", messages.t("explore.existingAccount"), group) { if (current) onExistingAccount() },
                            Command("theme", messages.t("keys.theme"), group) {
                                if (current) scope.launch {
                                    ThemeWipe.run(fromRight = !dark, motion = moving, changes = colorTheme.colors(dark) != colorTheme.colors(!dark)) {
                                        if (current) settings.updateTheme(if (dark) "light" else "dark")
                                    }
                                }
                            },
                            Command("colorTheme", messages.t("keys.colorTheme"), group, keywords = "theme colour color palette appearance") { if (current) themes = true },
                        )
                    }
                }
                val arrival = incoming.pending
                LaunchedEffect(arrival) {
                    if (arrival == null || !current) return@LaunchedEffect
                    val screen = arrival.text?.let { sharedLinkScreen(it, arrival.aiResult) }
                    if (screen == null) {
                        incoming.consume(arrival)
                        toaster.show("Pully can't open that link")
                    } else session.openIncoming(
                        screen, { current && incoming.pending === arrival }, { Sheets.closeAll() }, { links.committed(screen, arrival) },
                    )
                }
                Box(Modifier.fillMaxSize().themeWipe().background(colors.base)) {
                    GuestPages(
                        navigation, motion, session,
                        { if (current) onExistingAccount() },
                        { url -> if (current) native.openOnGithub(url) },
                        onGitHubSignIn = { if (workspace != null) exploring = false else githubAccountEntry.open() },
                        onCommands = { if (current) Commands.open() },
                        githubAccount = personalView?.account?.login,
                        personal = personalBrowse,
                        publicReview = publicReview,
                        welcomeSeen = explorePrefs.getBoolean("welcomeSeen", false),
                        onWelcomeSeen = { explorePrefs.edit().putBoolean("welcomeSeen", true).apply() },
                    )
                    personalBrowse?.let { mount -> PersonalBrowseAccountSheet(mount,
                        onOpenBrowser = { url -> if (current && foreground && personalAvailable) native.openOnGithub(url) }) }
                    GitHubAccountEntrySheet(githubAccountEntry)
                    if (themes) ColorThemeSheet(settings, { current }) { themes = false }
                    CommandPalette(commands)
                    ToastHost(toaster)
                }
            }
        }
    }

    /**
     * Mounts the whole app for [workspace] on this scene's own stack: open Review, Repo and Settings pages stay above
     * Home, and the owner retiring returns to Explore (Android's GuestPullsApp).
     */
    @Composable
    private fun PersonalApp(workspace: PersonalWorkspaceOwner, personal: PersonalAccountRuntime) {
        val app = remember(workspace) {
            Commands.close()
            val pending = navigation.stack.filter { it is Screen.Review || it is Screen.Repo || it == Screen.Settings }
            navigation.reset(Screen.Home)
            navigation.stack.addAll(pending)
            IosPersonalWorkspace(workspace, personal, native, settings, defaults, navigation, incoming) { current }
                .also { personalApp = it; it.foreground(foreground && personalAvailable) }
        }
        DisposableEffect(app) {
            val stop = workspace.onRetired {
                Commands.close()
                navigation.reset(Screen.Explore)
            }
            onDispose {
                stop()
                if (personalApp === app) personalApp = null
                app.close()
            }
        }
        app.Content(onExplore = {
            if (current) {
                exploring = true
                Commands.close()
                navigation.reset(Screen.Explore)
            }
        })
    }

    /** Settings changed on this phone, for the mounted workspace's sync. */
    fun touched(name: String) { if (current) personalApp?.touched(name) }

    /** A displayed fallback can wait through the real private sign-in/source verification. */
    fun prepareExistingEntry() = links.prepareExistingEntry()

    override fun close() {
        if (closed) return
        closed = true
        personalApp?.close()
        personalApp = null
        githubAccountEntry.close()
        personalBrowse?.close()
        personalActivityEpoch++
        foreground = false
        personalAvailable = false
        links.clear()
        personalActivity?.cancel()
        personal?.close()
        lifecycle.cancel()
        commands.close()
        publicReview.close()
        session.close()
    }
}

/** How long a saved GitHub login may take to be verified before Explore shows instead (Android's wait). */
private const val SAVED_LOGIN_WAIT_MS = 10_000L
