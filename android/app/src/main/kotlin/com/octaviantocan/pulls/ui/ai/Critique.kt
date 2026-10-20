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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.Critique
import com.octaviantocan.pulls.data.CritiqueFinding
import com.octaviantocan.pulls.data.ReviewLens
import com.octaviantocan.pulls.data.ServerStore
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
 * Find problems from the ⋯ sheet: the persisted model and effort, then General and the server's curated
 * review skills. Each skill is a launch action. The list is short, so it has no search (rule 89).
 */
@Composable
internal fun ReviewSheet(session: ReviewSession, onClose: () -> Unit, onPick: (String?) -> Unit) {
    var page by remember { mutableStateOf(ReviewSetupPage.Main) }
    PullsPagedSheet(
        onClose, page, parent = { if (it == ReviewSetupPage.Main) null else ReviewSetupPage.Main },
        onPage = { page = it },
        title = { when (it) { ReviewSetupPage.Main -> "Find problems"; ReviewSetupPage.Model -> "Select model"; ReviewSetupPage.Effort -> "Effort" } },
    ) { current ->
        when (current) {
            ReviewSetupPage.Main -> ReviewSetup(session, onPick, { page = ReviewSetupPage.Model }, { page = ReviewSetupPage.Effort })
            ReviewSetupPage.Model -> ModelPage(session.settings) { page = ReviewSetupPage.Main }
            ReviewSetupPage.Effort -> EffortPage(session.settings) { page = ReviewSetupPage.Main }
        }
    }
}

private enum class ReviewSetupPage { Main, Model, Effort }

/** The model is visible before any review skill can start a request. Shared by standalone and PR sheets. */
@Composable
internal fun ColumnScope.ReviewSetup(session: ReviewSession, onPick: (String?) -> Unit, onModel: () -> Unit, onEffort: () -> Unit) {
    ModelCard(session, onModel, onEffort = onEffort, gap = SheetTopGap)
    ReviewLensList(session, onPick)
}

/** General and the review skills, for the Review sheet and for the page of it in the pull request's actions (J27). */
@Composable
internal fun ColumnScope.ReviewLensList(session: ReviewSession, onPick: (String?) -> Unit) {
    val c = LocalPullsColors.current
    val context = LocalContext.current
    val server = remember { ServerStore.load(context).url }
    LaunchedEffect(server) { ReviewLenses.load(session, server) }
    val lenses = ReviewLenses.listFor(server)
    val error = ReviewLenses.errorFor(server)
    SheetCard {
        AiRunRow("General review", "Find bugs, risks and nits across the whole diff.", first = true) { onPick(null) }
        lenses?.forEach { lens -> AiRunRow(lens.title, lens.description, first = false) { onPick(lens.name) } }
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
private fun identity(critique: Critique): String = "${critique.variant}|${critique.revision}|${critique.generatedAt}|${critique.headSha}"

/**
 * Port of `CritiquePanel.vue`, drawn as a sheet's page: "AI review (11)" on top in the sheets' one heading, the summary in a
 * card, All, Bug, Risk and Nit as one segmented pill, and the findings as rows in one card with the Files sheet's checks,
 * not boxes inside a box; no Select all ("no boxes inside boxes", recording of 16 Oct 12:10, 18:24 to 19:43; N36). The
 * heading folds nothing, as no sheet heading does (rule 141).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CritiquePanelImpl(session: ReviewSession, modifier: Modifier, onRetry: (String?) -> Unit) {
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
    val toPost = visible.filter { it.id in ui.selected && it.id !in ui.posted }
    val canWrite = session.pull != null
    val canPost = canWrite && toPost.isNotEmpty() && !ui.posting && result?.headSha == headSha && result?.variant != null && result.revision != null

    Column(modifier.bringIntoViewRequester(requester).fillMaxWidth()) {
        // The count in parentheses after the name; the lens it ran through, a plain muted word, at the heading's end
        // (rules 14 and 49). No engine, model or token line: they live in the pickers, not on what the AI wrote (rule 98).
        SheetHeading(
            if (result != null) "${t("critique.title")} (${result.findings.size})" else t("critique.title"),
            first = true,
            extra = if (lens != null) ({
                val title = ReviewLenses.titleOf(lens, server)
                Text(title ?: lens, style = if (title != null) PullsType.sm else PullsType.sm.copy(fontFamily = PullsType.mono), color = c.muted, maxLines = 1)
            }) else null,
        )

        if (task != null && (running || task.activities.isNotEmpty())) {
            // The log's orb is the Stop button; there is no Stop pill beside it (rule 87).
            val stop = guardedStop(session, listOf("critique"), running) { session.cancelTask(task.id) }
            AiActivityLogFull(task.activities, running, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), startedAt = task.startedAt.takeIf { it > 0 }, endedAt = task.endedAt, outcome = outcomeOf(task), onStop = stop)
        }
        if (failed) {
            // A repeat is the try-again arrow, named for what it does (rule 87).
            Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.error ?: t("analyze.failed"), Modifier.weight(1f), style = PullsType.sm, color = c.error)
                FloatIcon(Lu.redo, "Run the review again", { onRetry(task.lens) }, flat = true, iconSize = 22.dp, tint = c.muted)
            }
        }

        if (result != null) {
            if (outdated || result.summary.isNotBlank()) SheetCard(gap = 6.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (outdated) Text(t("critique.outdated"), style = PullsType.sm, color = c.amber)
                    if (result.summary.isNotBlank()) AiMarkdown(session, result.summary, Modifier.fillMaxWidth())
                }
            }
            if (result.findings.isEmpty()) Text(t("critique.noFindings"), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = PullsType.sm, color = c.fade)
            else {
                SeverityTabs(ui.severities, Modifier.padding(top = 16.dp)) { ui.severities = it; session.findingSeverities = it }
                if (visible.isEmpty()) Text(t("critique.allFiltered"), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), style = PullsType.sm, color = c.fade)
                else SheetCard {
                    visible.forEachIndexed { index, finding ->
                        FindingRow(
                            session, finding, posted = finding.id in ui.posted, selected = finding.id in ui.selected, first = index == 0,
                            onSelect = { value -> ui.selected = if (value) ui.selected + finding.id else ui.selected - finding.id },
                            onJump = { session.focusFinding(finding) },
                        )
                    }
                }
            }
            if (result.dropped > 0) Text(tc("critique.dropped", result.dropped), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm, color = c.muted)

            if (result.findings.isNotEmpty() && canWrite) {
                // The review's text is a field in a white card, as every field on a sheet is (rule 49).
                SheetCard(gap = 16.dp) { SheetField(null, ui.postBody, { ui.postBody = it }, placeholder = t("critique.bodyPlaceholder"), first = true, enabled = !ui.posting, minLines = 3) }
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
                            tc("critique.post", toPost.size), { result?.let { postCritique(session, it, toPost.map { finding -> finding.id }) } },
                            enabled = canPost, leading = { if (ui.posting) Orb(size = 18.dp) },
                        )
                    }
                }
            }
        }
    }
}

private fun postCritique(session: ReviewSession, critique: Critique, ids: List<String>) {
    val ui = aiUi(session)
    val key = session.key ?: return
    if (ids.isEmpty() || ui.posting) return
    ui.posting = true
    ui.postError = null
    val body = ui.postBody
    session.scope.launch {
        runCatching { session.repo.postCritique(key, critique, ids, body) }
            .onSuccess { posted ->
                if (ui.critiqueFor != identity(critique)) return@onSuccess
                ui.posted = ui.posted + posted
                ui.selected = ui.selected - posted.toSet()
                session.reloadReviews()
            }
            .onFailure { ui.postError = it.message ?: it.toString() }
        ui.posting = false
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
private fun FindingRow(session: ReviewSession, finding: CritiqueFinding, posted: Boolean, selected: Boolean, first: Boolean, onSelect: (Boolean) -> Unit, onJump: () -> Unit) {
    val c = LocalPullsColors.current
    val location = lineRangeLabel(AskSelection(finding.path, finding.side, finding.startLine ?: finding.line, finding.line))
    Row(
        Modifier.fillMaxWidth().alpha(if (posted) 0.65f else 1f)
            .drawBehind { if (!first) drawRect(c.borderMute, Offset(ROW_INSET.toPx(), 0f), Size(size.width - ROW_INSET.toPx(), 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(MARK_WIDTH), contentAlignment = Alignment.TopCenter) {
            if (posted) LuIcon(Lu.check, tint = c.success, size = 22.dp)
            else PullsCheck(selected, onClick = { onSelect(!selected) }, label = t("critique.selectFinding"))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FindingHeading(finding.severity, finding.title)
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

/** The mark column of a finding row and where the line between rows starts: the Files sheet's (`ListRow`). */
private val MARK_WIDTH = 24.dp
private val ROW_INSET = 16.dp + MARK_WIDTH + 12.dp
