package com.octaviantocan.pulls.data

import com.octaviantocan.pulls.ui.review.splitFileLines
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** A hunk's lines are code whatever they start with, and a CRLF file reads the same in its patch and in full. */
class PatchLinesTest {
    private fun file(patch: String) = Json.parseToJsonElement(
        """{"diff":{"files":[{"path":"a.sql","status":"modified","hunks":[{"oldStart":1,"oldLines":4,"newStart":1,"newLines":4,"patch":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(patch))}}]}]},"groups":[]}""",
    ).asOpened().files.single()

    @Test
    fun aRemovedDashCommentAndAnAddedIncrementAreLinesNotHeaders() {
        val lines = file(" ---\n--- a SQL comment that goes\n----\n+++counter;\n+---\n end").patchLines
        assertEquals(listOf(' ', '-', '-', '+', '+', ' '), lines.map { it.kind })
        assertEquals(listOf("---", "-- a SQL comment that goes", "---", "++counter;", "---", "end"), lines.map { it.text })
        // The numbers after them stay true to the file.
        assertEquals(4, lines.last().oldLine)
        assertEquals(4, lines.last().newLine)
    }

    @Test
    fun aCrlfFilesLinesLoseTheirCarriageReturnInThePatchAndInFull() {
        assertEquals(listOf("a", "b"), file(" a\r\n-b\r").patchLines.map { it.text })
        assertEquals(listOf("a", "b"), splitFileLines("a\r\nb\r\n"))
    }
}
