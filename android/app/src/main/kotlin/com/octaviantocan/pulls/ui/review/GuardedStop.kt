package com.octaviantocan.pulls.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** How long after a run starts the orb ignores a tap, so the tap that started it can never also stop it (rule 63). */
private const val STOP_GRACE_MS = 700L

/**
 * [stop] for an orb that is the Stop button: null while nothing runs, and while something does it ignores a tap in the
 * first moments after [session] started a task of one of [kinds], the work this orb stops, so the tap that started the
 * work can never also stop it (rule 63). The moments count from that start itself, so coming back to work that is
 * already running stops it at the first tap, and starting some other task does not hold this Stop back.
 */
@Composable
internal fun guardedStop(session: ReviewSession, kinds: List<String>, running: Boolean, stop: () -> Unit): (() -> Unit)? {
    val latest by rememberUpdatedState(stop)
    return if (running) ({ if ((System.nanoTime() - session.lastStart(kinds)) / 1_000_000 > STOP_GRACE_MS) latest() }) else null
}
