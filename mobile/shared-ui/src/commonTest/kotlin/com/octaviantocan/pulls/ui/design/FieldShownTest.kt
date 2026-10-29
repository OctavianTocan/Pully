package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldShownTest {
    private fun typed(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test
    fun aFootFieldKeepsEachKeystrokeWhileItsPageIsAFrameBehind() {
        // The iPhone simulator's order for "Wh": the field composes with the page's "W" before the page passes "Wh".
        var before = "W"
        var shown = typed("W")
        for (text in listOf("Wh", "Wha", "What")) {
            val kept = typed(text)
            shown = fieldShown(value = before, before = before, kept = kept, lagging = true)
            assertEquals(text, shown.text)
            shown = fieldShown(value = text, before = before, kept = kept, lagging = true)
            before = text
        }
        assertEquals(typed("What"), shown)
    }

    @Test
    fun aFootFieldTakesTheCallersOwnChange() {
        // Send clears the bar: the page's text moves, so the field follows it.
        assertEquals(typed(""), fieldShown(value = "", before = "What", kept = typed("What"), lagging = true))
        assertEquals(typed("#12"), fieldShown(value = "#12", before = "", kept = typed(""), lagging = true))
    }

    @Test
    fun aFieldOutsideTheFootShowsTheCallersTextAtOnce() {
        // A caller that refuses a keystroke puts the field back, as before.
        assertEquals(typed("W"), fieldShown(value = "W", before = "W", kept = typed("Wh"), lagging = false))
    }

    @Test
    fun theFieldKeepsItsCursorWhileItHoldsTheCallersText() {
        val kept = TextFieldValue("What changed", TextRange(4))
        assertEquals(kept, fieldShown(value = "What changed", before = "What changed", kept = kept, lagging = false))
    }
}
