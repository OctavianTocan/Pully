package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.core.BACKWARD
import com.octaviantocan.pulls.core.FORWARD
import com.octaviantocan.pulls.core.pageWay
import kotlinx.coroutines.Job

/**
 * Swaps between screens of one page, such as the Inbox and Tours tabs: the leaving content fades
 * out blurring and slides 8dp away, the arriving content fades in sharpening from 8dp the other
 * side (`--p8-*`, 250ms smooth-out). [order] ranks the screens left to right, so a screen further
 * right comes in from the right and one further left from the left: the pages move the way the
 * person went (rule 105). A change [order] cannot know the way of, Back and a back swipe, comes
 * through [motion], which also holds a page under a back swipe (see [PageBack]). A centred title passes
 * [contentAlignment] `Center`, so the one leaving does not ride the edge of a container that shrinks around it.
 * [reach] above 0 makes the pages travel that share of their width instead of 8dp, and [slow] takes 350ms in and 250ms
 * out instead of 250 and 150: a sheet's pages move far and slow enough to see the way they went (H1).
 */
@Composable
fun <S> BlurSwitch(
    target: S,
    modifier: Modifier = Modifier,
    order: (S) -> Int,
    motion: PageMotion? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    reach: Float = 0f,
    slow: Boolean = false,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val step = with(LocalDensity.current) { PullsMotion.SlideDistance.roundToPx() }
    val travel = { width: Int -> if (reach > 0f) (width * reach).toInt() else step }
    val inMs = if (slow) PullsMotion.MEDIUM else PullsMotion.FAST
    val outMs = if (slow) PullsMotion.FAST else PullsMotion.QUICK
    val moving = rememberMotionEnabled()
    val change = remember { WayOfChange(target) }
    change.update(target, order, motion)
    val pages = updateTransition(target, label = "blur-switch")
    if (motion != null) {
        SideEffect { motion.onScreen = target }
        // The page a back swipe held keeps where the finger left it until it has gone; then the hold lets go.
        val settled = pages.currentState
        LaunchedEffect(settled) { if (settled == pages.targetState) motion.letGo(settled) }
    }
    pages.AnimatedContent(
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            if (!moving) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            val way = change.way
            ((fadeIn(tween(inMs, easing = PullsMotion.SmoothOut)) + slideInHorizontally(tween(inMs, easing = PullsMotion.SmoothOut)) { way * travel(it) }) togetherWith
                (fadeOut(tween(outMs, easing = PullsMotion.SmoothOut)) + slideOutHorizontally(tween(outMs, easing = PullsMotion.SmoothOut)) { -way * travel(it) }))
                // A screen of another height resizes the container on the same curve, so a sheet grows or shrinks with its page.
                .using(SizeTransform(clip = true) { _, _ -> tween(inMs, easing = PullsMotion.SmoothOut) })
        },
    ) { state ->
        val blur by transition.animateDp(transitionSpec = { tween(inMs, easing = PullsMotion.SmoothOut) }, label = "blur") {
            if (it == EnterExitState.Visible) 0.dp else PullsMotion.BlurMedium
        }
        Box(Modifier.then(if (motion != null) Modifier.heldBy(motion, state) else Modifier).softBlur(blur)) { content(state) }
    }
}

/** The way of the change in flight, fixed once when the target changes, so the leaving and arriving pages agree. */
class WayOfChange<S>(private var from: S) {
    var way = FORWARD
        private set

    fun update(to: S, order: (S) -> Int, motion: PageMotion?) {
        if (to == from) return
        way = motion?.take() ?: pageWay(order(from), order(to))
        from = to
    }
}

/**
 * What moved a [BlurSwitch] last, when its order cannot tell (rule 105). Back always goes backward, and a back swipe
 * moves the page with the finger while it is held and then carries on the way the finger went, so a swipe from the
 * right edge sends the pages left. One [PageMotion] drives one [BlurSwitch].
 */
@Stable
class PageMotion {
    private var next: Int? = null

    /** The next change goes [way] ([FORWARD] or [BACKWARD]) whatever the order says. Call it just before the change. */
    fun next(way: Int) {
        next = way
    }

    internal fun take(): Int? = next.also { next = null }

    /** The page the switch shows, which is the one a back swipe takes hold of. */
    internal var onScreen: Any? = null

    /** The page under a back swipe: it keeps the swipe's offset while it leaves, and nothing else does. */
    internal var held: Any? by mutableStateOf(null)

    /** A given-up swipe putting its page back, which the next swipe stops so the two do not fight over the hold. */
    internal var putBack: Job? = null

    /** How far the back swipe has gone, 0 to 1, and which way it moves the page, with the finger. */
    internal var hold by mutableFloatStateOf(0f)
    internal var holdWay by mutableIntStateOf(BACKWARD)

    /** The page that held the swipe has gone (or the switch settled elsewhere): drop the hold. */
    internal fun letGo(settled: Any?) {
        if (held != null && held != settled) {
            held = null
            hold = 0f
        }
    }
}

/** A page under a back swipe follows the finger up to 96dp and fades to 40% on the way, like the rows of a tab swipe (rule 88). */
private fun Modifier.heldBy(motion: PageMotion, state: Any?): Modifier = graphicsLayer {
    // Read in the draw layer, all of them every time, so the layer redraws when any changes.
    val p = motion.hold
    if (state != motion.held) return@graphicsLayer
    translationX = -motion.holdWay * p * PullsMotion.FingerRange.toPx()
    alpha = 1f - 0.6f * p
}
