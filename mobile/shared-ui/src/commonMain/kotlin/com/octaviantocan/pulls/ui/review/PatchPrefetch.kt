package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.ReviewFile

/** How many files' left-out patches one request brings: the one on screen and the next ones a swipe away. */
private const val PATCHES_AHEAD = 6

/** [path] and the next files in [files] whose patches were left out, so swiping on finds them already there. */
fun patchesAround(files: List<ReviewFile>, path: String): List<String> {
    val at = files.indexOfFirst { it.path == path }.coerceAtLeast(0)
    return (listOf(path) + files.drop(at).filter { it.patchOmitted }.map { it.path }).distinct().take(PATCHES_AHEAD)
}
