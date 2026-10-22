package com.octaviantocan.pulls.ios

import com.octaviantocan.pulls.data.sourceStorageNamespace
import com.octaviantocan.pulls.settings.UserDefaultsSettings
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.IosCueEngine
import com.octaviantocan.pulls.ui.RootSource
import com.octaviantocan.pulls.ui.home.HomeScreenServices
import com.octaviantocan.pulls.ui.settings.*
import platform.Foundation.NSUserDefaults

/** Native storage/output edges for the actual shared Settings, account, model, Stats and Feedback pages. */
internal fun iosSettingsServices(
    source: RootSource,
    home: HomeScreenServices,
    runtime: AccountRuntime,
    defaults: NSUserDefaults,
    accountOwner: () -> String?,
    server: () -> ServerPageServices,
    screen: () -> String,
    place: () -> String?,
    showScreen: (String) -> Unit,
): SettingsServices {
    val connection = requireNotNull(runtime.connection()) { "Pully could not open its saved credentials." }
    val owner = accountOwner()
    val namespace = requireNotNull(sourceStorageNamespace(connection.server, owner, owner != null, connection.headers)) {
        "Check the Server URL in Settings."
    }
    val current = { source.current && home.data.current }
    val engine = ControlFeedback.installed() as? IosCueEngine
        ?: error("Install the native Pully cue engine before presenting Settings.")
    val models = SourceModelSettingsServices(
        home.repo, UserDefaultsSettings(defaults, "pully-models.$namespace"),
        read = {
            val saved = requireNotNull(runtime.connection()) { "Pully could not open its saved model choice." }
            ModelConfiguration(saved.engine, saved.model)
        },
        write = { runtime.saveAnalysis(it.engine, it.model) },
        owner = current,
    )
    val account = SourceAccountPagesServices(home.repo, accountOwner, current, runtime::signOut, home.native::copyText)
    val stats = SourceStatsServices(home.repo, UserDefaultsSettings(defaults, "pully-stats.$namespace"), { home.settings.usageStats }, current)
    val feedback = SourceFeedbackServices(home.repo, home.settings, nativeFeedbackMetadata(), screen, place, current)
    return SettingsServices(home.settings, models, account, stats, feedback, server, object : SettingsFeedback {
        override fun vibrationOn() = engine.haptic(Cue.Toggle, Emphasis.Normal)
        override fun soundsChanged(enabled: Boolean) {
            engine.refresh()
            if (enabled) engine.play(Cue.Toggle, Emphasis.Normal, Direction.Forward, null)
        }
        override fun soundSelected(direction: Direction?) = engine.play(Cue.Select, Emphasis.Normal, direction, null)
    }, SettingsPlatformContent(
        // Android installer, browser-helper and notification pages have no native renderer in this app.
        pages = emptySet(), beforeStats = {}, afterModel = {}, advancedRows = {}, advancedFooter = {},
        page = { page, _ -> error("No native platform settings page: $page") },
    ), current, showScreen)
}
