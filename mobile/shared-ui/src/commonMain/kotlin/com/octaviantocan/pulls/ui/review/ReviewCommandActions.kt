package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.data.ReviewFile
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities

/** Palette rows capture the same full view and exact current file as the on-page controls. */
internal class ReviewCommandActions(
    private val session: ReviewSession,
    private val review: () -> ReviewCapabilities,
    private val aiPolicy: () -> AiCapabilities,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
) {
    private val view = session.captureView()
    private var closed = false
    val current: Boolean get() = !closed && view?.let(session::isCurrentView) == true
    val canDiff: Boolean get() = current && readiness.review(review()).canReadDiff
    val canMarkReviewed: Boolean get() = canDiff && readiness.review(review()).canMarkReviewed
    val canReadAi: Boolean get() = current && readiness.ai(aiPolicy()).canReadResults
    val canAnalyze: Boolean get() = current && readiness.ai(aiPolicy()).canAnalyze
    val canAsk: Boolean get() = current && readiness.ai(aiPolicy()).canAsk
    val canSubmit: Boolean get() = current && readiness.review(review()).canSubmitReview && session.canWrite && session.pull != null &&
        session.prOpened?.stale != true && !isStale(session, reviewOverview(session)?.overview)
    val canVerdict: Boolean get() = canSubmit && reviewOverview(session)?.overview?.isOpen == true
    fun close() { closed = true }
    fun diff(action: () -> Unit) { if (canDiff) action() }
    fun ai(action: () -> Unit) { if (canReadAi) action() }
    fun analyze(action: () -> Unit) { if (canAnalyze) action() }
    fun ask(action: () -> Unit) { if (canAsk) action() }
    fun submit(verdict: Boolean, action: () -> Unit) { if (if (verdict) canVerdict else canSubmit) action() }
    fun file(file: ReviewFile, action: () -> Unit) {
        if (canDiff && session.files.any { it === file }) action()
    }
    fun toggleReviewed(file: ReviewFile) = this.file(file) {
        if (readiness.review(review()).canMarkReviewed) session.setReviewed(listOf(file.sha), file.sha !in session.reviewed)
    }
}

@Composable
internal fun rememberReviewCommandActions(session: ReviewSession): ReviewCommandActions {
    val review by rememberUpdatedState(LocalReviewCapabilities.current)
    val ai by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val actions = remember(session, session.captureView(), readiness) { ReviewCommandActions(session, { review }, { ai }, readiness) }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
