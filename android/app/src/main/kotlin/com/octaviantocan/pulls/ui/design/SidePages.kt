package com.octaviantocan.pulls.ui.design

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.octaviantocan.pulls.sound.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Where a row of side-by-side pages sits, in pages: 0 is the first page exactly, 0.5 halfway to the second. */
@Stable
private class SideState(start: Float) {
    var at by mutableFloatStateOf(start)
    var glide: Job? = null

    /** The page a swipe settled on that the caller has yet to take up, so the change is not glided twice. */
    var thrownTo by mutableIntStateOf(-1)

    /** How fast the finger let go, in pages per second, for the glide that follows it. */
    var speed = 0f
}

/**
 * Screens that sit side by side, such as the Inbox and Tours (H20, as Grok's Ask, Imagine and Build do): the next one
 * is just past the right edge, a drag anywhere the screens do not use themselves moves both with the finger, and
 * letting go settles on the nearer one, or on the next one the way a throw went (rule 105). A change of [target] from
 * elsewhere, a tab, glides the row there. Nothing fades or blurs: the screens only move. [pages] is the order they sit
 * in and [onPage] takes the one a swipe settled on. Only the screens in view are composed. A swipe that a screen
 * takes itself (a row's swipe) does not move the pages.
 */
@Composable
fun <S> SidePages(
    target: S,
    pages: List<S>,
    onPage: (S) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (S) -> Unit,
) {
    val index = pages.indexOf(target).coerceAtLeast(0)
    val last = pages.lastIndex.coerceAtLeast(0)
    val state = remember { SideState(index.toFloat()) }
    val scope = rememberCoroutineScope()
    val moving = rememberMotionEnabled()
    val latest by rememberUpdatedState(onPage)
    var width by remember { mutableIntStateOf(0) }
    val fling = with(LocalDensity.current) { PullsMotion.Fling.toPx() }

    fun glideTo(page: Int, speed: Float = 0f) {
        state.glide?.cancel()
        if (!moving) {
            state.at = page.toFloat()
            return
        }
        state.glide = scope.launch {
            animate(state.at, page.toFloat(), initialVelocity = speed, animationSpec = PullsMotion.PageSpring) { value, _ -> state.at = value }
        }
    }

    LaunchedEffect(index) {
        val speed = if (state.thrownTo == index) state.speed else 0f
        state.thrownTo = -1
        state.speed = 0f
        if (state.at != index.toFloat()) glideTo(index, speed)
    }

    val drag = rememberDraggableState { delta ->
        if (width <= 0) return@rememberDraggableState
        state.at = (state.at - delta / width).coerceIn(0f, last.toFloat())
    }
    val shown by remember(pages) { derivedStateOf { pages.indices.filter { abs(it - state.at) < 1f } } }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { width = it.width }
            .draggable(
                drag, Orientation.Horizontal,
                onDragStarted = { state.glide?.cancel() },
                onDragStopped = { velocity ->
                    val at = state.at
                    // A throw goes on to the next page the way it went; a slow drag settles on the nearer one.
                    val to = when {
                        velocity < -fling -> ceil(at).toInt()
                        velocity > fling -> floor(at).toInt()
                        else -> at.roundToInt()
                    }.coerceIn(0, last)
                    val speed = if (width > 0) -velocity / width else 0f
                    if (to != index) {
                        Haptics.page()
                        state.thrownTo = to
                        state.speed = speed
                        latest(pages[to])
                    } else {
                        glideTo(to, speed)
                    }
                },
            ),
    ) {
        for (page in shown) {
            key(pages[page]) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = (page - state.at) * (size.width + PullsMotion.PageGap.toPx()) }) {
                    content(pages[page])
                }
            }
        }
    }
}
