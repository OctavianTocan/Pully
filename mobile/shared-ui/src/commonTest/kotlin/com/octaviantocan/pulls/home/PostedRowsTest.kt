package com.octaviantocan.pulls.ui.home

import com.octaviantocan.pulls.data.PostedRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PostedRowsTest {
    private fun row(id: String, title: String, author: String = "Claude Code", updatedAt: String = "2026-10-16T12:00:00Z") =
        PostedRow(id, title, author, 1, updatedAt, 1, 2, 0)

    @Test
    fun searchFindsPostedDiffsByTitleOrWhoPostedThem() {
        val rows = listOf(row("a", "Retry GitHub calls"), row("b", "Sign-in page", author = "Codex"))
        assertEquals(rows, rows.matching(""))
        assertEquals(listOf("a"), rows.matching("retry").map { it.id })
        assertEquals(listOf("b"), rows.matching("codex").map { it.id })
    }

    @Test
    fun readsWhenItChanged() {
        assertEquals(1_792_152_000_000L, row("a", "x").updatedAtMs())
        assertNull(row("a", "x", updatedAt = "yesterday").updatedAtMs())
    }

    @Test
    fun keysItsRowsApartFromPullRequests() {
        assertEquals("posted:a", postedKey(row("a", "x")))
        assertEquals("posted:agents", POSTED_SECTION)
    }
}
