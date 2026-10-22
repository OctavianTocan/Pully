package com.octaviantocan.pulls.ui.review

/**
 * How far through the tour's [count] stops the reader is, 0 to 1, moving with the scroll and never jumping: the stops
 * above the reading [line] plus the part of the one it crosses. [stops] are the stop cards on screen as (number from 1,
 * top, height). Once the last item ([lastHeight] tall) is on screen, [rest] is how far the list can still scroll, and
 * the line slides down to the [bottom] of the content as that runs out, so the ring is full exactly when the list
 * cannot scroll further. The slide is spread over the last item's height when that is shorter than the way down, so
 * the line does not leap when a short last card comes into view.
 */
fun tourProgress(count: Int, stops: List<Triple<Int, Int, Int>>, firstVisible: Int, line: Float, bottom: Float, rest: Float?, lastHeight: Float = 0f): Float {
    if (count <= 0) return 0f
    if (firstVisible > count) return 1f
    val way = (bottom - line).coerceAtLeast(0f)
    val reading = if (rest == null) line else {
        val window = minOf(way, lastHeight).coerceAtLeast(1f)
        line + way * (1f - (rest / window).coerceIn(0f, 1f))
    }
    val (number, top, height) = stops.lastOrNull { it.second <= reading } ?: return 0f
    val through = if (height <= 0) 1f else ((reading - top) / height).coerceIn(0f, 1f)
    return ((number - 1 + through) / count).coerceIn(0f, 1f)
}

