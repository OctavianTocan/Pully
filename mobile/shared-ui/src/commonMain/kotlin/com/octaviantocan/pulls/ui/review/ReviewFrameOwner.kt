package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities

/** Retained frame controls belong to the opening/full view that drew them, including before the diff arrives. */
internal class ReviewFrameOwner(
    private val session: ReviewSession,
    private val sourceCurrent: () -> Boolean,
    private val reviewPolicy: () -> ReviewCapabilities,
    private val aiPolicy: () -> AiCapabilities,
    private val tourInstalled: Boolean,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
) {
    private val view = session.captureView()
    private var closed = false
    val current: Boolean get() = !closed && sourceCurrent() && session.isCurrentFrame(view)
    val canReadDiff: Boolean get() = current && readiness.review(reviewPolicy()).canReadDiff
    val canReadTour: Boolean get() = current && tourInstalled && readiness.ai(aiPolicy()).canReadResults
    val canAsk: Boolean get() = current && readiness.ai(aiPolicy()).canAsk
    val canCritique: Boolean get() = current && view != null && readiness.ai(aiPolicy()).canCritique

    /** A host action can synchronously retire the view; its feedback must not escape that retirement. */
    fun critique(lens: String?, start: (String?) -> Unit, feedback: (alreadyRunning: Boolean) -> Unit): Boolean {
        if (!canCritique) return false
        val running = session.taskFor("critique")?.running == true
        if (!running) start(lens)
        if (canCritique) feedback(running)
        return true
    }

    fun close() { closed = true }
}

@Composable
internal fun rememberReviewFrameOwner(session: ReviewSession, currentSource: () -> Boolean, tourInstalled: Boolean): ReviewFrameOwner {
    val sourceCurrent by rememberUpdatedState(currentSource)
    val reviewPolicy by rememberUpdatedState(LocalReviewCapabilities.current)
    val aiPolicy by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val view = session.captureView()
    val owner = remember(session, view, tourInstalled, readiness) {
        ReviewFrameOwner(session, { sourceCurrent() }, { reviewPolicy }, { aiPolicy }, tourInstalled, readiness)
    }
    DisposableEffect(owner) { onDispose { owner.close() } }
    return owner
}
