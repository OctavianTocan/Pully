package com.octaviantocan.pulls.ios

import androidx.compose.runtime.key
import androidx.compose.ui.window.ComposeUIViewController
import com.octaviantocan.pulls.data.DarwinRpcTransport
import com.octaviantocan.pulls.data.RpcTransport
import com.octaviantocan.pulls.preview.RootJourneyTransport
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.settings.SettingsEffects
import com.octaviantocan.pulls.settings.UserDefaultsSettings
import com.octaviantocan.pulls.ui.AppNavigation
import com.octaviantocan.pulls.ui.IncomingLinkInbox
import com.octaviantocan.pulls.ui.NativeEntryEvidence
import com.octaviantocan.pulls.ui.NativeWorkspaceOwner
import com.octaviantocan.pulls.ui.Screen
import com.octaviantocan.pulls.ui.explore.NativeEntryMode
import com.octaviantocan.pulls.ui.explore.NativeEntryState
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.home.NativeSharedLinkHandoff
import com.octaviantocan.pulls.ui.settings.AccountStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController

/** One scene, one Compose stack. Public startup does not construct private application services. */
class IosPullyApp private constructor(
    homeActions: NativeHomeActions,
    accountStorage: AccountStorage,
    defaults: NSUserDefaults,
    transport: RpcTransport,
    fixture: Boolean,
    fullJourney: Boolean,
    fixtureIdentifier: String?,
    private val shareHandoff: NativeSharedLinkHandoff?,
    personalSessions: IosPersonalSessionStore?,
) {
    constructor(homeActions: NativeHomeActions, accountStorage: AccountStorage) :
        this(homeActions, accountStorage, NSUserDefaults.standardUserDefaults, DarwinRpcTransport(), false, true, null, null, null)

    constructor(homeActions: NativeHomeActions, accountStorage: AccountStorage, shareHandoff: NativeSharedLinkHandoff) :
        this(homeActions, accountStorage, NSUserDefaults.standardUserDefaults, DarwinRpcTransport(), false, true, null, shareHandoff, null)

    constructor(homeActions: NativeHomeActions, accountStorage: AccountStorage, personalSessions: IosPersonalSessionStore) :
        this(homeActions, accountStorage, NSUserDefaults.standardUserDefaults, DarwinRpcTransport(), false, true, null, null, personalSessions)

    internal constructor(homeActions: NativeHomeActions, accountStorage: AccountStorage, defaults: NSUserDefaults, transport: RootJourneyTransport) :
        this(homeActions, accountStorage, defaults, transport, true, transport.fullJourney, transport.fixtureIdentifier, null, null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val incoming = IncomingLinkInbox()
    private var privateWorkspace: IosPrivateWorkspace? = null
    private var closed = false
    private val evidence = NativeEntryEvidence(UserDefaultsSettings(defaults, "pully-entry"), accountStorage)
    private val entry = if (fixture) NativeEntryState(true) else evidence.entry()
    private val navigation = AppNavigation(rootScreen = if (entry.mode == NativeEntryMode.Explore) Screen.Explore else Screen.Home)
    private val settings = AppSettings(UserDefaultsSettings(defaults), object : SettingsEffects {
        override fun touched(name: String) { privateWorkspace?.touched(name) }
        override fun settingsChanged(key: String, value: String) { privateWorkspace?.settingChanged(key, value) }
        override fun experimentChanged(feature: String, enabled: Boolean) { privateWorkspace?.experimentChanged(feature, enabled) }
        override fun usageStatsEnabled(enabled: Boolean) { privateWorkspace?.usageChanged(enabled) }
    })
    private var initialWorkspace = true
    private val workspaces = NativeWorkspaceOwner(
        entry, navigation,
        createGuest = { source, stack, current ->
            shareHandoff?.revoke()
            privateWorkspace = null
            initialWorkspace = false
            IosGuestWorkspace(homeActions, settings, transport, scope, source, stack, incoming, current, personalSessions)
        },
        createExisting = { stack, current ->
            IosPrivateWorkspace(
                homeActions, accountStorage, defaults, transport, fixture, fullJourney, fixtureIdentifier,
                settings, stack, incoming, current,
                verified = { runtime -> if (!fixture && current()) evidence.verified(runtime) },
                coldStart = initialWorkspace,
                shareHandoff = shareHandoff,
            ).also { privateWorkspace = it; initialWorkspace = false }
        },
        selected = { if (!fixture) evidence.selected(it) },
    )
    private val personalIdentitySubscription = personalSessions?.observeIdentityChanged {
        if (!closed) workspaces.renewExplore()
    }

    val viewController: UIViewController = try {
        ComposeUIViewController {
            key(workspaces.generation) {
                when (val workspace = workspaces.workspace) {
                    is IosGuestWorkspace -> workspace.Content {
                        if (workspace.current) {
                            workspace.prepareExistingEntry()
                            workspaces.select(NativeEntryMode.ExistingServer)
                        }
                    }
                    is IosPrivateWorkspace -> workspace.Content(onExplore = if (fixture) null else {
                        {
                            if (workspaces.workspace === workspace) {
                                shareHandoff?.revoke()
                                workspaces.select(NativeEntryMode.Explore)
                            }
                        }
                    })
                }
            }
        }
    } catch (failure: Throwable) {
        personalIdentitySubscription?.invoke()
        workspaces.close()
        scope.cancel()
        throw failure
    }

    fun receiveLink(text: String) {
        if (!closed) incoming.receive(text, privateWorkspace?.incomingAudience())
    }

    fun rejectIncomingLink() {
        if (!closed) incoming.reject(privateWorkspace?.incomingAudience())
    }

    /** Scene disconnect, after native callbacks have detached. Ordinary backgrounding keeps this owner. */
    fun close() {
        if (closed) return
        closed = true
        personalIdentitySubscription?.invoke()
        try { workspaces.close() }
        finally { privateWorkspace = null; incoming.clear(); scope.cancel() }
    }
}
