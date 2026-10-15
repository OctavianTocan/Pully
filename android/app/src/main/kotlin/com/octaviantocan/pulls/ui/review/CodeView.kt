package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.octaviantocan.pulls.core.PinchFrame
import com.octaviantocan.pulls.data.AppSettings
import kotlin.math.roundToInt

/*
 * Phone-only code view controls (no web counterpart: the browser has its own zoom and the web diff
 * scrolls lines sideways). Code size and line wrapping are app settings, so they hold across PRs.
 */

/** One zoom step: 10%, snapped so repeated taps land on round values. */
private const val STEP = 0.1f

fun AppSettings.zoomBy(steps: Int) = updateCodeZoom(((codeZoom + steps * STEP) * 10).roundToInt() / 10f)

/**
 * Where a pinch ended: the code size it went [from] and [to], the point between the fingers when they lifted ([screen],
 * in the view's pixels) and the point of the code that was drawn there ([content], as the view was laid out before the
 * pinch). The view scrolls so that point stays under the fingers.
 */
class PinchEnd(val from: Float, val to: Float, val screen: Offset, val content: Offset) {
    val scale: Float get() = to / from
}

/**
 * One event of a pinch, read from the fingers that were down before it and still are, so a finger landing or lifting
 * never jerks the code: their centre before and after, and how much further apart they are (1 for no change).
 */
private fun pinchStep(event: PointerEvent): Triple<Offset, Offset, Float>? {
    val fingers = event.changes.filter { it.pressed && it.previousPressed }
    if (fingers.size < 2) return null
    val from = fingers.fold(Offset.Zero) { sum, it -> sum + it.previousPosition } / fingers.size.toFloat()
    val to = fingers.fold(Offset.Zero) { sum, it -> sum + it.position } / fingers.size.toFloat()
    val spreadBefore = fingers.sumOf { (it.previousPosition - from).getDistance().toDouble() }
    val spreadAfter = fingers.sumOf { (it.position - to).getDistance().toDouble() }
    val zoom = if (spreadBefore > 0.0) (spreadAfter / spreadBefore).toFloat() else 1f
    return Triple(from, to, zoom)
}

/**
 * Two fingers pinch the code: while they are down the code is drawn bigger or smaller around the point where they met,
 * so nothing is laid out again, and it does not pan with them (L17); when they lift, the new code size is written
 * once and [onEnd] scrolls the view so the line under them stays where it was. One finger is left alone, so the list
 * still scrolls: events are read on the initial pass and taken only while two pointers are down. The size stays within
 * [AppSettings.MIN_ZOOM] and [AppSettings.MAX_ZOOM].
 */
@Composable
fun Modifier.pinchToZoomCode(settings: AppSettings, onEnd: ((PinchEnd) -> Unit)? = null): Modifier {
    val frame = remember { mutableStateOf(PinchFrame()) }
    val ended = rememberUpdatedState(onEnd)
    return this
        .pointerInput(settings) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var start = settings.codeZoom
                var last = Offset.Unspecified
                var pivot = Offset.Unspecified
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        val step = pinchStep(event)
                        if (step != null) {
                            val (from, _, zoom) = step
                            if (!pivot.isSpecified) pivot = from
                            frame.value = frame.value.zoomAbout(zoom, pivot.x, pivot.y, AppSettings.MIN_ZOOM / start, AppSettings.MAX_ZOOM / start)
                            last = pivot
                            moved = true
                        }
                        event.changes.forEach { it.consume() }
                    } else {
                        // The fingers lifted: lay the code out once at its new size, then put the drawing back.
                        if (moved) {
                            val drawn = frame.value
                            val to = (start * drawn.scale).coerceIn(AppSettings.MIN_ZOOM, AppSettings.MAX_ZOOM)
                            val end = PinchEnd(start, to, last, Offset(drawn.contentX(last.x), drawn.contentY(last.y)))
                            if (to != settings.codeZoom) settings.updateCodeZoom(to)
                            ended.value?.invoke(end)
                            frame.value = PinchFrame()
                            pivot = Offset.Unspecified
                            moved = false
                        }
                        if (pressed == 0) break
                        start = settings.codeZoom
                    }
                }
            }
        }
        // Drawn inside the view's own bounds, so code pinched bigger never spills over the header or the bar.
        .clipToBounds()
        .graphicsLayer {
            val drawn = frame.value
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = drawn.scale
            scaleY = drawn.scale
            translationX = drawn.x
            translationY = drawn.y
        }
}
