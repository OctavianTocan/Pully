package com.octaviantocan.pulls.ui.settings

/** The pages of the Settings sheet. Each but the first slides in inside the same sheet and goes back to its [parent] (rule 78). */
enum class SettingsPage(val parent: SettingsPage?) {
    Main(null),
    Theme(Main),
    Language(Main),
    Advanced(Main),
    Server(Advanced),
    PastVersions(Advanced),
    Font(Main),
    AppFont(Main),
    Sound(Main),
    Model(Main),
    Effort(Main),
    WhatsNew(Main),
    Stats(Main),
    Links(Main),
    BrowserHelper(Main),
    Account(Main),
    AgentKeys(Main),
    AgentKey(AgentKeys),
    Feedback(Main),
    Notifications(Main),
}

/** The page's name in usage stats: `settings` for the main page, `settings-<page>` for one inside it. */
val SettingsPage.analyticsName: String get() = if (this == SettingsPage.Main) "settings" else "settings-${name.lowercase()}"

