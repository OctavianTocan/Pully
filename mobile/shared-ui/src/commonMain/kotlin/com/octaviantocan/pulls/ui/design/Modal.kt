package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog window that opens in 250ms from 96% size and leaves in 150ms (`modal` on transitions.dev,
 * rule 73). The window is kept until the exit has played, so [visible] going false does not cut it.
 */
@Composable
fun ModalReveal(visible: Boolean, onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false), content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible
    // Not yet idle covers a close during the entrance, when currentState is still false but the window is up.
    if (!visible && !state.currentState && state.isIdle) return
    ModalWindow(onDismissRequest, properties) { ModalSurface(state, motion, content) }
}

/** What [ModalReveal] draws inside its window: the 250ms grow-in and 150ms leave. */
@Composable
internal fun ModalSurface(state: MutableTransitionState<Boolean>, motion: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        state,
        enter = if (!motion) EnterTransition.None else fadeIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut)) + scaleIn(tween(PullsMotion.FAST, easing = PullsMotion.SmoothOut), PullsMotion.MODAL_SCALE),
        exit = if (!motion) ExitTransition.None else fadeOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) + scaleOut(tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut), PullsMotion.MODAL_SCALE),
    ) { content() }
}

@Composable
internal expect fun ModalWindow(onDismissRequest: () -> Unit, properties: DialogProperties, content: @Composable () -> Unit)
