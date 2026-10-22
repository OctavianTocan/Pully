package com.octaviantocan.pulls.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.data.Brief
import com.octaviantocan.pulls.ui.review.LocalReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewActionReadiness
import com.octaviantocan.pulls.ui.review.ReviewSession
import kotlinx.serialization.json.put

/** The same explicit rewrite action for both hosts; observation never starts a new brief. */
fun startBrief(session: ReviewSession, capabilities: AiCapabilities) {
    if (!capabilities.canBrief || session.captureView() == null || session.taskFor("brief")?.running == true) return
    session.startTask("brief", { put("locale", session.settings.locale) })
}

/** A retained rewrite must still describe the exact result and full view which drew its button. */
internal class BriefRewriteActions(
    private val session: ReviewSession,
    private val brief: Brief,
    private val readiness: ReviewActionReadiness = ReviewActionReadiness.Default,
    private val capabilities: () -> AiCapabilities,
) {
    private val view = session.captureView()
    private var closed = false
    val canRewrite: Boolean get() = !closed && readiness.ai(capabilities()).canBrief &&
        view?.let(session::isCurrentView) == true && session.saved?.brief == brief

    fun rewrite(action: () -> Unit) { if (canRewrite) action() }
    fun close() { closed = true }
}

@Composable
internal fun rememberBriefRewriteActions(session: ReviewSession, brief: Brief): BriefRewriteActions {
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val actions = remember(session, session.captureView(), brief, readiness) { BriefRewriteActions(session, brief, readiness) { capabilities } }
    DisposableEffect(actions) { onDispose { actions.close() } }
    return actions
}
