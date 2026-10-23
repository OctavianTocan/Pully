package com.octaviantocan.pulls.ui

/** The same always-available palette entries, with platform-only destinations supplied explicitly. */
fun appCommands(
    text: (String) -> String,
    nav: Nav,
    current: () -> Boolean,
    toggleTheme: () -> Unit,
    openThemes: () -> Unit,
    feedback: () -> Unit,
    update: (() -> Unit)?,
    whatsNew: (() -> Unit)?,
    explore: (() -> Unit)? = null,
): List<Command> {
    val group = text("palette.group.general")
    fun command(id: String, title: String, keywords: String? = null, run: () -> Unit) =
        Command(id, title, group, keywords = keywords) { if (current()) run() }
    return buildList {
        add(command("settings", text("keys.settings"), run = nav.settings))
        add(command("home", text("keys.home"), run = nav.home))
        explore?.let { add(command("explore", text("explore.browse"), run = it)) }
        add(command("theme", text("keys.theme"), run = toggleTheme))
        add(command("colorTheme", text("keys.colorTheme"), "theme colour color palette opencode appearance", openThemes))
        update?.let { add(command("update", "Update Pully", "upgrade new version install check latest", it)) }
        whatsNew?.let { add(command("whatsNew", "What's new", "changelog changes version release notes", it)) }
        add(command("feedback", "Send feedback", "feedback report bug idea note suggest problem", feedback))
    }
}
