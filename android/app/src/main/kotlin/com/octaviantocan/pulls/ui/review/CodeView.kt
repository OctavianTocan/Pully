package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
 * Two fingers pinch the code: while they are down the code is drawn bigger or smaller around the point between them
 * and moves with them, so nothing is laid out again and they can pan too; when they lift, the new code size is written
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
                var moved = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        val from = event.calculateCentroid(useCurrent = false)
                        val to = event.calculateCentroid(useCurrent = true)
                        if (from != Offset.Unspecified && to != Offset.Unspecified) {
                            frame.value = frame.value.next(event.calculateZoom(), from.x, from.y, to.x, to.y, AppSettings.MIN_ZOOM / start, AppSettings.MAX_ZOOM / start)
                            last = to
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
