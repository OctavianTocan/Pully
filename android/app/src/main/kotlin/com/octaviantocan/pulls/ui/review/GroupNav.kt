package com.octaviantocan.pulls.ui.review

import androidx.compose.ui.graphics.Color
import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.ReviewFile

/** `CATEGORY_ICON` from `components/diff/category-icons.ts`. */
val CATEGORY_ICON: Map<String, String> = mapOf(
    "ui" to "compass-tool-duotone",
    "api" to "plugs-connected-duotone",
    "core" to "code-duotone",
    "data" to "database-duotone",
    "cli" to "terminal-duotone",
    "security" to "shield-check-duotone",
    "tests" to "flask-duotone",
    "docs" to "book-open-duotone",
    "examples" to "play-circle-duotone",
    "deps" to "package-duotone",
    "build" to "hammer-duotone",
    "scripts" to "wrench-duotone",
    "config" to "sliders-horizontal-duotone",
    "i18n" to "translate-duotone",
    "assets" to "image-duotone",
    "other" to "dots-three-circle-duotone",
)

/**
 * `CATEGORY_COLOR_CLASS`: one hue per category, the `-800` stop in light and `-300` in dark
 * (values from the compiled web CSS). ARGB pairs, light first.
 */
val CATEGORY_COLOR: Map<String, Pair<Long, Long>> = mapOf(
    "ui" to (0xFF9D174D to 0xFFF9A8D4), // pink
    "api" to (0xFF1E40AF to 0xFF93C5FD), // blue
    "core" to (0xFF5B21B6 to 0xFFC4B5FD), // violet
    "data" to (0xFF92400E to 0xFFFCD34D), // amber
    "cli" to (0xFF3F6212 to 0xFFBEF264), // lime
    "security" to (0xFF991B1B to 0xFFFCA5A5), // red
    "tests" to (0xFF166534 to 0xFF86EFAC), // green
    "docs" to (0xFF075985 to 0xFF7DD3FC), // sky
    "examples" to (0xFF155E75 to 0xFF67E8F9), // cyan
    "deps" to (0xFF9A3412 to 0xFFFDBA74), // orange
    "build" to (0xFF854D0E to 0xFFFDE047), // yellow
    "scripts" to (0xFF065F46 to 0xFF6EE7B7), // emerald
    "config" to (0xFF115E59 to 0xFF5EEAD4), // teal
    "i18n" to (0xFF3730A3 to 0xFFA5B4FC), // indigo
    "assets" to (0xFF86198F to 0xFFF0ABFC), // fuchsia
    "other" to (0xFF262626 to 0xFFD4D4D4), // neutral
)

fun categoryIcon(category: String): String = CATEGORY_ICON[category] ?: CATEGORY_ICON.getValue("other")

fun categoryColor(category: String, dark: Boolean): Color = (CATEGORY_COLOR[category] ?: CATEGORY_COLOR.getValue("other")).let { Color(if (dark) it.second else it.first) }

/** `group-utils.ts` `groupProgress`: share of a group's files (and its children's) reviewed; an empty group counts as done. */
fun groupProgress(group: FileGroup, files: Map<String, ReviewFile>, reviewed: Set<String>): Float {
    val list = group.allPaths.mapNotNull(files::get)
    if (list.isEmpty()) return 1f
    return list.count { it.sha in reviewed }.toFloat() / list.size
}

/** `countGroupStats`: a group's +/- with its children's. */
fun groupStats(group: FileGroup, files: Map<String, ReviewFile>): Pair<Int, Int> {
    val list = group.allPaths.mapNotNull(files::get)
    return list.sumOf { it.additions } to list.sumOf { it.deletions }
}
