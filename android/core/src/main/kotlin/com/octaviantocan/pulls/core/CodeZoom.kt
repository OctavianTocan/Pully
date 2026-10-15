package com.octaviantocan.pulls.core

/*
 * The arithmetic of pinching code: while the fingers are down the code is only drawn bigger or smaller, and when they
 * lift it is laid out once at its new size and scrolled so the line under the fingers stays where it was.
 */

/**
 * The code as a pinch draws it: scaled by [scale] about its top-left corner, then moved by ([x], [y]) pixels. Nothing
 * is laid out again while the fingers are down.
 */
data class PinchFrame(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    /**
     * The next frame: the fingers' centre moved from ([fromX], [fromY]) to ([toX], [toY]) while they spread by [zoom].
     * The point of the code under the fingers stays under them, so two fingers also pan, and the scale stays within
     * [minScale] and [maxScale].
     */
    fun next(zoom: Float, fromX: Float, fromY: Float, toX: Float, toY: Float, minScale: Float, maxScale: Float): PinchFrame {
        val scaled = (scale * zoom).coerceIn(minScale, maxScale)
        val k = scaled / scale
        return PinchFrame(scaled, toX - k * (fromX - x), toY - k * (fromY - y))
    }

    /** The point of the code, as it was laid out before the pinch, that is drawn at [screenX]. */
    fun contentX(screenX: Float): Float = (screenX - x) / scale

    fun contentY(screenY: Float): Float = (screenY - y) / scale
}

/**
 * Where the top of a row goes once the code has been laid out [scale] times bigger, so that the point [within] pixels
 * into it stays at [screenY]. A row of code [grows] with it; a note or a thread under it keeps its size.
 */
fun rowTopAfterZoom(screenY: Float, within: Float, scale: Float, grows: Boolean): Float = screenY - within * if (grows) scale else 1f

/**
 * The sideways scroll of a line of code once it has been laid out [scale] times bigger, so that the character drawn at
 * [screenX] stays there. [contentX] is where that character was before (in the view's pixels), [offset] the scroll then,
 * and [codeStart] and [newCodeStart] where the code began in the row before and after (the number column grows too).
 */
fun codeScrollAfterZoom(offset: Float, codeStart: Float, newCodeStart: Float, contentX: Float, screenX: Float, scale: Float): Float {
    val into = contentX - codeStart + offset
    return (newCodeStart + into * scale - screenX).coerceAtLeast(0f)
}
