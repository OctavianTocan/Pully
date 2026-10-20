package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPlacesTest {
    private val pr = "https://github.com/OctavianTocan/pulls.review/pull/68"

    @Test
    fun readsConversationAnchors() {
        assertEquals(LinkPlace.Comment(3412345678), LinkPlaces.parse("$pr#issuecomment-3412345678"))
        assertEquals(LinkPlace.Review(3311223344), LinkPlaces.parse("$pr#pullrequestreview-3311223344"))
        assertEquals(LinkPlace.CodeComment(2445566778), LinkPlaces.parse("$pr#discussion_r2445566778"))
        assertEquals(LinkPlace.Description, LinkPlaces.parse("$pr#issue-3500000000"))
        assertEquals(LinkPlace.Conversation, LinkPlaces.parse("$pr#event-19876543210"))
        assertEquals(LinkPlace.Commits("51b512b"), LinkPlaces.parse("$pr#commits-pushed-51b512b"))
        assertNull(LinkPlaces.parse(pr))
        assertNull(LinkPlaces.parse("$pr/"))
    }

    @Test
    fun readsTheFilesTab() {
        val anchor = LinkPlaces.fileAnchor("android/CHANGELOG.md")
        assertEquals(64, anchor.length)
        assertEquals(LinkPlace.Files(), LinkPlaces.parse("$pr/files"))
        assertEquals(LinkPlace.Files(), LinkPlaces.parse("$pr/changes?w=1"))
        assertEquals(LinkPlace.Files(anchor), LinkPlaces.parse("$pr/files#diff-$anchor"))
        assertEquals(LinkPlace.Files(anchor, 12), LinkPlaces.parse("$pr/files#diff-${anchor}R12"))
        assertEquals(LinkPlace.Files(anchor, 7, left = true), LinkPlaces.parse("$pr/changes#diff-${anchor}L7-L9"))
        assertEquals(LinkPlace.Files(anchor, 3, commits = "51b512b"), LinkPlaces.parse("$pr/files/51b512b#diff-${anchor}R3"))
        assertEquals(LinkPlace.Files(commits = "c1b70d2..51b512b"), LinkPlaces.parse("$pr/files/c1b70d2..51b512b"))
        assertEquals(LinkPlace.CodeComment(2445566778), LinkPlaces.parse("$pr/files#r2445566778"))
        assertEquals(LinkPlace.CodeComment(2445566778), LinkPlaces.parse("$pr/files/51b512b#r2445566778"))
        assertEquals("android/CHANGELOG.md", LinkPlaces.fileFor(anchor.uppercase(), listOf("README.md", "android/CHANGELOG.md")))
        assertEquals("c1b70d2" to "51b512b", LinkPlaces.commitRange("c1b70d2..51b512b"))
        assertEquals("51b512b" to "51b512b", LinkPlaces.commitRange("51b512b"))
    }

    @Test
    fun matchesGithubsOwnFileAnchor() {
        // GitHub's anchor for README.md on any pull request: diff- and the SHA-256 of the path.
        assertEquals("b335630551682c19a781afebcf4d07bf978fb1f8ac04c6bf87428ed5106870f5", LinkPlaces.fileAnchor("README.md"))
    }

    @Test
    fun readsCommitsAndChecks() {
        assertEquals(LinkPlace.Commits(), LinkPlaces.parse("$pr/commits"))
        assertEquals(LinkPlace.Commits("51b512bdeadbeef"), LinkPlaces.parse("$pr/commits/51b512bdeadbeef"))
        val anchor = LinkPlaces.fileAnchor("README.md")
        assertEquals(LinkPlace.Commits("51b512b", LinkPlace.Files(anchor, 4)), LinkPlaces.parse("$pr/commits/51b512b#diff-${anchor}R4"))
        assertEquals(LinkPlace.Checks(), LinkPlaces.parse("$pr/checks"))
        assertEquals(LinkPlace.Checks(53318765432), LinkPlaces.parse("$pr/checks?check_run_id=53318765432"))
    }

    @Test
    fun readsAFileOnACommitOrCompare() {
        val anchor = LinkPlaces.fileAnchor("README.md")
        assertEquals(LinkPlace.Files(anchor, 10), LinkPlaces.parse("https://github.com/a/b/commit/51b512b#diff-${anchor}R10"))
        assertEquals(LinkPlace.Files(anchor), LinkPlaces.parse("https://www.github.com/a/b/compare/main...dev#diff-$anchor"))
        assertNull(LinkPlaces.parse("https://github.com/a/b/commit/51b512b"))
        assertNull(LinkPlaces.parse("https://example.com/a/b/pull/1#issuecomment-1"))
    }
}
