package com.octaviantocan.pulls.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.octaviantocan.pulls.data.IosUsageAnalytics
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.settings.*
import com.octaviantocan.pulls.ui.*
import com.octaviantocan.pulls.ui.home.*
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.review.IosPageCopies
import com.octaviantocan.pulls.ui.review.IosTourCacheOwner
import com.octaviantocan.pulls.ui.review.iosPageDirectory
import com.octaviantocan.pulls.ui.review.IosTourCopies
import com.octaviantocan.pulls.ui.review.ReviewCapabilities
import com.octaviantocan.pulls.ui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSHomeDirectory
import com.octaviantocan.pulls.data.RpcTransport
import kotlin.time.Clock

/** The existing private application graph, created only for the explicit/verified existing-server entry. */
internal class IosPrivateWorkspace(
    private val homeActions: NativeHomeActions,
    private val accountStorage: AccountStorage,
    private val defaults: NSUserDefaults,
    transport: RpcTransport,
    private val fixture: Boolean,
    private val fullJourney: Boolean,
    private val fixtureIdentifier: String?,
    private val settings: AppSettings,
    navigation: AppNavigation,
    private val incoming: IncomingLinkInbox,
    private val current: () -> Boolean,
    private val verified: (AccountRuntime) -> Unit,
    coldStart: Boolean,
    private val shareHandoff: NativeSharedLinkHandoff? = null,
) : NativeWorkspace {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val root: PullyRootState
    private val account: AccountRuntime
    private val analytics: IosUsageAnalytics
    private val sync: AccountSettingsSync
    private val lifecycle: HomeLifecycleSubscription
    private val observations: AccountObservationOwner
    private val tours: IosTourCacheOwner
    private val aiCapabilities = if (fullJourney) AiCapabilities.Full else AiCapabilities.None
    private val reviewCapabilities = if (fullJourney) ReviewCapabilities.Full else ReviewCapabilities.ReadOnly
    private var displayedScreen = "start"
    private var limitation by mutableStateOf<HomePreviewFeature?>(null)
    private var closed = false
    private var shareReady = false
    private var shareGrant = 0L
    private val sharedImports = shareHandoff?.let { SharedLinkImport(it, ::shareSource, ::receiveSharedLink) }

    init {
        var createdAccount: AccountRuntime? = null
        var createdRoot: PullyRootState? = null
        var createdAnalytics: IosUsageAnalytics? = null
        var createdLifecycle: HomeLifecycleSubscription? = null
        var createdObservations: AccountObservationOwner? = null
        var createdTours: IosTourCacheOwner? = null
        try {
            // These references are assigned before start/observeLifecycle can deliver a synchronous callback.
            var runtime: AccountRuntime? = null
            var synchronizer: AccountSettingsSync? = null
            var observationOwner: AccountObservationOwner? = null
            // Analytics samples this on native/RPC workers; only the immutable secure-storage snapshot may be read.
            fun configuration(): RepoConfiguration? = if (fixture) null else accountStorage.connection(false)?.let {
                RepoConfiguration(it.server, it.headers, it.engine, it.model, generation = it.generation)
            }
            fun owner(): String? = eligibleAccountOwner(runtime?.serverHasAccount, accountStorage.account(), accountStorage.connection(false))
            analytics = IosUsageAnalytics(
                scope, ::configuration, ::owner, { !fixture && settings.usageStats },
                NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown",
                coldStart = coldStart,
            )
            createdAnalytics = analytics
            tours = if (fixture) {
                val identifier = requireNotNull(fixtureIdentifier) { "The fixture tour cache requires its isolated UUID." }
                require(identifier.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                IosTourCacheOwner(NSHomeDirectory() + "/Library/Application Support/PullyUITestTours/" + identifier)
            } else IosTourCacheOwner()
            createdTours = tours
            root = PullyRootState(scope, { settings.inboxFilter }, navigation, current = current, pages = {
                // The account's own folder; pages of another account or server stay in theirs and are never read here.
                val epoch = runtime?.sourceEpoch
                val connection = if (fixture) null else accountStorage.connection(false)
                val email = owner()
                val namespace = connection?.let { sourceStorageNamespace(it.server, email, email != null, it.headers) }
                if (epoch == null || namespace == null) null
                else IosPageCopies(iosPageDirectory(namespace)) { !closed && current() && runtime?.sourceEpoch == epoch }
            })
            createdRoot = root
            account = AccountRuntime(
                accountStorage, transport, scope, { settings.effort },
                invalidated = {
                    pauseSharedImports()
                    analytics.setPrivateConnection(false)
                    root.invalidate()
                    if (!closed) tours.invalidateSource()
                    incoming.retireSource()
                    synchronizer?.cancelPending()
                    analytics.invalidateSource()
                    limitation = null
                    observationOwner?.sourceChanged()
                },
                synchronizeSettings = { requireNotNull(synchronizer).sync() },
                rpcObserver = analytics.rpcObserver,
            )
            createdAccount = account
            runtime = account
            observations = AccountObservationOwner(
                scope,
                verify = { refresh -> if (refresh) account.refreshAndWait() else account.awaitCurrentVerification() },
                pause = { pauseSharedImports(); root.pauseObservations(); analytics.setPrivateConnection(false) },
                resume = {
                    root.resumeObservations()
                    analytics.setPrivateConnection(true)
                    shareGrant++
                    shareReady = true
                    sharedImports?.sync()
                },
            )
            createdObservations = observations
            observationOwner = observations
            sync = AccountSettingsSync(
                settings, account.repo, UserDefaultsSettingsSync(defaults),
                owner = {
                    val email = owner()
                    val config = accountStorage.connection(false)?.let {
                        RepoConfiguration(it.server, it.headers, it.engine, it.model, generation = it.generation)
                    }
                    if (email == null || config == null) null
                    else sourceStorageNamespace(config.server, email, true, config.headers)
                },
                scope = scope, nowMillis = { Clock.System.now().toEpochMilliseconds() },
            )
            synchronizer = sync
            var resumed = false
            lifecycle = homeActions.observeLifecycle(object : HomeLifecycleObserver {
                override fun started() { if (!closed) analytics.foreground() }
                override fun resumed() {
                    if (closed) return
                    resumed = true
                    analytics.foreground()
                    observations.resumed()
                }
                override fun stopped() {
                    if (!closed) {
                        analytics.background()
                        observations.stopped()
                    }
                }
            })
            createdLifecycle = lifecycle
            if (shareHandoff != null) scope.launch {
                snapshotFlow { account.state to account.sourceEpoch }.collect { (settled, epoch) ->
                    if (closed || !current() || account.state !== settled || account.sourceEpoch != epoch || settled.problem != null) return@collect
                    if (settled.phase == AccountPhase.SignIn || settled.phase == AccountPhase.Ready && owner() == null) shareHandoff.revoke()
                }
            }
            // Debug fixture only: each change to the page stack goes to the system log, so a UI test whose Back lands
            // on the wrong page shows which pages were there.
            if (fixture) scope.launch {
                snapshotFlow { root.navigation.stack.joinToString(" > ") { it::class.simpleName ?: "Page" } }.collect { pages ->
                    NSLog("Pully route stack: " + pages.replace("%", "%%"))
                }
            }
            if (!resumed) account.start()
        } catch (failure: Throwable) {
            // A native controller/service construction failure cannot strand observers or app work.
            closed = true
            sharedImports?.close()
            runCatching { createdLifecycle?.cancel() }
            runCatching { createdObservations?.close() }
            runCatching { createdTours?.close() }
            runCatching { createdAccount?.close() }
            runCatching { createdRoot?.close() }
            runCatching { createdAnalytics?.close() }
            scope.cancel()
            throw failure
        }
    }

    @androidx.compose.runtime.Composable
    fun Content(onExplore: (() -> Unit)?, onGitHubAccount: (() -> Unit)? = null) {
        val settled = account.state
        LaunchedEffect(settled, account.sourceEpoch) {
            if (current() && settled === account.state && settled.phase == AccountPhase.Ready && settled.problem == null) {
                verified(account)
                observations.settled()
            }
        }
        PullyRootScreen(
            root, account, settings, homeActions,
            createHome = { source -> iosHomeServices(source, account, settings, homeActions, analytics, ::owner, root.reviewSessions, tours, fullJourney, fixture, { aiCapabilities }, defaults) { limitation = it } },
            createSettings = { source, home -> iosSettingsServices(source, home, account, defaults, ::owner, ::serverServices, { displayedScreen }, ::feedbackPlace, ::showScreen) },
            createReview = { source, home, services ->
                iosReviewFrameServices(
                    source, home, root.reviewSessions, analytics, services.models,
                    requireNotNull(home.tourCopies as? IosTourCopies) { "Home and review must share the native tour cache." },
                    incoming, { aiCapabilities }, { reviewCapabilities },
                    { source.current && home.data.current && source.observations.ready },
                )
            },
            incoming = incoming, incomingAudience = ::incomingAudience, openLink = ::receiveLink,
            serverServices = ::serverServices,
            limitation = limitation, clearLimitation = { limitation = null },
            firstFrame = analytics::firstFrame, showScreen = ::showScreen, fixture = fixture,
            aiCapabilities = aiCapabilities, reviewCapabilities = reviewCapabilities, onExplore = onExplore, onGitHubAccount = onGitHubAccount,
        )
    }

    private fun owner(): String? = eligibleAccountOwner(account.serverHasAccount, accountStorage.account(), accountStorage.connection(false))
    fun touched(name: String) { if (!closed && current()) sync.touched(name) }
    fun settingChanged(key: String, value: String) { if (!closed && current()) analytics.track("settings.changed", "key" to key, "value" to value) }
    fun experimentChanged(feature: String, enabled: Boolean) { if (!closed && current()) analytics.track("experiment.toggle", "experiment" to feature, "enabled" to enabled) }
    fun usageChanged(enabled: Boolean) {
        if (fixture || closed || !current()) return
        analytics.setEnabled(enabled)
        if (enabled) analytics.track("settings.changed", "key" to "usageStats", "value" to "on")
    }
    override fun beforeLeave() { analytics.setPrivateConnection(false) }

    private fun pauseSharedImports() {
        shareReady = false
        shareGrant++
    }

    private fun shareSource(): ShareImportSource? {
        if (closed || !current() || fixture || !shareReady || account.state.phase != AccountPhase.Ready || account.state.problem != null) return null
        val connection = accountStorage.connection(false) ?: return null
        val owner = owner() ?: return null
        val namespace = sourceStorageNamespace(connection.server, owner, true, connection.headers) ?: return null
        return ShareImportSource(namespace, connection.server, account.sourceEpoch, connection.generation, shareGrant)
    }

    private fun receiveSharedLink(payload: String) {
        if (shareSource() == null) return
        incoming.receiveShared(payload, incomingAudience())
    }

    private fun receiveLink(text: String) {
        if (!closed && current()) incoming.receive(text, incomingAudience())
    }

    private fun showScreen(name: String) { displayedScreen = name; analytics.showScreen(name) }

    private fun feedbackPlace(): String? = when (val page = root.navigation.stack.lastOrNull { it != Screen.Settings }) {
        is Screen.Review -> page.target.serverTarget
        is Screen.Repo -> "${page.owner}/${page.repo}"
        else -> null
    }

    fun incomingAudience(): IncomingLinkAudience? {
        if (closed || !current()) return null
        val saved = accountStorage.connection(false) ?: return null
        val owner = eligibleAccountOwner(account.serverHasAccount, accountStorage.account(), saved)
        return incomingLinkAudience(
            RepoConfiguration(saved.server, saved.headers, saved.engine, saved.model, generation = saved.generation),
            owner, owner != null,
        )
    }

    private fun serverServices(): ServerPageServices {
        val connection = account.connection() ?: throw IllegalStateException("Unlock the device and try again, or reset credentials.")
        val openingEpoch = account.sourceEpoch
        fun header(name: String) = connection.headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
        return object : ServerPageServices {
            override val configuration = ServerPageConfiguration(connection.server, header("CF-Access-Client-Id"), header("CF-Access-Client-Secret"))
            override fun save(configuration: ServerPageConfiguration): ServerConnectionCheck {
                if (closed || openingEpoch != account.sourceEpoch || account.connection()?.generation != connection.generation)
                    throw CancellationException("The server changed.")
                account.saveServer(configuration.url, configuration.accessId, configuration.accessSecret)
                return serverConnectionCheck(account.repo)
            }
        }
    }

    /** Called on scene disconnect before releasing native services; safe to call more than once. */
    override fun close() {
        if (closed) return
        closed = true
        sharedImports?.close()
        lifecycle.cancel()
        observations.close()
        tours.close()
        account.close()
        sync.cancelPending()
        root.close()
        incoming.retireSource()
        analytics.close()
        scope.cancel()
    }
}
