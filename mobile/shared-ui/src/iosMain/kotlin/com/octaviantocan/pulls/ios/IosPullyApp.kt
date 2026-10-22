package com.octaviantocan.pulls.ios

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import com.octaviantocan.pulls.data.DarwinRpcTransport
import com.octaviantocan.pulls.data.IosUsageAnalytics
import com.octaviantocan.pulls.data.RepoConfiguration
import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.settings.*
import com.octaviantocan.pulls.ui.*
import com.octaviantocan.pulls.ui.home.*
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.review.IosTourCacheOwner
import com.octaviantocan.pulls.ui.review.IosTourCopies
import com.octaviantocan.pulls.ui.review.ReviewCapabilities
import com.octaviantocan.pulls.preview.RootJourneyTransport
import com.octaviantocan.pulls.ui.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSHomeDirectory
import com.octaviantocan.pulls.data.RpcTransport
import platform.UIKit.UIViewController
import kotlin.time.Clock

/** One scene retains this application. Route changes, Safari and suspension do not close its lifetime. */
class IosPullyApp private constructor(
    homeActions: NativeHomeActions,
    private val accountStorage: AccountStorage,
    private val defaults: NSUserDefaults,
    transport: RpcTransport,
    private val fixture: Boolean,
    private val fullJourney: Boolean,
    private val fixtureIdentifier: String?,
) {
    constructor(homeActions: NativeHomeActions, accountStorage: AccountStorage) :
        this(homeActions, accountStorage, NSUserDefaults.standardUserDefaults, DarwinRpcTransport(), false, true, null)

    internal constructor(homeActions: NativeHomeActions, accountStorage: AccountStorage, defaults: NSUserDefaults, transport: RootJourneyTransport) :
        this(homeActions, accountStorage, defaults, transport, true, transport.fullJourney, transport.fixtureIdentifier)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings: AppSettings
    private val root: PullyRootState
    private val account: AccountRuntime
    private val analytics: IosUsageAnalytics
    private val sync: AccountSettingsSync
    private val lifecycle: HomeLifecycleSubscription
    private val observations: AccountObservationOwner
    private val tours: IosTourCacheOwner
    private val aiCapabilities = if (fullJourney) AiCapabilities.Full else AiCapabilities.None
    private val reviewCapabilities = if (fullJourney) ReviewCapabilities.Full else ReviewCapabilities.ReadOnly
    private val incoming = IncomingLinkInbox()
    private var displayedScreen = "start"
    private var limitation by mutableStateOf<HomePreviewFeature?>(null)
    private var closed = false
    val viewController: UIViewController

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
            lateinit var preferences: AppSettings
            // Analytics samples this on native/RPC workers; only the immutable secure-storage snapshot may be read.
            fun configuration(): RepoConfiguration? = if (fixture) null else accountStorage.connection(false)?.let {
                RepoConfiguration(it.server, it.headers, it.engine, it.model, generation = it.generation)
            }
            fun owner(): String? = eligibleAccountOwner(runtime?.serverHasAccount, accountStorage.account(), accountStorage.connection(false))
            analytics = IosUsageAnalytics(
                scope, ::configuration, ::owner, { !fixture && preferences.usageStats },
                NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "unknown",
            )
            createdAnalytics = analytics
            preferences = AppSettings(UserDefaultsSettings(defaults), object : SettingsEffects {
                override fun touched(name: String) { synchronizer?.touched(name) }
                override fun settingsChanged(key: String, value: String) = analytics.track("settings.changed", "key" to key, "value" to value)
                override fun experimentChanged(feature: String, enabled: Boolean) =
                    analytics.track("experiment.toggle", "experiment" to feature, "enabled" to enabled)
                override fun usageStatsEnabled(enabled: Boolean) {
                    if (fixture) return // A fixture preference must never clear an ordinary application's usage files.
                    analytics.setEnabled(enabled)
                    if (enabled) analytics.track("settings.changed", "key" to "usageStats", "value" to "on")
                }
            })
            settings = preferences
            tours = if (fixture) {
                val identifier = requireNotNull(fixtureIdentifier) { "The fixture tour cache requires its isolated UUID." }
                require(identifier.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                IosTourCacheOwner(NSHomeDirectory() + "/Library/Application Support/PullyUITestTours/" + identifier)
            } else IosTourCacheOwner()
            createdTours = tours
            root = PullyRootState(scope) { settings.inboxFilter }
            createdRoot = root
            account = AccountRuntime(
                accountStorage, transport, scope, { settings.effort },
                invalidated = {
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
                pause = root::pauseObservations,
                resume = root::resumeObservations,
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
            viewController = ComposeUIViewController {
                val settled = account.state
                LaunchedEffect(settled, account.sourceEpoch) {
                    if (settled.phase == AccountPhase.Ready && settled.problem == null) observations.settled()
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
                    aiCapabilities = aiCapabilities, reviewCapabilities = reviewCapabilities,
                )
            }
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
                        observations.stopped()
                        analytics.background()
                    }
                }
            })
            createdLifecycle = lifecycle
            if (!resumed) account.start()
        } catch (failure: Throwable) {
            // A native controller/service construction failure cannot strand observers or app work.
            closed = true
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

    private fun showScreen(name: String) { displayedScreen = name; analytics.showScreen(name) }

    private fun feedbackPlace(): String? = when (val page = root.navigation.stack.lastOrNull { it != Screen.Settings }) {
        is Screen.Review -> page.target.serverTarget
        is Screen.Repo -> "${page.owner}/${page.repo}"
        else -> null
    }

    private fun incomingAudience(): IncomingLinkAudience? {
        val saved = accountStorage.connection(false) ?: return null
        val owner = eligibleAccountOwner(account.serverHasAccount, accountStorage.account(), saved)
        return incomingLinkAudience(
            RepoConfiguration(saved.server, saved.headers, saved.engine, saved.model, generation = saved.generation),
            owner, owner != null,
        )
    }

    /** Scene delivery is input only; the shared inbox owns pending sign-in and the one Compose route stack. */
    fun receiveLink(text: String) { if (!closed) incoming.receive(text, incomingAudience()) }

    /** The native envelope has already discarded its unsafe URL. */
    fun rejectIncomingLink() { if (!closed) incoming.reject(incomingAudience()) }

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
    fun close() {
        if (closed) return
        closed = true
        lifecycle.cancel()
        observations.close()
        tours.close()
        account.close()
        sync.cancelPending()
        root.close()
        incoming.clear()
        analytics.close()
        scope.cancel()
    }
}
