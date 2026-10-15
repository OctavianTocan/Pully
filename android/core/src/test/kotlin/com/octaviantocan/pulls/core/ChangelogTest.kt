package com.octaviantocan.pulls.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangelogTest {
    private val text = """
        # What's new in Pulls

        <!--
        ## 9.9.9 is in a comment and is not a section
        -->

        ## 0.10.0

        Pull requests slide in,
        and you can edit them.

        - Edit a title in place.
        - Commits are plain lists
          with whole titles.

        ## 0.9.0

        Sheets rest at half height.

        * Profile can hide a repository.
    """.trimIndent()

    @Test
    fun parsesSectionsSummariesAndBullets() {
        val entries = parseChangelog(text)
        assertEquals(listOf("0.10.0", "0.9.0"), entries.map { it.version })
        assertEquals("Pull requests slide in, and you can edit them.", entries[0].summary)
        assertEquals(listOf("Edit a title in place.", "Commits are plain lists with whole titles."), entries[0].items)
        assertEquals(listOf("Profile can hide a repository."), entries[1].items)
    }

    @Test
    fun sortsBulletsUnderGroupHeadings() {
        val entries = parseChangelog(
            """
            ## 0.11.0

            Groups for what is new and what is fixed.

            - A bullet before any group.

            ### New

            - Edit a title in place.

            ### Fixed

            - Sheets no longer close
              on a scroll.
            """.trimIndent() + "\n\n" + text,
        )
        val groups = entries[0].groups
        assertEquals(listOf(null, "New", "Fixed"), groups.map { it.title })
        assertEquals(listOf("Sheets no longer close on a scroll."), groups[2].items)
        // Every bullet is in the flat list too, as an older build reads it.
        assertEquals(groups.flatMap { it.items }, entries[0].items)
        // A section without headings is one group with no title.
        assertEquals(listOf(ChangelogGroup(null, entries[1].items)), entries[1].groups)
    }

    @Test
    fun comparesVersionsAsNumbers() {
        assertTrue(compareVersions("0.10.0", "0.9.2") > 0)
        assertTrue(compareVersions("0.9.0", "0.10.0") < 0)
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertTrue(compareVersions("1.0.1", "1.0.0-rc1") > 0)
    }

    @Test
    fun listsWhatAPhoneHasNotSeen() {
        val entries = parseChangelog(text)
        assertEquals(listOf("0.10.0"), entries.since("0.9.0", "0.10.0").map { it.version })
        assertEquals(listOf("0.10.0", "0.9.0"), entries.since("0.8.0", "0.10.0").map { it.version })
        assertEquals(listOf("0.9.0"), entries.since("0.8.0", "0.9.0").map { it.version })
        // Nothing recorded yet: only the running version's own section.
        assertEquals(listOf("0.10.0"), entries.since(null, "0.10.0").map { it.version })
        assertEquals(emptyList(), entries.since("0.10.0", "0.10.0"))
    }

    @Test
    fun manifestFallsBackToItsNotes() {
        val old = UpdateManifest(31, "0.17.0", "abc", "https://x/pulls.apk", notes = "Better updates.")
        assertEquals(listOf(ChangelogEntry("0.17.0", "Better updates.", emptyList())), old.changesSince("0.16.0"))
        val new = old.copy(versionCode = 32, versionName = "0.10.0", changelog = text)
        assertEquals(listOf("0.10.0", "0.9.0"), new.changesSince("0.8.0").map { it.version })
    }

    @Test
    fun plainTextDropsMarkdown() {
        assertEquals("Tap Update in the card.", plainChangelogText("Tap **Update** in the `card`."))
        assertEquals("Read the docs.", plainChangelogText("Read the [docs](https://example.com)."))
    }

    /** The changelog the app ships: newest first, and every section has a summary and something under it. */
    @Test
    fun shippedChangelogIsWellFormed() {
        val entries = parseChangelog(File("../CHANGELOG.md").readText())
        assertTrue(entries.isNotEmpty())
        entries.zipWithNext().forEach { (newer, older) -> assertTrue(compareVersions(newer.version, older.version) > 0, "${newer.version} before ${older.version}") }
        entries.forEach { assertTrue(it.summary.isNotBlank() && it.items.isNotEmpty(), "${it.version} needs a summary and bullets") }
    }
}
