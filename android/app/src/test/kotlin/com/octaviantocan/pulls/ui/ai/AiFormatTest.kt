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
    fun formatTokens() {
        listOf(
            0L to "0", 950L to "950", 1000L to "1k", 1500L to "1.5k", 12_345L to "12.3k", 99_960L to "100k",
            200_000L to "200k", 123_456L to "123k", 999_499L to "999k", 999_500L to "1m", 1_234_567L to "1.2m", 250_000_000L to "250m",
        ).forEach { (count, expected) -> assertEquals(expected, formatTokens(count), "$count tokens") }
    }

    @Test
    fun formatCost() {
        listOf(
            0.00004 to "$0.000040", 0.0004 to "$0.00040", 0.0042 to "$0.0042", 0.042 to "$0.042",
            0.1 to "$0.10", 1.2749 to "$1.27", 12.0 to "$12.00",
        ).forEach { (usd, expected) -> assertEquals(expected, formatCost(usd), "$usd") }
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
