package com.octaviantocan.pulls.core

/**
 * The end of [path] that [fits], cut from the left at a folder boundary (rule 90): the end is what tells files apart.
 * Folders are dropped from the front one at a time, and a cut path starts with "…/". When not even the last folder fits
 * on its own, that folder is returned with its "…/" and the caller clips it. A path that fits whole is returned whole.
 */
fun pathTail(path: String, fits: (String) -> Boolean): String {
    if (path.isEmpty() || fits(path)) return path
    val parts = path.split('/')
    for (keep in parts.size - 1 downTo 1) {
        val tail = "…/" + parts.takeLast(keep).joinToString("/")
        if (fits(tail)) return tail
    }
    return "…/" + parts.last()
}

/**
 * [name] with invisible break points where a reader would split it: between the words of a camelCase or PascalCase
 * name, and after a dash or an underscore. A long file name then wraps as "AssessmentSession / Page.tsx" instead of
 * at whatever letter the line ran out on.
 */
fun softBreaks(name: String): String = buildString(name.length + 8) {
    name.forEachIndexed { i, ch ->
        val prev = name.getOrNull(i - 1)
        if (prev != null && ch.isUpperCase() && prev.isLowerCase()) append('\u200B')
        append(ch)
        if ((ch == '-' || ch == '_') && i < name.lastIndex) append('\u200B')
    }
}
