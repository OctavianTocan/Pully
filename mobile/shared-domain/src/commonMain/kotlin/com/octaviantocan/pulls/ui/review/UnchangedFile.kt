package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.DiffLine
import com.octaviantocan.pulls.data.ReviewFile

/** A file the change does not touch, as the diff's model holds one: every line unchanged, numbered the same on both sides. */
fun unchangedFile(path: String, text: String): ReviewFile {
    val lines = text.removeSuffix("\n").split('\n')
    return ReviewFile(
        path = path, status = "unchanged", additions = 0, deletions = 0,
        patchLines = lines.mapIndexed { i, line -> DiffLine(' ', line.removeSuffix("\r"), newLine = i + 1, oldLine = i + 1) },
    )
}
