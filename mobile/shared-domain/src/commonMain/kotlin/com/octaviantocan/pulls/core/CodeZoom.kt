package com.octaviantocan.pulls.core

/*
 * The arithmetic of pinching code: the code is laid out again at each new size as the fingers move, and scrolled so
 * the line under the fingers stays where it was.
 */

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
