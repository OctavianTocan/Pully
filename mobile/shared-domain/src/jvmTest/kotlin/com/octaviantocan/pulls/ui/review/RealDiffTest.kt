package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.asFullFile
import com.octaviantocan.pulls.data.asOpened
import com.octaviantocan.pulls.data.asReviewData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The diff logic against real server answers for antfu/pulls.review#106 (captured from `github-pr`, `file-content`, `review-data`). */
class RealDiffTest {
    private fun fixture(name: String): JsonElement =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/$name")!!.readText()).jsonObject.getValue("result")

    private val opened = fixture("pr106.json").asOpened()
    private val test = opened.files.first { it.path.endsWith("FileDiff.test.ts") }
    private val vue = opened.files.first { it.path.endsWith("FileDiff.vue") }

    @Test
    fun fullFileExpansionMatchesTheNewFileLineForLine() {
        val full = fixture("full-FileDiff.vue.json").asFullFile()
        val newLines = splitFileLines(full.new!!)
        val oldLines = splitFileLines(full.old!!)
        val rows = diffEntries(vue, full, null).map { (it as DiffEntry.Line).line }
        // Every new line appears once, in order, with its text; every old line too.
        assertEquals(newLines.indices.map { it + 1 }, rows.mapNotNull { it.newLine })
        assertEquals(oldLines.indices.map { it + 1 }, rows.mapNotNull { it.oldLine })
        rows.forEach { line -> line.newLine?.let { assertEquals(newLines[it - 1], line.text) } }
        rows.filter { it.kind == ' ' }.forEach { line -> assertEquals(oldLines[line.oldLine!! - 1], line.text) }
    }

    @Test
    fun patchGapsAddUpWithTheFullFile() {
        val full = fixture("full-FileDiff.test.ts.json").asFullFile()
        val gaps = diffEntries(test, full, emptySet()).filterIsInstance<DiffEntry.Gap>()
        val shown = test.patchLines.count { it.newLine != null }
        assertEquals(splitFileLines(full.new!!).size, shown + gaps.sumOf { it.count })
        assertEquals(112, gaps.first().count)
    }

    @Test
    fun theRealThreadAnchorsOnItsLine() {
        val data = fixture("review106.json").asReviewData()
        val thread = visibleThreads(data.threads, test.path, showThreads = true).single()
        val rows = diffEntries(test).map { e -> if (e is DiffEntry.Line) listOf(e.line) else emptyList() }
        val index = placeAnchors(rows, listOf(Anchor(thread.side, thread.line!!))).values.single()
        assertEquals(117, (diffEntries(test)[index] as DiffEntry.Line).line.newLine)
    }

    @Test
    fun oneSidedFilesStayUnified() {
        assertTrue(opened.files.all { !effectiveSplit("split", it, 900) })
    }
}
