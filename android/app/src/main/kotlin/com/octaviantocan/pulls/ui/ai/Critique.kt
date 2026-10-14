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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Critique
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.ui.design.ActionButton
import com.octaviantocan.pulls.ui.design.ButtonVariant
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Markdown
import com.octaviantocan.pulls.ui.design.PhIcon
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.TOUCH
import com.octaviantocan.pulls.ui.design.TextInput
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

    AiSheet(title = t("lens.title"), description = t("lens.description"), onDismiss = onDismiss) {
        TextInput(
            query, { query = it }, t("lens.search"), Modifier.fillMaxWidth(), icon = "magnifying-glass",
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                if (showGeneral) onPick(null) else matches.firstOrNull()?.let { onPick(it.first) }
            }),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (showGeneral) LensOption("magnifying-glass-duotone", t("lens.general"), t("lens.generalDescription"), mono = false, selected = current == null) { onPick(null) }
            matches.forEach { (name, description) -> LensOption("lightbulb-duotone", name, description, mono = true, selected = current == name) { onPick(name) } }
        }
        val error = ui.lensesError
        when {
            ui.lensesLoading -> Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spinner(c.fade)
                Text(t("lens.loading"), style = PullsType.sm, color = c.fade)
            }
            error != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("lens.loadFailed", "error" to error), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                ActionButton(onClick = ::load, small = true) { Text(t("common.retry")) }
            }
            ui.lenses?.isEmpty() == true -> Text(t("lens.none"), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = PullsType.sm, color = c.fade)
            ui.lenses != null && matches.isEmpty() -> Text(t("lens.noMatch"), Modifier.padding(horizontal = 8.dp, vertical = 6.dp), style = PullsType.sm, color = c.fade)
        }
    }
}

@Composable
private fun LensOption(icon: String, name: String, description: String?, mono: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = LocalPullsColors.current
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(8.dp)).background(if (selected) c.active else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon(icon, Modifier.padding(top = 3.dp), tint = c.fade, size = 16.dp)
        Column {
            Text(name, style = PullsType.sm.copy(fontWeight = FontWeight.Medium, fontFamily = if (mono) PullsType.mono else null), color = c.text)
            if (!description.isNullOrBlank()) Text(description, style = PullsType.xs, color = c.fade, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
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
        modifier.bringIntoViewRequester(requester).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.raised)
            .border(1.dp, c.border, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button) { ui.panelOpen = !ui.panelOpen },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhIcon(if (ui.panelOpen) "caret-down" else "caret-right", tint = c.text, size = 14.dp)
                PhIcon("magnifying-glass-duotone", tint = c.fade, size = 16.dp)
                Text(t("critique.title"), style = PullsType.sm.copy(fontWeight = FontWeight.Medium), color = c.text)
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
                if (running) ActionButton(onClick = { session.cancelTask(task.id) }, icon = "stop-duotone", small = true) { Text(t("common.stop")) }
            }
        }
        if (failed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhIcon("warning-circle-duotone", tint = c.error, size = 16.dp)
                Text(task.error ?: t("analyze.failed"), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                ActionButton(onClick = { session.runCritique(context, task.lens) }, small = true) { Text(t("common.retry")) }
            }
        }

        if (ui.panelOpen && result != null) {
            if (outdated) Text(t("critique.outdated"), style = PullsType.xs, color = c.amber)
            if (result.summary.isNotBlank()) Markdown(result.summary, Modifier.fillMaxWidth())
            if (result.findings.isEmpty()) Text(t("critique.noFindings"), style = PullsType.sm, color = c.fade)
            else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                    SeverityFilter(result, ui.severities) { ui.severities = it; session.findingSeverities = it }
                    if (selectable.isNotEmpty()) AiCheckbox(allSelected, { value ->
                        ui.selected = if (value) ui.selected + selectable.map { it.id } else ui.selected - selectable.map { it.id }.toSet()
                    }, label = t("critique.selectAll"))
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
                    TextInput(ui.postBody, { ui.postBody = it }, t("critique.bodyPlaceholder"), Modifier.fillMaxWidth(), singleLine = false, minLines = 3, enabled = !ui.posting)
                    val pending = session.reviewData?.pendingCount ?: 0
                    if (session.reviewData?.pendingReviewId != null) Text(tc("critique.joinsPending", pending), style = PullsType.xs, color = c.fade)
                    ui.postError?.let { Text(it, style = PullsType.xs, color = c.error) }
                    // On phones the floating chat button sits over the right edge; keep the post button clear of it.
                    FlowRow(Modifier.fillMaxWidth().padding(end = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (ui.posted.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PhIcon("check-circle-duotone", tint = c.success, size = 14.dp)
                                Text(tc("critique.postedCount", ui.posted.size), style = PullsType.xs, color = c.fade)
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        ActionButton(
                            onClick = { postCritique(session, toPost.map { it.id }) },
                            variant = ButtonVariant.Primary, icon = "paper-plane-tilt-duotone", loading = ui.posting, enabled = canPost, small = true,
                        ) { Text(tc("critique.post", toPost.size)) }
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
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.sunken).padding(4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SEVERITIES.forEach { severity ->
            val count = result.findings.count { it.severity == severity }
            val on = severity in value
            val disabled = count == 0
            Text(
                "${t("critique.severity.$severity")} $count",
                Modifier.defaultMinSize(minHeight = 36.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on && !disabled) c.raised else Color.Transparent)
                    .clickable(enabled = !disabled, role = Role.Checkbox) { onChange(SEVERITIES.filter { if (it == severity) !on else it in value }.toSet()) }
                    .alpha(if (disabled) 0.4f else 1f)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = PullsType.sm, color = if (on) c.text else c.muted, maxLines = 1,
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
        Modifier.fillMaxWidth().alpha(if (posted) 0.65f else 1f).clip(RoundedCornerShape(8.dp)).background(c.base).border(1.dp, c.border, RoundedCornerShape(8.dp))
            .padding(start = 0.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (posted) Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 10.dp)) { PhIcon("check-circle-duotone", tint = c.success, size = 18.dp, contentDescription = t("critique.postedFinding")) }
        else AiCheckbox(selected, onSelect, contentDescription = t("critique.selectFinding"))
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
