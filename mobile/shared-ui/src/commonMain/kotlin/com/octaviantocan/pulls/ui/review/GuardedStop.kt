package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.ui.ai.AiCapabilities
import com.octaviantocan.pulls.ui.ai.LocalAiCapabilities

/** How long after a run starts the orb ignores a tap, so the tap that started it can never also stop it (rule 63). */
private const val STOP_GRACE_MS = 700L

private class StopLifetime { var active = true }

private fun AiCapabilities.canStop(kinds: List<String>): Boolean = kinds.isNotEmpty() && kinds.all {
    when (it) {
        "analyze" -> canAnalyze
        "critique" -> canCritique
        "tour" -> canTour
        "brief" -> canBrief
        "ask", "chat" -> canAsk
        else -> false
    }
}

/**
 * [stop] for an orb that is the Stop button: null while nothing runs, and while something does it ignores a tap in the
 * first moments after [session] started a task of one of [kinds], the work this orb stops, so the tap that started the
 * work can never also stop it (rule 63). The moments count from that start itself, so coming back to work that is
 * already running stops it at the first tap, and starting some other task does not hold this Stop back.
 */
@Composable
fun guardedStop(session: ReviewSession, kinds: List<String>, running: Boolean, stop: () -> Unit): (() -> Unit)? {
    val latest by rememberUpdatedState(stop)
    val enabled by rememberUpdatedState(running)
    val capabilities by rememberUpdatedState(LocalAiCapabilities.current)
    val readiness = LocalReviewActionReadiness.current
    val view = session.captureView()
    val lifetime = remember(session, view, kinds, readiness) { StopLifetime() }
    DisposableEffect(lifetime) { onDispose { lifetime.active = false } }
    return if (running && readiness.ai(capabilities).canStop(kinds)) ({
        if (lifetime.active && enabled && session.isCurrentFrame(view) && readiness.ai(capabilities).canStop(kinds) &&
            session.millisSinceLastStart(kinds) > STOP_GRACE_MS) latest()
    }) else null
}
