package com.octaviantocan.pulls.core

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun attachesPicturesToTheBulletTheyFollow() {
        val entries = parseChangelog(
            """
            ## 0.40.0

            Rows show their state.

            ![The Inbox](changelog/0.40.0/inbox.png)

            ### New

            - Rows carry a state icon.

            ![A row with a draft icon](changelog/0.40.0/rows.png)

            - Search asks when it ends with a question mark.

            ![Asking from the search](changelog/0.40.0/ask.gif)
            """.trimIndent(),
        )
        val entry = entries.single()
        assertEquals(listOf("Rows carry a state icon.", "Search asks when it ends with a question mark."), entry.items)
        assertEquals("Rows show their state.", entry.summary)
        assertEquals(listOf("changelog/0.40.0/inbox.png"), entry.picturesAfter(-1).map { it.path })
        assertEquals(listOf("A row with a draft icon"), entry.picturesAfter(0).map { it.alt })
        assertEquals(listOf("changelog/0.40.0/ask.gif"), entry.picturesAfter(1).map { it.path })
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
    fun readsWhenEachVersionWasReleased() {
        val entries = parseChangelog(
            """
            ## 0.63.0

            #### 2026-10-26 13:28 UTC

            Changelog entries say when they came out.

            - Each version shows its date and time.

            ## 0.62.0
            #### 2026-02-31 10:00 UTC
            A time that does not exist.

            - Skipped.
            """.trimIndent() + "\n\n" + text,
        )
        assertEquals(Instant.parse("2026-10-26T13:28:00Z"), entries[0].released)
        // The release line is not part of the summary, as a build from before it reads the section too.
        assertEquals("Changelog entries say when they came out.", entries[0].summary)
        assertNull(entries[1].released)
        assertEquals("A time that does not exist.", entries[1].summary)
        assertNull(entries[2].released)
    }

    @Test
    fun writesTheReleaseTimeInTheReadersZone() {
        val released = Instant.parse("2026-10-26T13:28:00Z")
        assertEquals("26 Oct 2026, 14:28", changelogTime(released, ZoneId.of("Europe/Berlin"), Locale.UK))
        assertEquals("26 Oct 2026, 13:28", changelogTime(released, ZoneId.of("UTC"), Locale.UK))
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

    /** The changelog the app ships: newest first, and every section has a release time, a summary and something under it. */
    @Test
    fun shippedChangelogIsWellFormed() {
        val entries = parseChangelog(File("../CHANGELOG.md").readText())
        assertTrue(entries.isNotEmpty())
        entries.zipWithNext().forEach { (newer, older) -> assertTrue(compareVersions(newer.version, older.version) > 0, "${newer.version} before ${older.version}") }
        entries.forEach { assertTrue(it.summary.isNotBlank() && it.items.isNotEmpty(), "${it.version} needs a summary and bullets") }
        entries.forEach { assertNotNull(it.released, "${it.version} needs its release date and time") }
        entries.zipWithNext().forEach { (newer, older) -> assertTrue(newer.released!! >= older.released!!, "${newer.version} was released before ${older.version}") }
    }
}
