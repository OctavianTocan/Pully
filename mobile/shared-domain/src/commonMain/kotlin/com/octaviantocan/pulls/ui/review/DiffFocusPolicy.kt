package com.octaviantocan.pulls.ui.review

/** The item key of the line nearest [line] among [lines] (anchor -> item key) whose anchor starts with [prefix], or null. */
fun nearestLineKey(lines: Map<String, String>, prefix: String, line: Int): String? =
    lines.entries.mapNotNull { (anchor, key) -> anchor.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()?.let { it to key } }
        .minByOrNull { (at) -> kotlin.math.abs(at - line) }?.second
