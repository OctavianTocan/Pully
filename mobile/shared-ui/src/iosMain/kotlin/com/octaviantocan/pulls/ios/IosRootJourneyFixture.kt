package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.preview.PREVIEW_SERVER
import com.octaviantocan.pulls.preview.RootJourneyTransport
import com.octaviantocan.pulls.preview.RootJourneyStateStore
import com.octaviantocan.pulls.preview.RootJourneyScenario
import com.octaviantocan.pulls.ui.home.NativeHomeActions
import com.octaviantocan.pulls.ui.settings.AccountStorage
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSProcessInfo
import kotlin.native.Platform
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/** Explicit Debug test entry only. It cannot use standard preferences, real accounts or a network transport. */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun rootJourneyFixtureApplication(homeActions: NativeHomeActions, accountStorage: AccountStorage, defaults: NSUserDefaults): IosPullyApp {
    check(Platform.isDebugBinary) { "The root journey fixture requires a Debug framework." }
    // Kotlin writes an uncaught exception only to stderr, which the UI test run does not keep; the system log does.
    // The text goes in the format itself (a Kotlin String passed as a %@ argument crashed NSLog), with % escaped, in
    // pieces short enough that the log keeps each whole.
    setUnhandledExceptionHook { error ->
        error.stackTraceToString().take(4000).chunked(700).forEachIndexed { index, part ->
            NSLog("Uncaught Kotlin exception $index: " + part.replace("%", "%%"))
        }
        // Keep fixture failures fatal after recording their diagnostics for CI.
        terminateWithUnhandledException(error)
    }
    require(defaults != NSUserDefaults.standardUserDefaults) { "The fixture requires its isolated preferences suite." }
    require(accountStorage.connection(false)?.server == PREVIEW_SERVER) { "The fixture requires its sample server." }
    val headers = requireNotNull(accountStorage.connection(true)).headers
    require(headers.isEmpty() || headers == mapOf("X-Pully-Session" to RootJourneyTransport.SESSION)) { "The fixture cannot adopt ordinary credentials." }
    val stored = accountStorage.account()
    require(stored.email == null || stored.email == RootJourneyTransport.EMAIL) { "The fixture cannot adopt an ordinary account." }
    val mode = NSProcessInfo.processInfo.environment["PULLY_UI_TEST_ROOT_JOURNEY_MODE"] as? String
    require(mode == null || mode.isEmpty() || mode == "full") { "The root journey fixture mode is invalid." }
    val rawScenario = NSProcessInfo.processInfo.environment["PULLY_UI_TEST_ROOT_JOURNEY_SCENARIO"] as? String
    require(mode == "full" || rawScenario.isNullOrEmpty()) { "Sample failure scenarios require the explicit full Debug journey." }
    val scenario = RootJourneyScenario.fromLaunch(rawScenario)
    val identifier = requireNotNull(NSProcessInfo.processInfo.environment["PULLY_UI_TEST_ROOT_JOURNEY"] as? String) {
        "The root journey fixture requires its isolated UUID."
    }
    val transport = if (mode == "full") RootJourneyTransport.fullJourney(object : RootJourneyStateStore {
        private val key = "pully-root-fixture.full-state-v1"
        override fun read(): String? = defaults.stringForKey(key)
        override fun write(state: String) {
            require(state.length <= 32_000) { "The sample journey state is invalid." }
            defaults.setObject(state, forKey = key)
            check(defaults.synchronize()) { "The sample journey state could not be saved." }
        }
    }, fixtureIdentifier = identifier, scenario = scenario) else RootJourneyTransport.readOnly(identifier)
    return IosPullyApp(homeActions, accountStorage, defaults, transport)
}
