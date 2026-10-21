package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.octaviantocan.pulls.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Phone-only code view controls (no web counterpart: the browser has its own zoom and the web diff
 * scrolls lines sideways). Code size and line wrapping are app settings, so they hold across PRs.
 */

/** One zoom step: 10%, snapped so repeated taps land on round values. */
private const val STEP = 0.1f

fun AppSettings.zoomBy(steps: Int) = updateCodeZoom(((codeZoom + steps * STEP) * 10).roundToInt() / 10f)

/**
 * Keeps the code under the fingers while a pinch resizes it: [begin] reads what is under them as the pinch starts, and
 * [step] scrolls after each new size, always reckoned from that start.
 */
interface PinchKeeper {
    fun begin(at: Offset)
    fun step(from: Float, to: Float)
}

/** A size change smaller than this waits for the next, so finger jitter does not lay the code out again. */
private const val ZOOM_QUANTUM = 0.01f

/** The share of the way to the fingers' size the code moves each frame: most of it within a few frames. */
private const val ZOOM_EASE = 0.4f

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
 * Two fingers pinch the code: as they spread or close, the code's own font size and line height change and it is laid
 * out again across the full width, wrapping where wrap is on, never a smaller or bigger picture of the page ("it was
 * just scaling the actual font ... and that was really good. And now it somehow breaks the entire thing": recording
 * 16 Oct 09:50, 04:11 to 04:26; M9). [keep] holds the line under the point where the fingers met, and the code never
 * pans with them (L17). The size is saved once, when they lift. One finger is left alone, so the list still scrolls:
 * events are read on the initial pass and taken only while two pointers are down. The size stays within
 * [AppSettings.MIN_ZOOM] and [AppSettings.MAX_ZOOM].
 */
fun Modifier.pinchToZoomCode(settings: AppSettings, keep: PinchKeeper? = null): Modifier = pointerInput(settings, keep) {
    coroutineScope {
        var start = settings.codeZoom
        // Where the fingers have taken the size; NaN while there is no pinch.
        var goal = Float.NaN
        var easing: Job? = null
        fun lay(to: Float) {
            settings.previewCodeZoom(to)
            keep?.step(start, settings.codeZoom)
        }
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var scale = 1f
            var pivot = Offset.Unspecified
            fun end() {
                easing?.cancel()
                easing = null
                // The size lands where the fingers left it, and is saved once.
                if (!goal.isNaN()) {
                    if (abs(goal - settings.codeZoom) >= ZOOM_QUANTUM) lay(goal)
                    settings.updateCodeZoom(settings.codeZoom)
                }
                goal = Float.NaN
                pivot = Offset.Unspecified
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    pinchStep(event)?.let { (from, _, spread) ->
                        if (!pivot.isSpecified) {
                            pivot = from
                            start = settings.codeZoom
                            scale = 1f
                            keep?.begin(pivot)
                            goal = start
                            // Once a frame the size moves part of the way to the fingers, so it grows and shrinks smoothly
                            // between their events instead of in their steps, and the code is laid out at most once a frame
                            // ("make the scaling smooth": recording 16 Oct 13:06, 09:22 to 09:30; O21).
                            easing = launch {
                                while (true) {
                                    withFrameNanos { }
                                    val now = settings.codeZoom
                                    val gap = goal - now
                                    if (goal.isNaN() || abs(gap) < ZOOM_QUANTUM) continue
                                    lay(if (abs(gap) < ZOOM_QUANTUM * 3) goal else now + gap * ZOOM_EASE)
                                }
                            }
                        }
                        scale = (scale * spread).coerceIn(AppSettings.MIN_ZOOM / start, AppSettings.MAX_ZOOM / start)
                        goal = start * scale
                    }
                    event.changes.forEach { it.consume() }
                } else {
                    if (pivot.isSpecified) end()
                    if (pressed == 0) break
                }
            }
            if (pivot.isSpecified) end()
        }
    }
}
