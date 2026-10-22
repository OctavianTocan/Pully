package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.DiffLine
import kotlin.test.Test
import kotlin.test.assertEquals

class SplitRowsTest {
    private fun ctx(old: Int, new: Int) = DiffLine(' ', "c", newLine = new, oldLine = old)
    private fun del(old: Int) = DiffLine('-', "d", oldLine = old)
    private fun add(new: Int) = DiffLine('+', "a", newLine = new)

    @Test
    fun contextSitsOnBothSides() {
        assertEquals(listOf(SplitRow(0, 0), SplitRow(1, 1)), splitRows(listOf(ctx(1, 1), ctx(2, 2))))
    }

    @Test
    fun pairsDeletionsWithTheAdditionsThatFollow() {
        val lines = listOf(ctx(1, 1), del(2), del(3), add(2), ctx(4, 3))
        assertEquals(
            listOf(SplitRow(0, 0), SplitRow(1, 3), SplitRow(2, null), SplitRow(4, 4)),
            splitRows(lines),
        )
    }

    @Test
    fun extraAdditionsGetAnEmptyLeftSide() {
        val lines = listOf(del(1), add(1), add(2), add(3))
        assertEquals(listOf(SplitRow(0, 1), SplitRow(null, 2), SplitRow(null, 3)), splitRows(lines))
    }

    @Test
    fun aLoneAdditionHasNoLeftSide() {
        assertEquals(listOf(SplitRow(0, 0), SplitRow(null, 1)), splitRows(listOf(ctx(1, 1), add(2))))
    }
}
