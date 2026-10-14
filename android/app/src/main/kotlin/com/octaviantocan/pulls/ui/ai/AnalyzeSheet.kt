package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.AnalyzeChoice
import com.octaviantocan.pulls.core.AnalyzePreset
import com.octaviantocan.pulls.data.Repo
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.LocalMessages
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.LocalToaster
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsSwitch
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.review.ModelCatalogs
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.analysisModelLabel
import com.octaviantocan.pulls.ui.review.fetchCatalog
import com.octaviantocan.pulls.ui.settings.CliCatalog

/**
 * What the Analyze button's chevron opens: Quick, Standard and Deep, and Custom for picking the passes one by
 * one. Picking one runs it at once and becomes what the button runs next time. The analysis, the tour, the brief
 * and the review all run on the server, so the reviewer can leave and come back.
 */
@Composable
internal fun AnalyzeSheet(session: ReviewSession, onClose: () -> Unit, onModel: () -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val messages = LocalMessages.current
    val settings = session.settings
    val repo = remember { Repo(context) }
    val config = remember { ServerStore.load(context) }
    // The model's own efforts decide what Quick and Deep ask for, so fetch the list if the app has not yet.
    val catalog by produceState<CliCatalog?>(ModelCatalogs.get(config.engine), config.engine) {
        if (value == null) value = fetchCatalog(repo, config.engine).getOrNull()
    }
    val last = settings.analyzeChoice
    var customizing by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(AnalyzeChoice(null, last.tour, last.brief, last.review)) }

    fun run(choice: AnalyzeChoice) {
        settings.updateAnalyzeChoice(choice)
        onClose()
        if (session.taskFor("analyze")?.running == true) {
            toaster.show("Already analyzing")
        } else {
            session.runAnalyze(context, choice)
            toaster.show("${choice.title} analysis started. It keeps running if you leave.")
        }
    }

    if (customizing) {
        PullsSheet(onClose, "Custom analysis", back = { customizing = false }) {
            SheetCard {
                PassRow("Summary and file notes", "Always part of an analysis.", checked = true, first = true, enabled = false) {}
                PassRow("Tour", "A walk through the change, stop by stop.", custom.tour) { custom = custom.copy(tour = it) }
                PassRow("Brief", "A page made for this change: tables, checks, diagrams.", custom.brief) { custom = custom.copy(brief = it) }
                PassRow("AI review", "Looks for bugs and risks, line by line.", custom.review) { custom = custom.copy(review = it) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                ActionButton({ run(custom) }, variant = ButtonVariant.Primary) { Text("Analyze") }
            }
        }
        return
    }

    PullsSheet(onClose, "Analyze") {
        SheetCard {
            AnalyzePreset.entries.forEachIndexed { index, preset ->
                ChoiceRow(preset.title, preset.blurb, selected = last.preset == preset, first = index == 0) { run(AnalyzeChoice.of(preset)) }
            }
            ChoiceRow("Custom", if (last.preset == null) last.summary() else "Pick the passes yourself.", selected = last.preset == null, first = false) { customizing = true }
        }
        SheetCard {
            SheetRow("Model", onModel, first = true, iconless = true) {
                Text(analysisModelLabel(config, catalog, settings.effort, messages), Modifier.weight(1f, fill = false), style = PullsType.sm, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LuIcon(Lu.chev, tint = c.faint, size = 18.dp)
            }
        }
        Text(
            "The Analyze button runs the last choice. It all happens on the server, so you can leave this pull request and come back to the progress.",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = c.muted,
        )
    }
}

/** A choice with a line of what it does, and a tick on the one the button runs. */
@Composable
private fun ChoiceRow(title: String, blurb: String, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth()
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
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

/** Analyze with a chevron: the button runs the last choice, the chevron opens the others. */
@Composable
internal fun AnalyzeSplitButton(choice: String, onRun: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPullsColors.current
    Row(modifier.clip(CircleShape).background(c.primary), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.defaultMinSize(minHeight = TOUCH).clickable(role = Role.Button, onClickLabel = "Analyze, $choice", onClick = onRun).padding(start = 16.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Analyze", style = PullsType.sm, color = Color.White, maxLines = 1) }
        Box(Modifier.width(1.dp).height(20.dp).background(Color.White.copy(alpha = 0.35f)))
        Box(
            Modifier.size(TOUCH).clickable(role = Role.Button, onClickLabel = "Choose how to analyze", onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) { PhIcon("caret-down", tint = Color.White, size = 16.dp) }
    }
}
