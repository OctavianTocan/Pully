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
import com.octaviantocan.pulls.ui.settings.ColorThemeSheet
import com.octaviantocan.pulls.ui.settings.wantsDark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
) : NativeWorkspace {
    private var closed = false
    private val personalForeground = native as? NativePersonalForegroundActions
    private val session = GuestBrowseSession(transport, scope, source, navigation)
    val current get() = !closed && isCurrent() && session.current
    private val native = SourceHomeActions(native) { current }
    private val personal = personalSessions?.takeIf { it.configured && personalForeground != null }?.open(scope) { current }
    internal val personalBrowse = personal?.let { PersonalBrowseMount(it, scope, source, navigation) }
    private var foreground by mutableStateOf(false)
    private var personalAvailable by mutableStateOf(false)
    private var personalActivityEpoch = 0L
    private val commands = CommandPaletteOwner { current }
    private val links = NativeGuestLinkHandoff(incoming, navigation) { current }
    private val personalActivity = try {
        if (personal == null) null else personalForeground?.observePersonalForeground(object : NativePersonalForegroundObserver {
            override fun changed(active: Boolean) {
                val opening = ++personalActivityEpoch
                if (!current) return
                personalBrowse?.foreground(active, available = false)
                if (!current || personalActivityEpoch != opening) return
                foreground = active
                personalAvailable = false
                if (!active || !current || personalActivityEpoch != opening || !foreground) return
                val available = try { requireNotNull(personalSessions).verifyStorage(); true } catch (_: Exception) { false }
                if (!current || personalActivityEpoch != opening || !foreground) return
                personalAvailable = available
                personalBrowse?.availability(available)
                if (available) personal.restore()
            }
        })
    } catch (failure: Throwable) {
        personalBrowse?.close()
        personal?.close()
        commands.close()
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
        session.close()
        throw failure
    }

    @Composable
    fun Content(onExistingAccount: () -> Unit) {
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
            val personalView = personal?.view?.collectAsState()?.value
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
                        onGitHubSignIn = if (personalAvailable && personal != null) ({ if (current && foreground && personalAvailable) personalBrowse?.openAccount() }) else null,
                        onCommands = { if (current) Commands.open() },
                        githubAccount = personalView?.account?.login,
                        personal = personalBrowse,
                    )
                    personalBrowse?.let { mount -> PersonalBrowseAccountSheet(mount,
                        onOpenBrowser = { url -> if (current && foreground && personalAvailable) native.openOnGithub(url) }) }
                    if (themes) ColorThemeSheet(settings, { current }) { themes = false }
                    CommandPalette(commands)
                    ToastHost(toaster)
                }
            }
        }
    }

    /** A displayed fallback can wait through the real private sign-in/source verification. */
    fun prepareExistingEntry() = links.prepareExistingEntry()

    override fun close() {
        if (closed) return
        closed = true
        personalBrowse?.close()
        personalActivityEpoch++
        foreground = false
        personalAvailable = false
        links.clear()
        personalActivity?.cancel()
        personal?.close()
        lifecycle.cancel()
        commands.close()
        session.close()
    }
}
