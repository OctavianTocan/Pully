package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    Custom(Main),
    Model(Main),
    Effort(Model),
}

private fun AnalyzePage.title(): String = when (this) {
    AnalyzePage.Main -> "Analyze"
    AnalyzePage.Custom -> "Custom analysis"
    AnalyzePage.Model -> "Select model"
    AnalyzePage.Effort -> "Effort"
}

/**
 * What the Analyze button's chevron opens: Quick, Standard and Deep, and Custom for picking the passes one by
 * one. Picking one runs it at once and becomes what the button runs next time. The model, its effort and the
 * custom passes are pages inside this one sheet (rule 78), so choosing a model never closes it. The ⋯ sheet's Model
 * row opens it on the model page ([start]), where there is nothing to go back to. The analysis, the tour, the brief
 * and the review all run on the server, so the reviewer can leave and come back.
 */
@Composable
internal fun AnalyzeSheet(session: ReviewSession, onClose: () -> Unit, start: AnalyzePage = AnalyzePage.Main) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val settings = session.settings
    val last = settings.analyzeChoice
    var page by remember { mutableStateOf(start) }
    var custom by remember { mutableStateOf(AnalyzeChoice(null, last.tour, last.brief, last.review)) }

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

    // Opened straight on the model page, that page is the first one: there is no Analyze page behind it.
    val parent: (AnalyzePage) -> AnalyzePage? = { if (it == AnalyzePage.Model && start == AnalyzePage.Model) null else it.parent }
    PullsPagedSheet(onClose, page, parent = parent, onPage = { page = it }, title = { it.title() }) { current ->
        when (current) {
            AnalyzePage.Main -> AnalyzeMain(session, onRun = ::run, onCustom = { page = AnalyzePage.Custom }, onModel = { page = AnalyzePage.Model })
            AnalyzePage.Custom -> {
                SheetCard {
                    PassRow("Summary and file notes", "Always part of an analysis.", checked = true, first = true, enabled = false) {}
                    PassRow("Tour", "A walk through the change, stop by stop.", custom.tour) { custom = custom.copy(tour = it) }
                    PassRow("Brief", "A page made for this change: tables, checks, diagrams.", custom.brief) { custom = custom.copy(brief = it) }
                    PassRow("AI review", "Looks for bugs and risks, line by line.", custom.review) { custom = custom.copy(review = it) }
                }
                CtaButton("Analyze", { run(custom) }, Modifier.fillMaxWidth().padding(top = 16.dp))
            }
            AnalyzePage.Model -> ModelPage(settings, onDone = { if (start == AnalyzePage.Model) onClose() else page = AnalyzePage.Main }, onEffort = { page = AnalyzePage.Effort })
            AnalyzePage.Effort -> EffortPage(settings, onBack = { page = AnalyzePage.Model })
        }
    }
}

/** The first page: the presets, Custom, and the model they will run with. */
@Composable
private fun ColumnScope.AnalyzeMain(session: ReviewSession, onRun: (AnalyzeChoice) -> Unit, onCustom: () -> Unit, onModel: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val messages = LocalMessages.current
    val settings = session.settings
    val repo = remember { Repo(context) }
    // Read again each time the page opens, because the model page may have changed them.
    val config = ServerStore.load(context)
    // The model's own efforts decide what Quick and Deep ask for, so fetch the list if the app has not yet.
    val catalog by produceState<CliCatalog?>(ModelCatalogs.get(config.engine), config.engine, config.model) {
        value = ModelCatalogs.get(config.engine) ?: fetchCatalog(repo, config.engine).getOrNull()
    }
    val last = settings.analyzeChoice
    SheetCard {
        AnalyzePreset.entries.forEachIndexed { index, preset ->
            ChoiceRow(preset.title, preset.blurb, selected = last.preset == preset, first = index == 0) { onRun(AnalyzeChoice.of(preset)) }
        }
        ChoiceRow("Custom", if (last.preset == null) last.summary() else "Pick the passes yourself.", selected = last.preset == null, first = false, onClick = onCustom)
    }
    SheetCard {
        SheetRow("Model", onModel, first = true, iconless = true) {
            Text(analysisModelLabel(config, catalog, settings.effort, messages), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
        }
    }
}

/** A choice with a line of what it does, and a tick on the one the button runs. */
@Composable
private fun ChoiceRow(title: String, blurb: String, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .cueClickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = PullsType.row, color = c.text, maxLines = 1)
            Text(blurb, style = PullsType.sm, color = c.muted)
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

/** Analyze with a chevron: the button runs the last choice, the chevron opens the others. The dark pill, as the screen's one action. */
@Composable
internal fun AnalyzeSplitButton(choice: String, onRun: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier.clip(CircleShape).background(c.cta), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.defaultMinSize(minHeight = TOUCH).cueClickable(role = Role.Button, onClickLabel = "Analyze, $choice", onClick = onRun).padding(start = 18.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Analyze", style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.onCta, maxLines = 1) }
        Box(Modifier.width(1.dp).height(20.dp).background(c.onCta.copy(alpha = 0.3f)))
        Box(
            Modifier.size(TOUCH).cueClickable(role = Role.Button, onClickLabel = "Choose how to analyze", onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) { LuIcon(Lu.chevd, tint = c.onCta, size = 18.dp) }
    }
}
