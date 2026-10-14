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
 * The tab a horizontal drag lands on. A drag of [dx] pixels to the left past [threshold] shows the next tab, one to the
 * right shows the previous one, and the first and last tabs stay put. A shorter drag changes nothing.
 */
fun swipedTab(current: Int, count: Int, dx: Float, threshold: Float): Int = when {
    count <= 1 || dx > -threshold && dx < threshold -> current
    dx < 0f -> (current + 1).coerceAtMost(count - 1)
    else -> (current - 1).coerceAtLeast(0)
}
