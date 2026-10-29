package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldShownTest {
    private fun typed(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test
    fun aFootFieldKeepsEachKeystrokeWhileItsPageIsAFrameBehind() {
        // One iPhone simulator order for "Wh": the field composes with the page's "W" before the page passes "Wh".
        var before = "W"
        var shown = typed("W")
        for (text in listOf("Wh", "Wha", "What")) {
            val kept = typed(text)
            shown = fieldShown(value = before, before = before, sent = listOf(text), kept = kept, lagging = true)
            assertEquals(text, shown.text)
            shown = fieldShown(value = text, before = before, sent = listOf(text), kept = kept, lagging = true)
            before = text
        }
        assertEquals(typed("What"), shown)
    }

    @Test
    fun aFootFieldKeepsEachKeystrokeWhileItsPageStaysAKeystrokeBehind() {
        // The other order: the page has taken "W" but not yet "Wh" when the field composes after "Wh" was typed.
        val sent = listOf("W", "Wh")
        assertEquals(typed("Wh"), fieldShown(value = "W", before = "", sent = sent, kept = typed("Wh"), lagging = true))
        assertEquals(typed("Wha"), fieldShown(value = "Wh", before = "W", sent = sent + "Wha", kept = typed("Wha"), lagging = true))
    }

    @Test
    fun aFootFieldTakesTheCallersOwnChange() {
        // Send clears the bar: the page's text moves to one the field never sent, so the field follows it.
        assertEquals(typed(""), fieldShown(value = "", before = "What", sent = listOf("What?"), kept = typed("What?"), lagging = true))
        assertEquals(typed("#12"), fieldShown(value = "#12", before = "", sent = emptyList(), kept = typed(""), lagging = true))
    }

    @Test
    fun aFieldOutsideTheFootShowsTheCallersTextAtOnce() {
        // A caller that refuses a keystroke puts the field back, as before.
        assertEquals(typed("W"), fieldShown(value = "W", before = "W", sent = listOf("Wh"), kept = typed("Wh"), lagging = false))
    }

    @Test
    fun theFieldKeepsItsCursorWhileItHoldsTheCallersText() {
        val kept = TextFieldValue("What changed", TextRange(4))
        assertEquals(kept, fieldShown(value = "What changed", before = "What changed", sent = emptyList(), kept = kept, lagging = false))
    }

    @Test
    fun theEchoForgetsWhatThePageHasMovedPast() {
        val echo = FieldEcho("")
        listOf("a", "", "b", "bc").forEach(echo::send)
        echo.seen("b", settled = false)
        assertEquals(listOf("b", "bc"), echo.sent.toList())
        assertEquals("b", echo.before)
        // So a clear on send is the page's own text again, though the field was emptied earlier.
        assertEquals(typed(""), fieldShown(value = "", before = echo.before, sent = echo.sent, kept = typed("bc"), lagging = true))
        echo.seen("bc", settled = true)
        assertEquals(emptyList(), echo.sent.toList())
    }
}
