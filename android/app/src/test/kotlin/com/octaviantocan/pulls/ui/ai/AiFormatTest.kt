package com.octaviantocan.pulls.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors `components/ai/ai-format.test.ts`. */
class AiFormatTest {
    @Test
    fun formatClockDuration() {
        listOf(
            0L to "0s", -500L to "0s", 999L to "0s", 45_000L to "45s", 60_000L to "1m 0s",
            192_000L to "3m 12s", 3_900_000L to "1h 5m", (2 * 24 + 3) * 3_600_000L + 59_000 to "2d 3h",
        ).forEach { (ms, expected) -> assertEquals(expected, formatClockDuration(ms), "$ms ms") }
    }

    @Test
    fun effortLabelNamesKnownEffortsThroughI18n() {
        val translate: Translate = { key, _ -> "<$key>" }
        assertEquals("<ai.effort.xhigh>", effortLabel("xhigh", translate))
        assertEquals("<ai.effort.low>", effortLabel("low", translate))
        assertEquals("Turbo", effortLabel("turbo", translate))
    }

    @Test
    fun lastLineKeepsTheNewestNonEmptyLine() {
        assertEquals("Second idea", lastLine("First idea\n\nSecond idea\n  \n"))
    }

    @Test
    fun lastLineDropsMarkdownMarks() {
        assertEquals("Checking the parser", lastLine("## **Checking** the `parser`"))
        assertEquals("Planning", lastLine("__Planning__"))
    }

    @Test
    fun lastLineIgnoresHtmlCommentsAcrossLines() {
        assertEquals("Reading the diff", lastLine("Reading the diff\n<!-- hidden\nnote -->"))
    }

    @Test
    fun lastLineIsEmptyWhenThereIsNothing() {
        assertEquals("", lastLine(""))
        assertEquals("", lastLine("\n**\n"))
    }

    @Test
    fun verbKeyIsPresentTenseOnlyWhileRunning() {
        assertEquals("ai.verb.thinking.now", verbKey("thinking", "running"))
        assertEquals("ai.verb.thinking.past", verbKey("thinking", "done"))
        assertEquals("ai.verb.thinking.past", verbKey("thinking", "failed"))
    }
}
