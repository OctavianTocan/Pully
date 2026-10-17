package com.octaviantocan.pulls.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownLiveTest {
    private val text = "## Summary\n- **Fixed** the `cache`\n- see [docs](https://x.y)\n<!-- note -->"

    private fun folded(at: Int, cursor: Int) = markdownLive(text, cursor, DarkPullsColors).spanStyles.any {
        at >= it.start && at < it.end && it.item.color == Color.Transparent
    }

    @Test
    fun keepsEveryCharacter() {
        val live = markdownLive(text, 0, DarkPullsColors)
        assertEquals(text.length, live.text.length)
        // A list's dash reads as a bullet, one character for one (Q4).
        assertEquals("• **Fixed** the `cache`", live.text.lines()[1])
    }

    @Test
    fun marksFoldAwayOffTheCursorsLine() {
        val end = text.length
        // The heading's marks fold while the cursor is elsewhere, and show on its own line.
        assertTrue(folded(0, cursor = end))
        assertTrue(!folded(0, cursor = 3))
        // So do strong's stars and a link's address.
        val stars = text.indexOf("**")
        assertTrue(folded(stars, cursor = 0))
        assertTrue(!folded(stars, cursor = stars + 3))
        assertTrue(folded(text.indexOf("(https"), cursor = 0))
    }

    @Test
    fun stylesTheWords() {
        val live = markdownLive(text, 0, DarkPullsColors)
        val fixed = text.indexOf("Fixed")
        assertTrue(live.spanStyles.any { fixed >= it.start && fixed < it.end && it.item.fontWeight == FontWeight.SemiBold })
        val comment = text.indexOf("note")
        assertTrue(live.spanStyles.any { comment >= it.start && comment < it.end && it.item.color == DarkPullsColors.muted })
    }
}
