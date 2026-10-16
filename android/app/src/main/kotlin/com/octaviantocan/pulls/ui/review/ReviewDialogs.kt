package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import com.octaviantocan.pulls.sound.Sounds
import com.octaviantocan.pulls.ui.design.CtaButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.Lu
import com.octaviantocan.pulls.ui.design.LuIcon
import com.octaviantocan.pulls.ui.design.PullsSheet
import com.octaviantocan.pulls.ui.design.PullsType
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

private val VERDICTS = listOf("COMMENT", "APPROVE", "REQUEST_CHANGES")

/** Review changes: the pending note, a summary, the verdict, Discard review and Submit. */
@Composable
fun ReviewSubmitSheet(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean = true) {
    if (!top.reviewOpen) return
    PullsSheet({ top.reviewOpen = false }, t("review.reviewChanges"), expandable = true) {
        ReviewSubmitPage(session, top, canVerdict, top.reviewVerdict) { top.reviewOpen = false }
    }
}

/**
 * What Review changes holds, for its own sheet and for the page of it in the pull request's actions, where Approve and
 * Request changes open it on their [start] verdict (J29): the summary, the verdict and Submit, which is the check that the
 * verdict was meant. [onDone] closes what it is in once the review is sent.
 */
@Composable
internal fun ColumnScope.ReviewSubmitPage(session: ReviewSession, top: ReviewTopUi, canVerdict: Boolean, start: String, onDone: () -> Unit) {
    val c = LocalPullsColors.current
    val pull = session.pull ?: return
    val scope = rememberCoroutineScope()
    var body by rememberSaveable { mutableStateOf("") }
    var verdict by rememberSaveable { mutableStateOf(start) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pending = session.reviewData?.pendingCount ?: 0
    val sent = if (canVerdict) verdict else "COMMENT"
    val labels = mapOf("COMMENT" to t("review.comment"), "APPROVE" to t("review.approve"), "REQUEST_CHANGES" to t("review.requestChanges"))
    fun run(action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { action() }
                .onSuccess { body = ""; verdict = "COMMENT"; onDone(); session.reloadReviews(); Sounds.play(Cue.Success) }
                .onFailure { error = it.message ?: it.toString(); Sounds.play(Cue.Error, Emphasis.Subtle) }
            busy = false
        }
    }
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

/**
 * Merge, a page of the pull request's sheet behind Approve, merge or close (J29): the method, the commit
 * title and message prefilled, then Merge, which is the check that merging was meant. [onDone] closes what it is in.
 */
@Composable
internal fun ColumnScope.MergePage(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData, onDone: () -> Unit) {
    val overview = data.overview
    var method by rememberSaveable(overview.id) { mutableStateOf(overview.merge.defaultMethod) }
    val defaults = data.defaultCommits[method] ?: ("" to "")
    var title by rememberSaveable(overview.id) { mutableStateOf("") }
    var body by rememberSaveable(overview.id) { mutableStateOf("") }
    LaunchedEffect(method) {
        title = defaults.first
        body = defaults.second
        top.actionError = null
    }
    val c = LocalPullsColors.current
    val busy = top.busy == "merge"
    // While it shows, a failed merge is said here rather than in a toast.
    DisposableEffect(top) {
        top.mergeOpen = true
        onDispose { top.mergeOpen = false }
    }
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
            top.run(session, "merge", onOk = onDone) {
                session.repo.prAction(overview.id, "merge", method = method, title = sendTitle, body = sendBody, expectedHeadOid = overview.headOid)
            }
        },
        Modifier.fillMaxWidth().padding(top = 20.dp), enabled = !busy && data.mergeability.ready && (method == "rebase" || title.isNotBlank()),
    )
}
