package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PillButton
import com.octaviantocan.pulls.ui.design.PullsCheck
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.ReviewState
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc
import kotlinx.coroutines.launch

/** What a sheet says under its card: a few muted lines. */
@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color = LocalPullsColors.current.muted) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = color)
}

/** Review progress: Mark all, Invert and Reset, then every file (by path) with its tick and status; a tap jumps to the file. */
@Composable
fun ReviewProgressSheet(session: ReviewSession, top: ReviewTopUi) {
    if (!top.progressOpen) return
    val c = LocalPullsColors.current
    val rows = session.files.map { it to session.reviewState(it) }.sortedBy { it.first.path }
    val reviewedCount = rows.count { it.second == ReviewState.Reviewed }
    fun shasWhere(reviewed: Boolean) = rows.filter { (it.second == ReviewState.Reviewed) == reviewed }.map { it.first.sha }
    PullsSheet({ top.progressOpen = false }, t("reviewProgress.title"), expandable = true) {
        Hint(t("group.filesReviewed", "reviewed" to reviewedCount, "total" to rows.size))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(t("reviewProgress.markAll"), { session.setReviewed(shasWhere(false), true) }, enabled = reviewedCount != rows.size)
            PillButton(t("reviewProgress.invert"), {
                val (unmark, mark) = shasWhere(true) to shasWhere(false)
                session.setReviewed(unmark, false)
                session.setReviewed(mark, true)
            }, enabled = rows.isNotEmpty())
            PillButton(t("reviewProgress.reset"), { session.setReviewed(rows.map { it.first.sha }, false) }, enabled = reviewedCount > 0 || session.changedSinceReviewed.isNotEmpty())
        }
        SheetCard {
            rows.forEachIndexed { index, (file, state) ->
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).padding(start = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).clickable(role = Role.Checkbox, onClickLabel = t("file.markReviewed")) { session.setReviewed(listOf(file.sha), state != ReviewState.Reviewed) },
                        contentAlignment = Alignment.Center,
                    ) { PullsCheck(state == ReviewState.Reviewed, size = 22.dp) }
                    Box(
                        Modifier.weight(1f).defaultMinSize(minHeight = 44.dp).clickable(role = Role.Button) {
                            top.progressOpen = false
                            session.focus = Focus(file.path)
                        }.padding(vertical = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { FilePath(file.path, Modifier, dim = true, wrap = true, icon = false) }
                    val (label, color) = when (state) {
                        ReviewState.Changed -> "changed" to c.warning
                        ReviewState.Reviewed -> "reviewed" to c.accentTeal
                        else -> "unreviewed" to c.muted
                    }
                    Text(t("reviewProgress.status.$label"), style = PullsType.sm, color = color)
                }
            }
        }
    }
}

private val VERDICTS = listOf("COMMENT", "APPROVE", "REQUEST_CHANGES")

/** Review changes: the pending note, a summary, the verdict, Discard review and Submit. */
@Composable
fun ReviewSubmitSheet(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean = true) {
    if (!top.reviewOpen) return
    val c = LocalPullsColors.current
    val pull = session.pull ?: return
    val scope = rememberCoroutineScope()
    var body by rememberSaveable { mutableStateOf("") }
    var verdict by rememberSaveable { mutableStateOf("COMMENT") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = session.reviewData?.pendingCount ?: 0
    val sent = if (canVerdict) verdict else "COMMENT"
    val labels = mapOf("COMMENT" to t("review.comment"), "APPROVE" to t("review.approve"), "REQUEST_CHANGES" to t("review.requestChanges"))
    // Approve and Request changes in the More sheet open this one on their verdict.
    LaunchedEffect(top.reviewOpen) { if (top.reviewOpen) verdict = top.reviewVerdict }
    fun run(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { action() }
                .onSuccess { body = ""; verdict = "COMMENT"; top.reviewOpen = false; session.reloadReviews() }
                .onFailure { error = it.message ?: it.toString() }
            busy = false
        }
    }
    PullsSheet({ top.reviewOpen = false }, t("review.reviewChanges"), expandable = true) {
        if (pending > 0) Hint(tc("review.includesPending", pending))
        SheetCard { SheetField(null, body, { body = it }, placeholder = t("review.summaryPlaceholder"), first = true, enabled = !busy, minLines = 4) }
        // A closed or merged pull request only takes a plain comment; Approve and Request changes are for open ones.
        if (canVerdict) SheetCard {
            VERDICTS.forEachIndexed { index, option ->
                SheetRow(labels.getValue(option), { verdict = option }, first = index == 0, iconless = true) {
                    if (option == verdict) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
        error?.let { Hint(it, c.error) }
        CtaButton(
            if (busy) "Sending…" else t("common.submit"),
            { run { session.repo.submitReview(pull, sent, body) } },
            Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !busy && (sent == "APPROVE" || body.isNotBlank() || pending > 0),
        )
        if (session.reviewData?.pendingReviewId != null) SheetCard {
            SheetRow(t("review.discard"), { if (!busy) run { session.repo.discardReview(pull) } }, first = true, iconless = true, danger = true)
        }
    }
}

/** Merge: the method, the commit title and message prefilled from `defaultCommits`, then Merge. */
@Composable
fun MergeSheet(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData) {
    val overview = data.overview
    var method by rememberSaveable(overview.id) { mutableStateOf(overview.merge.defaultMethod) }
    val defaults = data.defaultCommits[method] ?: ("" to "")
    var title by rememberSaveable(overview.id) { mutableStateOf("") }
    var body by rememberSaveable(overview.id) { mutableStateOf("") }
    LaunchedEffect(top.mergeOpen, method) {
        if (!top.mergeOpen) return@LaunchedEffect
        title = defaults.first
        body = defaults.second
        top.actionError = null
    }
    if (!top.mergeOpen) return
    val c = LocalPullsColors.current
    val busy = top.busy == "merge"
    PullsSheet({ top.mergeOpen = false }, t("overview.merge.title", "n" to overview.number), expandable = true) {
        Hint(t("overview.merge.into", "head" to overview.headRef, "base" to overview.baseRef))
        if (overview.merge.methods.size > 1) SheetCard {
            overview.merge.methods.forEachIndexed { index, option ->
                SheetRow(t("overview.merge.method.$option"), { method = option }, first = index == 0, iconless = true) {
                    if (option == method) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
        if (method != "rebase") SheetCard {
            SheetField(t("overview.merge.commitTitle"), title, { title = it }, first = true, enabled = !busy)
            SheetField(t("overview.merge.commitMessage"), body, { body = it }, enabled = !busy, minLines = 5)
        } else {
            Hint(t("overview.merge.rebaseHint", "base" to overview.baseRef))
        }
        data.mergeability.warning?.let { Hint(t("overview.merge.$it"), c.warning) }
        top.actionError?.let { Hint(t("overview.actions.failed", "message" to it), c.error) }
        CtaButton(
            if (busy) "Merging…" else t("overview.merge.confirm"),
            {
                // Untouched fields stay unset so GitHub applies the repository's own defaults.
                val sendTitle = title.takeIf { it != defaults.first }
                val sendBody = body.takeIf { it != defaults.second }
                top.run(session, "merge", onOk = { top.mergeOpen = false }) {
                    session.repo.prAction(overview.id, "merge", method = method, title = sendTitle, body = sendBody, expectedHeadOid = overview.headOid)
                }
            },
            Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !busy && data.mergeability.ready && (method == "rebase" || title.isNotBlank()),
        )
    }
}
