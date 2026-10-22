package com.octaviantocan.pulls.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.octaviantocan.pulls.core.backWay
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlinx.coroutines.launch

/** A review's existing nested page motion consumes the root's single native edge gesture. */
@Composable
fun SharedPageBack(motion: PageMotion, enabled: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalSharedBackDispatcher.current
    val routeEligible = LocalBackConsumerEligible.current
    val scope = rememberCoroutineScope()
    val latestEnabled by rememberUpdatedState(enabled)
    val latestBack by rememberUpdatedState(onBack)
    val moving by rememberUpdatedState(rememberMotionEnabled())
    val way = backWay(LocalLayoutDirection.current == LayoutDirection.Rtl)
    DisposableEffect(dispatcher, motion, routeEligible, way) {
        val gesture = SharedPageBackGesture(motion, way, { latestEnabled && routeEligible() }, { moving }, { latestBack() }) {
            val held = motion.held
            if (held != null) motion.putBack = scope.launch {
                animate(motion.hold, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { value, _ -> motion.hold = value }
                if (motion.held == held) motion.held = null
            }
        }
        val consumer = dispatcher.register({ latestEnabled && routeEligible() }, gesture, gesture::commit)
        onDispose {
            dispatcher.remove(consumer)
            motion.putBack?.cancel()
            motion.putBack = null
            motion.held = null
            motion.hold = 0f
        }
    }
}

internal class SharedPageBackGesture(
    private val motion: PageMotion,
    private val way: Int,
    private val active: () -> Boolean,
    private val moving: () -> Boolean,
    private val onBack: () -> Unit,
    private val returnHold: () -> Unit,
) : SharedBackGesture {
    private var beganOn: Any? = null
    private var began = false

    override fun began() {
        motion.putBack?.cancel()
        motion.putBack = null
        beganOn = motion.onScreen
        began = true
        motion.held = beganOn
        motion.holdWay = way
    }

    override fun changed(progress: Float) {
        if (began && active() && moving() && beganOn == motion.onScreen) motion.hold = progress.coerceIn(0f, 1f)
    }

    override fun cancelled() {
        if (began) returnHold()
        began = false
        beganOn = null
    }

    fun commit() {
        if (!began || !active() || beganOn != motion.onScreen) { cancelled(); return }
        motion.next(way)
        onBack()
        began = false
        beganOn = null
    }
}
