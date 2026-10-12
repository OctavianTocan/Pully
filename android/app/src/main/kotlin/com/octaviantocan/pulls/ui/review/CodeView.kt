package com.octaviantocan.pulls.ui.review

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.octaviantocan.pulls.data.AppSettings
import com.octaviantocan.pulls.ui.design.IconButton
import com.octaviantocan.pulls.ui.design.LocalPullsColors
import com.octaviantocan.pulls.ui.design.PullsType
import com.octaviantocan.pulls.ui.home.DropdownOption
import com.octaviantocan.pulls.ui.home.PullsDropdown
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

/** The header's code menu: zoom out, the current size (tap resets), zoom in, and wrap lines. */
@Composable
fun CodeViewMenu(settings: AppSettings) {
    val c = LocalPullsColors.current
    var open by remember { mutableStateOf(false) }
    val changed = settings.codeZoom != 1f || settings.codeWrap
    Box {
        IconButton("text-aa-duotone", "Code size and wrapping", { open = !open }, active = open || changed)
        PullsDropdown(open, { open = false }) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton("magnifying-glass-minus", "Smaller code", { settings.zoomBy(-1) }, enabled = settings.codeZoom > AppSettings.MIN_ZOOM)
                Text(
                    "${(settings.codeZoom * 100).roundToInt()}%",
                    Modifier.widthIn(min = 56.dp),
                    style = PullsType.sm.copy(fontFamily = PullsType.mono, textAlign = TextAlign.Center),
                    color = c.text,
                )
                IconButton("magnifying-glass-plus", "Larger code", { settings.zoomBy(1) }, enabled = settings.codeZoom < AppSettings.MAX_ZOOM)
            }
            DropdownOption("Reset size", false, { settings.updateCodeZoom(1f) })
            DropdownOption("Wrap long lines", settings.codeWrap, { settings.updateCodeWrap(!settings.codeWrap) }, checkStart = true)
        }
    }
}
