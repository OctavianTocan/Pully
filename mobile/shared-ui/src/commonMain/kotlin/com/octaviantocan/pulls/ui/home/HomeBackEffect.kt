package com.octaviantocan.pulls.ui.home

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.octaviantocan.pulls.ui.SharedBackConsumer
import com.octaviantocan.pulls.ui.SharedBackDispatcher
import com.octaviantocan.pulls.core.backWay
import com.octaviantocan.pulls.ui.design.PageMotion
import com.octaviantocan.pulls.ui.design.PullsMotion
import com.octaviantocan.pulls.ui.design.rememberMotionEnabled
import kotlinx.coroutines.launch

/** Native gesture recognition moves the existing shared page; commit alone pops the same shared route stack. */
@Composable
fun HomeBackEffect(native: NativeHomeActions, motion: PageMotion, backs: SharedBackDispatcher, enabled: Boolean, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val active by rememberUpdatedState(enabled)
    val latest by rememberUpdatedState(onBack)
    val moving by rememberUpdatedState(rememberMotionEnabled())
    val rightEdge = LocalLayoutDirection.current == LayoutDirection.Rtl
    DisposableEffect(native, motion, rightEdge, backs) {
        val observer = homeBackObserver(motion, backs, rightEdge, { active }, { moving }, { latest() }) {
            val held = motion.held
            if (held != null) motion.putBack = scope.launch {
                animate(motion.hold, 0f, animationSpec = tween(PullsMotion.QUICK, easing = PullsMotion.SmoothOut)) { value, _ -> motion.hold = value }
                if (motion.held == held) motion.held = null
            }
        }
        val subscription = native.observeBack(rightEdge, observer)
        onDispose { subscription.cancel(); motion.putBack?.cancel(); motion.held = null; motion.hold = 0f }
    }
}

internal fun homeBackObserver(
    motion: PageMotion,
    backs: SharedBackDispatcher,
    rightEdge: Boolean,
    active: () -> Boolean,
    moving: () -> Boolean,
    onBack: () -> Unit,
    returnHold: () -> Unit,
): HomeBackObserver = object : HomeBackObserver {
    private var beganOn: Any? = null
    private var consumer: SharedBackConsumer? = null
    private val way = backWay(fromRightEdge = rightEdge)
    override fun enabled() = active() || backs.canConsume
    override fun began() {
        motion.putBack?.cancel()
        motion.putBack = null
        beganOn = motion.onScreen
        consumer = backs.current()
        if (consumer == null) {
            motion.held = beganOn
            motion.holdWay = way
        } else {
            // An edit cannot inherit a route swipe whose return it just interrupted.
            motion.held = null
            motion.hold = 0f
        }
    }
    override fun changed(progress: Float) { if (consumer == null && moving()) motion.hold = progress.coerceIn(0f, 1f) }
    override fun cancelled() {
        returnHold()
        beganOn = null
        consumer = null
    }
    override fun committed() {
        if (beganOn != motion.onScreen) { cancelled(); return }
        val captured = consumer
        if (captured != null) {
            // A removed/replaced editor cannot turn its gesture into a route pop.
            backs.consume(captured)
            beganOn = null
            consumer = null
            return
        }
        if (!active() || backs.canConsume) { cancelled(); return }
        motion.next(way)
        onBack()
        beganOn = null
    }
}
