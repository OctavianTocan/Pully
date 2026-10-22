package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.data.ReviewFile

/** Overview and file sheets share the same explicit viewer-progress workflow boundary as Diff. */
internal class ReviewProgressActions(
    private val session: ReviewSession,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val policy: () -> ReviewCapabilities,
) {
    private val view = session.captureView()
    private var closed = false
    val enabled: Boolean get() = !closed && view != null && session.isCurrentView(view) && readiness.review(policy()).canMarkReviewed

    fun mark(file: ReviewFile, reviewed: Boolean): Boolean {
        if (!enabled || session.files.none { it === file }) return false
        session.setReviewed(listOf(file.sha), reviewed)
        return true
    }

    fun close() { closed = true }
}

@Composable
internal fun rememberReviewProgressActions(session: ReviewSession): ReviewProgressActions {
    val policy by rememberUpdatedState(LocalReviewCapabilities.current)
    val view = session.captureView()
    val readiness = LocalReviewActionReadiness.current
    val actions = remember(session, view, readiness) { ReviewProgressActions(session, readiness) { policy } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
