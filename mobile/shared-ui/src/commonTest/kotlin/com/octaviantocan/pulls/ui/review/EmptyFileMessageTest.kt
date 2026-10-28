package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.ReviewFile
import kotlin.test.Test
import kotlin.test.assertEquals

class EmptyFileMessageTest {
    private fun file(status: String, previousPath: String? = null) =
        ReviewFile(path = "b.txt", status = status, additions = 0, deletions = 0, sha = "s", patchLines = emptyList(), previousPath = previousPath)

    @Test
    fun aFileWithNoLinesSaysWhy() {
        assertEquals("Moved without changes to its content.", emptyFileMessage(file("renamed", previousPath = "a.txt")))
        assertEquals("A new, empty file.", emptyFileMessage(file("added")))
        assertEquals("An empty file, deleted.", emptyFileMessage(file("removed")))
        assertEquals("No changes to its content.", emptyFileMessage(file("modified")))
    }
}
