package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.data.Critique
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.LocalReviewCapabilities
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewCapabilities
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Selection, retries and finding publication remain attached to the actual result and full review revision. */
internal class CritiqueActions(
    private val session: ReviewSession,
    private val result: Critique?,
    private val ai: () -> AiCapabilities,
    private val review: () -> ReviewCapabilities,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
) {
    private val owner = session.captureView()
    private val identity = result?.let(::critiqueIdentity)
    private val ui = aiUi(session)
    private var closed = false
    private val ownsResult: Boolean get() = owner?.let(session::isCurrentView) == true &&
        identity == session.saved?.critique?.let(::critiqueIdentity)
    val current: Boolean get() = !closed && readiness.ai(ai()).canReadResults && ownsResult
    val canPublish: Boolean get() = current && readiness.review(review()).canWriteComments && readiness.review(review()).canSubmitReview && session.canWrite &&
        session.pull != null && result?.variant != null && result.revision != null && result.headSha == session.prOpened?.headSha
    val canRetry: Boolean get() = current && readiness.ai(ai()).canCritique
    private val ownsPublicationReply: Boolean get() = !closed && ownsResult && readiness.installedAi(ai()).canReadResults &&
        readiness.installedReview(review()).canWriteComments && readiness.installedReview(review()).canSubmitReview && session.canWrite && ownsResult

    fun close() { closed = true }
    fun read(action: () -> Unit) { if (current) action() }
    fun retry(action: () -> Unit) { if (canRetry) action() }

    private fun requireCurrent() {
        if (!canPublish || ui.critiqueFor != identity) throw CancellationException("The drawn findings have retired.")
    }

    fun post(ids: List<String>) {
        val critique = result ?: return
        if (!canPublish || ui.critiqueFor != identity || ui.posting || ids.isEmpty() ||
            ids.any { id -> critique.findings.none { it.id == id } || id in ui.posted }) return
        val key = owner?.key ?: return
        val body = ui.postBody
        ui.posting = true
        ui.critiquePostOwner = this
        ui.postError = null
        session.scope.launch {
            try {
                requireCurrent()
                val posted = session.repo.postCritique(key, critique, ids, body)
                currentCoroutineContext().ensureActive()
                if (!ownsResult || ui.critiqueFor != identity) return@launch
                // A confirmed server receipt belongs to this result even if its sheet has closed.
                ui.posted = ui.posted + posted
                if (ownsPublicationReply) {
                    ui.selected = ui.selected - posted.toSet()
                    if (canPublish) session.reloadReviews()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (ownsPublicationReply && ui.critiqueFor == identity) ui.postError = failure.message ?: failure.toString()
            } finally {
                // Release only this request's lock, including dismissal. A newer result owns its own publication.
                if (ui.critiquePostOwner === this@CritiqueActions) {
                    ui.critiquePostOwner = null
                    ui.posting = false
                }
            }
        }
    }
}

@Composable
internal fun rememberCritiqueActions(session: ReviewSession): CritiqueActions {
    val ai by rememberUpdatedState(LocalAiCapabilities.current)
    val review by rememberUpdatedState(LocalReviewCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val result = session.saved?.critique
    val actions = remember(session, session.captureView(), result?.let(::critiqueIdentity), readiness) { CritiqueActions(session, result, { ai }, { review }, readiness) }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
