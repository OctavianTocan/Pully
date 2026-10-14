package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.FileGroup
import com.octaviantocan.pulls.data.ReviewFile

/** A group with its resolved files, the web `ResolvedGroup` (+ the label the uncategorized group gets). */
data class GroupView(val group: FileGroup, val label: String, val summary: String, val files: List<ReviewFile>, val missing: List<String>, val children: List<GroupView>) {
    val key: String get() = group.key
    val allFiles: List<ReviewFile> get() = files + children.flatMap { it.files }
}
