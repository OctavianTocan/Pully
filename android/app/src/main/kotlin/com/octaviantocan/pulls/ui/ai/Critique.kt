package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Critique
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsShadow
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.SoftChip
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.floatShadow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/** Starts an AI review through [lens] (null: the general review), like `critique.run`. [effort] overrides the Settings effort. */
internal fun ReviewSession.runCritique(context: android.content.Context, lens: String?, effort: String? = null) {
    val config = ServerStore.load(context)
    val variant = critiqueVariant(opened?.headSha, config.engine, config.model, (effort ?: settings.effort).ifBlank { null }, lens)
    startAiTask("critique", effort = effort, extra = {
        put("variant", variant)
        if (lens != null) put("lens", lens)
    })
}

/** The lens a critique is shown or runs with: the running task's, else the saved result's. */
private fun lensOf(session: ReviewSession): String? {
    val task = session.taskFor("critique")
    return if (task?.running == true) task.lens else session.saved?.critique?.lens
}

/** Port of `LensPicker.vue`: General plus the server's skills, searchable. */
@Composable
internal fun LensPickerSheet(session: ReviewSession, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    var query by remember { mutableStateOf("") }
    fun load() {
        ui.lensesLoading = true
        ui.lensesError = null
        session.scope.launch {
            runCatching { session.repo.lenses() }.onSuccess { ui.lenses = it }.onFailure { ui.lensesError = it.message ?: it.toString() }
            ui.lensesLoading = false
        }
    }
    LaunchedEffect(Unit) { if (ui.lenses == null && !ui.lensesLoading) load() }
    val current = lensOf(session)
    val needle = query.trim().lowercase()
    val all = ui.lenses.orEmpty()
    val matches = if (needle.isEmpty()) all else all.filter { (name, description) -> needle in name.lowercase() || description?.lowercase()?.contains(needle) == true }
    val showGeneral = needle.isEmpty()

    PullsSheet(onDismiss, t("lens.title"), expandable = true) {
        Text(t("lens.description"), Modifier.padding(top = 4.dp), style = PullsType.sm, color = c.muted)
        SheetCard {
            SheetField(
                null, query, { query = it }, placeholder = t("lens.search"), first = true,
                imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                onGo = { if (showGeneral) onPick(null) else matches.firstOrNull()?.let { onPick(it.first) } },
            )
        }
        val error = ui.lensesError
        SheetCard {
            if (showGeneral) LensOption(t("lens.general"), t("lens.generalDescription"), mono = false, selected = current == null, first = true) { onPick(null) }
            matches.forEachIndexed { index, (name, description) ->
                LensOption(name, description, mono = true, selected = current == name, first = !showGeneral && index == 0) { onPick(name) }
            }
            // No loading screen: the shape of the rows, replaced in place when they arrive.
            if (ui.lensesLoading) repeat(3) { Column(Modifier.padding(horizontal = 16.dp, vertical = 17.dp)) { Skeleton(Modifier.fillMaxWidth(if (it % 2 == 0) 0.5f else 0.7f)) } }
        }
        when {
            error != null -> Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("lens.loadFailed", "error" to error), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                PillButton(t("common.retry"), ::load)
            }
            ui.lenses?.isEmpty() == true -> Text(t("lens.none"), Modifier.padding(top = 12.dp), style = PullsType.sm, color = c.muted)
            ui.lenses != null && matches.isEmpty() -> Text(t("lens.noMatch"), Modifier.padding(top = 12.dp), style = PullsType.sm, color = c.muted)
        }
    }
}

@Composable
private fun LensOption(name: String, description: String?, mono: Boolean, selected: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = PullsType.row.copy(fontFamily = if (mono) PullsType.mono else null), color = c.text)
            if (!description.isNullOrBlank()) Text(description, style = PullsType.sm, color = c.muted)
        }
        if (selected) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
    }
}

/** A stable identity for a critique result, so a new one resets the selection (`show`). */
private fun identity(critique: Critique): String = "${critique.generatedAt}|${critique.headSha}|${critique.lens}|${critique.findings.size}"

/** Port of `CritiquePanel.vue` with its `FindingCard`s. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CritiquePanelImpl(session: ReviewSession, modifier: Modifier) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val ui = aiUi(session)
    val task = rememberLiveTask(session, "critique")
    val running = task?.running == true
    val result = session.saved?.critique
    if (result == null && task == null) return

    // A new result: default selection is every finding but nits; the post body is the default body.
    if (result != null && ui.critiqueFor != identity(result)) {
        ui.critiqueFor = identity(result)
        ui.selected = result.findings.filter { it.severity != "nit" }.map { it.id }.toSet()
        ui.posted = emptySet()
        ui.postError = null
        ui.postBody = critiqueReviewBody(result.summary, result.engine, result.model, result.lens)
    }

    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(running) { if (running) runCatching { requester.bringIntoView() } }

    val lens = if (running) task.lens else result?.lens
    val failed = task?.status == "failed"
    val headSha = session.opened?.headSha
    val outdated = result?.headSha != null && result.headSha != headSha
    val visible = result?.findings.orEmpty().filter { it.severity in ui.severities }
    val selectable = visible.filter { it.id !in ui.posted }
    val allSelected = selectable.isNotEmpty() && selectable.all { it.id in ui.selected }
    val toPost = visible.filter { it.id in ui.selected && it.id !in ui.posted }
    val canWrite = session.pull != null
    val canPost = canWrite && toPost.isNotEmpty() && !ui.posting && result?.headSha == headSha

    Column(
        modifier.bringIntoViewRequester(requester).fillMaxWidth().floatShadow(RoundedCornerShape(PullsRadius.card), PullsShadow.Note).clip(RoundedCornerShape(PullsRadius.card)).background(c.raised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { ui.panelOpen = !ui.panelOpen },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(t("critique.title"), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                LuIcon(if (ui.panelOpen) Lu.chevd else Lu.chev, tint = c.muted, size = 18.dp)
            }
            if (lens != null) Text(lens, Modifier.clip(RoundedCornerShape(4.dp)).background(c.active).padding(horizontal = 6.dp, vertical = 2.dp), style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.text)
            if (result != null) Text(tc("critique.findingCount", result.findings.size), style = PullsType.xs, color = c.fade)
            val engine = result?.let { critiqueEngineLine(it.engine, it.model, it.effort, it.usage?.model) }.orEmpty()
            if (engine.isNotEmpty()) Text(engine, style = PullsType.xs, color = c.mute)
            if (result != null && !running) AiUsageLine(result.usage)
        }

        if (task != null && (running || task.activities.isNotEmpty())) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                AiActivityLogFull(task.activities, running, Modifier.weight(1f), startedAt = task.startedAt.takeIf { it > 0 }, endedAt = task.endedAt, outcome = outcomeOf(task))
                if (running) PillButton(t("common.stop"), { session.cancelTask(task.id) })
            }
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.error ?: t("analyze.failed"), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                PillButton(t("common.retry"), { session.runCritique(context, task.lens) })
            }
        }

        if (ui.panelOpen && result != null) {
            if (outdated) Text(t("critique.outdated"), style = PullsType.xs, color = c.amber)
            if (result.summary.isNotBlank()) Markdown(result.summary, Modifier.fillMaxWidth())
            if (result.findings.isEmpty()) Text(t("critique.noFindings"), style = PullsType.sm, color = c.fade)
            else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                    SeverityFilter(result, ui.severities) { ui.severities = it; session.findingSeverities = it }
                    if (selectable.isNotEmpty()) Row(
                        Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(PullsRadius.md)).clickable(role = Role.Checkbox) {
                            ui.selected = if (!allSelected) ui.selected + selectable.map { it.id } else ui.selected - selectable.map { it.id }.toSet()
                        },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PullsCheck(allSelected, size = 20.dp)
                        Text(t("critique.selectAll"), style = PullsType.sm, color = c.text)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.forEach { finding ->
                        FindingCard(
                            finding, posted = finding.id in ui.posted, selected = finding.id in ui.selected,
                            onSelect = { value -> ui.selected = if (value) ui.selected + finding.id else ui.selected - finding.id },
                            onJump = { session.focusFinding(finding) },
                        )
                    }
                }
                if (visible.isEmpty()) Text(t("critique.allFiltered"), style = PullsType.sm, color = c.fade)
            }
            if (result.dropped > 0) Text(tc("critique.dropped", result.dropped), style = PullsType.xs, color = c.mute)

            if (result.findings.isNotEmpty() && canWrite) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = c.border)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.paper)) { SheetField(null, ui.postBody, { ui.postBody = it }, placeholder = t("critique.bodyPlaceholder"), first = true, enabled = !ui.posting, minLines = 3) }
                    val pending = session.reviewData?.pendingCount ?: 0
                    if (session.reviewData?.pendingReviewId != null) Text(tc("critique.joinsPending", pending), style = PullsType.xs, color = c.fade)
                    ui.postError?.let { Text(it, style = PullsType.xs, color = c.error) }
                    // On phones the floating chat button sits over the right edge; keep the post button clear of it.
                    FlowRow(Modifier.fillMaxWidth().padding(end = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (ui.posted.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                LuIcon(Lu.check, tint = c.success, size = 16.dp)
                                Text(tc("critique.postedCount", ui.posted.size), style = PullsType.sm, color = c.muted)
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        CtaButton(
                            tc("critique.post", toPost.size), { postCritique(session, toPost.map { it.id }) },
                            enabled = canPost, leading = { if (ui.posting) Orb(size = 18.dp) },
                        )
                    }
                }
            }
        }
    }
}

private fun postCritique(session: ReviewSession, ids: List<String>) {
    val ui = aiUi(session)
    val key = session.key ?: return
    if (ids.isEmpty() || ui.posting) return
    ui.posting = true
    ui.postError = null
    val body = ui.postBody
    session.scope.launch {
        runCatching { session.repo.postCritique(key, ids, body) }
            .onSuccess { posted ->
                ui.posted = ui.posted + posted
                ui.selected = ui.selected - posted.toSet()
                session.reloadReviews()
            }
            .onFailure { ui.postError = it.message ?: it.toString() }
        ui.posting = false
    }
}

/** `ActionToggleGroup multiple`: Bug n / Risk n / Nit n, empty severities disabled. */
@Composable
private fun SeverityFilter(result: Critique, value: Set<String>, onChange: (Set<String>) -> Unit) {
    val c = LocalPullsColors.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SEVERITIES.forEach { severity ->
            val count = result.findings.count { it.severity == severity }
            val on = severity in value
            SoftChip(
                t("critique.severity.$severity"), on && count > 0,
                // A severity with no findings is dimmed and cannot be toggled, so the filter keeps its value.
                { if (count > 0) onChange(SEVERITIES.filter { if (it == severity) !on else it in value }.toSet()) },
                Modifier.alpha(if (count == 0) 0.4f else 1f), count = "$count",
            )
        }
    }
}

/** Port of `FindingCard.vue`. */
@Composable
private fun FindingCard(finding: CritiqueFinding, posted: Boolean, selected: Boolean, onSelect: (Boolean) -> Unit, onJump: () -> Unit) {
    val c = LocalPullsColors.current
    val location = lineRangeLabel(AskSelection(finding.path, finding.side, finding.startLine ?: finding.line, finding.line))
    Row(
        Modifier.fillMaxWidth().alpha(if (posted) 0.65f else 1f).clip(RoundedCornerShape(PullsRadius.xl)).background(c.paper)
            .padding(start = 0.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (posted) Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 10.dp)) { LuIcon(Lu.check, tint = c.success, size = 20.dp) }
        else PullsCheck(selected, Modifier.padding(start = 12.dp, top = 10.dp, end = 10.dp), size = 20.dp, onClick = { onSelect(!selected) }, label = t("critique.selectFinding"))
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SeverityBadge(finding.severity)
                Text(finding.title, style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
                // `ml-auto truncate text-xs font-mono op-fade`: pushed to the row's end, no underline (hover-only on the web).
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        location,
                        Modifier.defaultMinSize(minHeight = 32.dp).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClickLabel = t("critique.jumpToLine"), onClick = onJump).padding(vertical = 6.dp),
                        style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.fade, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Markdown(finding.body, Modifier.fillMaxWidth())
            finding.suggestion?.let { suggestion ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t("critique.suggestion"), style = PullsType.xs, color = c.mute)
                    SelectionContainer {
                        Text(
                            suggestion,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(c.raised).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                            style = PullsType.xs.copy(fontFamily = PullsType.mono), color = c.text, softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
