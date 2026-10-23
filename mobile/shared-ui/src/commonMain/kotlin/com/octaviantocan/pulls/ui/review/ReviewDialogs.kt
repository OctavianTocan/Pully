package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsPagedSheet
import com.octaviantocan.pulls.ui.design.PullsSpace
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.design.SheetCard
import com.octaviantocan.pulls.ui.design.SheetField
import com.octaviantocan.pulls.ui.design.SheetRow
import com.octaviantocan.pulls.ui.design.t
import com.octaviantocan.pulls.ui.design.tc

/** What a sheet says under its card: a few muted lines. */
@Composable
private fun Hint(text: String, color: androidx.compose.ui.graphics.Color = LocalPullsColors.current.muted) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), style = PullsType.sm.copy(lineHeight = PullsType.sm.fontSize * 1.45f), color = color)
}

private val VERDICTS = listOf("COMMENT", "APPROVE", "REQUEST_CHANGES")

/** The actual summary/verdict state stays mounted while fresh submission is temporarily paused. */
internal class ReviewSubmissionState(body: String, verdict: String) {
    var body by mutableStateOf(body)
    var verdict by mutableStateOf(verdict)
}

private val submissionSaver = listSaver<ReviewSubmissionState, String>(
    save = { listOf(it.body, it.verdict) },
    restore = { ReviewSubmissionState(it[0], it[1]) },
)

@Composable
internal fun ReviewSubmissionContent(
    session: ReviewSession,
    top: ReviewTopUi,
    start: String,
    content: @Composable (ReviewMutationActions, ReviewSubmissionState) -> Unit,
) {
    val actions = rememberReviewMutationActions(session, top)
    if (!actions.canKeepSubmission) return
    val state = rememberSaveable(session, session.captureView(), saver = submissionSaver) { ReviewSubmissionState("", start) }
    content(actions, state)
}

/** Review changes: the pending note, a summary, the verdict, Discard review and Submit. */
@Composable
fun ReviewSubmitSheet(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean = true) {
    val actions = rememberReviewMutationActions(session, top)
    if (!top.reviewOpen || !actions.canKeepSubmission) return
    key(session, session.captureView()) {
        var discard by remember { mutableStateOf(false) }
        PullsPagedSheet(
            { top.reviewOpen = false }, discard, parent = { if (it) false else null }, onPage = { discard = it },
            title = { if (it) t("review.discard") else t("review.reviewChanges") },
        ) { confirming ->
            if (confirming) ReviewDiscardPage(session, top) { top.reviewOpen = false }
            else ReviewSubmitPage(session, top, canVerdict, top.reviewVerdict, onDiscard = { discard = true }) { top.reviewOpen = false }
        }
    }
}

/**
 * What Review changes holds, for its own sheet and for the page of it in the pull request's actions, where Approve and
 * Request changes open it on their [start] verdict (J29): the summary, the verdict and Submit, which is the check that the
 * verdict was meant. [onDone] closes what it is in once the review is sent.
 */
@Composable
fun ColumnScope.ReviewSubmitPage(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean, start: String, onDiscard: () -> Unit, onDone: () -> Unit) {
    ReviewSubmissionContent(session, top, start) { actions, draft ->
        val c = LocalPullsColors.current
        val busy = actions.busy
        val pending = session.reviewData?.pendingCount ?: 0
        val pendingId = session.reviewData?.pendingReviewId
        val verdicts = canVerdict && actions.canVerdict
        val sent = if (verdicts) draft.verdict else "COMMENT"
        val labels = mapOf("COMMENT" to t("review.comment"), "APPROVE" to t("review.approve"), "REQUEST_CHANGES" to t("review.requestChanges"))
        if (pending > 0) Hint(tc("review.includesPending", pending))
        SheetCard { SheetField(null, draft.body, { if (actions.canSubmit && !actions.busy) draft.body = it }, placeholder = t("review.summaryPlaceholder"), first = true, enabled = actions.canSubmit && !busy, minLines = 4) }
        // A closed or merged pull request only takes a plain comment; Approve and Request changes are for open ones.
        if (verdicts) SheetCard {
            VERDICTS.forEachIndexed { index, option ->
                SheetRow(labels.getValue(option), { if (actions.canVerdict && !actions.busy) draft.verdict = option }, first = index == 0, iconless = true) {
                    if (option == draft.verdict) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
                }
            }
        }
        actions.error?.let { Hint(it, c.error) }
        CtaButton(
            if (busy) "Sending…" else t("common.submit"),
            { actions.submit(sent, draft.body, pendingId) { draft.body = ""; draft.verdict = "COMMENT"; onDone() } },
            Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !busy && actions.canSubmit(sent, pendingId) && (sent == "APPROVE" || draft.body.isNotBlank() || pending > 0),
        )
        if (session.reviewData?.pendingReviewId != null) SheetCard {
            SheetRow(t("review.discard"), { if (!actions.busy && actions.canSubmit("COMMENT", pendingId)) onDiscard() }, first = true, iconless = true, danger = true)
        }
    }
}

/** Discard names the pending comments before the one button that removes them. */
@Composable
fun ColumnScope.ReviewDiscardPage(session: ReviewSession, top: ReviewTopUi, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val actions = rememberReviewMutationActions(session, top)
    val pendingId = remember(session, session.captureView()) { session.reviewData?.pendingReviewId }
    val pending = remember(session, session.captureView(), pendingId) { session.reviewData?.pendingCount ?: 0 }
    if (!actions.canKeepSubmission) return
    if (pendingId == null) {
        Text(t("review.noPendingReview"), Modifier.padding(top = PullsSpace.x2), style = PullsType.read, color = c.muted)
        return
    }
    Text(tc("review.confirmDiscard", pending), Modifier.padding(top = PullsSpace.x2), style = PullsType.read, color = c.muted)
    actions.error?.let { Hint(it, c.error) }
    if (session.reviewData?.pendingReviewId != pendingId) Hint(t("review.pendingReviewChanged"), c.error)
    CtaButton(t("review.discard"), { actions.discard(pendingId, onDone) }, Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !actions.busy && actions.canSubmit("COMMENT", pendingId), container = c.error)
}

/**
 * Merge, a page of the pull request's sheet behind Approve, merge or close (J29): the method, the commit
 * title and message prefilled, then Merge, which is the check that merging was meant. [onDone] closes what it is in.
 */
@Composable
fun ColumnScope.MergePage(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData, onDone: () -> Unit) {
    val overview = data.overview
    val actions = rememberReviewMutationActions(session, top, data)
    var method by rememberSaveable(session, session.captureView(), overview.id) { mutableStateOf(overview.merge.defaultMethod) }
    val defaults = data.defaultCommits[method] ?: ("" to "")
    var title by rememberSaveable(session, session.captureView(), overview.id) { mutableStateOf("") }
    var body by rememberSaveable(session, session.captureView(), overview.id) { mutableStateOf("") }
    LaunchedEffect(method) {
        title = defaults.first
        body = defaults.second
        top.actionError = null
    }
    val c = LocalPullsColors.current
    val busy = actions.busy
    // While it shows, a failed merge is said here rather than in a toast.
    DisposableEffect(top) {
        top.mergeOpen = true
        onDispose { top.mergeOpen = false }
    }
    Hint(t("overview.merge.into", "head" to overview.headRef, "base" to overview.baseRef))
    if (overview.merge.methods.size > 1) SheetCard {
        overview.merge.methods.forEachIndexed { index, option ->
            SheetRow(t("overview.merge.method.$option"), { if (actions.current && !actions.busy) method = option }, first = index == 0, iconless = true) {
                if (option == method) LuIcon(Lu.check, tint = c.accentTeal, size = 20.dp)
            }
        }
    }
    if (method != "rebase") SheetCard {
        SheetField(t("overview.merge.commitTitle"), title, { if (actions.current && !actions.busy) title = it }, first = true, enabled = !busy)
        SheetField(t("overview.merge.commitMessage"), body, { if (actions.current && !actions.busy) body = it }, enabled = !busy, minLines = 5)
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
            actions.pullRequest("merge", method, sendTitle, sendBody, after = onDone)
        },
        Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !busy && actions.canPrAction("merge", method) && (method == "rebase" || title.isNotBlank()),
    )
}
