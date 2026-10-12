package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.DiffLine
import kotlin.test.Test
import kotlin.test.assertEquals

class WordEmphasisTest {
    @Test
    fun tokenizesLikeWordsWithSpace() =
        assertEquals(listOf("text", "::", "TextView", ",").let { listOf("text", ":", ":", "TextView", ",") }, wordTokens("text::TextView,"))

    @Test
    fun changedWordOnly() {
        val (d, a) = wordEmphasis("    .text_size(px(12.))", "    .text_size(crate::theme::text_px(12.))")
        // The shared prefix and the `(12.))` tail stay neutral.
        assertEquals("px", "    .text_size(px(12.))".substring(d.single()))
        assertEquals("crate::theme::text_px", "    .text_size(crate::theme::text_px(12.))".substring(a.single()))
    }

    @Test
    fun singleNeutralCharJoinsChanges() {
        val (d, a) = wordEmphasis("a.b", "c.d")
        assertEquals(listOf(0..2), d)
        // Pierre's `pushOrJoinSpan` never joins the diff's last item, so the added side keeps two touching spans.
        assertEquals(listOf(0..1, 2..2), a)
    }

    @Test
    fun identicalLinesHaveNoEmphasis() = assertEquals(emptyList<IntRange>() to emptyList(), wordEmphasis("same", "same"))

    @Test
    fun pairsRemovalsWithFollowingAdditions() {
        val lines = listOf(DiffLine(' ', "x"), DiffLine('-', "foo bar"), DiffLine('+', "foo baz"), DiffLine('+', "extra"))
        val map = lineEmphasis(lines)
        assertEquals(listOf(4..6), map[lines[1]])
        assertEquals(listOf(4..6), map[lines[2]])
        assertEquals(null, map[lines[3]])
    }
}
