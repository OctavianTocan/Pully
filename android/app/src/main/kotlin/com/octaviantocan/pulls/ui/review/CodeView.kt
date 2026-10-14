package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
 * Two-finger pinch on the diff scales the code; one-finger scrolling is left alone. Events are
 * read on the initial pass and consumed only while two pointers are down, so the list still scrolls.
 */
fun Modifier.pinchToZoomCode(settings: AppSettings): Modifier = pointerInput(settings) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start = settings.codeZoom
        var scale = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2) {
                scale *= event.calculateZoom()
                settings.updateCodeZoom(start * scale)
                event.changes.forEach { it.consume() }
            } else {
                start = settings.codeZoom
                scale = 1f
            }
        }
    }
}
