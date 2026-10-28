package com.octaviantocan.pulls.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.octaviantocan.pulls.data.IosUsageAnalytics
import com.octaviantocan.pulls.data.PersonalAccountRuntime
import com.octaviantocan.pulls.data.PersonalAuthError
import com.octaviantocan.pulls.data.PersonalAuthProblem
import com.octaviantocan.pulls.data.PersonalWorkspaceOwner
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.settings.AccountSettingsSync
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.UserDefaultsSettings
import com.octaviantocan.pulls.settings.UserDefaultsSettingsSync
import com.octaviantocan.pulls.ui.*
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.home.HomePreviewFeature
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.review.IosPageCopies
import com.octaviantocan.pulls.ui.review.IosTourCacheOwner
import com.octaviantocan.pulls.ui.review.IosTourCopies
import com.octaviantocan.pulls.ui.review.ReviewCapabilities
import com.octaviantocan.pulls.ui.review.iosPageDirectory
import com.octaviantocan.pulls.ui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import kotlin.time.Clock

/**
 * The whole private app for one verified GitHub workspace, as Android's GuestPullsApp mounts it after one GitHub login
 * (`github-one-login-full-workspace`, `ios-github-login-opens-the-whole-app`): Home, Repo, Review, Tours, Ask, Settings
 * and AI, every request through [owner]'s own repository. It never reads or writes the server sign-in's storage, and
 * the GitHub bearer stays inside [owner]. Usage events are not recorded for it yet.
 */
internal class IosPersonalWorkspace(
    private val owner: PersonalWorkspaceOwner,
    private val personal: PersonalAccountRuntime,
    private val homeActions: NativeHomeActions,
    private val settings: AppSettings,
    private val defaults: NSUserDefaults,
    navigation: AppNavigation,
    private val incoming: IncomingLinkInbox,
    private val isCurrent: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false
    val current: Boolean get() = !closed && isCurrent() && owner.current
    /** The workspace account's own storage, keyed by its immutable id, never its mutable login (as Android). */
    private val namespace = requireNotNull(sourceStorageNamespace(owner.server, owner.accountId, true)) { "The GitHub workspace has no server." }
    private val analysis = UserDefaultsSettings(defaults, "pully-workspace-analysis.$namespace")
    private val repo = owner.repository(
        engine = { analysis.getString("engine", null) ?: "claude-code" },
        model = { analysis.getString("model", null).orEmpty() },
        effort = { settings.effort },
        admitted = { current },
    )
    private val account = WorkspaceRootAccount(repo, owner.login)
    private val analytics = IosUsageAnalytics(
        scope, { null }, { null }, { false },
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown",
        coldStart = false,
    )
    private val tours = IosTourCacheOwner()
    private val root = PullyRootState(scope, { settings.inboxFilter }, navigation, current = { current }, pages = {
        IosPageCopies(iosPageDirectory(namespace)) { current }
    })
    private val sync = AccountSettingsSync(
        settings, repo, UserDefaultsSettingsSync(defaults), owner = { namespace.takeIf { current } },
        scope = scope, nowMillis = { Clock.System.now().toEpochMilliseconds() },
    )
    private val observations = AccountObservationOwner(
        scope,
        verify = { current && owner.foregroundWorkspace() != null },
        pause = root::pauseObservations,
        resume = root::resumeObservations,
    )
    private var displayedScreen = "start"
    private var limitation by mutableStateOf<HomePreviewFeature?>(null)

    init {
        // Settings follow the account from its other phones, as Android's workspace mount syncs them.
        scope.launch { runCatching { sync.sync() } }
    }

    /** The guest scene's foreground and GitHub admission: observations run only while both hold. */
    fun foreground(active: Boolean) {
        if (closed) return
        if (active && current) observations.resumed() else observations.stopped()
    }

    fun touched(name: String) { if (current) sync.touched(name) }

    @Composable
    fun Content(onExplore: () -> Unit) {
        PullyRootScreen(
            root, account, settings, homeActions,
            createHome = { source ->
                iosHomeServices(
                    source, repo, namespace, settings, homeActions, analytics, { owner.login.takeIf { current } }, root.reviewSessions,
                    tours, fullJourney = true, fixture = false, aiPolicy = { AiCapabilities.Full }, defaults = defaults,
                ) { limitation = it }
            },
            createSettings = { source, home ->
                iosSettingsServices(
                    source, home, namespace,
                    readModel = { ModelConfiguration(analysis.getString("engine", null) ?: "claude-code", analysis.getString("model", null).orEmpty()) },
                    writeModel = { if (current) analysis.edit().putString("engine", it.engine).putString("model", it.model).apply() },
                    signOut = ::signOut,
                    defaults, { owner.login.takeIf { current } }, ::serverServices, { displayedScreen }, ::feedbackPlace, { displayedScreen = it },
                    agentKeysUnavailableReason = "Agent keys are unavailable for GitHub workspaces. This connection does not support external agents posting diffs.",
                )
            },
            createReview = { source, home, services ->
                iosReviewFrameServices(
                    source, home, root.reviewSessions, analytics, services.models,
                    requireNotNull(home.tourCopies as? IosTourCopies) { "Home and review must share the native tour cache." },
                    incoming, { AiCapabilities.Full }, { ReviewCapabilities.Full },
                    { source.current && home.data.current && source.observations.ready },
                )
            },
            incoming = incoming, incomingAudience = ::incomingAudience, openLink = { if (current) incoming.receive(it, incomingAudience()) },
            serverServices = ::serverServices,
            limitation = limitation, clearLimitation = { limitation = null },
            firstFrame = {}, showScreen = { displayedScreen = it },
            aiCapabilities = AiCapabilities.Full, reviewCapabilities = ReviewCapabilities.Full, onExplore = onExplore,
        )
    }

    /** Links from outside open here only for this workspace's own server and account. */
    fun incomingAudience(): IncomingLinkAudience? =
        if (current) incomingLinkAudience(RepoConfiguration(owner.server), owner.accountId, true) else null

    private suspend fun signOut() {
        if (!current) throw CancellationException("The GitHub workspace changed.")
        personal.signOut()
        if (personal.view.value.account != null) throw PersonalAuthError(PersonalAuthProblem.Unavailable)
    }

    /** The workspace's server is Pully's own; Settings shows it but cannot change it. */
    private fun serverServices(): ServerPageServices = object : ServerPageServices {
        override val configuration = ServerPageConfiguration(owner.server.removeSuffix("/workspace"), "", "")
        override fun save(configuration: ServerPageConfiguration): ServerConnectionCheck =
            throw IllegalStateException("This GitHub login uses Pully's own server. Sign out to use another server.")
    }

    private fun feedbackPlace(): String? = when (val page = root.navigation.stack.lastOrNull { it != Screen.Settings }) {
        is Screen.Review -> page.target.serverTarget
        is Screen.Repo -> "${page.owner}/${page.repo}"
        else -> null
    }

    fun close() {
        if (closed) return
        closed = true
        observations.close()
        tours.close()
        sync.cancelPending()
        root.close()
        analytics.close()
        scope.cancel()
    }
}
