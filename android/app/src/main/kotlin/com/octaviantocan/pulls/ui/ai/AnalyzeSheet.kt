package com.octaviantocan.pulls.ui.ai

import com.octaviantocan.pulls.ui.settings.AiSettingsLogic
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.review.EffortPage
import com.octaviantocan.pulls.ui.review.ModelCatalogs
import com.octaviantocan.pulls.ui.review.ModelPage
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.analysisModelLabel
import com.octaviantocan.pulls.ui.review.fetchCatalog
import com.octaviantocan.pulls.ui.settings.CliCatalog

/** The pages of the Analyze sheet. Each but the first slides in inside the same sheet and goes back to its [parent] (rule 78). */
internal enum class AnalyzePage(val parent: AnalyzePage?) {
    Main(null),
    Tour(null),
    Custom(Main),
    Model(Main),
    Effort(Model),
    TourEffort(Tour),
}

private fun AnalyzePage.title(): String = when (this) {
    AnalyzePage.Main -> "Analyze"
    AnalyzePage.Tour -> "No tour yet"
    AnalyzePage.Custom -> "Custom analysis"
    AnalyzePage.Model -> "Select model"
    AnalyzePage.Effort, AnalyzePage.TourEffort -> "Effort"
}

/**
 * What the Analyze button's chevron opens: Quick, Standard and Deep, and Custom for picking the passes one by
 * one. Picking one runs it at once and becomes what the button runs next time. The model, its effort and the
 * custom passes are pages inside this one sheet (rule 78), so choosing a model never closes it. The ⋯ sheet's Model
 * row opens it on the model page ([start]), where there is nothing to go back to. The analysis, the tour, the brief
 * and the review all run on the server, so the reviewer can leave and come back.
 */
@Composable
internal fun AnalyzeSheet(session: ReviewSession, onClose: () -> Unit, start: AnalyzePage = AnalyzePage.Main, onTour: () -> Unit = {}) {
    var page by remember { mutableStateOf(start) }
    val custom = rememberCustomAnalysis(session)
    // Opened straight on the model page, that page is the first one: there is no Analyze page behind it. Opened from the
    // tour icon, the model page goes back to the tour's page.
    val parent: (AnalyzePage) -> AnalyzePage? = {
        when {
            it != AnalyzePage.Model -> it.parent
            start == AnalyzePage.Model -> null
            else -> start
        }
    }
    PullsPagedSheet(onClose, page, parent = parent, onPage = { page = it }, title = { it.title() }) { current ->
        AnalyzePageContent(
            session, current, custom, onPage = { page = it }, onClose = onClose, onTour = onTour,
            onModelDone = { if (start == AnalyzePage.Model) onClose() else page = parent(AnalyzePage.Model) ?: AnalyzePage.Main },
        )
    }
}

/** The passes a custom analysis runs, kept by the sheet so its page and the Analyze page share them. */
@Composable
internal fun rememberCustomAnalysis(session: ReviewSession): MutableState<AnalyzeChoice> {
    val last = session.settings.analyzeChoice
    return remember { mutableStateOf(AnalyzeChoice(null, last.tour, last.brief, last.review)) }
}

/** An Analyze page's title, for a sheet that holds these pages among its own. */
internal fun analyzePageTitle(page: AnalyzePage): String = page.title()

/**
 * What one of the Analyze pages holds, for its own sheet and for the pull request's actions sheet, which opens them as
 * pages of its own (J27). [onPage] goes to another of them, [onModelDone] is where the model page goes once a model is
 * picked, and [onClose] closes the sheet they are in.
 */
@Composable
internal fun ColumnScope.AnalyzePageContent(
    session: ReviewSession,
    current: AnalyzePage,
    custom: MutableState<AnalyzeChoice>,
    onPage: (AnalyzePage) -> Unit,
    onModelDone: () -> Unit,
    onClose: () -> Unit,
    onTour: () -> Unit = {},
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settings = session.settings

    fun run(choice: AnalyzeChoice) {
        settings.updateAnalyzeChoice(choice)
        onClose()
        if (session.taskFor("analyze")?.running == true) {
            toaster.show("Already analyzing")
        } else {
            session.runAnalyze(context, choice)
            toaster.show("${choice.title} analysis started. It keeps running if you leave.", cue = Cue.Loading)
        }
    }

    when (current) {
        AnalyzePage.Main -> AnalyzeMain(session, onRun = ::run, onCustom = { onPage(AnalyzePage.Custom) }, onModel = { onPage(AnalyzePage.Model) })
        AnalyzePage.Tour -> TourStart(session, onModel = { onPage(AnalyzePage.Model) }, onEffort = { onPage(AnalyzePage.TourEffort) }) {
            onClose()
            generateTour(session, context)
            onTour()
        }
        AnalyzePage.Custom -> {
            var choice by custom
            SheetCard {
                PassRow("Summary and file notes", "Always part of an analysis.", checked = true, first = true, enabled = false) {}
                PassRow("Tour", "A walk through the change, section by section.", choice.tour) { choice = choice.copy(tour = it) }
                PassRow("Brief", "A page made for this change: tables, checks, diagrams.", choice.brief) { choice = choice.copy(brief = it) }
                PassRow("AI review", "Looks for bugs and risks, line by line.", choice.review) { choice = choice.copy(review = it) }
            }
            CtaButton("Analyze", { run(choice) }, Modifier.fillMaxWidth().padding(top = 16.dp))
        }
        AnalyzePage.Model -> ModelPage(settings, onDone = onModelDone, onEffort = { onPage(AnalyzePage.Effort) })
        AnalyzePage.Effort -> EffortPage(settings, onBack = { onPage(AnalyzePage.Model) })
        AnalyzePage.TourEffort -> EffortPage(settings, onBack = { onPage(AnalyzePage.Tour) })
    }
}

/**
 * What the tour icon opens while the pull request has no tour (rule 113): that there is none yet, the model that would
 * write it, and Generate. Nothing on the Overview offers a tour; this sheet is the one way in, every time.
 */
@Composable
private fun ColumnScope.TourStart(session: ReviewSession, onModel: () -> Unit, onEffort: () -> Unit, onGenerate: () -> Unit) {
    val c = LocalPullsColors.current
    Text(
        "Pulls reads the change and the code around it, then walks you through it section by section.",
        Modifier.padding(horizontal = 4.dp),
        style = PullsType.read, color = c.muted,
    )
    // The effort sits under the model as a row of its own, where the tour is set up ("I would maybe put the effort level
    // here in this menu", recording 15 Oct 15:37, 13:10 to 13:17; L25).
    ModelCard(session, onModel, Modifier.padding(top = 16.dp), onEffort = onEffort)
    CtaButton("Generate tour", onGenerate, Modifier.fillMaxWidth().padding(top = 16.dp), enabled = session.key != null)
}

/** The row that names the model an analysis or a tour runs with; a tap opens the model page. */
@Composable
private fun ModelCard(session: ReviewSession, onModel: () -> Unit, modifier: Modifier = Modifier, onEffort: (() -> Unit)? = null) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val messages = LocalMessages.current
    val repo = remember { Repo(context) }
    remember { ModelCatalogs.warm(context) }
    // Read again each time the page opens, because the model page may have changed them.
    val config = ServerStore.load(context)
    // The model's own efforts decide what Quick and Deep ask for, so fetch the list if the app has not yet.
    val catalog by produceState<CliCatalog?>(ModelCatalogs.get(config.engine), config.engine, config.model) {
        value = ModelCatalogs.get(config.engine) ?: fetchCatalog(repo, config.engine).getOrNull()
    }
    val model = AiSettingsLogic.current(catalog, config.model)
    val efforts = model?.efforts.orEmpty()
    // With its own Effort row the Model row names the model alone; a model with one effort has no choice to offer.
    val effortRow = onEffort != null && efforts.size > 1
    SheetCard(modifier) {
        SheetRow("Model", onModel, first = true, iconless = true) {
            val label = if (effortRow) analysisModelLabel(config, catalog, "", messages).substringBefore(" · ") else analysisModelLabel(config, catalog, session.settings.effort, messages)
            Text(label, Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
        if (effortRow) {
            val effort = session.settings.effort.ifBlank { model?.defaultEffort.orEmpty() }
            SheetRow("Effort", onEffort!!, iconless = true) {
                if (effort.isNotBlank()) Text(AiSettingsLogic.effortLabel(effort) { messages.t(it) }, style = PullsType.sm, color = c.muted)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
    }
}

/** The first page: the presets, Custom, and the model they will run with. */
@Composable
private fun ColumnScope.AnalyzeMain(session: ReviewSession, onRun: (AnalyzeChoice) -> Unit, onCustom: () -> Unit, onModel: () -> Unit) {
    val last = session.settings.analyzeChoice
    SheetCard {
        AnalyzePreset.entries.forEachIndexed { index, preset ->
            ChoiceRow(preset.title, preset.blurb, selected = last.preset == preset, first = index == 0) { onRun(AnalyzeChoice.of(preset)) }
        }
        ChoiceRow("Custom", if (last.preset == null) last.summary() else "Pick the passes yourself.", selected = last.preset == null, first = false, onClick = onCustom)
    }
    ModelCard(session, onModel)
}

/** A choice with a line of what it does, and a tick on the one the button runs. The Review sheet's lenses use it too (rule 70). */
@Composable
internal fun ChoiceRow(title: String, blurb: String?, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .cueClickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            // A title wraps rather than being cut (rule 2).
            Text(title, style = PullsType.row, color = c.text)
            if (!blurb.isNullOrBlank()) Text(blurb, style = PullsType.sm, color = c.muted)
        }
        Box(Modifier.padding(start = 4.dp)) { if (selected) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp) }
    }
}

/** A pass of the analysis with its switch. */
@Composable
private fun PassRow(title: String, hint: String, checked: Boolean, first: Boolean = false, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = PullsType.row, color = c.text)
            Text(hint, style = PullsType.sm, color = c.muted)
        }
        PullsSwitch(checked, onChange, title, enabled = enabled)
    }
}

