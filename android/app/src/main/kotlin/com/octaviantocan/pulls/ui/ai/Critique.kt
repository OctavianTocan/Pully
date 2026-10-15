package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.octaviantocan.pulls.data.ReviewLens
import com.octaviantocan.pulls.data.ServerStore
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Orb
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
import com.octaviantocan.pulls.ui.review.guardedStop
import kotlinx.coroutines.CancellationException
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

/**
 * The server's review skills, kept for the whole run: the list is short and changes only when the server's
 * curated folder does, so the Review sheet shows it at once and refreshes it quietly (rules 50 and 52). It
 * belongs to the server it came from: after a switch in Settings the old server's skills are never offered.
 */
internal object ReviewLenses {
    /** The server URL [list] and [error] came from. */
    private var server by mutableStateOf<String?>(null)
    private var list by mutableStateOf<List<ReviewLens>?>(null)
    private var error by mutableStateOf<String?>(null)
    private var loadingFor: String? = null

    /** The skills [server] offers; null until they arrive. */
    fun listFor(server: String): List<ReviewLens>? = list.takeIf { this.server == server }

    /** Why [server]'s skills did not load; null when they did or are on their way. */
    fun errorFor(server: String): String? = error.takeIf { this.server == server }

    /** The title to show for a lens a review ran with, when [server] still offers it. */
    fun titleOf(name: String, server: String): String? = listFor(server)?.firstOrNull { it.name == name }?.title

    fun load(session: ReviewSession, server: String) {
        if (server != this.server) {
            this.server = server
            list = null
            error = null
        }
        if (loadingFor == server) return
        loadingFor = server
        session.scope.launch {
            try {
                val lenses = session.repo.lenses()
                if (this@ReviewLenses.server == server) {
                    list = lenses
                    error = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (this@ReviewLenses.server == server) error = e.message ?: e.toString()
            } finally {
                if (loadingFor == server) loadingFor = null
            }
        }
    }
}

/**
 * "Review with AI" from the ⋯ sheet: General, then the server's curated lenses by title, a tick on the one
 * this pull request was last reviewed with. A tap starts the review. The list is short, so it has no search (rule 89).
 */
@Composable
internal fun ReviewSheet(session: ReviewSession, onClose: () -> Unit, onPick: (String?) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val server = remember { ServerStore.load(context).url }
    LaunchedEffect(server) { ReviewLenses.load(session, server) }
    val lenses = ReviewLenses.listFor(server)
    val error = ReviewLenses.errorFor(server)
    val current = lensOf(session)

    PullsSheet(onClose, "Review") {
        SheetCard {
            ChoiceRow("General", "Bugs, risks and nits across the whole diff.", selected = current == null, first = true) { onPick(null) }
            lenses?.forEach { lens -> ChoiceRow(lens.title, lens.description, selected = current == lens.name, first = false) { onPick(lens.name) } }
            // No loading screen: the shape of the rows, replaced in place when they arrive (rule 51).
            if (lenses == null && error == null) repeat(3) { ChoiceSkeleton() }
        }
        when {
            // A refresh that fails keeps the list it had; only a list that never arrived says why.
            lenses == null && error != null -> Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not load the review skills: $error", Modifier.weight(1f), style = PullsType.sm, color = c.error)
                FloatIcon(Lu.redo, "Load the review skills again", { ReviewLenses.load(session, server) }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
            lenses?.isEmpty() == true -> Text("The server offers no review skills.", Modifier.padding(top = 12.dp), style = PullsType.sm, color = c.muted)
        }
    }
}

/** A [ChoiceRow]'s shape while the lenses are on their way: its title and its line. */
@Composable
private fun ChoiceSkeleton() {
    val c = LocalPullsColors.current
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawRect(c.borderMute, Offset(16.dp.toPx(), 0f), Size(size.width - 16.dp.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Skeleton(Modifier.fillMaxWidth(0.35f))
        Skeleton(Modifier.fillMaxWidth(0.8f))
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
    val server = remember { ServerStore.load(context).url }
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
                Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(4.dp)).cueClickable(role = Role.Button) { ui.panelOpen = !ui.panelOpen },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(t("critique.title"), style = PullsType.row.copy(fontWeight = FontWeight.Medium), color = c.text)
                LuIcon(if (ui.panelOpen) Lu.chevd else Lu.chev, tint = c.muted, size = 18.dp)
            }
            // The lens and the count are plain muted words at body size, not a tag (rules 14 and 49).
            if (lens != null) {
                val title = ReviewLenses.titleOf(lens, server)
                Text(title ?: lens, style = if (title != null) PullsType.sm else PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted)
            }
            if (result != null) Text(tc("critique.findingCount", result.findings.size), style = PullsType.sm, color = c.muted)
            // No engine, model or token line: they live in the pickers, not on what the AI wrote (rule 98).
        }

        if (task != null && (running || task.activities.isNotEmpty())) {
            // The log's orb is the Stop button; there is no Stop pill beside it (rule 87).
            val stop = guardedStop(session, listOf("critique"), running) { session.cancelTask(task.id) }
            AiActivityLogFull(task.activities, running, Modifier.fillMaxWidth(), startedAt = task.startedAt.takeIf { it > 0 }, endedAt = task.endedAt, outcome = outcomeOf(task), onStop = stop)
        }
        if (failed) {
            // A repeat is the try-again arrow, named for what it does (rule 87).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.error ?: t("analyze.failed"), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                FloatIcon(Lu.redo, "Run the review again", { session.runCritique(context, task.lens) }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
        }

        if (ui.panelOpen && result != null) {
            if (outdated) Text(t("critique.outdated"), style = PullsType.sm, color = c.amber)
            if (result.summary.isNotBlank()) AiMarkdown(session, result.summary, Modifier.fillMaxWidth())
            if (result.findings.isEmpty()) Text(t("critique.noFindings"), style = PullsType.sm, color = c.fade)
            else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, itemVerticalAlignment = Alignment.CenterVertically) {
                    SeverityFilter(result, ui.severities) { ui.severities = it; session.findingSeverities = it }
                    if (selectable.isNotEmpty()) Row(
                        Modifier.defaultMinSize(minHeight = TOUCH).clip(RoundedCornerShape(PullsRadius.md)).cueClickable(role = Role.Checkbox) {
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
                            session, finding, posted = finding.id in ui.posted, selected = finding.id in ui.selected,
                            onSelect = { value -> ui.selected = if (value) ui.selected + finding.id else ui.selected - finding.id },
                            onJump = { session.focusFinding(finding) },
                        )
                    }
                }
                if (visible.isEmpty()) Text(t("critique.allFiltered"), style = PullsType.sm, color = c.fade)
            }
            if (result.dropped > 0) Text(tc("critique.dropped", result.dropped), style = PullsType.sm, color = c.muted)

            if (result.findings.isNotEmpty() && canWrite) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.fillMaxWidth().height(1.dp).background(c.border))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(PullsRadius.xl)).background(c.paper)) { SheetField(null, ui.postBody, { ui.postBody = it }, placeholder = t("critique.bodyPlaceholder"), first = true, enabled = !ui.posting, minLines = 3) }
                    val pending = session.reviewData?.pendingCount ?: 0
                    if (session.reviewData?.pendingReviewId != null) Text(tc("critique.joinsPending", pending), style = PullsType.sm, color = c.muted)
                    ui.postError?.let { Text(it, style = PullsType.sm, color = c.error) }
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
private fun FindingCard(session: ReviewSession, finding: CritiqueFinding, posted: Boolean, selected: Boolean, onSelect: (Boolean) -> Unit, onJump: () -> Unit) {
    val c = LocalPullsColors.current
    val location = lineRangeLabel(AskSelection(finding.path, finding.side, finding.startLine ?: finding.line, finding.line))
    Row(
        Modifier.fillMaxWidth().alpha(if (posted) 0.65f else 1f).clip(RoundedCornerShape(PullsRadius.xl)).background(c.paper)
            .padding(start = 0.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        if (posted) Row(Modifier.padding(start = 12.dp, top = 12.dp, end = 10.dp)) { LuIcon(Lu.check, tint = c.success, size = 20.dp) }
        else PullsCheck(selected, Modifier.padding(start = 12.dp, top = 10.dp, end = 10.dp), size = 20.dp, onClick = { onSelect(!selected) }, label = t("critique.selectFinding"))
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FindingHeading(finding.severity, finding.title, Modifier.padding(top = 2.dp))
            // The place is a cite: teal text that jumps to the line (rule 96).
            Text(
                location,
                Modifier.defaultMinSize(minHeight = 32.dp).clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClickLabel = t("critique.jumpToLine"), onClick = onJump).padding(vertical = 6.dp),
                style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.activeText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AiMarkdown(session, finding.body, Modifier.fillMaxWidth())
            finding.suggestion?.let { suggestion -> FindingSuggestion(session, finding.path, suggestion) }
        }
    }
}
