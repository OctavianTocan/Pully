package com.octaviantocan.pulls.ui.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A file outside the change opens in the app as unchanged lines (N20), and a focus lands on the nearest line (N21). */
class FileViewTest {
    @Test
    fun aFileOutsideTheChangeIsEveryLineUnchanged() {
        val file = unchangedFile("src/a.kt", "one\r\ntwo\nthree\n")
        assertEquals(listOf("one", "two", "three"), file.patchLines.map { it.text })
        assertEquals(listOf(1, 2, 3), file.patchLines.map { it.newLine })
        assertEquals(setOf(' '), file.patchLines.map { it.kind }.toSet())
        // No additions, so it is never shown split in two.
        assertEquals(false, effectiveSplit("split", file, 900))
    }

    @Test
    fun aLineTheRowsDoNotHoldLandsOnTheNearest() {
        val lines = mapOf("a.kt|additions|3" to "k3", "a.kt|additions|40" to "k40", "a.kt|deletions|12" to "d12")
        assertEquals("k3", nearestLineKey(lines, "a.kt|additions|", 12))
        assertEquals("k40", nearestLineKey(lines, "a.kt|additions|", 90))
        assertNull(nearestLineKey(emptyMap(), "a.kt|additions|", 1))
    }
}
