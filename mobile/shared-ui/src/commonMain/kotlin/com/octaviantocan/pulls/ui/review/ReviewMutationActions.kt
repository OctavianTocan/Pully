package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.data.ExpectedPendingReview
import kotlinx.coroutines.CancellationException
import com.octaviantocan.pulls.ui.design.Toaster

/** The current server facts, using the same stale-head and lifecycle policy as the review frame. */
internal fun reviewOverview(session: ReviewSession): PrOverviewData? {
    val observed = session.prOpened?.let { opened -> normalizedPrState(opened.state).takeIf { !opened.stale || it == "merged" } }
    return parsePrOverviewData(session.overview, !session.overviewStored, session.prOpened?.headSha, observed)
}

/** Confirmed writes belong to the exact review which drew the confirmation, independently of server permissions. */
internal class ReviewMutationActions(
    private val session: ReviewSession,
    private val top: ReviewTopUi,
    drawn: PrOverviewData?,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val capabilities: () -> ReviewCapabilities,
) {
    private val view = session.captureView()
    private val id = drawn?.overview?.id
    private val head = drawn?.overview?.headOid
    private var closed = false
    val current: Boolean get() = !closed && view?.let(session::isCurrentView) == true
    val busy: Boolean get() = top.busy != null
    val error: String? get() = top.actionError
    fun close() { closed = true }

    fun read(action: () -> Unit) { if (current && readiness.eligible) action() }

    private fun freshOverview(): PrOverviewData? = reviewOverview(session)?.takeIf {
        current && !session.overviewStored && session.prOpened?.stale != true &&
            it.overview.id == id && it.overview.headOid == head && head == session.prOpened?.headSha
    }

    val canKeepSubmission: Boolean get() = current && readiness.installedReview(capabilities()).canSubmitReview && session.canWrite && session.pull != null &&
        session.prOpened?.stale != true && !isStale(session, reviewOverview(session)?.overview) && current
    val canSubmit: Boolean get() = canKeepSubmission && readiness.review(capabilities()).canSubmitReview
    val canVerdict: Boolean get() {
        val state = reviewOverview(session)?.overview?.state ?: normalizedPrState(session.prOpened?.state.orEmpty())
        return canSubmit && state in setOf("open", "draft")
    }
    private val ownsEditReply: Boolean get() = readiness.installedReview(capabilities()).canEditPullRequest && freshOverview() != null
    private val ownsTextReply: Boolean get() = ownsEditReply && freshOverview()?.overview?.viewer?.canEditText == true
    val canEditText: Boolean get() = readiness.review(capabilities()).canEditPullRequest && ownsTextReply
    val canEditPullRequest: Boolean get() = readiness.review(capabilities()).canEditPullRequest && freshOverview() != null

    fun canSubmit(verdict: String, pending: Long?): Boolean = canSubmit &&
        verdict in setOf("COMMENT", "APPROVE", "REQUEST_CHANGES") &&
        (verdict == "COMMENT" || canVerdict) && session.reviewData?.pendingReviewId == pending

    fun canPrAction(action: String, method: String? = null): Boolean {
        if (!readiness.review(capabilities()).canEditPullRequest) return false
        val data = freshOverview() ?: return false
        return permits(data, action, method)
    }

    private fun permits(data: PrOverviewData, action: String, method: String? = null): Boolean {
        val overview = data.overview
        return when (action) {
            "merge" -> overview.isOpen && data.mergeability.allowed && data.mergeability.ready && method in overview.merge.methods
            "auto-merge" -> overview.isOpen && data.mergeability.allowed && overview.viewer.canEnableAutoMerge &&
                data.mergeability.blocker in setOf("blocked", "checking") && method in overview.merge.methods
            "cancel-auto-merge" -> overview.isOpen && overview.autoMergeMethod != null && overview.viewer.canDisableAutoMerge
            "update-branch" -> overview.isOpen && overview.viewer.canUpdateBranch
            "edit" -> overview.viewer.canEditText
            else -> stateActions(overview).any { it.first == action }
        }
    }

    private fun requireOwner(ready: Boolean) {
        if (!current || !ready) throw CancellationException("The drawn review confirmation has retired.")
    }

    private suspend fun guarded(before: () -> Boolean, after: () -> Boolean = before, action: suspend () -> Unit) {
        requireOwner(before())
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            requireOwner(after())
            throw failure
        }
        requireOwner(after())
    }

    fun submit(verdict: String, body: String, pending: Long?, after: () -> Unit): Boolean {
        if (!canSubmit(verdict, pending) || busy) return false
        val pull = session.pull ?: return false
        val drawnHead = session.prOpened?.headSha ?: return false
        top.run(session.scope, "submit-review", { if (canKeepSubmission) after() }, session::reloadReviews) {
            guarded({ canSubmit(verdict, pending) }, { canKeepSubmission }) {
                session.repo.submitReview(pull, verdict, body, headSha = drawnHead, pending = ExpectedPendingReview(pending))
            }
        }
        return true
    }

    fun discard(pending: Long?, after: () -> Unit): Boolean {
        if (pending == null || !canSubmit("COMMENT", pending) || busy) return false
        val pull = session.pull ?: return false
        top.run(session.scope, "discard-review", { if (canKeepSubmission) after() }, session::reloadReviews) {
            guarded({ canSubmit("COMMENT", pending) }, { canKeepSubmission }) { session.repo.discardReview(pull, ExpectedPendingReview(pending)) }
        }
        return true
    }

    fun pullRequest(action: String, method: String? = null, title: String? = null, body: String? = null, after: () -> Unit = {}): Boolean {
        if (busy || !canPrAction(action, method)) return false
        val pullId = id ?: return false
        top.run(session.scope, action, { if (ownsEditReply) after() }, session::refresh) {
            // A successful state transition may itself remove that action. Its owner and host must still be current.
            guarded({ canPrAction(action, method) }, { ownsEditReply }) {
                session.repo.prAction(pullId, action, method, title, body, expectedHeadOid = head)
            }
        }
        return true
    }

    /** A just-confirmed state change can beat its background refresh. Read current abilities before its explicit Undo. */
    private fun undo(action: String): Boolean {
        if (busy || !current || !readiness.review(capabilities()).canEditPullRequest) return false
        val pull = session.pull ?: return false
        val pullId = id ?: return false
        top.run(session.scope, action, refresh = session::refresh) {
            guarded({ readiness.review(capabilities()).canEditPullRequest }, { ownsEditReply }) {
                val fresh = session.repo.overview(pull)
                requireOwner(readiness.review(capabilities()).canEditPullRequest)
                val data = parsePrOverviewData(fresh, expectedHead = session.prOpened?.headSha)
                check(data != null && data.overview.id == pullId && data.overview.headOid == head && permits(data, action)) {
                    "The pull request changed. Reopen its actions before trying again."
                }
                session.repo.prAction(pullId, action, expectedHeadOid = head)
            }
        }
        return true
    }

    suspend fun edit(title: String? = null, body: String? = null) {
        guarded({ canEditText }, { ownsTextReply }) {
            session.repo.prAction(id ?: throw CancellationException("The pull request is unavailable."), "edit", title = title, body = body, expectedHeadOid = head)
        }
        session.reloadOverview()
    }

    /** The toast owns this callback after its confirmation page goes away. A replaced toast has no authority. */
    fun showUndo(toaster: Toaster, message: String, inverse: String) {
        if (!current) return
        val undo = ReviewMutationActions(session, top, reviewOverview(session), readiness) { top.reviewCapabilities }
        var shown: com.octaviantocan.pulls.ui.design.ToastMessage? = null
        toaster.show(message, "Undo") {
            if (shown != null && toaster.message === shown && undo.undo(inverse)) toaster.clear(shown!!)
        }
        shown = toaster.message
    }
}

@Composable
internal fun rememberReviewMutationActions(session: ReviewSession, top: ReviewTopUi, data: PrOverviewData? = reviewOverview(session)): ReviewMutationActions {
    val capabilities by rememberUpdatedState(LocalReviewCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val actions = remember(session, top, session.captureView(), data?.overview?.id, data?.overview?.headOid, readiness) {
        ReviewMutationActions(session, top, data, readiness) { capabilities }
    }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
