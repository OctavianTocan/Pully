package com.octaviantocan.pulls.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors `state/command-rank.test.ts` orderings. */
class CommandRankTest {
    private fun ranked(query: String, vararg items: String) = CommandRank.rankItems(items.toList(), query) { it }

    @Test
    fun emptyQueryKeepsOrder() = assertEquals(listOf("b", "a"), ranked("  ", "b", "a"))

    @Test
    fun startBeatsWordStartBeatsInside() =
        assertEquals(listOf("file next", "next file", "unfiled"), ranked("file", "unfiled", "next file", "file next"))

    @Test
    fun initialsAndScattered() {
        assertEquals(listOf("next file", "a nefx"), ranked("nf", "a nefx", "next file"))
        assertNull(CommandRank.rank("abc", "abd"))
    }

    @Test
    fun allTermsMustMatch() {
        assertEquals(listOf("jump to src/app.ts"), ranked("jump app", "jump to src/app.ts", "jump to readme"))
    }

    @Test
    fun tiesKeepInputOrder() = assertEquals(listOf("go a", "go b"), ranked("go", "go a", "go b"))
}
