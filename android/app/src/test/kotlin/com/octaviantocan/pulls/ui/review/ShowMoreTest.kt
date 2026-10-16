package com.octaviantocan.pulls.ui.review

import com.octaviantocan.pulls.data.ReviewFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Overview's long lists stop early (N5), and file groups that start the same say it once (N34). */
class ShowMoreTest {
    private fun section(label: String?, vararg paths: String) =
        FileSection(label, paths.map { ReviewFile(it, "modified", 1, 1, patchLines = emptyList()) })

    @Test
    fun aLongListShowsItsFirstFewAndAShortOneShowsAll() {
        assertEquals(FIRST_FEW, firstFew(12, open = false))
        assertEquals(12, firstFew(12, open = true))
        assertEquals(3, firstFew(3, open = false))
        // One hidden row is cheaper than the line that would offer it.
        assertEquals(FIRST_FEW + 1, firstFew(FIRST_FEW + 1, open = false))
        assertEquals(FIRST_FEW, firstFew(FIRST_FEW + 2, open = false))
    }

    @Test
    fun groupsThatStartTheSameSayItOnce() {
        val shared = sharePrefixes(
            listOf(
                section("Wayland capture producer › Runtime", "a"),
                section("Wayland capture producer › Outbox", "b"),
                section("Wayland capture producer › Tests", "c"),
                section("Docs", "d"),
            ),
        )
        assertEquals(listOf("Runtime", "Outbox", "Tests", "Docs"), shared.map { it.name })
        assertEquals(listOf("Wayland capture producer", "Wayland capture producer", "Wayland capture producer", null), shared.map { it.heading })
        // The fold keeps the whole name, so a folded group stays folded however its row is named.
        assertEquals("Wayland capture producer › Runtime", shared[0].label)
        assertTrue(opensHeading(shared[0], null))
        assertFalse(opensHeading(shared[1], shared[0]))
    }

    @Test
    fun aLoneChildKeepsItsWholeNameUnlessItsParentIsJustAbove() {
        val lone = sharePrefixes(listOf(section("Docs", "a"), section("Capture › Runtime", "b")))
        assertNull(lone[1].heading)
        assertEquals("Capture › Runtime", lone[1].name)

        val underParent = sharePrefixes(listOf(section("Capture", "a"), section("Capture › Runtime", "b")))
        assertEquals("Capture", underParent[1].heading)
        assertEquals("Runtime", underParent[1].name)
        // The parent's own row already says it, so no line of its own goes over the child.
        assertFalse(opensHeading(underParent[1], underParent[0]))
    }

    @Test
    fun theCutNeverSplitsAGroupFromItsHeading() {
        val sections = sharePrefixes(
            listOf(section("A", "a"), section("B", "b"), section("C", "c"), section("D", "d")) +
                (1..4).map { section("Capture › Part $it", "p$it") } +
                listOf(section("E", "e"), section("F", "f")),
        )
        // The fifth section opens a run of four that share "Capture", so the first few run on to the end of it.
        assertEquals(8, firstFewSections(sections, open = false))
        assertEquals(sections.size, firstFewSections(sections, open = true))
        val plain = (1..9).map { section("S$it", "s$it") }
        assertEquals(FIRST_FEW, firstFewSections(plain, open = false))
    }
}
