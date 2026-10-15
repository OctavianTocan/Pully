package com.octaviantocan.pulls.core

/**
 * The file to show after [current] is marked reviewed: the next one in [order] that is not reviewed, wrapping to the
 * top when the ones below it are done. `null` when every file is reviewed, or [current] is not in [order].
 */
fun nextUnreviewed(order: List<String>, current: String, isReviewed: (String) -> Boolean): String? {
    val at = order.indexOf(current)
    if (at < 0) return null
    return (order.drop(at + 1) + order.take(at)).firstOrNull { !isReviewed(it) }
}

/**
 * Whether the Summary tab shows the author's description: always while the AI has written no [summary], and over one
 * only when the person switched to the description while that same summary was on screen ([switchedAwayFrom]). A
 * summary written after that takes the description's place again. Both are [summaryKey]s.
 */
fun showsDescription(summary: String?, switchedAwayFrom: String?): Boolean = summary == null || summary == switchedAwayFrom

/**
 * What tells one written summary from another: when its analysis was written and its [text], so a new analysis that
 * happens to say the same words is still new. Null without a summary.
 */
fun summaryKey(generatedAt: String?, text: String?): String? = text?.let { "${generatedAt.orEmpty()}\n$it" }

/**
 * The tab a horizontal drag lands on. A drag of [dx] pixels to the left past [threshold], or a throw that way faster
 * than [fling] (px/s, [velocity] at the release), shows the next tab; to the right, the previous one (see [swipeStep]).
 * The first and last tabs stay put. A shorter, slower drag changes nothing.
 */
fun swipedTab(current: Int, count: Int, dx: Float, threshold: Float, velocity: Float = 0f, fling: Float = Float.POSITIVE_INFINITY): Int =
    if (count <= 1) current else (current + swipeStep(dx, threshold, velocity, fling)).coerceIn(0, count - 1)

/**
 * Where a horizontal drag of [dx] pixels takes the diff from file [current] of [count] (rule 93): to the left past
 * [threshold], or thrown that way faster than [fling] (see [swipeStep]), is the next file and to the right the one
 * before. Left of the last file it returns [count] and right of the first it returns -1, which are the views either
 * side of the files (the Tour and the Overview). A shorter, slower drag returns [current].
 */
fun swipedFile(current: Int, count: Int, dx: Float, threshold: Float, velocity: Float = 0f, fling: Float = Float.POSITIVE_INFINITY): Int =
    (current + swipeStep(dx, threshold, velocity, fling)).coerceIn(-1, count)
