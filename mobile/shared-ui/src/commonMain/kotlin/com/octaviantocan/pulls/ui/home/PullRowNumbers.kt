package com.octaviantocan.pulls.ui.home

/** A row's age in the fewest characters: `now`, `43m`, `6h`, `2d`, `3w`, `5mo`, `2y`. Empty when the time is unknown. */
fun compactAge(thenMs: Long?, nowMs: Long): String {
    if (thenMs == null) return ""
    val seconds = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    val day = 86_400L
    return when {
        seconds < 60 -> "now"
        seconds < 3_600 -> "${seconds / 60}m"
        seconds < day -> "${seconds / 3_600}h"
        seconds < 7 * day -> "${seconds / day}d"
        seconds < 30 * day -> "${seconds / (7 * day)}w"
        seconds < 365 * day -> "${seconds / (30 * day)}mo"
        else -> "${seconds / (365 * day)}y"
    }
}

/** A line count that fits the diff column: `640`, `1.2k`, `12k`. */
fun compactCount(n: Int): String = when {
    n < 1_000 -> "$n"
    n < 10_000 -> "${n / 1_000}.${n % 1_000 / 100}k"
    else -> "${n / 1_000}k"
}
