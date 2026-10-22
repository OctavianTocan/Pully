package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.core.OpenTarget
import com.octaviantocan.pulls.sound.ControlFeedback
import com.octaviantocan.pulls.sound.Cue
import com.octaviantocan.pulls.sound.Emphasis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/** Existing thread writes keep their shared busy/retry behavior and their drawn owner and current host permission. */
internal class ReviewThreadActions(
    private val session: ReviewSession,
    scope: CoroutineScope,
    private val capabilities: () -> ReviewCapabilities,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    feedback: (Cue, Emphasis) -> Unit = { cue, emphasis -> ControlFeedback.play(cue, emphasis) },
) {
    private val owner = session.captureView()
    private var closed = false
    val canKeepDraft: Boolean get() = !closed && readiness.installedReview(capabilities()).canWriteComments && session.canWrite && session.pull != null &&
        owner?.let(session::isCurrentView) == true
    val canWrite: Boolean get() = canKeepDraft && readiness.review(capabilities()).canWriteComments
    private val actions = ThreadActions(scope, { if (canWrite) session.reloadReviews() }, { cue, emphasis -> if (canWrite) feedback(cue, emphasis) })
    val busy: Boolean get() = actions.busy
    val error: String? get() = actions.error
    fun close() { closed = true }
    private fun requireOwner() { if (!canKeepDraft) throw CancellationException("The drawn review thread has retired.") }

    fun run(action: suspend (OpenTarget.Pull) -> Unit, after: () -> Unit = {}) {
        if (!canWrite) return
        val pull = session.pull ?: return
        actions.run({
            if (!canWrite) throw CancellationException("The drawn review thread is unavailable.")
            try {
                action(pull)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                requireOwner()
                throw error
            }
            requireOwner()
        }) { if (canKeepDraft) after() }
    }
}

@Composable
internal fun rememberReviewThreadActions(session: ReviewSession): ReviewThreadActions {
    val scope = rememberCoroutineScope()
    val owner = session.captureView()
    val capabilities by rememberUpdatedState(LocalReviewCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val actions = remember(session, owner, scope, readiness) { ReviewThreadActions(session, scope, { capabilities }, readiness) }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
