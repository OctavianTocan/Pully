package com.octaviantocan.pulls.ui.design

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.core.backWay
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * Back for pages drawn by a [BlurSwitch] with [motion] (rule 105). The Back button goes backward. A back swipe moves
 * the page with the finger while it is held, and when it lets go the pages carry on that way: right after a swipe from
 * the left edge, left after one from the right. A swipe given up puts the page back.
 */
@Composable
fun PageBack(motion: PageMotion, enabled: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onBack)
    val moving = rememberMotionEnabled()
    PredictiveBackHandler(enabled) { events ->
        motion.putBack?.cancel()
        motion.held = motion.onScreen
        var way = BACKWARD
        try {
            events.collect { event ->
                way = backWay(fromRightEdge = event.swipeEdge == BackEventCompat.EDGE_RIGHT)
                motion.holdWay = way
                if (moving) motion.hold = event.progress
            }
        } catch (e: CancellationException) {
            val held = motion.held
            motion.putBack = scope.launch {
                animate(motion.hold, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { v, _ -> motion.hold = v }
                if (motion.held == held) motion.held = null
            }
            throw e
        }
        motion.next(way)
        latest()
    }
}
