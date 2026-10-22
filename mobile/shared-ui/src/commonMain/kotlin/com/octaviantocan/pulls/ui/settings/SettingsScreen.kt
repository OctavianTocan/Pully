package com.octaviantocan.pulls.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.settings.AppSettings
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Direction
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Material
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.sound.directionOf
import com.octaviantocan.pulls.ui.Nav
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.EngineMark
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Messages
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.ThemeWipe
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.review.CodeFontPage
import com.octaviantocan.pulls.ui.review.EffortPage
import com.octaviantocan.pulls.ui.review.ModelPage
import com.octaviantocan.pulls.ui.review.analysisModelLabel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
fun SettingsPage.title(openedKeyName: String?): String = when (this) {
    SettingsPage.Main -> t("common.settings")
    SettingsPage.Theme -> t("settings.colorTheme")
    SettingsPage.Language -> t("settings.language")
    SettingsPage.Advanced -> "Advanced"
    SettingsPage.Server -> "Server"
    SettingsPage.PastVersions -> "Past versions"
    SettingsPage.Font -> "Code font"
    SettingsPage.AppFont -> "App font"
    SettingsPage.Sound -> "Sound"
    SettingsPage.Model -> "Select model"
    SettingsPage.Effort -> "Effort"
    SettingsPage.WhatsNew -> "What's new"
    SettingsPage.Stats -> t("stats.title")
    SettingsPage.Links -> "GitHub links"
    SettingsPage.BrowserHelper -> t("settings.experiments.browserHelper")
    SettingsPage.Account -> "Account"
    SettingsPage.AgentKeys -> "Agent keys"
    SettingsPage.AgentKey -> openedKeyName ?: "Agent key"
    SettingsPage.Feedback -> "Send feedback"
    SettingsPage.Notifications -> "Notifications"
}

/**
 * Settings, as a sheet like the rest of the app: grouped white cards on paper, one row for each choice.
 * The colour theme, language, the app and code fonts, the sound, the model, its effort and Advanced (the server, past versions and the UI Book) are pages inside this one
 * sheet (rule 78). Rows carry no explanations under them: a row says what it is, and its page says the rest (H8).
 * A setting that changes how something looks shows the result (rule 81): the diff layout as two small diffs,
 * the code size and font as a sample line.
 */
@Composable
fun SettingsScreen(nav: Nav, services: SettingsServices) {
    androidx.compose.runtime.key(services) { SettingsScreenBody(nav, services) }
}

@Composable
private fun SettingsScreenBody(nav: Nav, services: SettingsServices) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    LaunchedEffect(page) { if (services.current()) services.showScreen(page.analyticsName) }
    PullsPagedSheet(nav.back, page, parent = { it.parent }, onPage = { if (services.current()) page = it }, title = { it.title(services.account.ui.opened?.name) }) { current ->
        SettingsPageContent(nav, current, services, onPage = { if (services.current()) page = it })
    }
}

/** What one page of the Settings sheet holds. */
@Composable
fun ColumnScope.SettingsPageContent(nav: Nav, page: SettingsPage, services: SettingsServices, onPage: (SettingsPage) -> Unit) {
    val settings = services.settings
    when (page) {
        SettingsPage.Main -> MainPage(nav, services, open = onPage)
        SettingsPage.Theme -> ColorThemePage(services.colorThemes)
        SettingsPage.Language -> LanguagePage(settings, onDone = { onPage(SettingsPage.Main) })
        SettingsPage.Advanced -> AdvancedPage(nav, services, open = onPage)
        SettingsPage.Server -> ServerPage(remember(services) { services.server() })
        SettingsPage.Font -> CodeFontPage(settings, onDone = { onPage(SettingsPage.Main) })
        SettingsPage.AppFont -> AppFontPage(settings, onDone = { onPage(SettingsPage.Main) })
        SettingsPage.Sound -> SoundPage(settings, services, onDone = { onPage(SettingsPage.Main) })
        SettingsPage.Model -> ModelPage(settings, services.models, onDone = { onPage(SettingsPage.Main) })
        SettingsPage.Effort -> EffortPage(settings, services.models, onBack = { onPage(SettingsPage.Main) })
        SettingsPage.Stats -> StatsPage(settings, services.stats)
        SettingsPage.Account -> AccountPage(services.account)
        SettingsPage.AgentKeys -> AgentKeysPage(services.account, open = onPage)
        SettingsPage.AgentKey -> AgentKeyPage(services.account, onDone = { onPage(SettingsPage.AgentKeys) })
        SettingsPage.Feedback -> FeedbackPage(services.feedbackPage, onSent = { onPage(SettingsPage.Main) })
        else -> {
            check(page in services.platform.pages) { "This settings page has no installed native renderer." }
            services.platform.page(this, page, onPage)
        }
    }
}

/**
 * The two diff layouts as small diffs you tap, here and in the diff's own Text and tools sheet. Both can be chosen on any
 * screen: a phone held upright shows the split too, two narrow columns (H10).
 */
@Composable
fun LayoutChoices(settings: com.octaviantocan.pulls.settings.AppSettings, modifier: Modifier = Modifier, onChoose: (String) -> Unit = settings::updateLayout) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LayoutChoice(split = false, selected = settings.layout != "split", label = t("settings.unified"), onClick = { onChoose("unified") }, modifier = Modifier.weight(1f))
        LayoutChoice(split = true, selected = settings.layout == "split", label = t("settings.split"), onClick = { onChoose("split") }, modifier = Modifier.weight(1f))
    }
}

/** The three code sizes, as multiples of the 12.5sp default (the prototype's 11, 12.5 and 14.5). */
val ZOOMS = listOf("Smaller" to 0.88f, "Default" to 1f, "Larger" to 1.16f)

@Composable
private fun ColumnScope.MainPage(nav: Nav, services: SettingsServices, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val settings = services.settings
    val messages = LocalMessages.current
    val config = remember(services) { services.models.configuration() }
    // The catalog gives the model its name; until it comes, the id is written as a name (H7).
    val catalog by produceState<CliCatalog?>(services.models.cached(config.engine), services, config.engine) {
        if (value == null) value = services.models.fetch(config.engine).getOrNull()
    }
    val language = Messages.LOCALES.firstOrNull { it.first == settings.locale }?.second ?: settings.locale
    val scope = rememberCoroutineScope()
    val moving = rememberMotionEnabled()
    val systemDark = isSystemInDarkTheme()
    val zoomIndex = ZOOMS.indexOfFirst { kotlin.math.abs(settings.codeZoom - it.second) < 0.04f }.coerceAtLeast(0)

    // Who is signed in and the keys agents post with, first: the phone is where they are made (Tavi, 16 Oct 12:47).
    AccountGroup(services.account, open)
    Group("Appearance") {
        Box(Modifier.padding(top = 10.dp)) {
            val themes = listOf("system", "light", "dark")
            CapsuleTabs(
                listOf("System", "Light", "Dark"),
                themes.indexOf(settings.theme).coerceAtLeast(0),
                { picked ->
                    // The new look wipes in from the top corner on the side of the choice: Dark from the right (H5). System
                    // while the phone is already in the look on screen changes nothing, so nothing wipes (J2).
                    val next = when (themes[picked]) { "dark" -> true; "light" -> false; else -> systemDark }
                    // A theme with one look keeps it either way, so only a palette that differs wipes.
                    val changes = com.octaviantocan.pulls.ui.design.ColorThemes.of(settings.colorTheme).colors(next) != c
                    scope.launch { ThemeWipe.run(fromRight = picked == 2, motion = moving, changes = changes) { settings.updateTheme(themes[picked]) } }
                },
                Modifier.fillMaxWidth(),
            )
        }
        SheetCard {
            // The colour theme (K18): its name, and its page lists every theme with a preview.
            SheetRow(t("settings.colorTheme"), { open(SettingsPage.Theme) }, first = true, iconless = true) {
                Text(com.octaviantocan.pulls.ui.design.ColorThemes.of(settings.colorTheme).name, style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow(t("settings.language"), { open(SettingsPage.Language) }, iconless = true) {
                Text(language, style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
    Group(t("settings.layout")) {
        // The two layouts drawn small, so the choice is the thing chosen (rule 81).
        LayoutChoices(settings, Modifier.padding(top = 10.dp))
    }
    // The faces, the app's and the code's, in one section of their own (recording 16 Oct 12:10, 06:29 to 06:42; N10).
    Group("Fonts") {
        SheetCard {
            SheetRow("App font", { open(SettingsPage.AppFont) }, first = true, iconless = true) {
                Text(com.octaviantocan.pulls.ui.design.AppFont.of(settings.appFont).label, style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            SheetRow("Code font", { open(SettingsPage.Font) }, iconless = true) {
                Text(com.octaviantocan.pulls.ui.design.CodeFont.of(settings.codeFont).label, style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
    Group("Code") {
        SheetCard {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CapsuleTabs(ZOOMS.map { it.first }, zoomIndex, { settings.updateCodeZoom(ZOOMS[it].second) }, Modifier.fillMaxWidth())
                CodeSample(settings.codeZoom, wrap = settings.codeWrap)
            }
            SheetRow("Wrap long lines", { settings.updateCodeWrap(!settings.codeWrap) }, iconless = true) {
                PullsSwitch(settings.codeWrap, { settings.updateCodeWrap(it) }, "Wrap long lines")
            }
        }
    }
    Group("Sounds") {
        SheetCard {
            SheetRow("Button sounds", { toggleSounds(settings, services, !settings.sounds) }, first = true, iconless = true) {
                PullsSwitch(settings.sounds, { toggleSounds(settings, services, it) }, "Button sounds")
            }
            // Which material the sounds are made of, as a page like the code font's (J5); only while there are sounds.
            if (settings.sounds) {
                SheetRow("Sound", { open(SettingsPage.Sound) }, iconless = true) {
                    Text(Material.of(settings.soundMaterial).label, style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
            // Taps, swipes and outcomes are felt as well (H18); turning it on answers with a click.
            SheetRow("Vibration", { toggleHaptics(settings, services, !settings.haptics) }, iconless = true) {
                PullsSwitch(settings.haptics, { toggleHaptics(settings, services, it) }, "Vibration")
            }
        }
    }
    services.platform.beforeStats(this, open)
    ExperimentalSettings(settings, services::current) { services.platform.experimentalRows(this, open) }
    // Usage stats, on until turned off; off drops what is queued (root CONTRACTS, analytics-can-be-turned-off). What it
    // counts is said to a screen reader, not in a line under the row (settings-no-helper-lines).
    Group(t("stats.title")) {
        SheetCard {
            val usage = t("stats.usage")
            val hint = t("stats.usageHint")
            SheetRow(usage, { settings.updateUsageStats(!settings.usageStats) }, Modifier.semantics { stateDescription = hint }, first = true, iconless = true) {
                PullsSwitch(settings.usageStats, { settings.updateUsageStats(it) }, usage)
            }
            SheetRow(t("stats.title"), { open(SettingsPage.Stats) }, iconless = true) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
    Group(t("settings.cached")) {
        SheetCard {
            SheetRow(t("settings.autoRefresh"), { settings.updateAutoRefresh(!settings.autoRefresh) }, first = true, iconless = true) {
                PullsSwitch(settings.autoRefresh, { settings.updateAutoRefresh(it) }, t("settings.autoRefresh"))
            }
        }
    }
    Group(t("settings.llm.title")) {
        SheetCard {
            // The effort is a row of its own right under the model, not at the foot of the model list, which Pi's long
            // list made a scroll away ("just make it another row right under that": recording 16 Oct 09:50, 00:44 to
            // 01:04; M2). A model with one effort has nothing to choose, so it has no Effort row.
            val model = AiSettingsLogic.current(catalog, config.model)
            val effortRow = (model?.efforts?.size ?: 0) > 1
            SheetRow("Model", { open(SettingsPage.Model) }, first = true, icon = { EngineMark(config.engine, size = 22.dp) }) {
                val label = analysisModelLabel(config, catalog, settings.effort, messages)
                Text(if (effortRow) label.substringBefore(" · ") else label, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
            if (effortRow) {
                val effort = settings.effort.ifBlank { model?.defaultEffort.orEmpty() }
                SheetRow("Effort", { open(SettingsPage.Effort) }, iconless = true) {
                    if (effort.isNotBlank()) Text(AiSettingsLogic.effortLabel(effort) { messages.t(it) }, style = PullsType.sm, color = c.muted)
                    LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
                }
            }
        }
    }
    services.platform.afterModel(this, open)
    // Feedback on the app, kept on the server for agents to act on later (Tavi, 20 Oct 06:10; nav-feedback-from-anywhere).
    Box(Modifier.padding(top = 24.dp)) {
        SheetCard {
            SheetRow("Send feedback", { open(SettingsPage.Feedback) }, first = true, iconless = true) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
    // The server and the UI Book are for setting the app up, not for using it: one row at the bottom (H9).
    Box(Modifier.padding(top = 24.dp)) {
        SheetCard {
            SheetRow("Advanced", { open(SettingsPage.Advanced) }, first = true, iconless = true) {
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/** Advanced: which server the phone reads from, the past versions of the app, and the UI Book. */
@Composable
private fun ColumnScope.AdvancedPage(nav: Nav, services: SettingsServices, open: (SettingsPage) -> Unit) {
    val c = LocalPullsColors.current
    val config = remember(services) { services.server().configuration }
    SheetCard {
        SheetRow("Server", { open(SettingsPage.Server) }, first = true, iconless = true) {
            Text(config.url.removePrefix("https://").removePrefix("http://").ifBlank { "Not set" }, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
        services.platform.advancedRows(this, open)
    }
    services.platform.advancedFooter(this, nav)
}

/** Turns vibration on or off; turning it on is felt at once. */
private fun toggleHaptics(settings: AppSettings, services: SettingsServices, on: Boolean) {
    if (!services.current()) return
    settings.updateHaptics(on)
    if (on) services.feedback.vibrationOn()
}

/** Turns the button sounds on or off. Turning them on answers with a toggle, so the first sound is the proof they work. */
private fun toggleSounds(settings: AppSettings, services: SettingsServices, on: Boolean) {
    if (!services.current()) return
    settings.updateSounds(on)
    services.feedback.soundsChanged(on)
}

/**
 * The four materials the sounds are made of, the chosen one ticked. The rows are silent themselves: picking one
 * says one cue in the new material, so choosing is hearing it, and goes back as the code font does.
 */
@Composable
private fun ColumnScope.SoundPage(settings: AppSettings, services: SettingsServices, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val chosen = Material.of(settings.soundMaterial)
    SheetCard {
        Material.entries.forEachIndexed { index, material ->
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
                    .drawBehind { if (index > 0) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
                    .clickable(role = Role.RadioButton, onClickLabel = material.label) {
                        if (!services.current()) return@clickable
                        settings.updateSoundMaterial(material.id)
                        services.feedback.soundSelected(directionOf(chosen.ordinal, material.ordinal))
                        onDone()
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(material.label, Modifier.weight(1f), style = PullsType.row, color = c.text, maxLines = 1)
                if (material == chosen) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
            }
        }
    }
}

/** The languages the app is written in; picking one changes the app at once and goes back. */
@Composable
private fun ColumnScope.LanguagePage(settings: AppSettings, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    SheetCard {
        Messages.LOCALES.forEachIndexed { index, (code, name) ->
            SheetRow(name, { settings.updateLocale(code); onDone() }, first = index == 0, iconless = true) {
                if (code == settings.locale) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
            }
        }
    }
}

