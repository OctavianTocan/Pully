package com.octaviantocan.pulls.core

/*
 * Which way a change of page moves (rule 105). A way is 1 or -1: 1 is forward, the new page comes in from the right
 * and the old one leaves to the left, as when a finger pushes the pages left; -1 is backward, everything moves right.
 * Every page, tab, file and view change in the app takes its way from one of these, never from a guess at the call.
 */

/** Forward: the new page comes in from the right and the old one leaves to the left. */
const val FORWARD = 1

/** Backward: the new page comes in from the left and the old one leaves to the right. */
const val BACKWARD = -1

/** A change between pages ranked left to right goes forward to one at or after [from], backward to one before it. */
fun pageWay(from: Int, to: Int): Int = if (to >= from) FORWARD else BACKWARD

/**
 * Which way a horizontal swipe sends the pages: with the finger. A finger moving left (negative [dx]) pushes them
 * forward, one moving right pulls them back; 0 when it has not moved.
 */
fun swipeWay(dx: Float): Int = when {
    dx < 0f -> FORWARD
    dx > 0f -> BACKWARD
    else -> 0
}

/**
 * Which way a back swipe moves the page it leaves: with the finger. From the left edge the finger moves right, so the
 * pages go backward; from the right edge it moves left, so they go the other way. The Back button, which has no edge,
 * goes backward.
 */
fun backWay(fromRightEdge: Boolean): Int = if (fromRightEdge) FORWARD else BACKWARD

/**
 * Where a swipe that let go goes: 1 for the next page, -1 for the previous one, 0 for nowhere. A drag of [dx] pixels
 * past [threshold] counts, and so does a quick throw: a release faster than [fling] (px/s) in the direction the finger
 * already went, however short. A throw back against the drag cancels it, so a finger can change its mind.
 */
fun swipeStep(dx: Float, threshold: Float, velocity: Float = 0f, fling: Float = Float.POSITIVE_INFINITY): Int = when {
    velocity <= -fling -> if (dx < 0f) FORWARD else 0
    velocity >= fling -> if (dx > 0f) BACKWARD else 0
    dx <= -threshold -> FORWARD
    dx >= threshold -> BACKWARD
    else -> 0
}
