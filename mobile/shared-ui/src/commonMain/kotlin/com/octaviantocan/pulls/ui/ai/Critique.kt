package com.octaviantocan.pulls.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.octaviantocan.pulls.ui.settings.ModelSettingsServices
import com.octaviantocan.pulls.ui.review.rememberFindingFocusAction
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Critique
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.sound.cueClickable
import com.octaviantocan.pulls.ui.design.CapsuleTabs
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.FloatIcon
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.Orb
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsRadius
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.SheetTopGap
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetHeading
import com.octaviantocan.pulls.ui.design.Skeleton
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import com.octaviantocan.pulls.ui.review.ReviewSession
import com.octaviantocan.pulls.ui.review.ModelPage
import com.octaviantocan.pulls.ui.review.EffortPage
import com.octaviantocan.pulls.ui.review.guardedStop

/**
 * Find problems from the ⋯ sheet: the persisted model and effort, then General and the server's curated
 * review skills. Each skill is a launch action. The list is short, so it has no search (rule 89).
 */
@Composable
fun ReviewSheet(session: ReviewSession, models: ModelSettingsServices, onClose: () -> Unit, onPick: (String?) -> Unit) {
    key(session, session.captureView(), models) { ReviewSheetBody(session, models, onClose, onPick) }
}

@Composable
private fun ReviewSheetBody(session: ReviewSession, models: ModelSettingsServices, onClose: () -> Unit, onPick: (String?) -> Unit) {
    val actions = rememberAiSetupActions(session, models)
    var page by remember { mutableStateOf(ReviewSetupPage.Main) }
    PullsPagedSheet(
        onClose, page, parent = { if (it == ReviewSetupPage.Main) null else ReviewSetupPage.Main },
        onPage = { next -> actions.read { page = next } },
        title = { when (it) { ReviewSetupPage.Main -> "Find problems"; ReviewSetupPage.Model -> "Select model"; ReviewSetupPage.Effort -> "Effort" } },
    ) { current ->
        when (current) {
            ReviewSetupPage.Main -> ReviewSetup(session, models, onPick, { page = ReviewSetupPage.Model }, { page = ReviewSetupPage.Effort })
            ReviewSetupPage.Model -> ModelPage(session.settings, models) { actions.read { page = ReviewSetupPage.Main } }
            ReviewSetupPage.Effort -> EffortPage(session.settings, models) { actions.read { page = ReviewSetupPage.Main } }
        }
    }
}

private enum class ReviewSetupPage { Main, Model, Effort }

/** The model is visible before any review skill can start a request. Shared by standalone and PR sheets. */
@Composable
fun ColumnScope.ReviewSetup(session: ReviewSession, models: ModelSettingsServices, onPick: (String?) -> Unit, onModel: () -> Unit, onEffort: () -> Unit) {
    ModelCard(session, models, onModel, onEffort = onEffort, gap = SheetTopGap)
    ReviewLensList(session, models, onPick)
}

/** General and the review skills, for the Review sheet and for the page of it in the pull request's actions (J27). */
@Composable
internal fun ColumnScope.ReviewLensList(session: ReviewSession, models: ModelSettingsServices, onPick: (String?) -> Unit) {
    val c = LocalPullsColors.current
    val actions = rememberAiSetupActions(session, models)
    val catalog = reviewLenses(session) ?: return
    LaunchedEffect(session, catalog) { catalog.load(session) }
    val lenses = catalog.list
    val error = catalog.error
    val enabled = actions.canCritique && session.key != null
    fun pick(lens: String?) { if (actions.canCritique && session.key != null) onPick(lens) }
    SheetCard {
        AiRunRow("General review", "Find bugs, risks and nits across the whole diff.", first = true, enabled = enabled) { pick(null) }
        lenses?.forEach { lens -> AiRunRow(lens.title, lens.description, first = false, enabled = enabled) { pick(lens.name) } }
        // No loading screen: the shape of the rows, replaced in place when they arrive (rule 51).
        if (lenses == null && error == null) repeat(3) { ChoiceSkeleton() }
    }
    when {
        // A refresh that fails keeps the list it had; only a list that never arrived says why.
        lenses == null && error != null -> Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Could not load the review skills: $error", Modifier.weight(1f), style = PullsType.sm, color = c.error)
            FloatIcon(Lu.redo, "Load the review skills again", { actions.read { catalog.load(session) } }, flat = true, iconSize = 22.dp, tint = c.muted)
        }
        lenses?.isEmpty() == true -> Text("The server offers no review skills.", Modifier.padding(top = 12.dp), style = PullsType.sm, color = c.muted)
    }
}

/** An [AiRunRow]'s shape while the lenses are on their way: its title and its line. */
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
internal fun critiqueIdentity(critique: Critique): String = "${critique.variant}|${critique.revision}|${critique.generatedAt}|${critique.headSha}"

/**
 * Port of `CritiquePanel.vue`, drawn as a sheet's page: "AI review (11)" on top in the sheets' one heading, the summary in a
 * card, All, Bug, Risk and Nit as one segmented pill, and the findings as rows in one card with the Files sheet's checks,
 * not boxes inside a box; no Select all ("no boxes inside boxes", recording of 16 Oct 12:10, 18:24 to 19:43; N36). The
 * heading folds nothing, as no sheet heading does (rule 141).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CritiquePanel(session: ReviewSession, modifier: Modifier = Modifier, onRetry: (String?) -> Unit) {
    if (!LocalAiCapabilities.current.canReadResults) return
    key(session, session.captureView(), session.saved?.critique?.let(::critiqueIdentity)) { CritiquePanelBody(session, modifier, onRetry) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CritiquePanelBody(session: ReviewSession, modifier: Modifier, onRetry: (String?) -> Unit) {
    val c = LocalPullsColors.current
    val ui = aiUi(session)
    val actions = rememberCritiqueActions(session)
    val lenses = reviewLenses(session)
    val task = rememberLiveTask(session, "critique")
    val running = task?.running == true
    val result = session.saved?.critique
    val experimental = session.settings.experiments.mergeRisk
    val mergeRisk = result?.mergeRisk?.takeIf { experimental }
    if (result == null && task == null) return

    // A new result: default selection is every finding but nits; the post body is the default body.
    if (result != null && ui.critiqueFor != critiqueIdentity(result)) {
        ui.critiqueFor = critiqueIdentity(result)
        ui.selected = result.findings.filter { it.severity != "nit" }.map { it.id }.toSet()
        ui.posted = emptySet()
        ui.postError = null
    }
    val bodyIdentity = result?.let { "${critiqueIdentity(it)}|$experimental" }
    if (result != null && ui.critiqueBodyFor != bodyIdentity) {
        ui.critiqueBodyFor = bodyIdentity
        val summary = listOfNotNull(result.summary, mergeRisk?.let { experimentalMergeRiskMarkdown(it) }).joinToString("\n\n")
        ui.postBody = critiqueReviewBody(summary, result.engine, result.model, result.lens)
    }

    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(running) { if (running) runCatching { requester.bringIntoView() } }

    val lens = if (running) task.lens else result?.lens
    val failed = task?.status == "failed"
    val headSha = session.opened?.headSha
    val outdated = result?.headSha != null && result.headSha != headSha
    val visible = result?.findings.orEmpty().filter { it.severity in ui.severities }
    val toPost = visible.filter { it.id in ui.selected && it.id !in ui.posted }
    val canWrite = actions.canPublish
    val canPost = canWrite && toPost.isNotEmpty() && !ui.posting && result?.headSha == headSha && result?.variant != null && result.revision != null

    Column(modifier.bringIntoViewRequester(requester).fillMaxWidth()) {
        // The count in parentheses after the name; the lens it ran through, a plain muted word, at the heading's end
        // (rules 14 and 49). No engine, model or token line: they live in the pickers, not on what the AI wrote (rule 98).
        SheetHeading(
            if (result != null) "${t("critique.title")} (${result.findings.size})" else t("critique.title"),
            first = true,
            extra = if (lens != null) ({
                val title = lenses?.titleOf(lens)
                Text(title ?: lens, style = if (title != null) PullsType.sm else PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted, maxLines = 1)
            }) else null,
        )

        if (task != null && (running || task.activities.isNotEmpty())) {
            // The log's orb is the Stop button; there is no Stop pill beside it (rule 87).
            val stop = guardedStop(session, listOf("critique"), running) { session.cancelTask(task.id) }
            AiActivityLogFull(task.activities, running, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), startedAt = task.startedAt.takeIf { it > 0 }, endedAt = task.endedAt, outcome = outcomeOf(task), onStop = stop, reconnecting = task?.id in session.taskObservationErrors)
        }
        if (failed) {
            // A repeat is the try-again arrow, named for what it does (rule 87).
            Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.error ?: t("analyze.failed"), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                if (actions.canRetry) FloatIcon(Lu.redo, "Run the review again", { actions.retry { onRetry(task.lens) } }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
        }

        if (result != null) {
            if (outdated || result.summary.isNotBlank()) SheetCard(gap = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (outdated) Text(t("critique.outdated"), style = PullsType.sm, color = c.amber)
                    if (result.summary.isNotBlank()) AiMarkdown(session, result.summary, Modifier.fillMaxWidth())
                }
            }
            mergeRisk?.let { ExperimentalMergeRisk(session, it) }
            if (result.findings.isEmpty()) Text(t("critique.noFindings"), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = PullsType.sm, color = c.fade)
            else {
                SeverityTabs(ui.severities, Modifier.padding(top = 16.dp)) { selected -> actions.read { ui.severities = selected; session.findingSeverities = selected } }
                if (visible.isEmpty()) Text(t("critique.allFiltered"), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = PullsType.sm, color = c.fade)
                else SheetCard {
                    visible.forEachIndexed { index, finding ->
                        FindingRow(
                            session, finding, posted = finding.id in ui.posted, selected = finding.id in ui.selected, first = index == 0,
                            selectable = canWrite,
                            onSelect = { value -> actions.read { ui.selected = if (value) ui.selected + finding.id else ui.selected - finding.id } },
                        )
                    }
                }
            }
            if (result.dropped > 0) Text(tc("critique.dropped", result.dropped), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted)

            if (result.findings.isNotEmpty() && canWrite) {
                // The review's text is a field in a white card, as every field on a sheet is (rule 49).
                SheetCard(gap = 16.dp) { SheetField(null, ui.postBody, { body -> actions.read { ui.postBody = body } }, placeholder = t("critique.bodyPlaceholder"), first = true, enabled = !ui.posting, minLines = 3) }
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pending = session.reviewData?.pendingCount ?: 0
                    if (session.reviewData?.pendingReviewId != null) Text(tc("critique.joinsPending", pending), Modifier.padding(horizontal = 16.dp), style = PullsType.sm, color = c.muted)
                    ui.postError?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = PullsType.sm, color = c.error) }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                        if (ui.posted.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                LuIcon(Lu.check, tint = c.success, size = 16.dp)
                                Text(tc("critique.postedCount", ui.posted.size), style = PullsType.sm, color = c.muted)
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        CtaButton(
                            tc("critique.post", toPost.size), { actions.post(toPost.map { finding -> finding.id }) },
                            enabled = canPost, leading = { if (ui.posting) Orb(size = 18.dp) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * All, Bug, Risk and Nit as one segmented pill, the kit's capsule (`CapsuleTabs`): all the findings, or one severity
 * ("one pill selector with All", recording of 16 Oct 12:10, 18:24 to 19:43; N36).
 */
@Composable
private fun SeverityTabs(value: Set<String>, modifier: Modifier = Modifier, onChange: (Set<String>) -> Unit) {
    val labels = listOf("All") + SEVERITIES.map { t("critique.severity.$it") }
    val selected = if (value.size == 1) SEVERITIES.indexOf(value.first()) + 1 else 0
    CapsuleTabs(
        labels, selected,
        { index -> onChange(if (index == 0) SEVERITIES.toSet() else setOf(SEVERITIES[index - 1])) },
        modifier.fillMaxWidth(),
    )
}

/**
 * A finding as a row of the Files sheet: the same 22dp check in a 24dp column on the first line (a green tick once
 * posted), the text 12dp after it, and the line between rows starting where the text does (rule 139; N36). Under the
 * severity and the title, the place is a cite that jumps to the line (rule 96), then the body and any suggestion.
 */
@Composable
private fun FindingRow(session: ReviewSession, finding: CritiqueFinding, posted: Boolean, selected: Boolean, first: Boolean, selectable: Boolean, onSelect: (Boolean) -> Unit) {
    val c = LocalPullsColors.current
    val focus = rememberFindingFocusAction(session, finding)
    val location = lineRangeLabel(AskSelection(finding.path, finding.side, finding.startLine ?: finding.line, finding.line))
    Row(
        Modifier.fillMaxWidth().alpha(if (posted) 0.65f else 1f)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(ROW_INSET.toPx(), 0f), Size(size.width - ROW_INSET.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(MARK_WIDTH), contentAlignment = Alignment.TopCenter) {
            if (posted) LuIcon(Lu.check, tint = c.success, size = 22.dp)
            else if (selectable) PullsCheck(selected, onClick = { onSelect(!selected) }, label = t("critique.selectFinding"))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FindingHeading(finding.severity, finding.title)
            Text(
                location,
                Modifier.defaultMinSize(minHeight = 32.dp).clip(RoundedCornerShape(PullsRadius.sm)).cueClickable(role = Role.Button, onClickLabel = t("critique.jumpToLine"), onClick = focus::open).padding(vertical = 6.dp),
                style = PullsType.sm.copy(fontFamily = PullsType.mono), color = c.activeText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            AiMarkdown(session, finding.body, Modifier.fillMaxWidth())
            finding.suggestion?.let { suggestion -> FindingSuggestion(session, finding.path, suggestion) }
        }
    }
}

/** The mark column of a finding row and where the line between rows starts: the Files sheet's (`ListRow`). */
private val MARK_WIDTH = 24.dp
private val ROW_INSET = 16.dp + MARK_WIDTH + 12.dp
