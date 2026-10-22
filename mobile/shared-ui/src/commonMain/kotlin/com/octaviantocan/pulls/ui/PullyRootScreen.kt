package com.octaviantocan.pulls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities
import com.octaviantocan.pulls.ui.design.*
import com.octaviantocan.pulls.ui.home.*
import com.octaviantocan.pulls.ui.review.LocalReviewCapabilities
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewCapabilities
import com.octaviantocan.pulls.ui.review.ReviewFrameServices
import com.octaviantocan.pulls.ui.review.ReviewScreen
import com.octaviantocan.pulls.ui.settings.*
import kotlinx.coroutines.launch

/** Real account/client and the existing screens. Native code only supplies services and controller lifetime. */
@Composable
fun PullyRootScreen(
    root: PullyRootState,
    account: AccountRuntime,
    settings: AppSettings,
    native: NativeHomeActions,
    createHome: (RootSource) -> HomeScreenServices,
    createSettings: (RootSource, HomeScreenServices) -> SettingsServices,
    createReview: (RootSource, HomeScreenServices, SettingsServices) -> ReviewFrameServices,
    incoming: IncomingLinkInbox,
    incomingAudience: () -> IncomingLinkAudience?,
    openLink: (String) -> Unit,
    serverServices: () -> ServerPageServices,
    limitation: HomePreviewFeature?,
    clearLimitation: () -> Unit,
    firstFrame: () -> Unit,
    showScreen: (String) -> Unit,
    fixture: Boolean = false,
    aiCapabilities: AiCapabilities = AiCapabilities.None,
    reviewCapabilities: ReviewCapabilities = ReviewCapabilities.ReadOnly,
    onExplore: (() -> Unit)? = null,
) {
    val colorTheme = ColorThemes.of(settings.colorTheme)
    val colors = colorTheme.colors(wantsDark(settings))
    val messages = rememberMessages(settings.locale)
    if (AppFonts.selected != settings.appFont) AppFonts.selected = settings.appFont
    if (CodeFonts.selected != settings.codeFont) CodeFonts.selected = settings.codeFont
    val state = account.state
    // Remember the result independently of root.generation: a denied source cannot cause a composition retry loop.
    val home = if (state.phase == AccountPhase.Ready) remember(state, account.sourceEpoch) {
        runCatching { root.home(account.repo, account.sourceEpoch, createHome) }
    } else null
    val signIn = if (state.phase == AccountPhase.SignIn) remember(account.sourceEpoch, state.googleClientId) {
        runCatching { account.signInServices() }
    } else null
    val settingsServices = home?.getOrNull()?.let { services ->
        remember(services) { runCatching { root.settings(services, createSettings) } }
    }
    PullsMaterialTheme(colors, messages) {
        CompositionLocalProvider(
            LocalColorTheme provides colorTheme,
            LocalNativeHomeActions provides native,
            LocalAiCapabilities provides aiCapabilities,
            LocalReviewCapabilities provides reviewCapabilities,
        ) {
            // The old source's transient sheets, errors, page motion and saveable stack leave together.
            key(root.navigation, root.generation) {
                val navigation = root.navigation
                val motion = remember { PageMotion() }
                val backs = remember { SharedBackDispatcher() }
                val bars = remember { FootBars() }
                val toaster = remember { Toaster() }
                val scope = rememberCoroutineScope()
                val moving = rememberMotionEnabled()
                val dark = wantsDark(settings)
                var server by remember { mutableStateOf<ServerPageServices?>(null) }
                var resetting by remember { mutableStateOf<Long?>(null) }
                var themes by remember { mutableStateOf(false) }
                var feedback by remember { mutableStateOf(false) }
                val openingEpoch = account.sourceEpoch
                val openingGeneration = root.generation
                val ownedNative = remember(root, native, openingGeneration) {
                    SourceHomeActions(native) { root.current && root.generation == openingGeneration }
                }
                val readyHome = home?.getOrNull()
                val readySettings = settingsServices?.getOrNull()
                val currentAiPolicy by rememberUpdatedState(aiCapabilities)
                val currentReviewPolicy by rememberUpdatedState(reviewCapabilities)
                val actionReadiness = remember(readyHome, readySettings) {
                    ReviewActionReadiness(
                        current = { readyHome != null && readySettings != null && readyHome.data.current && readySettings.current() && readyHome.observations.ready },
                        aiPolicy = { currentAiPolicy },
                        reviewPolicy = { currentReviewPolicy },
                    )
                }
                val uriHandler = if (readyHome == null) LocalUriHandler.current else remember(readyHome, openLink, toaster) {
                    rootUriHandler(readyHome, openLink) { toaster.show("Pully can't open that link") }
                }
                CompositionLocalProvider(
                    LocalSharedBackDispatcher provides backs,
                    LocalFootBars provides bars,
                    LocalToaster provides toaster,
                    LocalUriHandler provides uriHandler,
                    LocalReviewActionReadiness provides actionReadiness,
                    LocalNativeHomeActions provides ownedNative,
                ) {
                    if (readyHome != null && readySettings != null) UseCommands {
                        appCommands(
                            { messages.t(it) }, readyHome.navigation(navigation.nav), readySettings::current,
                            toggleTheme = {
                                scope.launch {
                                    ThemeWipe.run(fromRight = !dark, motion = moving, changes = colorTheme.colors(dark) != colorTheme.colors(!dark)) {
                                        if (readySettings.current()) settings.updateTheme(if (dark) "light" else "dark")
                                    }
                                }
                            },
                            openThemes = { themes = true }, feedback = { feedback = true }, update = null, whatsNew = null,
                            explore = onExplore,
                        )
                    }
                    val arrival = incoming.pending
                    LaunchedEffect(arrival, readyHome, readySettings, state.phase) {
                        if (arrival == null || readyHome == null || readySettings == null || state.phase != AccountPhase.Ready) return@LaunchedEffect
                        val audience = incomingAudience() ?: return@LaunchedEffect
                        if (!readyHome.data.current || !readySettings.current()) return@LaunchedEffect
                        incoming.openPending(audience, navigation, { readyHome.data.current && readySettings.current() }, {
                            Sheets.closeAll()
                            if (incoming.pending === arrival && readyHome.data.current && readySettings.current()) {
                                Commands.close(); themes = false; feedback = false; server = null; resetting = null
                            }
                        }, { toaster.show("Pully can't open that link") })
                    }
                    HomeBackEffect(ownedNative, motion, backs, navigation.stack.size > 1) { navigation.nav.back() }
                    LaunchedEffect(readyHome) { if (readyHome != null) { withFrameNanos { }; firstFrame() } }
                    val screen = navigation.stack.lastOrNull()
                    LaunchedEffect(state.phase, screen) {
                        showScreen(when (screen) {
                            is Screen.Repo -> "repo"
                            is Screen.Review -> "review"
                            Screen.Settings -> "settings"
                            Screen.UiBook -> "book"
                            else -> if (state.phase == AccountPhase.Ready) "home" else "account"
                        })
                    }
                    fun openServer() {
                        if (openingEpoch != account.sourceEpoch) return
                        serverServices().let { server = it }
                    }
                    BoxWithConstraints(Modifier.fillMaxSize().background(colors.base).themeWipe()) {
                        val wide = maxWidth >= 768.dp
                        if (PullsType.wide != wide) PullsType.wide = wide
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = PullsSpace.x4, vertical = PullsSpace.x2),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(t("mobilePreview.title"), Modifier.semantics { heading() }, style = PullsType.lg, color = colors.text)
                                    if (fixture) Text(t("mobilePreview.fixture"), style = PullsType.sm, color = colors.muted)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(PullsSpace.x2)) {
                                    if (onExplore != null && readySettings == null && signIn?.getOrNull() == null)
                                        PillButton(t("explore.browse"), onExplore)
                                    FloatIcon(Lu.cog, if (readySettings != null) "Settings" else "Server", {
                                        try {
                                            if (readySettings != null) {
                                                if (readySettings.current()) navigation.nav.settings()
                                            } else openServer()
                                        } catch (failure: Exception) { toaster.show(failure.message ?: "Pully could not open its saved credentials.") }
                                    }, flat = true)
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (state.phase) {
                                    AccountPhase.Checking -> RowsSkeleton()
                                    AccountPhase.SignIn -> {
                                        val services = signIn?.getOrNull()
                                        if (services != null) SignInPage(services, ::SignInArtwork, onServerSettings = ::openServer, onExplore = onExplore)
                                        else RootAccountProblem(signIn?.exceptionOrNull()?.message, account::refresh, account::retryStorage) { resetting = openingEpoch }
                                    }
                                    AccountPhase.Problem -> RootAccountProblem(state.problem, account::refresh, account::retryStorage) { resetting = openingEpoch }
                                    AccountPhase.Ready -> {
                                        val services = home?.getOrNull()
                                        if (services == null || readySettings == null) RootAccountProblem(home?.exceptionOrNull()?.message ?: settingsServices?.exceptionOrNull()?.message, account::refresh, account::retryStorage) { resetting = openingEpoch }
                                        else AppPages(navigation, motion, Modifier.fillMaxSize()) { route ->
                                            when (route) {
                                                Screen.Home -> HomeScreen(navigation.nav, root.homeUi, services)
                                                is Screen.Repo -> RepoScreen(route.owner, route.repo, navigation.nav, route.tours, services)
                                                is Screen.Review -> {
                                                    val frame = remember(services, readySettings) { root.review(services) { source, homeServices -> createReview(source, homeServices, readySettings) } }
                                                    CompositionLocalProvider(LocalHomeScreenServices provides services, LocalNativeHomeActions provides frame.overview.native) {
                                                        ReviewScreen(route.target, navigation.nav, frame, route.tour, route.from, route.place, route.aiResult)
                                                    }
                                                }
                                                else -> error("A route passed its uninstalled renderer boundary.")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        FootBarLayer(bars)
                        if (readyHome != null && readySettings != null) {
                            if (navigation.stack.lastOrNull() == Screen.Settings) SettingsScreen(navigation.nav, readySettings)
                            if (themes) ColorThemeSheet(settings, readySettings::current) { themes = false }
                            if (feedback) FeedbackSheet(readySettings.feedbackPage) { feedback = false }
                            CommandPalette(remember(readyHome) { root.commands(readyHome) })
                        }
                        server?.let { services -> PullsSheet({ server = null }, "Server") { ServerPage(services) } }
                        if (limitation != null) PullsSheet(clearLimitation, t("mobilePreview.limitationTitle"), expandable = false) {
                            Text(t("mobilePreview.limitationBody"), Modifier.padding(top = SheetTopGap), style = PullsType.row, color = colors.text)
                        }
                        resetting?.let { expectedEpoch -> PullsSheet({ resetting = null }, "Reset credentials?", expandable = false) {
                            Text("Forget this phone’s account session and Access credentials.", Modifier.padding(top = SheetTopGap), style = PullsType.read, color = colors.text)
                            CtaButton("Reset credentials", {
                                try { account.resetCredentials(expectedEpoch); resetting = null }
                                catch (_: kotlinx.coroutines.CancellationException) { resetting = null }
                                catch (failure: Exception) { toaster.show(failure.message ?: "Pully could not reset its credentials.") }
                            }, Modifier.fillMaxWidth().padding(top = PullsSpace.x4))
                        } }
                        ToastHost(toaster)
                    }
                }
            }
        }
    }
}

/** Explicit page links share incoming routing; external pages use the existing native browser adapter. */
private fun rootUriHandler(home: HomeScreenServices, openLink: (String) -> Unit, invalid: () -> Unit) = object : UriHandler {
    override fun openUri(uri: String) {
        if (!home.data.current) return
        if (HomeLinks.opensInPully(uri)) { openLink(uri); return }
        val authority = uri.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (!uri.startsWith("https://", true) && !uri.startsWith("http://", true) || authority.isBlank() || '@' in authority || uri.any { it.isWhitespace() || it.code < 32 }) {
            invalid()
            return
        }
        home.native.openInBrowser(uri)
    }
}

@Composable
private fun RootAccountProblem(problem: String?, retry: () -> Unit, retryStorage: () -> Unit, reset: () -> Unit) {
    val colors = LocalPullsColors.current
    val toaster = LocalToaster.current
    Column(Modifier.fillMaxWidth().padding(PullsSpace.x4), verticalArrangement = Arrangement.spacedBy(PullsSpace.x3)) {
        Text(problem ?: "Pully could not open the server.", style = PullsType.read, color = colors.error)
        CtaButton("Try again", retry, Modifier.fillMaxWidth())
        SheetRow("Unlock and retry", onClick = {
            try { retryStorage() } catch (failure: Exception) { toaster.show(failure.message ?: "Pully could not open its saved credentials.") }
        })
        SheetRow("Reset credentials", onClick = reset)
    }
}
